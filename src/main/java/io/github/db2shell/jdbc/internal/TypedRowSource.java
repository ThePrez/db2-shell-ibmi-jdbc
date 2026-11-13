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

    private final ColumnInfo[] cols;
    private final boolean unicode;
    private final Charset charset;
    private final byte[] digits = new byte[10];
    private final byte colon;
    private final byte dash;
    private final byte space;
    private boolean mappingErrorReported = false;

    // current row buffer
    private byte[] buf;
    private int len;

    public TypedRowSource(final Object _lock, final Session _session, final List<ColumnInfo> _cols, final int _width,
            final boolean _unicode) {
        super(_lock, _session, _width, 0);
        cols = _cols.toArray(new ColumnInfo[0]);
        unicode = _unicode;
        charset = _session.getCharset();
        final byte[] d = "0123456789".getBytes(charset);
        System.arraycopy(d, 0, digits, 0, 10);
        colon = ":".getBytes(charset)[0];
        dash = "-".getBytes(charset)[0];
        space = " ".getBytes(charset)[0];
    }

    @Override
    protected Object[] decodeRow(final byte[] _firstLine) throws SQLException {
        buf = _firstLine;
        len = _firstLine.length;
        int p = 0;
        final Object[] row = new Object[cols.length];
        if (len > 0 && buf[0] == space) {
            // A fetch-time data mapping error (e.g. division by zero, invalid date): the utility does not report
            // it, and prints the whole row value as blanks. Values are returned as NULL with a warning.
            if (!mappingErrorReported) {
                mappingErrorReported = true;
                addWarning(new java.sql.SQLWarning("Data mapping error in a row (for example division by zero or an "
                        + "invalid date/time value); its values were returned as NULL", "01519"));
            }
            buf = null;
            return row;
        }
        for (int c = 0; c < cols.length; c++) {
            ensure(p + 1);
            if (buf[p] == dash) {
                row[c] = null;
                p++;
                continue;
            }
            int n = 0;
            int digitsSeen = 0;
            while (true) {
                ensure(p + 1);
                final byte b = buf[p];
                if (b == colon) {
                    p++;
                    break;
                }
                final int dv = digit(b);
                if (dv < 0 || digitsSeen > 10) {
                    throw SqlErrors.protocol("bad token length in column " + (c + 1) + " near '"
                            + session.decode(buf, Math.max(0, p - 10), Math.min(len - Math.max(0, p - 10), 40)) + "'");
                }
                n = n * 10 + dv;
                digitsSeen++;
                p++;
            }
            ensure(p + n);
            row[c] = convert(cols[c], buf, p, n);
            p += n;
        }
        buf = null;
        return row;
    }

    private int digit(final byte _b) {
        for (int i = 0; i < 10; i++) {
            if (digits[i] == _b) {
                return i;
            }
        }
        return -1;
    }

    /** Makes sure at least {@code _n} bytes of the row are buffered, reading continuation lines as needed. */
    private void ensure(final int _n) throws SQLException {
        while (len < _n) {
            final byte[] next = continuation();
            if (buf.length < len + next.length) {
                final byte[] nb = new byte[Math.max(buf.length * 2, len + next.length)];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(next, 0, buf, len, next.length);
            len += next.length;
        }
    }

    private Object convert(final ColumnInfo _col, final byte[] _b, final int _off, final int _n) throws SQLException {
        switch (_col.getKind()) {
        case STRING:
        case CLOB:
        case XML:
            if (unicode) {
                final String hex = new String(_b, _off, _n, charset);
                return new String(SqlLiterals.unhex(hex), StandardCharsets.UTF_16BE);
            }
            return new String(_b, _off, _n, charset);
        default:
            return Values.decode(_col.getKind(), new String(_b, _off, _n, charset));
        }
    }
}
