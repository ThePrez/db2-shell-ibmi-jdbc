package io.github.db2shell.jdbc.internal;

/**
 * Parses the utility's native fixed-width display output. Column boundaries come from the dash line; a value
 * of {@code -} is NULL; trailing blanks are removed and right-aligned numbers are trimmed.
 */
public final class RawRowSource extends StreamRowSource {

    private final int[][] m_cols;

    public RawRowSource(final Object _lock, final Session _session, final int[][] _cols, final int _width,
            final long _maxRows) {
        super(_lock, _session, _width, _maxRows);
        m_cols = _cols;
    }

    @Override
    protected Object[] decodeRow(final byte[] _line) {
        final Object[] row = new Object[m_cols.length];
        for (int i = 0; i < m_cols.length; i++) {
            row[i] = field(m_session, _line, m_cols[i][0], m_cols[i][1]);
        }
        return row;
    }

    /**
     * Extracts one display field. Visible for the session's internal queries.
     *
     * @param _session
     *            the session (used to decode bytes in the job charset)
     * @param _line
     *            the output line
     * @param _start
     *            the start offset of the field
     * @param _len
     *            the width of the field
     * @return the field value without trailing blanks (and trimmed if it is a right-aligned number), or null for
     *         NULL or a field beyond the end of the line
     */
    static String field(final Session _session, final byte[] _line, final int _start, final int _len) {
        if (_start >= _line.length) {
            return null;
        }
        final int n = Math.min(_len, _line.length - _start);
        final String s = _session.decode(_line, _start, n);
        int end = s.length();
        while (end > 0 && ' ' == s.charAt(end - 1)) {
            end--;
        }
        final String v = s.substring(0, end);
        if ("-".equals(v)) {
            return null;
        }
        if (v.startsWith(" ")) {
            final String t = v.trim();
            if ("-".equals(t)) {
                return null;
            }
            if (isNumeric(t)) {
                return t;
            }
        }
        return v;
    }

    /**
     * Whether a trimmed field looks like a number as displayed by the utility.
     *
     * @param _s
     *            the trimmed field
     * @return true if it contains at least one digit and only number characters
     */
    private static boolean isNumeric(final String _s) {
        if (_s.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < _s.length(); i++) {
            final char c = _s.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if ('-' != c && '+' != c && '.' != c && ',' != c && 'E' != c && 'e' != c) {
                return false;
            }
        }
        return digit;
    }

    /**
     * Column names from the header line.
     *
     * @param _session
     *            the session (used to decode bytes in the job charset)
     * @param _header
     *            the header line
     * @param _cols
     *            the column boundaries: {start, width} per column
     * @return the trimmed names; COLn for columns beyond the end of the header
     */
    public static String[] headerNames(final Session _session, final byte[] _header, final int[][] _cols) {
        final String[] names = new String[_cols.length];
        for (int i = 0; i < _cols.length; i++) {
            final int s = _cols[i][0];
            if (s >= _header.length) {
                names[i] = "COL" + (i + 1);
                continue;
            }
            names[i] = _session.decode(_header, s, Math.min(_cols[i][1], _header.length - s)).trim();
        }
        return names;
    }
}
