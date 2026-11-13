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

    private final DriverConfig m_config;
    private final Engine m_engine;
    private final Set<Statement> m_statements = Collections.newSetFromMap(new WeakHashMap<Statement, Boolean>());
    private boolean m_readOnly = false;
    private int m_holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
    private Map<String, Class<?>> m_typeMap = new HashMap<String, Class<?>>();
    private final Properties m_clientInfo = new Properties();
    private SQLWarning m_warnings;
    private int m_savepointCounter = 0;
    private int m_networkTimeout = 0;

    Db2ShellConnection(final DriverConfig _config, final Engine _engine) {
        m_config = _config;
        m_engine = _engine;
    }

    /**
     * Starts a db2 utility process for the given configuration and wraps it in a connection. Warnings raised
     * while the session was set up are available from {@link #getWarnings()}.
     *
     * @param _cfg
     *            the parsed driver configuration
     * @return the open connection
     * @throws SQLException
     *             if the db2 utility cannot be started or the session cannot be initialized
     */
    static Db2ShellConnection open(final DriverConfig _cfg) throws SQLException {
        final Engine engine = new Engine(_cfg);
        engine.open();
        final Db2ShellConnection c = new Db2ShellConnection(_cfg, engine);
        c.collectWarnings();
        return c;
    }

    // =================================================================================== internal access

    Engine engine() {
        return m_engine;
    }

    DriverConfig config() {
        return m_config;
    }

    void checkOpen() throws SQLException {
        if (m_engine.isClosed()) {
            throw SqlErrors.closed("Connection");
        }
    }

    void collectWarnings() {
        for (final SQLWarning w : m_engine.takeConnectionWarnings()) {
            addWarning(w);
        }
    }

    /**
     * Appends a warning to this connection's warning chain.
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

    void unregister(final Statement _s) {
        synchronized (m_statements) {
            m_statements.remove(_s);
        }
    }

    private <T extends Statement> T register(final T _s) {
        synchronized (m_statements) {
            m_statements.add(_s);
        }
        return _s;
    }

    /** True if the server supports the BOOLEAN data type (IBM i 7.5 and later). */
    boolean booleanSupported() throws SQLException {
        final Engine.ServerInfo si = m_engine.getServerInfo();
        return si.getVersion() > 7 || (7 == si.getVersion() && si.getRelease() >= 5);
    }

    // =================================================================================== statements

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, m_holdability);
    }

    @Override
    public Statement createStatement(final int _type, final int _concurrency) throws SQLException {
        return createStatement(_type, _concurrency, m_holdability);
    }

    @Override
    public Statement createStatement(final int _type, final int _concurrency, final int _holdability)
            throws SQLException {
        checkOpen();
        return register(new Db2ShellStatement(this, checkType(_type), checkConcurrency(_concurrency), _holdability));
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql) throws SQLException {
        return prepareStatement(_sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, m_holdability);
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final int _type, final int _concurrency)
            throws SQLException {
        return prepareStatement(_sql, _type, _concurrency, m_holdability);
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
                ResultSet.CONCUR_READ_ONLY, m_holdability, _autoGeneratedKeys, null));
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final int[] _columnIndexes) throws SQLException {
        return prepareStatement(_sql, Statement.RETURN_GENERATED_KEYS);
    }

    @Override
    public PreparedStatement prepareStatement(final String _sql, final String[] _columnNames) throws SQLException {
        checkOpen();
        return register(new Db2ShellPreparedStatement(this, _sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, m_holdability, Statement.RETURN_GENERATED_KEYS, _columnNames));
    }

    @Override
    public CallableStatement prepareCall(final String _sql) throws SQLException {
        return prepareCall(_sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, m_holdability);
    }

    @Override
    public CallableStatement prepareCall(final String _sql, final int _type, final int _concurrency)
            throws SQLException {
        return prepareCall(_sql, _type, _concurrency, m_holdability);
    }

    @Override
    public CallableStatement prepareCall(final String _sql, final int _type, final int _concurrency,
            final int _holdability) throws SQLException {
        checkOpen();
        return register(new Db2ShellCallableStatement(this, _sql, checkType(_type), checkConcurrency(_concurrency),
                _holdability));
    }

    /**
     * Maps a requested result set type to one the driver supports, adding a warning when it is downgraded.
     *
     * @param _type
     *            the requested {@code ResultSet.TYPE_*} value
     * @return the type that will actually be used ({@code TYPE_SCROLL_SENSITIVE} becomes {@code TYPE_SCROLL_INSENSITIVE})
     */
    private int checkType(final int _type) {
        if (ResultSet.TYPE_SCROLL_SENSITIVE == _type) {
            addWarning(new SQLWarning("TYPE_SCROLL_SENSITIVE is not supported; using TYPE_SCROLL_INSENSITIVE", "01S02"));
            return ResultSet.TYPE_SCROLL_INSENSITIVE;
        }
        return _type;
    }

    private int checkConcurrency(final int _concurrency) {
        if (ResultSet.CONCUR_READ_ONLY != _concurrency) {
            addWarning(new SQLWarning("Updatable result sets are not supported; using CONCUR_READ_ONLY", "01S02"));
        }
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public String nativeSQL(final String _sql) throws SQLException {
        return SqlText.parse(_sql, m_engine.getCharset(), true).getSql();
    }

    // =================================================================================== transactions

    @Override
    public void setAutoCommit(final boolean _autoCommit) throws SQLException {
        checkOpen();
        m_engine.setAutoCommit(_autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return m_engine.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        m_engine.commit();
        collectWarnings();
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        m_engine.rollback();
        collectWarnings();
    }

    /**
     * Sets the transaction isolation level used when auto-commit is off.
     *
     * @param _level
     *            one of the {@code Connection.TRANSACTION_*} constants
     * @throws SQLException
     *             if the connection is closed or the level is not a valid isolation level
     */
    @Override
    public void setTransactionIsolation(final int _level) throws SQLException {
        checkOpen();
        switch (_level) {
        case TRANSACTION_NONE:
        case TRANSACTION_READ_UNCOMMITTED:
        case TRANSACTION_READ_COMMITTED:
        case TRANSACTION_REPEATABLE_READ:
        case TRANSACTION_SERIALIZABLE:
            m_engine.setIsolation(_level);
            break;
        default:
            throw new SQLException("Invalid transaction isolation level " + _level, "HY024");
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return m_engine.getIsolation();
    }

    /**
     * Creates an unnamed savepoint in the current transaction.
     *
     * @return the new savepoint, identified by a connection-unique numeric id
     * @throws SQLException
     *             if the connection is closed or the SAVEPOINT statement fails
     */
    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkOpen();
        final int id = ++m_savepointCounter;
        final Db2ShellSavepoint sp = new Db2ShellSavepoint(id, null);
        m_engine.savepoint(sp.sqlName());
        return sp;
    }

    /**
     * Creates a named savepoint in the current transaction.
     *
     * @param _name
     *            the savepoint name; must not be null or blank
     * @return the new savepoint
     * @throws SQLException
     *             if the connection is closed, the name is empty, or the SAVEPOINT statement fails
     */
    @Override
    public Savepoint setSavepoint(final String _name) throws SQLException {
        checkOpen();
        if (null == _name || _name.trim().isEmpty()) {
            throw new SQLException("Savepoint name must not be empty", "3B001");
        }
        final Db2ShellSavepoint sp = new Db2ShellSavepoint(0, _name);
        m_engine.savepoint(sp.sqlName());
        return sp;
    }

    @Override
    public void rollback(final Savepoint _savepoint) throws SQLException {
        checkOpen();
        m_engine.rollbackTo(Db2ShellSavepoint.sqlName(_savepoint));
    }

    @Override
    public void releaseSavepoint(final Savepoint _savepoint) throws SQLException {
        checkOpen();
        m_engine.releaseSavepoint(Db2ShellSavepoint.sqlName(_savepoint));
    }

    // =================================================================================== misc

    /**
     * Closes every statement still open on this connection and then ends the db2 utility process. Failures
     * closing individual statements are traced and do not stop the connection from closing. Calling this on a
     * closed connection does nothing.
     *
     * @throws SQLException
     *             declared by {@link Connection#close()}; not thrown by this implementation
     */
    @Override
    public void close() throws SQLException {
        if (m_engine.isClosed()) {
            return;
        }
        final Statement[] open;
        synchronized (m_statements) {
            open = m_statements.toArray(new Statement[0]);
            m_statements.clear();
        }
        for (final Statement s : open) {
            try {
                s.close();
            } catch (final SQLException e) {
                m_engine.getTracer().trace("close", "Ignoring failure closing statement: " + e);
            }
        }
        m_engine.close();
    }

    @Override
    public boolean isClosed() {
        return m_engine.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new Db2ShellDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(final boolean _readOnly) throws SQLException {
        checkOpen();
        m_readOnly = _readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return m_readOnly;
    }

    @Override
    public void setCatalog(final String _catalog) throws SQLException {
        checkOpen();
        // a single catalog (the relational database) is available; ignore
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return m_engine.getServerInfo().getRdbName();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        collectWarnings();
        return m_warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        m_warnings = null;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return m_typeMap;
    }

    @Override
    public void setTypeMap(final Map<String, Class<?>> _map) throws SQLException {
        checkOpen();
        m_typeMap = null == _map ? new HashMap<String, Class<?>>() : _map;
    }

    @Override
    public void setHoldability(final int _holdability) throws SQLException {
        checkOpen();
        m_holdability = _holdability;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return m_holdability;
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
        return m_engine.isValid(_timeout);
    }

    /**
     * Sets or removes a single client info property. Client info is kept locally and is not sent to the server.
     *
     * @param _name
     *            the property name
     * @param _value
     *            the property value, or null to remove the property
     * @throws SQLClientInfoException
     *             declared by {@link Connection#setClientInfo(String, String)}; not thrown by this implementation
     */
    @Override
    public void setClientInfo(final String _name, final String _value) throws SQLClientInfoException {
        if (null == _value) {
            m_clientInfo.remove(_name);
        } else {
            m_clientInfo.setProperty(_name, _value);
        }
    }

    /**
     * Replaces all client info properties. Client info is kept locally and is not sent to the server.
     *
     * @param _properties
     *            the new set of client info properties
     * @throws SQLClientInfoException
     *             if the connection is closed; every property is reported as failed
     */
    @Override
    public void setClientInfo(final Properties _properties) throws SQLClientInfoException {
        if (m_engine.isClosed()) {
            final Map<String, ClientInfoStatus> failed = new HashMap<String, ClientInfoStatus>();
            for (final String n : _properties.stringPropertyNames()) {
                failed.put(n, ClientInfoStatus.REASON_UNKNOWN);
            }
            throw new SQLClientInfoException("Connection is closed", failed);
        }
        m_clientInfo.clear();
        m_clientInfo.putAll(_properties);
    }

    @Override
    public String getClientInfo(final String _name) throws SQLException {
        checkOpen();
        return m_clientInfo.getProperty(_name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        final Properties p = new Properties();
        p.putAll(m_clientInfo);
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

    /**
     * Sets the default schema by running {@code SET SCHEMA}. A name that is not already quoted is quoted, so
     * it is used exactly as given (case-sensitive). A null schema is ignored.
     *
     * @param _schema
     *            the schema name, or null to leave the current schema unchanged
     * @throws SQLException
     *             if the connection is closed or the SET SCHEMA statement fails
     */
    @Override
    public void setSchema(final String _schema) throws SQLException {
        checkOpen();
        if (null == _schema) {
            return;
        }
        final String ident = _schema.startsWith("\"") ? _schema : "\"" + _schema.replace("\"", "\"\"") + "\"";
        final java.util.List<SQLWarning> w = new java.util.ArrayList<SQLWarning>();
        m_engine.update("SET SCHEMA " + ident, "SET", 0, w);
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return m_engine.queryValue("VALUES CURRENT SCHEMA");
    }

    /**
     * Aborts the connection: cancels any running statement immediately and closes the db2 utility process on
     * the given executor.
     *
     * @param _executor
     *            the executor used to perform the close; must not be null
     * @throws SQLException
     *             if the executor is null
     */
    @Override
    public void abort(final Executor _executor) throws SQLException {
        if (null == _executor) {
            throw new SQLException("Executor must not be null", "HY009");
        }
        m_engine.cancel();
        _executor.execute(new Runnable() {
            @Override
            public void run() {
                m_engine.close();
            }
        });
    }

    @Override
    public void setNetworkTimeout(final Executor _executor, final int _milliseconds) throws SQLException {
        checkOpen();
        m_networkTimeout = _milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return m_networkTimeout;
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
        return m_engine.getServerInfo().getJobName();
    }

    /** The charset used to talk to the db2 utility. */
    public java.nio.charset.Charset getEncoding() {
        return m_engine.getCharset();
    }
}
