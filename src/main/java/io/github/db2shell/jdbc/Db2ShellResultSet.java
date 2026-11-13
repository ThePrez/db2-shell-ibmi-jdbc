package io.github.db2shell.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.db2shell.jdbc.internal.ColumnInfo;
import io.github.db2shell.jdbc.internal.Lobs;
import io.github.db2shell.jdbc.internal.RowSource;
import io.github.db2shell.jdbc.internal.SqlErrors;
import io.github.db2shell.jdbc.internal.Values;

/**
 * A read-only result set. Forward-only result sets stream rows from the db2 utility as they are read; scrollable
 * (insensitive) result sets read all rows into memory when created.
 */
public class Db2ShellResultSet extends ReadOnlyResultSet {

    private final Db2ShellStatement m_statement;
    private final List<ColumnInfo> m_columns;
    private final Map<String, Integer> m_labelIndex = new HashMap<String, Integer>();
    private final int m_type;
    private final int m_holdability;
    private RowSource m_source;
    private List<Object[]> m_all; // scrollable only
    private Object[] m_row;
    private int m_rowNumber = 0; // 1-based; 0 = before first
    private boolean m_afterLast = false;
    private boolean m_closed = false;
    private boolean m_wasNull = false;
    private SQLWarning m_warnings;
    private int m_fetchSize = 0;
    private int m_fetchDirection = FETCH_FORWARD;
    private boolean m_sourceWarningsTaken = false;

    /**
     * Creates a result set over a row source. Column labels are indexed both as given and upper-cased, so
     * {@link #findColumn(String)} can match case-insensitively; the first column wins when labels repeat.
     *
     * @param _stmt
     *            the statement that produced the result set, or null for an in-memory result set
     * @param _cols
     *            the column descriptions
     * @param _src
     *            the source of the rows; owned (and closed) by this result set
     * @param _type
     *            the {@code ResultSet.TYPE_*} value
     * @param _holdability
     *            the {@code ResultSet} holdability value
     */
    Db2ShellResultSet(final Db2ShellStatement _stmt, final List<ColumnInfo> _cols, final RowSource _src,
            final int _type, final int _holdability) {
        m_statement = _stmt;
        m_columns = _cols;
        m_source = _src;
        m_type = _type;
        m_holdability = _holdability;
        for (int i = _cols.size() - 1; i >= 0; i--) {
            m_labelIndex.put(_cols.get(i).getName().toUpperCase(Locale.ROOT), i + 1);
        }
        for (int i = _cols.size() - 1; i >= 0; i--) {
            m_labelIndex.put(_cols.get(i).getName(), i + 1);
        }
    }

    /** Builds an in-memory result set (used for metadata and generated keys). */
    static Db2ShellResultSet ofRows(final Db2ShellStatement _stmt, final List<ColumnInfo> _cols,
            final List<Object[]> _rows) {
        return new Db2ShellResultSet(_stmt, _cols, new RowSource.ListRowSource(_rows), TYPE_SCROLL_INSENSITIVE,
                HOLD_CURSORS_OVER_COMMIT);
    }

    List<ColumnInfo> columns() {
        return m_columns;
    }

    // =================================================================================== state

    private void checkOpen() throws SQLException {
        if (m_closed) {
            throw SqlErrors.closed("ResultSet");
        }
    }

    private void checkScrollable() throws SQLException {
        checkOpen();
        if (TYPE_FORWARD_ONLY == m_type) {
            throw new SQLException("Operation not allowed on a TYPE_FORWARD_ONLY result set", "24000");
        }
    }

    /**
     * Reads every remaining row from the source into memory, for scrollable access. Does nothing if the rows
     * are already loaded.
     *
     * @throws SQLException
     *             if reading from the db2 utility fails
     */
    private void loadAll() throws SQLException {
        if (null != m_all) {
            return;
        }
        m_all = new ArrayList<Object[]>();
        Object[] r;
        while (null != (r = m_source.next())) {
            m_all.add(r);
        }
        takeSourceWarnings();
    }

    /**
     * Moves the row source's warnings onto this result set's warning chain, once only.
     */
    private void takeSourceWarnings() {
        if (m_sourceWarningsTaken) {
            return;
        }
        m_sourceWarningsTaken = true;
        for (final SQLWarning w : m_source.getWarnings()) {
            addWarning(w);
        }
    }

    /**
     * Appends a warning to this result set's warning chain.
     *
     * @param _w
     *            the warning to add
     */
    private void addWarning(final SQLWarning _w) {
        if (null == m_warnings) {
            m_warnings = _w;
        } else {
            m_warnings.setNextWarning(_w);
        }
    }

    /**
     * Returns the raw value of a column in the current row and records whether it was null, for
     * {@link #wasNull()}.
     *
     * @param _col
     *            the 1-based column index
     * @return the column value, or null for SQL NULL
     * @throws SQLException
     *             if the result set is closed, there is no current row, or the index is out of range
     */
    private Object value(final int _col) throws SQLException {
        checkOpen();
        if (null == m_row) {
            throw new SQLException("No current row", "24000");
        }
        if (_col < 1 || _col > m_columns.size()) {
            throw new SQLException("Column index " + _col + " out of range (1.." + m_columns.size() + ")", "07009");
        }
        final Object v = m_row[_col - 1];
        m_wasNull = null == v;
        return v;
    }

    private Object value(final String _label) throws SQLException {
        return value(findColumn(_label));
    }

    /**
     * Closes this result set on behalf of its statement (without notifying the statement back). A failure to
     * close is traced and otherwise ignored.
     */
    void closeQuietlyFromStatement() {
        try {
            closeInternal(false);
        } catch (final SQLException e) {
            trace("closeQuietlyFromStatement", e);
        }
    }

    /**
     * Writes a trace line through the owning statement's connection. In-memory result sets that have no
     * statement are not traced.
     *
     * @param _what
     *            a short label for the event
     * @param _msg
     *            the message or exception to trace
     */
    private void trace(final String _what, final Object _msg) {
        if (null != m_statement) {
            m_statement.engine().getTracer().trace(_what, _msg);
        }
    }

    /**
     * Closes the result set and its row source. Does nothing if already closed.
     *
     * @param _notify
     *            true to tell the owning statement that this result set has closed
     * @throws SQLException
     *             if closing the row source fails
     */
    private void closeInternal(final boolean _notify) throws SQLException {
        if (m_closed) {
            return;
        }
        m_closed = true;
        m_row = null;
        m_all = null;
        try {
            m_source.close();
        } finally {
            if (_notify && null != m_statement) {
                m_statement.resultClosed(this);
            }
        }
    }

    /**
     * Applies the statement's maximum field size to a character value.
     *
     * @param _s
     *            the value, or null
     * @return the value, cut to the maximum field size if one is set
     */
    private String truncate(final String _s) {
        if (null == _s || null == m_statement) {
            return _s;
        }
        final int max = m_statement.maxFieldSize();
        return max > 0 && _s.length() > max ? _s.substring(0, max) : _s;
    }

    // =================================================================================== navigation

    /**
     * Moves to the next row. A forward-only result set reads the next row from the db2 utility; when the rows
     * run out, the source's warnings are collected.
     *
     * @return true if there is a current row, false when past the last row
     * @throws SQLException
     *             if the result set is closed or reading from the db2 utility fails
     */
    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (TYPE_FORWARD_ONLY != m_type) {
            return relative(1);
        }
        if (m_afterLast) {
            return false;
        }
        m_row = m_source.next();
        if (null == m_row) {
            m_afterLast = true;
            m_rowNumber = 0;
            takeSourceWarnings();
            return false;
        }
        m_rowNumber++;
        return true;
    }

    @Override
    public void close() throws SQLException {
        closeInternal(true);
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return m_wasNull;
    }

    /**
     * Reports whether the cursor is before the first row. For a scrollable result set, an empty result is
     * never before the first row, as the JDBC contract requires.
     *
     * @return true if the cursor is before the first row
     * @throws SQLException
     *             if the result set is closed or reading from the db2 utility fails
     */
    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        if (TYPE_FORWARD_ONLY == m_type) {
            return 0 == m_rowNumber && !m_afterLast;
        }
        loadAll();
        return 0 == m_rowNumber && !m_afterLast && !m_all.isEmpty();
    }

    /**
     * Reports whether the cursor is after the last row. For a scrollable result set, an empty result is never
     * after the last row, as the JDBC contract requires.
     *
     * @return true if the cursor is after the last row
     * @throws SQLException
     *             if the result set is closed or reading from the db2 utility fails
     */
    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        if (TYPE_FORWARD_ONLY == m_type) {
            return m_afterLast;
        }
        loadAll();
        return m_afterLast && !m_all.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return 1 == m_rowNumber;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkScrollable();
        loadAll();
        return m_rowNumber > 0 && m_rowNumber == m_all.size();
    }

    /**
     * Moves the cursor before the first row.
     *
     * @throws SQLException
     *             if the result set is closed or forward-only, or reading from the db2 utility fails
     */
    @Override
    public void beforeFirst() throws SQLException {
        checkScrollable();
        loadAll();
        m_rowNumber = 0;
        m_row = null;
        m_afterLast = false;
    }

    /**
     * Moves the cursor after the last row.
     *
     * @throws SQLException
     *             if the result set is closed or forward-only, or reading from the db2 utility fails
     */
    @Override
    public void afterLast() throws SQLException {
        checkScrollable();
        loadAll();
        m_rowNumber = 0;
        m_row = null;
        m_afterLast = true;
    }

    @Override
    public boolean first() throws SQLException {
        return absolute(1);
    }

    @Override
    public boolean last() throws SQLException {
        return absolute(-1);
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return m_afterLast ? 0 : m_rowNumber;
    }

    /**
     * Moves to the given row of a scrollable result set.
     *
     * @param _row
     *            the 1-based row number; negative values count back from the end ({@code -1} is the last row)
     * @return true if the cursor is on a row, false if it ended up before the first or after the last row
     * @throws SQLException
     *             if the result set is closed or forward-only, or reading from the db2 utility fails
     */
    @Override
    public boolean absolute(final int _row) throws SQLException {
        checkScrollable();
        loadAll();
        final int n = m_all.size();
        final int target = _row >= 0 ? _row : n + 1 + _row;
        if (target <= 0) {
            beforeFirst();
            return false;
        }
        if (target > n) {
            afterLast();
            return false;
        }
        m_rowNumber = target;
        m_afterLast = false;
        m_row = m_all.get(target - 1);
        return true;
    }

    /**
     * Moves the cursor a number of rows relative to the current position. On a forward-only result set only
     * forward moves are allowed, and they are performed by calling {@link #next()} repeatedly.
     *
     * @param _rows
     *            the number of rows to move; negative moves backwards (scrollable result sets only)
     * @return true if the cursor is on a row afterwards
     * @throws SQLException
     *             if the result set is closed, a backward move is requested on a forward-only result set, or
     *             reading from the db2 utility fails
     */
    @Override
    public boolean relative(final int _rows) throws SQLException {
        checkOpen();
        if (TYPE_FORWARD_ONLY == m_type) {
            if (_rows < 0) {
                checkScrollable();
            }
            boolean ok = null != m_row;
            for (int i = 0; i < _rows; i++) {
                ok = next();
                if (!ok) {
                    break;
                }
            }
            return ok;
        }
        loadAll();
        final int current = m_afterLast ? m_all.size() + 1 : m_rowNumber;
        final int target = current + _rows;
        if (target <= 0) {
            beforeFirst();
            return false;
        }
        if (target > m_all.size()) {
            afterLast();
            return false;
        }
        return absolute(target);
    }

    @Override
    public boolean previous() throws SQLException {
        checkScrollable();
        return relative(-1);
    }

    /**
     * Records the fetch direction hint.
     *
     * @param _direction
     *            the {@code ResultSet.FETCH_*} value
     * @throws SQLException
     *             if the result set is closed, or a direction other than FETCH_FORWARD is requested on a
     *             forward-only result set
     */
    @Override
    public void setFetchDirection(final int _direction) throws SQLException {
        checkOpen();
        if (FETCH_FORWARD != _direction && TYPE_FORWARD_ONLY == m_type) {
            throw new SQLException("Only FETCH_FORWARD is allowed on a forward-only result set", "HY106");
        }
        m_fetchDirection = _direction;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return m_fetchDirection;
    }

    @Override
    public void setFetchSize(final int _rows) throws SQLException {
        checkOpen();
        m_fetchSize = _rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return m_fetchSize;
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return m_type;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return m_holdability;
    }

    @Override
    public boolean isClosed() {
        return m_closed;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return m_statement;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return m_warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        m_warnings = null;
    }

    @Override
    public String getCursorName() throws SQLException {
        throw SqlErrors.unsupported("getCursorName");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new Db2ShellResultSetMetaData(m_columns);
    }

    /**
     * Finds a column by label: first an exact match, then case-insensitively, then with surrounding double
     * quotes removed. When labels repeat, the first matching column is returned.
     *
     * @param _label
     *            the column label
     * @return the 1-based column index
     * @throws SQLException
     *             if the result set is closed or no column has that label
     */
    @Override
    public int findColumn(final String _label) throws SQLException {
        checkOpen();
        Integer i = m_labelIndex.get(_label);
        if (null == i) {
            i = m_labelIndex.get(_label.toUpperCase(Locale.ROOT));
        }
        if (null == i && _label.startsWith("\"") && _label.endsWith("\"") && _label.length() > 1) {
            i = m_labelIndex.get(_label.substring(1, _label.length() - 1));
        }
        if (null == i) {
            throw new SQLException("Column '" + _label + "' not found", "42703");
        }
        return i;
    }

    // =================================================================================== getters

    @Override
    public String getString(final int _col) throws SQLException {
        return truncate(Values.toString(value(_col)));
    }

    @Override
    public boolean getBoolean(final int _col) throws SQLException {
        return Values.toBoolean(value(_col));
    }

    @Override
    public byte getByte(final int _col) throws SQLException {
        return Values.toByte(value(_col));
    }

    @Override
    public short getShort(final int _col) throws SQLException {
        return Values.toShort(value(_col));
    }

    @Override
    public int getInt(final int _col) throws SQLException {
        return Values.toInt(value(_col));
    }

    @Override
    public long getLong(final int _col) throws SQLException {
        return Values.toLong(value(_col));
    }

    @Override
    public float getFloat(final int _col) throws SQLException {
        return (float) Values.toDouble(value(_col));
    }

    @Override
    public double getDouble(final int _col) throws SQLException {
        return Values.toDouble(value(_col));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(final int _col, final int _scale) throws SQLException {
        final BigDecimal bd = Values.toBigDecimal(value(_col));
        return null == bd ? null : bd.setScale(_scale, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getBigDecimal(final int _col) throws SQLException {
        return Values.toBigDecimal(value(_col));
    }

    @Override
    public byte[] getBytes(final int _col) throws SQLException {
        return Values.toBytes(value(_col));
    }

    @Override
    public Date getDate(final int _col) throws SQLException {
        return Values.toDate(value(_col), null);
    }

    @Override
    public Time getTime(final int _col) throws SQLException {
        return Values.toTime(value(_col), null);
    }

    @Override
    public Timestamp getTimestamp(final int _col) throws SQLException {
        return Values.toTimestamp(value(_col), null);
    }

    @Override
    public Date getDate(final int _col, final Calendar _cal) throws SQLException {
        return Values.toDate(value(_col), _cal);
    }

    @Override
    public Time getTime(final int _col, final Calendar _cal) throws SQLException {
        return Values.toTime(value(_col), _cal);
    }

    @Override
    public Timestamp getTimestamp(final int _col, final Calendar _cal) throws SQLException {
        return Values.toTimestamp(value(_col), _cal);
    }

    @Override
    public InputStream getAsciiStream(final int _col) throws SQLException {
        final String s = Values.toString(value(_col));
        return null == s ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(final int _col) throws SQLException {
        final String s = Values.toString(value(_col));
        return null == s ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_16BE));
    }

    /**
     * Returns a column as a binary stream: the bytes of a binary value, otherwise the UTF-8 encoding of its
     * string form.
     *
     * @param _col
     *            the 1-based column index
     * @return the stream, or null for SQL NULL
     * @throws SQLException
     *             if the result set is closed, there is no current row, or the index is out of range
     */
    @Override
    public InputStream getBinaryStream(final int _col) throws SQLException {
        final Object v = value(_col);
        if (null == v) {
            return null;
        }
        return new ByteArrayInputStream(v instanceof byte[] ? (byte[]) v : Values.toString(v).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Reader getCharacterStream(final int _col) throws SQLException {
        final String s = Values.toString(value(_col));
        return null == s ? null : new StringReader(s);
    }

    /**
     * Returns a column as its default Java object. LOB and XML values are wrapped in {@link Clob},
     * {@link Blob} and {@link SQLXML} objects; strings are cut to the statement's maximum field size.
     *
     * @param _col
     *            the 1-based column index
     * @return the value, or null for SQL NULL
     * @throws SQLException
     *             if the result set is closed, there is no current row, or the index is out of range
     */
    @Override
    public Object getObject(final int _col) throws SQLException {
        final Object v = value(_col);
        if (null == v) {
            return null;
        }
        switch (m_columns.get(_col - 1).getKind()) {
        case CLOB:
            return new Lobs.ClobImpl((String) v);
        case BLOB:
            return new Lobs.BlobImpl((byte[]) v);
        case XML:
            return new Lobs.SqlXmlImpl((String) v);
        case STRING:
            return truncate((String) v);
        default:
            return v;
        }
    }

    @Override
    public Object getObject(final int _col, final Map<String, Class<?>> _map) throws SQLException {
        return getObject(_col);
    }

    @Override
    public <T> T getObject(final int _col, final Class<T> _type) throws SQLException {
        if (null == _type) {
            throw new SQLException("Type must not be null", "HY009");
        }
        return Values.toClass(value(_col), _type);
    }

    @Override
    public Ref getRef(final int _col) throws SQLException {
        throw SqlErrors.unsupported("getRef");
    }

    @Override
    public Blob getBlob(final int _col) throws SQLException {
        final Object v = value(_col);
        return null == v ? null : new Lobs.BlobImpl(Values.toBytes(v));
    }

    @Override
    public Clob getClob(final int _col) throws SQLException {
        final Object v = value(_col);
        return null == v ? null : new Lobs.ClobImpl(Values.toString(v));
    }

    @Override
    public Array getArray(final int _col) throws SQLException {
        throw SqlErrors.unsupported("getArray");
    }

    /**
     * Returns a column parsed as a {@link URL}.
     *
     * @param _col
     *            the 1-based column index
     * @return the URL, or null for SQL NULL
     * @throws SQLException
     *             if the result set is closed, there is no current row, the index is out of range, or the value
     *             is not a valid URL
     */
    @Override
    public URL getURL(final int _col) throws SQLException {
        final String s = Values.toString(value(_col));
        if (null == s) {
            return null;
        }
        try {
            return new URL(s.trim());
        } catch (final MalformedURLException e) {
            throw new SQLException("Not a valid URL: " + s, "22018", e);
        }
    }

    /**
     * Returns a column as a {@link RowId} wrapping a copy of its bytes.
     *
     * @param _col
     *            the 1-based column index
     * @return the row id, or null for SQL NULL
     * @throws SQLException
     *             if the result set is closed, there is no current row, or the index is out of range
     */
    @Override
    public RowId getRowId(final int _col) throws SQLException {
        final Object v = value(_col);
        if (null == v) {
            return null;
        }
        final byte[] b = Values.toBytes(v);
        return new RowId() {
            @Override
            public byte[] getBytes() {
                return b.clone();
            }

            @Override
            public String toString() {
                return Values.toString(b);
            }
        };
    }

    @Override
    public NClob getNClob(final int _col) throws SQLException {
        final Object v = value(_col);
        return null == v ? null : new Lobs.ClobImpl(Values.toString(v));
    }

    @Override
    public SQLXML getSQLXML(final int _col) throws SQLException {
        final Object v = value(_col);
        return null == v ? null : new Lobs.SqlXmlImpl(Values.toString(v));
    }

    @Override
    public String getNString(final int _col) throws SQLException {
        return getString(_col);
    }

    @Override
    public Reader getNCharacterStream(final int _col) throws SQLException {
        return getCharacterStream(_col);
    }

    // ------------------------------------------------------------------ by label

    @Override
    public String getString(final String _l) throws SQLException {
        return getString(findColumn(_l));
    }

    @Override
    public boolean getBoolean(final String _l) throws SQLException {
        return getBoolean(findColumn(_l));
    }

    @Override
    public byte getByte(final String _l) throws SQLException {
        return getByte(findColumn(_l));
    }

    @Override
    public short getShort(final String _l) throws SQLException {
        return getShort(findColumn(_l));
    }

    @Override
    public int getInt(final String _l) throws SQLException {
        return getInt(findColumn(_l));
    }

    @Override
    public long getLong(final String _l) throws SQLException {
        return getLong(findColumn(_l));
    }

    @Override
    public float getFloat(final String _l) throws SQLException {
        return getFloat(findColumn(_l));
    }

    @Override
    public double getDouble(final String _l) throws SQLException {
        return getDouble(findColumn(_l));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(final String _l, final int _scale) throws SQLException {
        return getBigDecimal(findColumn(_l), _scale);
    }

    @Override
    public byte[] getBytes(final String _l) throws SQLException {
        return getBytes(findColumn(_l));
    }

    @Override
    public Date getDate(final String _l) throws SQLException {
        return getDate(findColumn(_l));
    }

    @Override
    public Time getTime(final String _l) throws SQLException {
        return getTime(findColumn(_l));
    }

    @Override
    public Timestamp getTimestamp(final String _l) throws SQLException {
        return getTimestamp(findColumn(_l));
    }

    @Override
    public InputStream getAsciiStream(final String _l) throws SQLException {
        return getAsciiStream(findColumn(_l));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(final String _l) throws SQLException {
        return getUnicodeStream(findColumn(_l));
    }

    @Override
    public InputStream getBinaryStream(final String _l) throws SQLException {
        return getBinaryStream(findColumn(_l));
    }

    @Override
    public Object getObject(final String _l) throws SQLException {
        return getObject(findColumn(_l));
    }

    @Override
    public Reader getCharacterStream(final String _l) throws SQLException {
        return getCharacterStream(findColumn(_l));
    }

    @Override
    public BigDecimal getBigDecimal(final String _l) throws SQLException {
        return getBigDecimal(findColumn(_l));
    }

    @Override
    public Object getObject(final String _l, final Map<String, Class<?>> _map) throws SQLException {
        return getObject(findColumn(_l), _map);
    }

    @Override
    public Ref getRef(final String _l) throws SQLException {
        return getRef(findColumn(_l));
    }

    @Override
    public Blob getBlob(final String _l) throws SQLException {
        return getBlob(findColumn(_l));
    }

    @Override
    public Clob getClob(final String _l) throws SQLException {
        return getClob(findColumn(_l));
    }

    @Override
    public Array getArray(final String _l) throws SQLException {
        return getArray(findColumn(_l));
    }

    @Override
    public Date getDate(final String _l, final Calendar _cal) throws SQLException {
        return getDate(findColumn(_l), _cal);
    }

    @Override
    public Time getTime(final String _l, final Calendar _cal) throws SQLException {
        return getTime(findColumn(_l), _cal);
    }

    @Override
    public Timestamp getTimestamp(final String _l, final Calendar _cal) throws SQLException {
        return getTimestamp(findColumn(_l), _cal);
    }

    @Override
    public URL getURL(final String _l) throws SQLException {
        return getURL(findColumn(_l));
    }

    @Override
    public RowId getRowId(final String _l) throws SQLException {
        return getRowId(findColumn(_l));
    }

    @Override
    public NClob getNClob(final String _l) throws SQLException {
        return getNClob(findColumn(_l));
    }

    @Override
    public SQLXML getSQLXML(final String _l) throws SQLException {
        return getSQLXML(findColumn(_l));
    }

    @Override
    public String getNString(final String _l) throws SQLException {
        return getNString(findColumn(_l));
    }

    @Override
    public Reader getNCharacterStream(final String _l) throws SQLException {
        return getNCharacterStream(findColumn(_l));
    }

    @Override
    public <T> T getObject(final String _l, final Class<T> _type) throws SQLException {
        return getObject(findColumn(_l), _type);
    }

    @Override
    public <T> T unwrap(final Class<T> _iface) throws SQLException {
        if (_iface.isInstance(this)) {
            return _iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + _iface.getName(), "HY000");
    }

    @Override
    public boolean isWrapperFor(final Class<?> _iface) {
        return _iface.isInstance(this);
    }

    /** Raw access used by the CLI tool. */
    Object rawValue(final int _col) throws SQLException {
        return value(_col);
    }
}
