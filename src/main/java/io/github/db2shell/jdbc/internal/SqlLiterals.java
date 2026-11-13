package io.github.db2shell.jdbc.internal;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds SQL literals that survive the trip through the db2 utility's single-line, job-CCSID input:
 * newlines become {@code CHR(10)}/{@code CHR(13)}, text the job CCSID cannot represent becomes a UTF-16
 * {@code UX'...'} literal, and long values are split into concatenated pieces so input lines stay short.
 */
public final class SqlLiterals {

    /** Max characters per character-literal piece. */
    static final int CHAR_PIECE = 8000;
    /** Max characters per UX (UTF-16) literal piece: 4 hex digits per character. */
    static final int UX_PIECE = 4000;
    /** Max bytes per BX literal piece: 2 hex digits per byte. */
    static final int BX_PIECE = 8000;
    /** Concatenations whose total length exceeds this are promoted to LOBs. */
    static final int VARCHAR_LIMIT = 32000;
    private static final char[] s_hexDigits = "0123456789ABCDEF".toCharArray();

    private SqlLiterals() {
    }

    /** True if every character can be represented in the charset. */
    public static boolean canEncode(final Charset _cs, final String _s) {
        final CharsetEncoder enc = _cs.newEncoder();
        return enc.canEncode(_s);
    }

    /**
     * Builds a literal for the given string content. Text the charset cannot encode becomes a UTF-16 literal;
     * newlines become CHR(10)/CHR(13) and long text is split into concatenated pieces (cast to CLOB when the
     * total exceeds the VARCHAR limit).
     *
     * @param _s
     *            the string content
     * @param _cs
     *            the job charset
     * @return the SQL literal or concatenation expression
     */
    public static String string(final String _s, final Charset _cs) {
        if (!canEncode(_cs, _s)) {
            return unicode(_s);
        }
        final boolean hasNewline = _s.indexOf('\n') >= 0 || _s.indexOf('\r') >= 0;
        if (!hasNewline && _s.length() <= CHAR_PIECE) {
            return quote(_s);
        }
        // split into pieces at newlines and every CHAR_PIECE characters
        final StringBuilder sb = new StringBuilder(_s.length() + 32);
        int pieces = 0;
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < _s.length(); i++) {
            final char c = _s.charAt(i);
            if ('\n' == c || '\r' == c) {
                if (cur.length() > 0) {
                    pieces = appendPiece(sb, pieces, quote(cur.toString()));
                    cur.setLength(0);
                }
                pieces = appendPiece(sb, pieces, '\n' == c ?"CHR(10)" : "CHR(13)");
            } else {
                cur.append(c);
                if (cur.length() >= CHAR_PIECE && !Character.isHighSurrogate(c)) {
                    pieces = appendPiece(sb, pieces, quote(cur.toString()));
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() > 0) {
            pieces = appendPiece(sb, pieces, quote(cur.toString()));
        }
        if (0 == pieces) {
            return "''";
        }
        if (1 == pieces) {
            return sb.toString();
        }
        String first = sb.toString();
        if (_s.length() > VARCHAR_LIMIT) {
            // promote to CLOB so the concatenation is not limited to 32K
            final int firstEnd = first.indexOf(" CONCAT ");
            first = "CAST(" + first.substring(0, firstEnd) + " AS CLOB(" + (_s.length() + 16) + "))"
                    + first.substring(firstEnd);
        }
        return "(" + first + ")";
    }

    /**
     * Appends a piece to a concatenation.
     *
     * @param _sb
     *            the concatenation built so far
     * @param _pieces
     *            the number of pieces already appended
     * @param _piece
     *            the piece to append
     * @return the new number of pieces
     */
    private static int appendPiece(final StringBuilder _sb, final int _pieces, final String _piece) {
        if (_pieces > 0) {
            _sb.append(" CONCAT ");
        }
        _sb.append(_piece);
        return _pieces + 1;
    }

    /** A plain single-quoted literal with embedded quotes doubled. */
    public static String quote(final String _s) {
        return "'" + _s.replace("'", "''") + "'";
    }

    /**
     * A UTF-16 graphic literal, split into pieces when long (never inside a surrogate pair).
     *
     * @param _s
     *            the string content
     * @return the UX literal or concatenation expression
     */
    public static String unicode(final String _s) {
        final byte[] b = _s.getBytes(StandardCharsets.UTF_16BE);
        final int chars = b.length / 2;
        if (chars <= UX_PIECE) {
            return "UX'" + hex(b, 0, b.length) + "'";
        }
        final StringBuilder sb = new StringBuilder(b.length * 2 + 64);
        sb.append('(');
        int off = 0;
        while (off < b.length) {
            int end = Math.min(b.length, off + UX_PIECE * 2);
            // never split a surrogate pair
            if (end < b.length && Character.isHighSurrogate((char) (((b[end - 2] & 0xff) << 8) | (b[end - 1] & 0xff)))) {
                end -= 2;
            }
            final String piece = "UX'" + hex(b, off, end - off) + "'";
            if (off > 0) {
                sb.append(" CONCAT ").append(piece);
            } else if (chars > VARCHAR_LIMIT / 2) {
                sb.append("CAST(").append(piece).append(" AS DBCLOB(").append(chars + 16).append(") CCSID 1200)");
            } else {
                sb.append(piece);
            }
            off = end;
        }
        return sb.append(')').toString();
    }

    /**
     * A binary literal ({@code BX'..'}), split into pieces when long.
     *
     * @param _b
     *            the bytes
     * @return the BX literal or concatenation expression
     */
    public static String binary(final byte[] _b) {
        if (_b.length <= BX_PIECE) {
            return "BX'" + hex(_b, 0, _b.length) + "'";
        }
        final StringBuilder sb = new StringBuilder(_b.length * 2 + 64);
        sb.append('(');
        for (int off = 0; off < _b.length; off += BX_PIECE) {
            final String piece = "BX'" + hex(_b, off, Math.min(BX_PIECE, _b.length - off)) + "'";
            if (off > 0) {
                sb.append(" CONCAT ").append(piece);
            } else if (_b.length > VARCHAR_LIMIT) {
                sb.append("CAST(").append(piece).append(" AS BLOB(").append(_b.length + 16).append("))");
            } else {
                sb.append(piece);
            }
        }
        return sb.append(')').toString();
    }

    /**
     * Upper-case hex encoding of part of a byte array.
     *
     * @param _b
     *            the bytes
     * @param _off
     *            the offset of the first byte
     * @param _len
     *            the number of bytes
     * @return two hex digits per byte
     */
    public static String hex(final byte[] _b, final int _off, final int _len) {
        final char[] c = new char[_len * 2];
        for (int i = 0; i < _len; i++) {
            final int v = _b[_off + i] & 0xff;
            c[i * 2] = s_hexDigits[v >>> 4];
            c[i * 2 + 1] = s_hexDigits[v & 0xf];
        }
        return new String(c);
    }

    /**
     * Decodes hex text. A trailing odd digit is ignored.
     *
     * @param _s
     *            the hex text
     * @return the bytes
     */
    public static byte[] unhex(final String _s) {
        final int n = _s.length() / 2;
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((Character.digit(_s.charAt(i * 2), 16) << 4) | Character.digit(_s.charAt(i * 2 + 1), 16));
        }
        return b;
    }

    /**
     * Splits text into string-literal pieces suitable for building a CLOB/VARCHAR variable in a compound.
     *
     * @param _s
     *            the text (normally SQL statement text)
     * @return a quoted literal, or a concatenation of quoted pieces (cast to CLOB when longer than the VARCHAR
     *         limit)
     */
    public static String concatenatedLiteral(final String _s) {
        if (_s.length() <= CHAR_PIECE) {
            return quote(_s);
        }
        final StringBuilder sb = new StringBuilder(_s.length() + 64);
        int off = 0;
        while (off < _s.length()) {
            int end = Math.min(_s.length(), off + CHAR_PIECE);
            if (end < _s.length() && Character.isHighSurrogate(_s.charAt(end - 1))) {
                end--;
            }
            final String piece = quote(_s.substring(off, end));
            if (off > 0) {
                sb.append(" CONCAT ").append(piece);
            } else if (_s.length() > VARCHAR_LIMIT) {
                sb.append("CAST(").append(piece).append(" AS CLOB(").append(_s.length() + 16).append("))");
            } else {
                sb.append(piece);
            }
            off = end;
        }
        return sb.toString();
    }
}
