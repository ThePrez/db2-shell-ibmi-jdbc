package io.github.db2shell.jdbc.internal;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

/**
 * Decodes the typed token stream produced by {@link QueryBuilder#stream}: per row, one token per column, each
 * either {@code -} (NULL) or {@code <byte length>:<bytes>}, spread over as many fixed-width lines as needed.
 */
public final class TypedRowSource extends StreamRowSource {

    private final ColumnInfo[] m_cols;
    private final boolean m_unicode;
    private final Charset m_charset;
    private final byte[] m_digits = new byte[10];
    private final byte m_colon;
    private final byte m_dash;
    private final byte m_space;
    private boolean m_mappingErrorReported = false;

    // current row buffer
    private byte[] m_buf;
    private int m_len;

    /**
     * Creates a source over the typed result currently open in the session.
     *
     * @param _lock
     *            the connection-level lock guarding the session
     * @param _session
     *            the session whose current result this source reads
     * @param _cols
     *            the result columns, in token order
     * @param _width
     *            the width of each output line
     * @param _unicode
     *            whether character data is hex-encoded UTF-16
     */
    public TypedRowSource(final Object _lock, final Session _session, final List<ColumnInfo> _cols, final int _width,
            final boolean _unicode) {
        super(_lock, _session, _width, 0);
        m_cols = _cols.toArray(new ColumnInfo[0]);
        m_unicode = _unicode;
        m_charset = _session.getCharset();
        final byte[] d = "0123456789".getBytes(m_charset);
        System.arraycopy(d, 0, m_digits, 0, 10);
        m_colon = ":".getBytes(m_charset)[0];
        m_dash = "-".getBytes(m_charset)[0];
        m_space = " ".getBytes(m_charset)[0];
    }

    /**
     * Decodes one row of tokens, reading continuation lines as needed. A row printed as blanks (a fetch-time data
     * mapping error) is returned with all values NULL and a one-time warning.
     *
     * @param _firstLine
     *            the first line of the row
     * @return the decoded column values
     * @throws SQLException
     *             if the token stream is malformed or a value cannot be decoded
     */
    @Override
    protected Object[] decodeRow(final byte[] _firstLine) throws SQLException {
        m_buf = _firstLine;
        m_len = _firstLine.length;
        int p = 0;
        final Object[] row = new Object[m_cols.length];
        if (m_len > 0 && m_buf[0] == m_space) {
            // A fetch-time data mapping error (e.g. division by zero, invalid date): the utility does not report
            // it, and prints the whole row value as blanks. Values are returned as NULL with a warning.
            if (!m_mappingErrorReported) {
                m_mappingErrorReported = true;
                addWarning(new java.sql.SQLWarning("Data mapping error in a row (for example division by zero or an "
                        + "invalid date/time value); its values were returned as NULL", "01519"));
            }
            m_buf = null;
            return row;
        }
        for (int c = 0; c < m_cols.length; c++) {
            ensure(p + 1);
            if (m_buf[p] == m_dash) {
                row[c] = null;
                p++;
                continue;
            }
            int n = 0;
            int digitsSeen = 0;
            while (true) {
                ensure(p + 1);
                final byte b = m_buf[p];
                if (b == m_colon) {
                    p++;
                    break;
                }
                final int dv = digit(b);
                if (dv < 0 || digitsSeen > 10) {
                    throw SqlErrors.protocol("bad token length in column " + (c + 1) + " near '"
                            + m_session.decode(m_buf, Math.max(0, p - 10), Math.min(m_len - Math.max(0, p - 10), 40)) + "'");
                }
                n = n * 10 + dv;
                digitsSeen++;
                p++;
            }
            ensure(p + n);
            row[c] = convert(m_cols[c], m_buf, p, n);
            p += n;
        }
        m_buf = null;
        return row;
    }

    /**
     * The value of a digit byte in the job charset.
     *
     * @param _b
     *            the byte
     * @return 0-9, or -1 if the byte is not a digit
     */
    private int digit(final byte _b) {
        for (int i = 0; i < 10; i++) {
            if (m_digits[i] == _b) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Makes sure at least {@code _n} bytes of the row are buffered, reading continuation lines as needed.
     *
     * @param _n
     *            the number of bytes required
     * @throws SQLException
     *             if the result ends in the middle of the row or the session fails
     */
    private void ensure(final int _n) throws SQLException {
        while (m_len < _n) {
            final byte[] next = continuation();
            if (m_buf.length < m_len + next.length) {
                final byte[] nb = new byte[Math.max(m_buf.length * 2, m_len + next.length)];
                System.arraycopy(m_buf, 0, nb, 0, m_len);
                m_buf = nb;
            }
            System.arraycopy(next, 0, m_buf, m_len, next.length);
            m_len += next.length;
        }
    }

    /**
     * Converts the bytes of one token value to the internal Java value for the column.
     *
     * @param _col
     *            the column
     * @param _b
     *            the row buffer
     * @param _off
     *            the offset of the value in the buffer
     * @param _n
     *            the length of the value in bytes
     * @return the value
     * @throws SQLException
     *             if the value cannot be decoded
     */
    private Object convert(final ColumnInfo _col, final byte[] _b, final int _off, final int _n) throws SQLException {
        switch (_col.getKind()) {
        case STRING:
        case CLOB:
        case XML:
            if (m_unicode) {
                final String hex = new String(_b, _off, _n, m_charset);
                return new String(SqlLiterals.unhex(hex), StandardCharsets.UTF_16BE);
            }
            return new String(_b, _off, _n, m_charset);
        default:
            return Values.decode(_col.getKind(), new String(_b, _off, _n, m_charset));
        }
    }
}
