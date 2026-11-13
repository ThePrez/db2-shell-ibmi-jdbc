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

    private final Db2ShellStatement statement;
    private final List<ColumnInfo> columns;
    private final Map<String, Integer> labelIndex = new HashMap<String, Integer>();
    private final int type;
    private final int holdability;
    private RowSource source;
    private List<Object[]> all; // scrollable only
    private Object[] row;
    private int rowNumber = 0; // 1-based; 0 = before first
    private boolean afterLast = false;
    private boolean closed = false;
    private boolean wasNull = false;
    private SQLWarning warnings;
    private int fetchSize = 0;
    private int fetchDirection = FETCH_FORWARD;
    private boolean sourceWarningsTaken = false;

    Db2ShellResultSet(final Db2ShellStatement _stmt, final List<ColumnInfo> _cols, final RowSource _src,
            final int _type, final int _holdability) {
        statement = _stmt;
        columns = _cols;
        source = _src;
        type = _type;
        holdability = _holdability;
        for (int i = _cols.size() - 1; i >= 0; i--) {
            labelIndex.put(_cols.get(i).getName().toUpperCase(Locale.ROOT), i + 1);
        }
        for (int i = _cols.size() - 1; i >= 0; i--) {
            labelIndex.put(_cols.get(i).getName(), i + 1);
        }
    }

    /** Builds an in-memory result set (used for metadata and generated keys). */
    static Db2ShellResultSet ofRows(final Db2ShellStatement _stmt, final List<ColumnInfo> _cols,
            final List<Object[]> _rows) {
        return new Db2ShellResultSet(_stmt, _cols, new RowSource.ListRowSource(_rows), TYPE_SCROLL_INSENSITIVE,
                HOLD_CURSORS_OVER_COMMIT);
    }

    List<ColumnInfo> columns() {
        return columns;
    }

    // =================================================================================== state

    private void checkOpen() throws SQLException {
        if (closed) {
            throw SqlErrors.closed("ResultSet");
        }
    }

    private void checkScrollable() throws SQLException {
        checkOpen();
        if (type == TYPE_FORWARD_ONLY) {
            throw new SQLException("Operation not allowed on a TYPE_FORWARD_ONLY result set", "24000");
        }
    }

    private void loadAll() throws SQLException {
        if (null != all) {
            return;
        }
        all = new ArrayList<Object[]>();
        Object[] r;
        while (null != (r = source.next())) {
            all.add(r);
        }
        takeSourceWarnings();
    }

    private void takeSourceWarnings() {
        if (sourceWarningsTaken) {
            return;
        }
        sourceWarningsTaken = true;
        for (final SQLWarning w : source.getWarnings()) {
            addWarning(w);
        }
    }

    private void addWarning(final SQLWarning _w) {
        if (null == warnings) {
            warnings = _w;
        } else {
            warnings.setNextWarning(_w);
        }
    }

    private Object value(final int _col) throws SQLException {
        checkOpen();
        if (null == row) {
            throw new SQLException("No current row", "24000");
        }
        if (_col < 1 || _col > columns.size()) {
            throw new SQLException("Column index " + _col + " out of range (1.." + columns.size() + ")", "07009");
        }
        final Object v = row[_col - 1];
        wasNull = null == v;
        return v;
    }

    private Object value(final String _label) throws SQLException {
        return value(findColumn(_label));
    }

    void closeQuietlyFromStatement() {
        try {
            closeInternal(false);
        } catch (final SQLException e) {
            // ignore
        }
    }

    private void closeInternal(final boolean _notify) throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        row = null;
        all = null;
        try {
            source.close();
        } finally {
            if (_notify && null != statement) {
                statement.resultClosed(this);
            }
        }
    }

    private String truncate(final String _s) {
        if (null == _s || null == statement) {
            return _s;
        }
        final int max = statement.maxFieldSize();
        return max > 0 && _s.length() > max ? _s.substring(0, max) : _s;
    }

    // =================================================================================== navigation

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (type != TYPE_FORWARD_ONLY) {
            return relative(1);
        }
        if (afterLast) {
            return false;
        }
        row = source.next();
        if (null == row) {
            afterLast = true;
            rowNumber = 0;
            takeSourceWarnings();
            return false;
        }
        rowNumber++;
        return true;
    }

    @Override
    public void close() throws SQLException {
        closeInternal(true);
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        if (type == TYPE_FORWARD_ONLY) {
            return rowNumber == 0 && !afterLast;
        }
        loadAll();
        return rowNumber == 0 && !afterLast && !all.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        if (type == TYPE_FORWARD_ONLY) {
            return afterLast;
        }
        loadAll();
        return afterLast && !all.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return rowNumber == 1;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkScrollable();
        loadAll();
        return rowNumber > 0 && rowNumber == all.size();
    }

    @Override
    public void beforeFirst() throws SQLException {
        checkScrollable();
        loadAll();
        rowNumber = 0;
        row = null;
        afterLast = false;
    }

    @Override
    public void afterLast() throws SQLException {
        checkScrollable();
        loadAll();
        rowNumber = 0;
        row = null;
        afterLast = true;
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
        return afterLast ? 0 : rowNumber;
    }

    @Override
    public boolean absolute(final int _row) throws SQLException {
        checkScrollable();
        loadAll();
        final int n = all.size();
        int target = _row >= 0 ? _row : n + 1 + _row;
        if (target <= 0) {
            beforeFirst();
            return false;
        }
        if (target > n) {
            afterLast();
            return false;
        }
        rowNumber = target;
        afterLast = false;
        row = all.get(target - 1);
        return true;
    }

    @Override
    public boolean relative(final int _rows) throws SQLException {
        checkOpen();
        if (type == TYPE_FORWARD_ONLY) {
            if (_rows < 0) {
                checkScrollable();
            }
            boolean ok = null != row;
            for (int i = 0; i < _rows; i++) {
                ok = next();
                if (!ok) {
                    break;
                }
            }
            return ok;
        }
        loadAll();
        final int current = afterLast ? all.size() + 1 : rowNumber;
        final int target = current + _rows;
        if (target <= 0) {
            beforeFirst();
            return false;
        }
        if (target > all.size()) {
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

    @Override
    public void setFetchDirection(final int _direction) throws SQLException {
        checkOpen();
        if (_direction != FETCH_FORWARD && type == TYPE_FORWARD_ONLY) {
            throw new SQLException("Only FETCH_FORWARD is allowed on a forward-only result set", "HY106");
        }
        fetchDirection = _direction;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return fetchDirection;
    }

    @Override
    public void setFetchSize(final int _rows) throws SQLException {
        checkOpen();
        fetchSize = _rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return type;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public String getCursorName() throws SQLException {
        throw SqlErrors.unsupported("getCursorName");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new Db2ShellResultSetMetaData(columns);
    }

    @Override
    public int findColumn(final String _label) throws SQLException {
        checkOpen();
        Integer i = labelIndex.get(_label);
        if (null == i) {
            i = labelIndex.get(_label.toUpperCase(Locale.ROOT));
        }
        if (null == i && _label.startsWith("\"") && _label.endsWith("\"") && _label.length() > 1) {
            i = labelIndex.get(_label.substring(1, _label.length() - 1));
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

    @Override
    public Object getObject(final int _col) throws SQLException {
        final Object v = value(_col);
        if (null == v) {
            return null;
        }
        switch (columns.get(_col - 1).getKind()) {
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
