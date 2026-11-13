package io.github.db2shell.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import io.github.db2shell.jdbc.internal.Engine;
import io.github.db2shell.jdbc.internal.Lobs;
import io.github.db2shell.jdbc.internal.SqlErrors;
import io.github.db2shell.jdbc.internal.SqlText;

/**
 * A connection: one interactive db2 utility process. Session state (current schema, path, QTEMP, global
 * temporary tables, transactions) persists for the life of the connection.
 */
public class Db2ShellConnection implements Connection {

    private final DriverConfig config;
    private final Engine engine;
    private final Set<Statement> statements = Collections.newSetFromMap(new WeakHashMap<Statement, Boolean>());
    private boolean readOnly = false;
    private int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
    private Map<String, Class<?>> typeMap = new HashMap<String, Class<?>>();
    private final Properties clientInfo = new Properties();
    private SQLWarning warnings;
    private int savepointCounter = 0;
    private int networkTimeout = 0;

    Db2ShellConnection(final DriverConfig _config, final Engine _engine) {
        config = _config;
        engine = _engine;
    }

    static Db2ShellConnection open(final DriverConfig _cfg) throws SQLException {
        final Engine engine = new Engine(_cfg);
        engine.open();
        final Db2ShellConnection c = new Db2ShellConnection(_cfg, engine);
        c.collectWarnings();
        return c;
    }

    // =================================================================================== internal access

    Engine engine() {
        return engine;
    }

    DriverConfig config() {
        return config;
    }

    void checkOpen() throws SQLException {
        if (engine.isClosed()) {
            throw SqlErrors.closed("Connection");
        }
    }

    void collectWarnings() {
        for (final SQLWarning w : engine.takeConnectionWarnings()) {
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

    void unregister(final Statement _s) {
        synchronized (statements) {
            statements.remove(_s);
        }
    }

    private <T extends Statement> T register(final T _s) {
        synchronized (statements) {
            statements.add(_s);
        }
        return _s;
    }

    /** True if the server supports the BOOLEAN data type (IBM i 7.5 and later). */
    boolean booleanSupported() throws SQLException {
        final Engine.ServerInfo si = engine.getServerInfo();
        return si.version > 7 || (si.version == 7 && si.release >= 5);
    }

    // =================================================================================== statements

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, holdability);
    }

    @Override
    public Statement createStatement(final int _type, final int _concurrency) throws SQLException {
        return createStatement(_type, _concurrency, holdability);
    }

    @Override
    public Statement createStatement(final int _type, final int _concurrency, final int _holdability)
            throws SQLException {
        checkOpen();
        return register(new Db2ShellStatement(this, checkType(_type), checkConcurrency(_concurrency), _holdability));
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql) throws SQLException {
        return prepareStatement(_sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, holdability);
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final int _type, final int _concurrency)
            throws SQLException {
        return prepareStatement(_sql, _type, _concurrency, holdability);
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final int _type, final int _concurrency,
            final int _holdability) throws SQLException {
        checkOpen();
        return register(new Db2ShellPreparedStatement(this, _sql, checkType(_type), checkConcurrency(_concurrency),
                _holdability, Statement.NO_GENERATED_KEYS, null));
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final int _autoGeneratedKeys) throws SQLException {
        checkOpen();
        return register(new Db2ShellPreparedStatement(this, _sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, holdability, _autoGeneratedKeys, null));
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final int[] _columnIndexes) throws SQLException {
        return prepareStatement(_sql, Statement.RETURN_GENERATED_KEYS);
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final String[] _columnNames) throws SQLException {
        checkOpen();
        return register(new Db2ShellPreparedStatement(this, _sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, holdability, Statement.RETURN_GENERATED_KEYS, _columnNames));
    }

    @Override
    public CallableStatement prepareCall(final String _sql) throws SQLException {
        return prepareCall(_sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, holdability);
    }

    @Override
    public CallableStatement prepareCall(final String _sql, final int _type, final int _concurrency)
            throws SQLException {
        return prepareCall(_sql, _type, _concurrency, holdability);
    }

    @Override
    public CallableStatement prepareCall(final String _sql, final int _type, final int _concurrency,
            final int _holdability) throws SQLException {
        checkOpen();
        return register(new Db2ShellCallableStatement(this, _sql, checkType(_type), checkConcurrency(_concurrency),
                _holdability));
    }

    private int checkType(final int _type) {
        if (_type == ResultSet.TYPE_SCROLL_SENSITIVE) {
            addWarning(new SQLWarning("TYPE_SCROLL_SENSITIVE is not supported; using TYPE_SCROLL_INSENSITIVE", "01S02"));
            return ResultSet.TYPE_SCROLL_INSENSITIVE;
        }
        return _type;
    }

    private int checkConcurrency(final int _concurrency) {
        if (_concurrency != ResultSet.CONCUR_READ_ONLY) {
            addWarning(new SQLWarning("Updatable result sets are not supported; using CONCUR_READ_ONLY", "01S02"));
        }
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public String nativeSQL(final String _sql) throws SQLException {
        return SqlText.parse(_sql, engine.getCharset(), true).getSql();
    }

    // =================================================================================== transactions

    @Override
    public void setAutoCommit(final boolean _autoCommit) throws SQLException {
        checkOpen();
        engine.setAutoCommit(_autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return engine.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        engine.commit();
        collectWarnings();
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        engine.rollback();
        collectWarnings();
    }

    @Override
    public void setTransactionIsolation(final int _level) throws SQLException {
        checkOpen();
        switch (_level) {
        case TRANSACTION_NONE:
        case TRANSACTION_READ_UNCOMMITTED:
        case TRANSACTION_READ_COMMITTED:
        case TRANSACTION_REPEATABLE_READ:
        case TRANSACTION_SERIALIZABLE:
            engine.setIsolation(_level);
            break;
        default:
            throw new SQLException("Invalid transaction isolation level " + _level, "HY024");
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return engine.getIsolation();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkOpen();
        final int id = ++savepointCounter;
        final Db2ShellSavepoint sp = new Db2ShellSavepoint(id, null);
        engine.savepoint(sp.sqlName());
        return sp;
    }

    @Override
    public Savepoint setSavepoint(final String _name) throws SQLException {
        checkOpen();
        if (null == _name || _name.trim().isEmpty()) {
            throw new SQLException("Savepoint name must not be empty", "3B001");
        }
        final Db2ShellSavepoint sp = new Db2ShellSavepoint(0, _name);
        engine.savepoint(sp.sqlName());
        return sp;
    }

    @Override
    public void rollback(final Savepoint _savepoint) throws SQLException {
        checkOpen();
        engine.rollbackTo(Db2ShellSavepoint.sqlName(_savepoint));
    }

    @Override
    public void releaseSavepoint(final Savepoint _savepoint) throws SQLException {
        checkOpen();
        engine.releaseSavepoint(Db2ShellSavepoint.sqlName(_savepoint));
    }

    // =================================================================================== misc

    @Override
    public void close() throws SQLException {
        if (engine.isClosed()) {
            return;
        }
        final Statement[] open;
        synchronized (statements) {
            open = statements.toArray(new Statement[0]);
            statements.clear();
        }
        for (final Statement s : open) {
            try {
                s.close();
            } catch (final SQLException e) {
                // ignore
            }
        }
        engine.close();
    }

    @Override
    public boolean isClosed() {
        return engine.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new Db2ShellDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(final boolean _readOnly) throws SQLException {
        checkOpen();
        readOnly = _readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
    }

    @Override
    public void setCatalog(final String _catalog) throws SQLException {
        checkOpen();
        // a single catalog (the relational database) is available; ignore
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return engine.getServerInfo().rdbName;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        collectWarnings();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return typeMap;
    }

    @Override
    public void setTypeMap(final Map<String, Class<?>> _map) throws SQLException {
        checkOpen();
        typeMap = null == _map ? new HashMap<String, Class<?>>() : _map;
    }

    @Override
    public void setHoldability(final int _holdability) throws SQLException {
        checkOpen();
        holdability = _holdability;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public Clob createClob() throws SQLException {
        checkOpen();
        return new Lobs.ClobImpl("");
    }

    @Override
    public Blob createBlob() throws SQLException {
        checkOpen();
        return new Lobs.BlobImpl(new byte[0]);
    }

    @Override
    public NClob createNClob() throws SQLException {
        checkOpen();
        return new Lobs.ClobImpl("");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        checkOpen();
        return new Lobs.SqlXmlImpl("");
    }

    @Override
    public boolean isValid(final int _timeout) throws SQLException {
        if (_timeout < 0) {
            throw new SQLException("Timeout must not be negative", "HY092");
        }
        return engine.isValid(_timeout);
    }

    @Override
    public void setClientInfo(final String _name, final String _value) throws SQLClientInfoException {
        if (null == _value) {
            clientInfo.remove(_name);
        } else {
            clientInfo.setProperty(_name, _value);
        }
    }

    @Override
    public void setClientInfo(final Properties _properties) throws SQLClientInfoException {
        if (engine.isClosed()) {
            final Map<String, ClientInfoStatus> failed = new HashMap<String, ClientInfoStatus>();
            for (final String n : _properties.stringPropertyNames()) {
                failed.put(n, ClientInfoStatus.REASON_UNKNOWN);
            }
            throw new SQLClientInfoException("Connection is closed", failed);
        }
        clientInfo.clear();
        clientInfo.putAll(_properties);
    }

    @Override
    public String getClientInfo(final String _name) throws SQLException {
        checkOpen();
        return clientInfo.getProperty(_name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        final Properties p = new Properties();
        p.putAll(clientInfo);
        return p;
    }

    @Override
    public Array createArrayOf(final String _typeName, final Object[] _elements) throws SQLException {
        throw SqlErrors.unsupported("createArrayOf");
    }

    @Override
    public Struct createStruct(final String _typeName, final Object[] _attributes) throws SQLException {
        throw SqlErrors.unsupported("createStruct");
    }

    @Override
    public void setSchema(final String _schema) throws SQLException {
        checkOpen();
        if (null == _schema) {
            return;
        }
        final String ident = _schema.startsWith("\"") ? _schema : "\"" + _schema.replace("\"", "\"\"") + "\"";
        final java.util.List<SQLWarning> w = new java.util.ArrayList<SQLWarning>();
        engine.update("SET SCHEMA " + ident, "SET", 0, w);
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return engine.queryValue("VALUES CURRENT SCHEMA");
    }

    @Override
    public void abort(final Executor _executor) throws SQLException {
        if (null == _executor) {
            throw new SQLException("Executor must not be null", "HY009");
        }
        engine.cancel();
        _executor.execute(new Runnable() {
            @Override
            public void run() {
                engine.close();
            }
        });
    }

    @Override
    public void setNetworkTimeout(final Executor _executor, final int _milliseconds) throws SQLException {
        checkOpen();
        networkTimeout = _milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeout;
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

    /** The job running the db2 utility, e.g. {@code 123456/QUSER/QP0ZSPWP}. */
    public String getJobName() throws SQLException {
        return engine.getServerInfo().jobName;
    }

    /** The charset used to talk to the db2 utility. */
    public java.nio.charset.Charset getEncoding() {
        return engine.getCharset();
    }
}
