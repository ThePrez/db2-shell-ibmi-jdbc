package io.github.db2shell.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.Charset;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import io.github.db2shell.jdbc.internal.ColumnInfo;
import io.github.db2shell.jdbc.internal.Lobs;
import io.github.db2shell.jdbc.internal.Params;
import io.github.db2shell.jdbc.internal.SqlErrors;
import io.github.db2shell.jdbc.internal.SqlLiterals;
import io.github.db2shell.jdbc.internal.SqlText;

/**
 * A prepared statement. The db2 utility cannot bind parameters, so each parameter value is converted to a SQL
 * literal and substituted for its marker when the statement executes. The statement text is parsed once.
 */
public class Db2ShellPreparedStatement extends Db2ShellStatement implements PreparedStatement {

    protected final SqlText parsed;
    protected final String[] literals;
    protected final int[] paramTypes;
    private final int autoKeys;
    private final String[] keyColumns;
    private final List<String> batch = new ArrayList<String>();
    private Boolean booleanSupported;

    Db2ShellPreparedStatement(final Db2ShellConnection _conn, final String _sql, final int _type,
            final int _concurrency, final int _holdability, final int _autoKeys, final String[] _keyColumns)
            throws SQLException {
        super(_conn, _type, _concurrency, _holdability);
        parsed = parse(_sql);
        literals = new String[parsed.getParameterCount()];
        paramTypes = new int[parsed.getParameterCount()];
        Arrays.fill(paramTypes, Types.VARCHAR);
        autoKeys = _autoKeys;
        keyColumns = _keyColumns;
    }

    /** Constructor for subclasses that parse the SQL themselves. */
    Db2ShellPreparedStatement(final Db2ShellConnection _conn, final SqlText _parsed, final int _type,
            final int _concurrency, final int _holdability) {
        super(_conn, _type, _concurrency, _holdability);
        parsed = _parsed;
        literals = new String[_parsed.getParameterCount()];
        paramTypes = new int[_parsed.getParameterCount()];
        Arrays.fill(paramTypes, Types.VARCHAR);
        autoKeys = Statement.NO_GENERATED_KEYS;
        keyColumns = null;
    }

    // =================================================================================== helpers

    protected Charset charset() {
        return engine().getCharset();
    }

    protected boolean booleans() throws SQLException {
        if (null == booleanSupported) {
            booleanSupported = connection.booleanSupported();
        }
        return booleanSupported;
    }

    protected int index(final int _i) throws SQLException {
        checkOpen();
        if (_i < 1 || _i > literals.length) {
            throw new SQLException("Parameter index " + _i + " out of range (1.." + literals.length + ")", "07009");
        }
        return _i - 1;
    }

    protected void set(final int _i, final String _literal, final int _type) throws SQLException {
        final int i = index(_i);
        literals[i] = _literal;
        paramTypes[i] = _type;
    }

    /** The statement text with the current parameter values substituted. */
    protected String boundSql() throws SQLException {
        return parsed.bind(literals);
    }

    // =================================================================================== execution

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!run(parsed, boundSql(), Statement.NO_GENERATED_KEYS, null)) {
            throw new SQLException("The statement did not return a result set", "02000");
        }
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) executeLargeUpdate();
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        if (parsed.isQuery()) {
            throw new SQLException("executeUpdate cannot run a query; use executeQuery", "07003");
        }
        run(parsed, boundSql(), autoKeys, keyColumns);
        return Math.max(0, getLargeUpdateCount());
    }

    @Override
    public boolean execute() throws SQLException {
        return run(parsed, boundSql(), autoKeys, keyColumns);
    }

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        batch.add(boundSql());
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        final List<String> work = new ArrayList<String>(batch);
        batch.clear();
        return runBatch(work, false);
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        Arrays.fill(literals, null);
        Arrays.fill(paramTypes, Types.VARCHAR);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        if (!parsed.isQuery()) {
            return null;
        }
        final String[] vals = new String[literals.length];
        for (int i = 0; i < vals.length; i++) {
            vals[i] = null == literals[i] ? "NULL" : literals[i];
        }
        try {
            final List<ColumnInfo> cols = engine().describe(parsed.bind(vals), getQueryTimeout());
            return new Db2ShellResultSetMetaData(cols);
        } catch (final SQLException e) {
            // e.g. an untyped NULL where a parameter has not been set yet
            return null;
        }
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        final int[] modes = new int[literals.length];
        Arrays.fill(modes, ParameterMetaData.parameterModeIn);
        return new Db2ShellParameterMetaData(paramTypes.clone(), modes);
    }

    // ------------------------------------------------------------------ disallowed Statement methods

    @Override
    public ResultSet executeQuery(final String _sql) throws SQLException {
        throw new SQLException("Cannot call executeQuery(String) on a PreparedStatement", "HY000");
    }

    @Override
    public int executeUpdate(final String _sql) throws SQLException {
        throw new SQLException("Cannot call executeUpdate(String) on a PreparedStatement", "HY000");
    }

    @Override
    public boolean execute(final String _sql) throws SQLException {
        throw new SQLException("Cannot call execute(String) on a PreparedStatement", "HY000");
    }

    @Override
    public void addBatch(final String _sql) throws SQLException {
        throw new SQLException("Cannot call addBatch(String) on a PreparedStatement", "HY000");
    }

    // =================================================================================== setters

    @Override
    public void setNull(final int _i, final int _sqlType) throws SQLException {
        index(_i);
        set(_i, Params.nullLiteral(_sqlType, _sqlType == Types.BOOLEAN || _sqlType == Types.BIT ? booleans() : true),
                _sqlType);
    }

    @Override
    public void setNull(final int _i, final int _sqlType, final String _typeName) throws SQLException {
        setNull(_i, _sqlType);
    }

    @Override
    public void setBoolean(final int _i, final boolean _x) throws SQLException {
        index(_i);
        set(_i, Params.literal(_x, Params.UNSPECIFIED, -1, charset(), booleans()), Types.BOOLEAN);
    }

    @Override
    public void setByte(final int _i, final byte _x) throws SQLException {
        set(_i, Byte.toString(_x), Types.SMALLINT);
    }

    @Override
    public void setShort(final int _i, final short _x) throws SQLException {
        set(_i, Short.toString(_x), Types.SMALLINT);
    }

    @Override
    public void setInt(final int _i, final int _x) throws SQLException {
        set(_i, Integer.toString(_x), Types.INTEGER);
    }

    @Override
    public void setLong(final int _i, final long _x) throws SQLException {
        set(_i, Long.toString(_x), Types.BIGINT);
    }

    @Override
    public void setFloat(final int _i, final float _x) throws SQLException {
        set(_i, Params.literal(_x, Params.UNSPECIFIED, -1, charset(), true), Types.REAL);
    }

    @Override
    public void setDouble(final int _i, final double _x) throws SQLException {
        set(_i, Params.literal(_x, Params.UNSPECIFIED, -1, charset(), true), Types.DOUBLE);
    }

    @Override
    public void setBigDecimal(final int _i, final BigDecimal _x) throws SQLException {
        set(_i, Params.literal(_x, null == _x ? Types.DECIMAL : Params.UNSPECIFIED, -1, charset(), true),
                Types.DECIMAL);
    }

    @Override
    public void setString(final int _i, final String _x) throws SQLException {
        set(_i, Params.literal(_x, null == _x ? Types.VARCHAR : Params.UNSPECIFIED, -1, charset(), true),
                Types.VARCHAR);
    }

    @Override
    public void setNString(final int _i, final String _x) throws SQLException {
        set(_i, null == _x ? Params.nullLiteral(Types.NVARCHAR, true) : SqlLiterals.unicode(_x), Types.NVARCHAR);
    }

    @Override
    public void setBytes(final int _i, final byte[] _x) throws SQLException {
        set(_i, null == _x ? Params.nullLiteral(Types.VARBINARY, true) : SqlLiterals.binary(_x), Types.VARBINARY);
    }

    @Override
    public void setDate(final int _i, final Date _x) throws SQLException {
        set(_i, Params.literal(_x, null == _x ? Types.DATE : Params.UNSPECIFIED, -1, charset(), true), Types.DATE);
    }

    @Override
    public void setTime(final int _i, final Time _x) throws SQLException {
        set(_i, Params.literal(_x, null == _x ? Types.TIME : Params.UNSPECIFIED, -1, charset(), true), Types.TIME);
    }

    @Override
    public void setTimestamp(final int _i, final Timestamp _x) throws SQLException {
        set(_i, Params.literal(_x, null == _x ? Types.TIMESTAMP : Params.UNSPECIFIED, -1, charset(), true),
                Types.TIMESTAMP);
    }

    @Override
    public void setDate(final int _i, final Date _x, final Calendar _cal) throws SQLException {
        if (null == _x || null == _cal) {
            setDate(_i, _x);
            return;
        }
        final LocalDateTime ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(_x.getTime()),
                _cal.getTimeZone().toZoneId());
        set(_i, Params.literal(ldt.toLocalDate(), Params.UNSPECIFIED, -1, charset(), true), Types.DATE);
    }

    @Override
    public void setTime(final int _i, final Time _x, final Calendar _cal) throws SQLException {
        if (null == _x || null == _cal) {
            setTime(_i, _x);
            return;
        }
        final LocalDateTime ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(_x.getTime()),
                _cal.getTimeZone().toZoneId());
        set(_i, Params.literal(ldt.toLocalTime(), Params.UNSPECIFIED, -1, charset(), true), Types.TIME);
    }

    @Override
    public void setTimestamp(final int _i, final Timestamp _x, final Calendar _cal) throws SQLException {
        if (null == _x || null == _cal) {
            setTimestamp(_i, _x);
            return;
        }
        final LocalDateTime ldt = LocalDateTime.ofInstant(_x.toInstant(), _cal.getTimeZone().toZoneId());
        set(_i, Params.literal(ldt, Params.UNSPECIFIED, -1, charset(), true), Types.TIMESTAMP);
    }

    @Override
    public void setObject(final int _i, final Object _x, final int _targetSqlType) throws SQLException {
        setObject(_i, _x, _targetSqlType, -1);
    }

    @Override
    public void setObject(final int _i, final Object _x) throws SQLException {
        index(_i);
        final boolean b = _x instanceof Boolean ? booleans() : true;
        set(_i, Params.literal(_x, Params.UNSPECIFIED, -1, charset(), b), typeOf(_x));
    }

    @Override
    public void setObject(final int _i, final Object _x, final int _targetSqlType, final int _scale)
            throws SQLException {
        index(_i);
        final boolean b = _targetSqlType == Types.BOOLEAN || _targetSqlType == Types.BIT || _x instanceof Boolean
                ? booleans() : true;
        set(_i, Params.literal(_x, _targetSqlType, _scale, charset(), b), _targetSqlType);
    }

    private static int typeOf(final Object _x) {
        if (_x instanceof Integer || _x instanceof Short || _x instanceof Byte) {
            return Types.INTEGER;
        }
        if (_x instanceof Long) {
            return Types.BIGINT;
        }
        if (_x instanceof BigDecimal) {
            return Types.DECIMAL;
        }
        if (_x instanceof Double) {
            return Types.DOUBLE;
        }
        if (_x instanceof Float) {
            return Types.REAL;
        }
        if (_x instanceof Boolean) {
            return Types.BOOLEAN;
        }
        if (_x instanceof byte[]) {
            return Types.VARBINARY;
        }
        if (_x instanceof Date || _x instanceof java.time.LocalDate) {
            return Types.DATE;
        }
        if (_x instanceof Time || _x instanceof java.time.LocalTime) {
            return Types.TIME;
        }
        if (_x instanceof java.util.Date || _x instanceof java.time.temporal.Temporal) {
            return Types.TIMESTAMP;
        }
        return Types.VARCHAR;
    }

    @Override
    public void setAsciiStream(final int _i, final InputStream _x, final int _length) throws SQLException {
        setAsciiStream(_i, _x, (long) _length);
    }

    @Override
    public void setAsciiStream(final int _i, final InputStream _x, final long _length) throws SQLException {
        if (null == _x) {
            setNull(_i, Types.VARCHAR);
            return;
        }
        setString(_i, new String(Lobs.readAll(_x, _length), java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Override
    public void setAsciiStream(final int _i, final InputStream _x) throws SQLException {
        setAsciiStream(_i, _x, -1L);
    }

    @Override
    @Deprecated
    public void setUnicodeStream(final int _i, final InputStream _x, final int _length) throws SQLException {
        if (null == _x) {
            setNull(_i, Types.VARCHAR);
            return;
        }
        setString(_i, new String(Lobs.readAll(_x, _length), java.nio.charset.StandardCharsets.UTF_16BE));
    }

    @Override
    public void setBinaryStream(final int _i, final InputStream _x, final int _length) throws SQLException {
        setBinaryStream(_i, _x, (long) _length);
    }

    @Override
    public void setBinaryStream(final int _i, final InputStream _x, final long _length) throws SQLException {
        setBytes(_i, null == _x ? null : Lobs.readAll(_x, _length));
    }

    @Override
    public void setBinaryStream(final int _i, final InputStream _x) throws SQLException {
        setBinaryStream(_i, _x, -1L);
    }

    @Override
    public void setCharacterStream(final int _i, final Reader _x, final int _length) throws SQLException {
        setCharacterStream(_i, _x, (long) _length);
    }

    @Override
    public void setCharacterStream(final int _i, final Reader _x, final long _length) throws SQLException {
        setString(_i, null == _x ? null : Lobs.readAll(_x, _length));
    }

    @Override
    public void setCharacterStream(final int _i, final Reader _x) throws SQLException {
        setCharacterStream(_i, _x, -1L);
    }

    @Override
    public void setNCharacterStream(final int _i, final Reader _x, final long _length) throws SQLException {
        setNString(_i, null == _x ? null : Lobs.readAll(_x, _length));
    }

    @Override
    public void setNCharacterStream(final int _i, final Reader _x) throws SQLException {
        setNCharacterStream(_i, _x, -1L);
    }

    @Override
    public void setRef(final int _i, final Ref _x) throws SQLException {
        throw SqlErrors.unsupported("setRef");
    }

    @Override
    public void setBlob(final int _i, final Blob _x) throws SQLException {
        setBytes(_i, null == _x ? null : _x.getBytes(1, (int) _x.length()));
    }

    @Override
    public void setBlob(final int _i, final InputStream _x, final long _length) throws SQLException {
        setBinaryStream(_i, _x, _length);
    }

    @Override
    public void setBlob(final int _i, final InputStream _x) throws SQLException {
        setBinaryStream(_i, _x, -1L);
    }

    @Override
    public void setClob(final int _i, final Clob _x) throws SQLException {
        setString(_i, null == _x ? null : _x.getSubString(1, (int) _x.length()));
    }

    @Override
    public void setClob(final int _i, final Reader _x, final long _length) throws SQLException {
        setCharacterStream(_i, _x, _length);
    }

    @Override
    public void setClob(final int _i, final Reader _x) throws SQLException {
        setCharacterStream(_i, _x, -1L);
    }

    @Override
    public void setNClob(final int _i, final NClob _x) throws SQLException {
        setNString(_i, null == _x ? null : _x.getSubString(1, (int) _x.length()));
    }

    @Override
    public void setNClob(final int _i, final Reader _x, final long _length) throws SQLException {
        setNCharacterStream(_i, _x, _length);
    }

    @Override
    public void setNClob(final int _i, final Reader _x) throws SQLException {
        setNCharacterStream(_i, _x, -1L);
    }

    @Override
    public void setSQLXML(final int _i, final SQLXML _x) throws SQLException {
        setString(_i, null == _x ? null : _x.getString());
    }

    @Override
    public void setArray(final int _i, final Array _x) throws SQLException {
        throw SqlErrors.unsupported("setArray");
    }

    @Override
    public void setURL(final int _i, final URL _x) throws SQLException {
        setString(_i, null == _x ? null : _x.toString());
    }

    @Override
    public void setRowId(final int _i, final RowId _x) throws SQLException {
        setBytes(_i, null == _x ? null : _x.getBytes());
    }
}
