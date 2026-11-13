package io.github.db2shell.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.db2shell.jdbc.internal.Engine;
import io.github.db2shell.jdbc.internal.Lobs;
import io.github.db2shell.jdbc.internal.SqlErrors;
import io.github.db2shell.jdbc.internal.SqlText;
import io.github.db2shell.jdbc.internal.Values;

/**
 * A callable statement. IN parameters are substituted as literals. OUT and INOUT parameters (and the return
 * value of {@code {? = call func(...)}}) are supported by running the call inside a compound statement that
 * declares a variable per output parameter and copies the results into a temporary table, which is then read.
 * Result sets returned by procedures are not available through the db2 utility.
 */
public class Db2ShellCallableStatement extends Db2ShellPreparedStatement implements CallableStatement {

    private static final Pattern s_returnCall = Pattern.compile("(?is)^\\s*\\{\\s*\\?\\s*=\\s*call\\s+(.+?)\\s*\\}\\s*;?\\s*$");
    private static final int NOT_OUT = Integer.MIN_VALUE;

    private final boolean m_returnValue;
    private final int[] m_outTypes;
    private final int[] m_outScales;
    private Object[] m_outValues;
    private boolean m_lastWasNull = false;

    /**
     * Creates a callable statement. For the {@code {? = call f(...)}} form, parameter 1 is the function's return
     * value and is pre-registered as a VARCHAR output parameter.
     *
     * @param _conn
     *            the connection that owns this statement
     * @param _sql
     *            the CALL statement text, or {@code {? = call f(...)}}
     * @param _type
     *            the result set type ({@code ResultSet.TYPE_*})
     * @param _concurrency
     *            the result set concurrency ({@code ResultSet.CONCUR_*})
     * @param _holdability
     *            the result set holdability ({@code ResultSet.*_CURSORS_*})
     * @throws SQLException
     *             if the SQL text cannot be parsed
     */
    Db2ShellCallableStatement(final Db2ShellConnection _conn, final String _sql, final int _type,
            final int _concurrency, final int _holdability) throws SQLException {
        super(_conn, parseCall(_conn, _sql), _type, _concurrency, _holdability);
        m_returnValue = s_returnCall.matcher(_sql).matches();
        m_outTypes = new int[m_parsed.getParameterCount()];
        m_outScales = new int[m_parsed.getParameterCount()];
        Arrays.fill(m_outTypes, NOT_OUT);
        Arrays.fill(m_outScales, -1);
        if (m_returnValue) {
            // the return value is always an output parameter; default its type until registered
            m_outTypes[0] = Types.VARCHAR;
            m_literals[0] = "";
        }
    }

    /**
     * Parses the call text, rewriting the {@code {? = call f(...)}} form to {@code SET ? = f(...)}.
     *
     * @param _conn
     *            the connection, whose charset is used for parsing
     * @param _sql
     *            the statement text
     * @return the parsed statement
     * @throws SQLException
     *             if the SQL text cannot be parsed
     */
    private static SqlText parseCall(final Db2ShellConnection _conn, final String _sql) throws SQLException {
        final Matcher m = s_returnCall.matcher(_sql);
        if (m.matches()) {
            // {? = call f(...)} becomes SET ? = f(...) inside the compound statement
            return SqlText.parse("SET ? = " + m.group(1), _conn.engine().getCharset(), true);
        }
        return SqlText.parse(_sql, _conn.engine().getCharset(), true);
    }

    // =================================================================================== registration

    @Override
    public void registerOutParameter(final int _i, final int _sqlType) throws SQLException {
        registerOutParameter(_i, _sqlType, -1);
    }

    /**
     * Registers a parameter as OUT (or INOUT, if an input value is also set).
     *
     * @param _i
     *            the 1-based parameter index
     * @param _sqlType
     *            the {@link Types} constant of the output value
     * @param _scale
     *            the number of decimal digits for DECIMAL/NUMERIC, or -1 for the default
     * @throws SQLException
     *             if the index is invalid or the statement is closed
     */
    @Override
    public void registerOutParameter(final int _i, final int _sqlType, final int _scale) throws SQLException {
        final int i = index(_i);
        m_outTypes[i] = _sqlType;
        m_outScales[i] = _scale;
        if (null == m_literals[i]) {
            m_literals[i] = ""; // OUT-only: no input value required
        }
    }

    @Override
    public void registerOutParameter(final int _i, final int _sqlType, final String _typeName) throws SQLException {
        registerOutParameter(_i, _sqlType, -1);
    }

    /**
     * Clears all input values. Output registrations are kept, so registered OUT parameters remain usable without
     * an input value.
     *
     * @throws SQLException
     *             if the statement is closed
     */
    @Override
    public void clearParameters() throws SQLException {
        super.clearParameters();
        for (int i = 0; i < m_outTypes.length; i++) {
            if (NOT_OUT != m_outTypes[i]) {
                m_literals[i] = "";
            }
        }
    }

    /**
     * Tells whether any parameter is registered for output.
     *
     * @return true if at least one parameter is OUT or INOUT
     */
    private boolean hasOutputs() {
        for (final int t : m_outTypes) {
            if (NOT_OUT != t) {
                return true;
            }
        }
        return false;
    }

    // =================================================================================== execution

    /**
     * Executes the call. Without output parameters this is a plain execution; otherwise the output values are
     * captured and made available to the getters.
     *
     * @return true if a plain execution produced a result set; always false when there are output parameters
     * @throws SQLException
     *             if a parameter is unset, the statement is closed, or execution fails
     */
    @Override
    public boolean execute() throws SQLException {
        if (!hasOutputs()) {
            return super.execute();
        }
        runWithOutputs();
        return false;
    }

    @Override
    public int executeUpdate() throws SQLException {
        execute();
        return 0;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        execute();
        return 0;
    }

    /**
     * Runs the call inside a compound statement that declares a variable per output parameter, then copies the
     * variables into the SESSION.JDBC_OUT temporary table and reads them back into the output values.
     *
     * @throws SQLException
     *             if an IN parameter is unset, the statement is closed, or the database reports an error
     */
    private void runWithOutputs() throws SQLException {
        checkOpen();
        m_outValues = null;
        final Engine engine = engine();
        final int n = m_outTypes.length;
        int varlen = 0;
        for (final int t : m_outTypes) {
            if (NOT_OUT != t && isVarLength(t)) {
                varlen++;
            }
        }
        final int strLen = Math.max(256, Math.min(32000, 30000 / Math.max(1, varlen)));
        final boolean bools = booleans();
        final StringBuilder table = new StringBuilder("DECLARE GLOBAL TEMPORARY TABLE SESSION.JDBC_OUT (");
        final StringBuilder declares = new StringBuilder("BEGIN ");
        final StringBuilder inits = new StringBuilder();
        final StringBuilder insertCols = new StringBuilder();
        final String[] args = new String[n];
        boolean first = true;
        for (int i = 0; i < n; i++) {
            if (NOT_OUT == m_outTypes[i]) {
                if (null == m_literals[i]) {
                    throw new SQLException("Parameter " + (i + 1) + " has not been set", "07001");
                }
                args[i] = m_literals[i];
                continue;
            }
            final String var = "JDBC_P" + (i + 1);
            final String type = declaredType(m_outTypes[i], m_outScales[i], strLen, bools);
            table.append(first ? "" : ", ").append("P").append(i + 1).append(' ').append(type);
            insertCols.append(first ? "" : ", ").append(var);
            declares.append("DECLARE ").append(var).append(' ').append(type).append("; ");
            if (null != m_literals[i] && !m_literals[i].isEmpty()) {
                inits.append("SET ").append(var).append(" = ").append(m_literals[i]).append("; ");
            }
            args[i] = var;
            first = false;
        }
        table.append(") ON COMMIT PRESERVE ROWS NOT LOGGED ON ROLLBACK PRESERVE ROWS WITH REPLACE");
        final String call = m_parsed.bind(args);
        final String compound = declares.toString() + inits + call + "; INSERT INTO SESSION.JDBC_OUT VALUES ("
                + insertCols + "); END";
        engine.execInternal(table.toString(), getQueryTimeout());
        engine.execInternal(compound, getQueryTimeout());
        final Engine.QueryResult qr = engine.query("SELECT * FROM SESSION.JDBC_OUT", 0, getQueryTimeout());
        final Object[] row = qr.getRows().next();
        qr.getRows().close();
        m_outValues = new Object[n];
        if (null != row) {
            int c = 0;
            for (int i = 0; i < n; i++) {
                if (NOT_OUT != m_outTypes[i]) {
                    m_outValues[i] = row[c++];
                }
            }
        }
        addWarnings(qr.getWarnings());
        m_connection.collectWarnings();
    }

    /**
     * Tells whether a type is declared with the shared variable-length budget in {@link #runWithOutputs()}.
     *
     * @param _t
     *            a {@link Types} constant
     * @return true for character, binary and object types
     */
    private static boolean isVarLength(final int _t) {
        switch (_t) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.NCHAR:
        case Types.NVARCHAR:
        case Types.LONGNVARCHAR:
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.OTHER:
        case Types.JAVA_OBJECT:
            return true;
        default:
            return false;
        }
    }

    /**
     * Maps a registered output type to the SQL type used to declare its variable and temporary table column.
     *
     * @param _t
     *            the registered {@link Types} constant
     * @param _scale
     *            the registered scale, or -1 for the default
     * @param _strLen
     *            the length to use for variable-length character and binary types
     * @param _bools
     *            true if the server supports the BOOLEAN type
     * @return the SQL type declaration
     */
    private String declaredType(final int _t, final int _scale, final int _strLen, final boolean _bools) {
        final int lob = m_connection.config().getLobMaxLength();
        switch (_t) {
        case Types.TINYINT:
        case Types.SMALLINT:
            return "SMALLINT";
        case Types.INTEGER:
            return "INTEGER";
        case Types.BIGINT:
            return "BIGINT";
        case Types.DECIMAL:
        case Types.NUMERIC:
            return "DECIMAL(63, " + (_scale >= 0 ? Math.min(_scale, 62) : 15) + ")";
        case Types.REAL:
            return "REAL";
        case Types.FLOAT:
        case Types.DOUBLE:
            return "DOUBLE";
        case Types.DATE:
            return "DATE";
        case Types.TIME:
            return "TIME";
        case Types.TIMESTAMP:
        case Types.TIMESTAMP_WITH_TIMEZONE:
            return "TIMESTAMP(12)";
        case Types.BIT:
        case Types.BOOLEAN:
            return _bools ? "BOOLEAN" : "SMALLINT";
        case Types.NCHAR:
        case Types.NVARCHAR:
        case Types.LONGNVARCHAR:
            return "VARGRAPHIC(" + Math.max(128, _strLen / 2) + ") CCSID 1200";
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
            return "VARBINARY(" + _strLen + ")";
        case Types.CLOB:
        case Types.SQLXML:
            return "CLOB(" + lob + ")";
        case Types.NCLOB:
            return "DBCLOB(" + lob + ") CCSID 1200";
        case Types.BLOB:
            return "BLOB(" + lob + ")";
        default:
            return "VARCHAR(" + _strLen + ")";
        }
    }

    /**
     * Describes the parameters, reporting each as IN, OUT or INOUT according to its registration and whether an
     * input value is set.
     *
     * @return the parameter metadata
     * @throws SQLException
     *             if the statement is closed
     */
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        final int[] modes = new int[m_literals.length];
        final int[] types = m_paramTypes.clone();
        for (int i = 0; i < modes.length; i++) {
            if (NOT_OUT == m_outTypes[i]) {
                modes[i] = ParameterMetaData.parameterModeIn;
            } else {
                types[i] = m_outTypes[i];
                final boolean in = null != m_literals[i] && !m_literals[i].isEmpty();
                modes[i] = in ? ParameterMetaData.parameterModeInOut : ParameterMetaData.parameterModeOut;
            }
        }
        return new Db2ShellParameterMetaData(types, modes);
    }

    // =================================================================================== getters

    /**
     * Returns an output value from the last execution and records whether it was NULL for {@link #wasNull()}.
     *
     * @param _i
     *            the 1-based parameter index
     * @return the raw output value, or null for SQL NULL
     * @throws SQLException
     *             if the index is invalid, the parameter is not registered for output, the statement has not been
     *             executed, or the statement is closed
     */
    private Object out(final int _i) throws SQLException {
        checkOpen();
        if (_i < 1 || _i > m_outTypes.length) {
            throw new SQLException("Parameter index " + _i + " out of range (1.." + m_outTypes.length + ")", "07009");
        }
        if (NOT_OUT == m_outTypes[_i - 1]) {
            throw new SQLException("Parameter " + _i + " was not registered as an output parameter", "07009");
        }
        if (null == m_outValues) {
            throw new SQLException("The statement has not been executed", "HY010");
        }
        final Object v = m_outValues[_i - 1];
        m_lastWasNull = null == v;
        return v;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return m_lastWasNull;
    }

    @Override
    public String getString(final int _i) throws SQLException {
        return Values.toString(out(_i));
    }

    @Override
    public boolean getBoolean(final int _i) throws SQLException {
        return Values.toBoolean(out(_i));
    }

    @Override
    public byte getByte(final int _i) throws SQLException {
        return Values.toByte(out(_i));
    }

    @Override
    public short getShort(final int _i) throws SQLException {
        return Values.toShort(out(_i));
    }

    @Override
    public int getInt(final int _i) throws SQLException {
        return Values.toInt(out(_i));
    }

    @Override
    public long getLong(final int _i) throws SQLException {
        return Values.toLong(out(_i));
    }

    @Override
    public float getFloat(final int _i) throws SQLException {
        return (float) Values.toDouble(out(_i));
    }

    @Override
    public double getDouble(final int _i) throws SQLException {
        return Values.toDouble(out(_i));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(final int _i, final int _scale) throws SQLException {
        final BigDecimal bd = Values.toBigDecimal(out(_i));
        return null == bd ? null : bd.setScale(_scale, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getBigDecimal(final int _i) throws SQLException {
        return Values.toBigDecimal(out(_i));
    }

    @Override
    public byte[] getBytes(final int _i) throws SQLException {
        return Values.toBytes(out(_i));
    }

    @Override
    public Date getDate(final int _i) throws SQLException {
        return Values.toDate(out(_i), null);
    }

    @Override
    public Time getTime(final int _i) throws SQLException {
        return Values.toTime(out(_i), null);
    }

    @Override
    public Timestamp getTimestamp(final int _i) throws SQLException {
        return Values.toTimestamp(out(_i), null);
    }

    @Override
    public Date getDate(final int _i, final Calendar _cal) throws SQLException {
        return Values.toDate(out(_i), _cal);
    }

    @Override
    public Time getTime(final int _i, final Calendar _cal) throws SQLException {
        return Values.toTime(out(_i), _cal);
    }

    @Override
    public Timestamp getTimestamp(final int _i, final Calendar _cal) throws SQLException {
        return Values.toTimestamp(out(_i), _cal);
    }

    /**
     * Returns an output value as an object; LOB and XML output types are wrapped in their JDBC interfaces.
     *
     * @param _i
     *            the 1-based parameter index
     * @return the value, or null for SQL NULL
     * @throws SQLException
     *             if the parameter is not a readable output parameter
     */
    @Override
    public Object getObject(final int _i) throws SQLException {
        final Object v = out(_i);
        if (null == v) {
            return null;
        }
        switch (m_outTypes[_i - 1]) {
        case Types.CLOB:
        case Types.NCLOB:
            return new Lobs.ClobImpl(Values.toString(v));
        case Types.BLOB:
            return new Lobs.BlobImpl(Values.toBytes(v));
        case Types.SQLXML:
            return new Lobs.SqlXmlImpl(Values.toString(v));
        default:
            return v;
        }
    }

    @Override
    public Object getObject(final int _i, final Map<String, Class<?>> _map) throws SQLException {
        return getObject(_i);
    }

    @Override
    public <T> T getObject(final int _i, final Class<T> _type) throws SQLException {
        return Values.toClass(out(_i), _type);
    }

    @Override
    public Ref getRef(final int _i) throws SQLException {
        throw SqlErrors.unsupported("getRef");
    }

    @Override
    public Blob getBlob(final int _i) throws SQLException {
        final Object v = out(_i);
        return null == v ? null : new Lobs.BlobImpl(Values.toBytes(v));
    }

    @Override
    public Clob getClob(final int _i) throws SQLException {
        final Object v = out(_i);
        return null == v ? null : new Lobs.ClobImpl(Values.toString(v));
    }

    @Override
    public NClob getNClob(final int _i) throws SQLException {
        final Object v = out(_i);
        return null == v ? null : new Lobs.ClobImpl(Values.toString(v));
    }

    @Override
    public SQLXML getSQLXML(final int _i) throws SQLException {
        final Object v = out(_i);
        return null == v ? null : new Lobs.SqlXmlImpl(Values.toString(v));
    }

    @Override
    public Array getArray(final int _i) throws SQLException {
        throw SqlErrors.unsupported("getArray");
    }

    /**
     * Returns an output value parsed as a URL.
     *
     * @param _i
     *            the 1-based parameter index
     * @return the URL, or null for SQL NULL
     * @throws SQLException
     *             if the parameter is not a readable output parameter, or the value is not a valid URL
     */
    @Override
    public URL getURL(final int _i) throws SQLException {
        final String s = Values.toString(out(_i));
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
    public RowId getRowId(final int _i) throws SQLException {
        throw SqlErrors.unsupported("getRowId");
    }

    @Override
    public String getNString(final int _i) throws SQLException {
        return getString(_i);
    }

    @Override
    public Reader getNCharacterStream(final int _i) throws SQLException {
        return getCharacterStream(_i);
    }

    @Override
    public Reader getCharacterStream(final int _i) throws SQLException {
        final String s = getString(_i);
        return null == s ? null : new java.io.StringReader(s);
    }

    // =================================================================================== named parameters

    private static SQLException named() {
        return SqlErrors.unsupported("Named parameters");
    }

    @Override
    public void registerOutParameter(final String _n, final int _t) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(final String _n, final int _t, final int _s) throws SQLException {
        throw named();
    }

    @Override
    public void registerOutParameter(final String _n, final int _t, final String _tn) throws SQLException {
        throw named();
    }

    @Override
    public URL getURL(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public void setURL(final String _n, final URL _x) throws SQLException {
        throw named();
    }

    @Override
    public void setNull(final String _n, final int _t) throws SQLException {
        throw named();
    }

    @Override
    public void setBoolean(final String _n, final boolean _x) throws SQLException {
        throw named();
    }

    @Override
    public void setByte(final String _n, final byte _x) throws SQLException {
        throw named();
    }

    @Override
    public void setShort(final String _n, final short _x) throws SQLException {
        throw named();
    }

    @Override
    public void setInt(final String _n, final int _x) throws SQLException {
        throw named();
    }

    @Override
    public void setLong(final String _n, final long _x) throws SQLException {
        throw named();
    }

    @Override
    public void setFloat(final String _n, final float _x) throws SQLException {
        throw named();
    }

    @Override
    public void setDouble(final String _n, final double _x) throws SQLException {
        throw named();
    }

    @Override
    public void setBigDecimal(final String _n, final BigDecimal _x) throws SQLException {
        throw named();
    }

    @Override
    public void setString(final String _n, final String _x) throws SQLException {
        throw named();
    }

    @Override
    public void setBytes(final String _n, final byte[] _x) throws SQLException {
        throw named();
    }

    @Override
    public void setDate(final String _n, final Date _x) throws SQLException {
        throw named();
    }

    @Override
    public void setTime(final String _n, final Time _x) throws SQLException {
        throw named();
    }

    @Override
    public void setTimestamp(final String _n, final Timestamp _x) throws SQLException {
        throw named();
    }

    @Override
    public void setAsciiStream(final String _n, final InputStream _x, final int _l) throws SQLException {
        throw named();
    }

    @Override
    public void setBinaryStream(final String _n, final InputStream _x, final int _l) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(final String _n, final Object _x, final int _t, final int _s) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(final String _n, final Object _x, final int _t) throws SQLException {
        throw named();
    }

    @Override
    public void setObject(final String _n, final Object _x) throws SQLException {
        throw named();
    }

    @Override
    public void setCharacterStream(final String _n, final Reader _x, final int _l) throws SQLException {
        throw named();
    }

    @Override
    public void setDate(final String _n, final Date _x, final Calendar _c) throws SQLException {
        throw named();
    }

    @Override
    public void setTime(final String _n, final Time _x, final Calendar _c) throws SQLException {
        throw named();
    }

    @Override
    public void setTimestamp(final String _n, final Timestamp _x, final Calendar _c) throws SQLException {
        throw named();
    }

    @Override
    public void setNull(final String _n, final int _t, final String _tn) throws SQLException {
        throw named();
    }

    @Override
    public String getString(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public boolean getBoolean(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public byte getByte(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public short getShort(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public int getInt(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public long getLong(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public float getFloat(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public double getDouble(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public byte[] getBytes(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Date getDate(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Time getTime(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Timestamp getTimestamp(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Object getObject(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public BigDecimal getBigDecimal(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Object getObject(final String _n, final Map<String, Class<?>> _m) throws SQLException {
        throw named();
    }

    @Override
    public Ref getRef(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Blob getBlob(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Clob getClob(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Array getArray(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Date getDate(final String _n, final Calendar _c) throws SQLException {
        throw named();
    }

    @Override
    public Time getTime(final String _n, final Calendar _c) throws SQLException {
        throw named();
    }

    @Override
    public Timestamp getTimestamp(final String _n, final Calendar _c) throws SQLException {
        throw named();
    }

    @Override
    public RowId getRowId(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public void setRowId(final String _n, final RowId _x) throws SQLException {
        throw named();
    }

    @Override
    public void setNString(final String _n, final String _x) throws SQLException {
        throw named();
    }

    @Override
    public void setNCharacterStream(final String _n, final Reader _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public void setNClob(final String _n, final NClob _x) throws SQLException {
        throw named();
    }

    @Override
    public void setClob(final String _n, final Reader _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public void setBlob(final String _n, final InputStream _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public void setNClob(final String _n, final Reader _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public NClob getNClob(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public void setSQLXML(final String _n, final SQLXML _x) throws SQLException {
        throw named();
    }

    @Override
    public SQLXML getSQLXML(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public String getNString(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Reader getNCharacterStream(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public Reader getCharacterStream(final String _n) throws SQLException {
        throw named();
    }

    @Override
    public void setBlob(final String _n, final Blob _x) throws SQLException {
        throw named();
    }

    @Override
    public void setClob(final String _n, final Clob _x) throws SQLException {
        throw named();
    }

    @Override
    public void setAsciiStream(final String _n, final InputStream _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public void setBinaryStream(final String _n, final InputStream _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public void setCharacterStream(final String _n, final Reader _x, final long _l) throws SQLException {
        throw named();
    }

    @Override
    public void setAsciiStream(final String _n, final InputStream _x) throws SQLException {
        throw named();
    }

    @Override
    public void setBinaryStream(final String _n, final InputStream _x) throws SQLException {
        throw named();
    }

    @Override
    public void setCharacterStream(final String _n, final Reader _x) throws SQLException {
        throw named();
    }

    @Override
    public void setNCharacterStream(final String _n, final Reader _x) throws SQLException {
        throw named();
    }

    @Override
    public void setClob(final String _n, final Reader _x) throws SQLException {
        throw named();
    }

    @Override
    public void setBlob(final String _n, final InputStream _x) throws SQLException {
        throw named();
    }

    @Override
    public void setNClob(final String _n, final Reader _x) throws SQLException {
        throw named();
    }

    @Override
    public <T> T getObject(final String _n, final Class<T> _x) throws SQLException {
        throw named();
    }
}
