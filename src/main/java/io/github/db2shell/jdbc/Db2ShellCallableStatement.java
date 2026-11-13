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

    private static final Pattern RETURN_CALL = Pattern.compile("(?is)^\\s*\\{\\s*\\?\\s*=\\s*call\\s+(.+?)\\s*\\}\\s*;?\\s*$");
    private static final int NOT_OUT = Integer.MIN_VALUE;

    private final boolean returnValue;
    private final int[] outTypes;
    private final int[] outScales;
    private Object[] outValues;
    private boolean lastWasNull = false;

    Db2ShellCallableStatement(final Db2ShellConnection _conn, final String _sql, final int _type,
            final int _concurrency, final int _holdability) throws SQLException {
        super(_conn, parseCall(_conn, _sql), _type, _concurrency, _holdability);
        returnValue = RETURN_CALL.matcher(_sql).matches();
        outTypes = new int[parsed.getParameterCount()];
        outScales = new int[parsed.getParameterCount()];
        Arrays.fill(outTypes, NOT_OUT);
        Arrays.fill(outScales, -1);
        if (returnValue) {
            // the return value is always an output parameter; default its type until registered
            outTypes[0] = Types.VARCHAR;
            literals[0] = "";
        }
    }

    private static SqlText parseCall(final Db2ShellConnection _conn, final String _sql) throws SQLException {
        final Matcher m = RETURN_CALL.matcher(_sql);
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

    @Override
    public void registerOutParameter(final int _i, final int _sqlType, final int _scale) throws SQLException {
        final int i = index(_i);
        outTypes[i] = _sqlType;
        outScales[i] = _scale;
        if (null == literals[i]) {
            literals[i] = ""; // OUT-only: no input value required
        }
    }

    @Override
    public void registerOutParameter(final int _i, final int _sqlType, final String _typeName) throws SQLException {
        registerOutParameter(_i, _sqlType, -1);
    }

    @Override
    public void clearParameters() throws SQLException {
        super.clearParameters();
        for (int i = 0; i < outTypes.length; i++) {
            if (outTypes[i] != NOT_OUT) {
                literals[i] = "";
            }
        }
    }

    private boolean hasOutputs() {
        for (final int t : outTypes) {
            if (t != NOT_OUT) {
                return true;
            }
        }
        return false;
    }

    // =================================================================================== execution

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

    private void runWithOutputs() throws SQLException {
        checkOpen();
        outValues = null;
        final Engine engine = engine();
        final int n = outTypes.length;
        int varlen = 0;
        for (final int t : outTypes) {
            if (t != NOT_OUT && isVarLength(t)) {
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
            if (outTypes[i] == NOT_OUT) {
                if (null == literals[i]) {
                    throw new SQLException("Parameter " + (i + 1) + " has not been set", "07001");
                }
                args[i] = literals[i];
                continue;
            }
            final String var = "JDBC_P" + (i + 1);
            final String type = declaredType(outTypes[i], outScales[i], strLen, bools);
            table.append(first ? "" : ", ").append("P").append(i + 1).append(' ').append(type);
            insertCols.append(first ? "" : ", ").append(var);
            declares.append("DECLARE ").append(var).append(' ').append(type).append("; ");
            if (null != literals[i] && !literals[i].isEmpty()) {
                inits.append("SET ").append(var).append(" = ").append(literals[i]).append("; ");
            }
            args[i] = var;
            first = false;
        }
        table.append(") ON COMMIT PRESERVE ROWS NOT LOGGED ON ROLLBACK PRESERVE ROWS WITH REPLACE");
        final String call = parsed.bind(args);
        final String compound = declares.toString() + inits + call + "; INSERT INTO SESSION.JDBC_OUT VALUES ("
                + insertCols + "); END";
        engine.execInternal(table.toString(), getQueryTimeout());
        engine.execInternal(compound, getQueryTimeout());
        final Engine.QueryResult qr = engine.query("SELECT * FROM SESSION.JDBC_OUT", 0, getQueryTimeout());
        final Object[] row = qr.rows.next();
        qr.rows.close();
        outValues = new Object[n];
        if (null != row) {
            int c = 0;
            for (int i = 0; i < n; i++) {
                if (outTypes[i] != NOT_OUT) {
                    outValues[i] = row[c++];
                }
            }
        }
        addWarnings(qr.warnings);
        connection.collectWarnings();
    }

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

    private String declaredType(final int _t, final int _scale, final int _strLen, final boolean _bools) {
        final int lob = connection.config().getLobMaxLength();
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

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        final int[] modes = new int[literals.length];
        final int[] types = paramTypes.clone();
        for (int i = 0; i < modes.length; i++) {
            if (outTypes[i] == NOT_OUT) {
                modes[i] = ParameterMetaData.parameterModeIn;
            } else {
                types[i] = outTypes[i];
                final boolean in = null != literals[i] && !literals[i].isEmpty();
                modes[i] = in ? ParameterMetaData.parameterModeInOut : ParameterMetaData.parameterModeOut;
            }
        }
        return new Db2ShellParameterMetaData(types, modes);
    }

    // =================================================================================== getters

    private Object out(final int _i) throws SQLException {
        checkOpen();
        if (_i < 1 || _i > outTypes.length) {
            throw new SQLException("Parameter index " + _i + " out of range (1.." + outTypes.length + ")", "07009");
        }
        if (outTypes[_i - 1] == NOT_OUT) {
            throw new SQLException("Parameter " + _i + " was not registered as an output parameter", "07009");
        }
        if (null == outValues) {
            throw new SQLException("The statement has not been executed", "HY010");
        }
        final Object v = outValues[_i - 1];
        lastWasNull = null == v;
        return v;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastWasNull;
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

    @Override
    public Object getObject(final int _i) throws SQLException {
        final Object v = out(_i);
        if (null == v) {
            return null;
        }
        switch (outTypes[_i - 1]) {
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

    @Override public void registerOutParameter(String _n, int _t) throws SQLException { throw named(); }
    @Override public void registerOutParameter(String _n, int _t, int _s) throws SQLException { throw named(); }
    @Override public void registerOutParameter(String _n, int _t, String _tn) throws SQLException { throw named(); }
    @Override public URL getURL(String _n) throws SQLException { throw named(); }
    @Override public void setURL(String _n, URL _x) throws SQLException { throw named(); }
    @Override public void setNull(String _n, int _t) throws SQLException { throw named(); }
    @Override public void setBoolean(String _n, boolean _x) throws SQLException { throw named(); }
    @Override public void setByte(String _n, byte _x) throws SQLException { throw named(); }
    @Override public void setShort(String _n, short _x) throws SQLException { throw named(); }
    @Override public void setInt(String _n, int _x) throws SQLException { throw named(); }
    @Override public void setLong(String _n, long _x) throws SQLException { throw named(); }
    @Override public void setFloat(String _n, float _x) throws SQLException { throw named(); }
    @Override public void setDouble(String _n, double _x) throws SQLException { throw named(); }
    @Override public void setBigDecimal(String _n, BigDecimal _x) throws SQLException { throw named(); }
    @Override public void setString(String _n, String _x) throws SQLException { throw named(); }
    @Override public void setBytes(String _n, byte[] _x) throws SQLException { throw named(); }
    @Override public void setDate(String _n, Date _x) throws SQLException { throw named(); }
    @Override public void setTime(String _n, Time _x) throws SQLException { throw named(); }
    @Override public void setTimestamp(String _n, Timestamp _x) throws SQLException { throw named(); }
    @Override public void setAsciiStream(String _n, InputStream _x, int _l) throws SQLException { throw named(); }
    @Override public void setBinaryStream(String _n, InputStream _x, int _l) throws SQLException { throw named(); }
    @Override public void setObject(String _n, Object _x, int _t, int _s) throws SQLException { throw named(); }
    @Override public void setObject(String _n, Object _x, int _t) throws SQLException { throw named(); }
    @Override public void setObject(String _n, Object _x) throws SQLException { throw named(); }
    @Override public void setCharacterStream(String _n, Reader _x, int _l) throws SQLException { throw named(); }
    @Override public void setDate(String _n, Date _x, Calendar _c) throws SQLException { throw named(); }
    @Override public void setTime(String _n, Time _x, Calendar _c) throws SQLException { throw named(); }
    @Override public void setTimestamp(String _n, Timestamp _x, Calendar _c) throws SQLException { throw named(); }
    @Override public void setNull(String _n, int _t, String _tn) throws SQLException { throw named(); }
    @Override public String getString(String _n) throws SQLException { throw named(); }
    @Override public boolean getBoolean(String _n) throws SQLException { throw named(); }
    @Override public byte getByte(String _n) throws SQLException { throw named(); }
    @Override public short getShort(String _n) throws SQLException { throw named(); }
    @Override public int getInt(String _n) throws SQLException { throw named(); }
    @Override public long getLong(String _n) throws SQLException { throw named(); }
    @Override public float getFloat(String _n) throws SQLException { throw named(); }
    @Override public double getDouble(String _n) throws SQLException { throw named(); }
    @Override public byte[] getBytes(String _n) throws SQLException { throw named(); }
    @Override public Date getDate(String _n) throws SQLException { throw named(); }
    @Override public Time getTime(String _n) throws SQLException { throw named(); }
    @Override public Timestamp getTimestamp(String _n) throws SQLException { throw named(); }
    @Override public Object getObject(String _n) throws SQLException { throw named(); }
    @Override public BigDecimal getBigDecimal(String _n) throws SQLException { throw named(); }
    @Override public Object getObject(String _n, Map<String, Class<?>> _m) throws SQLException { throw named(); }
    @Override public Ref getRef(String _n) throws SQLException { throw named(); }
    @Override public Blob getBlob(String _n) throws SQLException { throw named(); }
    @Override public Clob getClob(String _n) throws SQLException { throw named(); }
    @Override public Array getArray(String _n) throws SQLException { throw named(); }
    @Override public Date getDate(String _n, Calendar _c) throws SQLException { throw named(); }
    @Override public Time getTime(String _n, Calendar _c) throws SQLException { throw named(); }
    @Override public Timestamp getTimestamp(String _n, Calendar _c) throws SQLException { throw named(); }
    @Override public RowId getRowId(String _n) throws SQLException { throw named(); }
    @Override public void setRowId(String _n, RowId _x) throws SQLException { throw named(); }
    @Override public void setNString(String _n, String _x) throws SQLException { throw named(); }
    @Override public void setNCharacterStream(String _n, Reader _x, long _l) throws SQLException { throw named(); }
    @Override public void setNClob(String _n, NClob _x) throws SQLException { throw named(); }
    @Override public void setClob(String _n, Reader _x, long _l) throws SQLException { throw named(); }
    @Override public void setBlob(String _n, InputStream _x, long _l) throws SQLException { throw named(); }
    @Override public void setNClob(String _n, Reader _x, long _l) throws SQLException { throw named(); }
    @Override public NClob getNClob(String _n) throws SQLException { throw named(); }
    @Override public void setSQLXML(String _n, SQLXML _x) throws SQLException { throw named(); }
    @Override public SQLXML getSQLXML(String _n) throws SQLException { throw named(); }
    @Override public String getNString(String _n) throws SQLException { throw named(); }
    @Override public Reader getNCharacterStream(String _n) throws SQLException { throw named(); }
    @Override public Reader getCharacterStream(String _n) throws SQLException { throw named(); }
    @Override public void setBlob(String _n, Blob _x) throws SQLException { throw named(); }
    @Override public void setClob(String _n, Clob _x) throws SQLException { throw named(); }
    @Override public void setAsciiStream(String _n, InputStream _x, long _l) throws SQLException { throw named(); }
    @Override public void setBinaryStream(String _n, InputStream _x, long _l) throws SQLException { throw named(); }
    @Override public void setCharacterStream(String _n, Reader _x, long _l) throws SQLException { throw named(); }
    @Override public void setAsciiStream(String _n, InputStream _x) throws SQLException { throw named(); }
    @Override public void setBinaryStream(String _n, InputStream _x) throws SQLException { throw named(); }
    @Override public void setCharacterStream(String _n, Reader _x) throws SQLException { throw named(); }
    @Override public void setNCharacterStream(String _n, Reader _x) throws SQLException { throw named(); }
    @Override public void setClob(String _n, Reader _x) throws SQLException { throw named(); }
    @Override public void setBlob(String _n, InputStream _x) throws SQLException { throw named(); }
    @Override public void setNClob(String _n, Reader _x) throws SQLException { throw named(); }
    @Override public <T> T getObject(String _n, Class<T> _x) throws SQLException { throw named(); }
}
