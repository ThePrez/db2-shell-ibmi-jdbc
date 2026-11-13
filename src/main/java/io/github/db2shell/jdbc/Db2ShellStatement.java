package io.github.db2shell.jdbc;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.db2shell.jdbc.internal.ColumnInfo;
import io.github.db2shell.jdbc.internal.Engine;
import io.github.db2shell.jdbc.internal.RowSource;
import io.github.db2shell.jdbc.internal.SqlErrors;
import io.github.db2shell.jdbc.internal.SqlLiterals;
import io.github.db2shell.jdbc.internal.SqlText;

/** A statement. Each execution sends SQL to the connection's db2 utility session. */
public class Db2ShellStatement implements Statement {

    private static final Pattern s_insertTarget = Pattern.compile(
            "(?is)^\\s*INSERT\\s+INTO\\s+((?:\"(?:[^\"]|\"\")+\"|[\\w$#@]+)(?:\\s*\\.\\s*(?:\"(?:[^\"]|\"\")+\"|[\\w$#@]+))?).*");

    protected final Db2ShellConnection m_connection;
    protected final int m_resultSetType;
    protected final int m_resultSetConcurrency;
    protected final int m_resultSetHoldability;
    private Db2ShellResultSet m_currentResult;
    private long m_updateCount = -1;
    private Db2ShellResultSet m_generatedKeys;
    private SQLWarning m_warnings;
    private boolean m_closed = false;
    private long m_maxRows = 0;
    private int m_maxFieldSize = 0;
    private int m_fetchSize = 0;
    private int m_fetchDirection = ResultSet.FETCH_FORWARD;
    private int m_queryTimeout;
    protected boolean m_escapeProcessing = true;
    private boolean m_poolable = false;
    private boolean m_closeOnCompletion = false;
    private final List<String> m_batch = new ArrayList<String>();

    /**
     * Creates a statement owned by the given connection. The query timeout starts at the connection's configured
     * default.
     *
     * @param _conn
     *            the connection that owns this statement
     * @param _type
     *            the result set type ({@code ResultSet.TYPE_*})
     * @param _concurrency
     *            the result set concurrency ({@code ResultSet.CONCUR_*})
     * @param _holdability
     *            the result set holdability ({@code ResultSet.*_CURSORS_*})
     */
    Db2ShellStatement(final Db2ShellConnection _conn, final int _type, final int _concurrency, final int _holdability) {
        m_connection = _conn;
        m_resultSetType = _type;
        m_resultSetConcurrency = _concurrency;
        m_resultSetHoldability = _holdability;
        m_queryTimeout = _conn.config().getQueryTimeout();
    }

    // =================================================================================== helpers

    protected void checkOpen() throws SQLException {
        if (m_closed) {
            throw SqlErrors.closed("Statement");
        }
        m_connection.checkOpen();
    }

    protected Engine engine() {
        return m_connection.engine();
    }

    protected SqlText parse(final String _sql) throws SQLException {
        return SqlText.parse(_sql, engine().getCharset(), m_escapeProcessing);
    }

    /**
     * Appends warnings to this statement's warning chain.
     *
     * @param _ws
     *            the warnings to append, in order
     */
    void addWarnings(final List<SQLWarning> _ws) {
        for (final SQLWarning w : _ws) {
            if (null == m_warnings) {
                m_warnings = w;
            } else {
                m_warnings.setNextWarning(w);
            }
        }
    }

    /**
     * Closes the current result set and any generated-keys result set, and resets the update count, so the
     * statement is ready for its next execution.
     *
     * @throws SQLException
     *             if closing a result set fails
     */
    private void closeCurrent() throws SQLException {
        if (null != m_currentResult) {
            m_currentResult.closeQuietlyFromStatement();
            m_currentResult = null;
        }
        if (null != m_generatedKeys) {
            m_generatedKeys.closeQuietlyFromStatement();
            m_generatedKeys = null;
        }
        m_updateCount = -1;
    }

    /**
     * Called by a result set when it closes. If the closed result set is the current one and
     * {@link #closeOnCompletion()} is in effect, this statement closes itself; a failure to close is traced, not
     * thrown.
     *
     * @param _rs
     *            the result set that was closed
     */
    void resultClosed(final Db2ShellResultSet _rs) {
        if (_rs == m_currentResult && m_closeOnCompletion) {
            try {
                close();
            } catch (final SQLException e) {
                m_connection.engine().getTracer().trace("closeOnCompletion", e);
            }
        }
    }

    /**
     * Executes normalized SQL.
     *
     * @param _st the parsed statement (for its keyword)
     * @param _sql the final SQL text with parameters bound
     * @param _autoKeys a Statement.*_GENERATED_KEYS constant
     * @param _keyColumns explicit generated-key column names, or null
     * @return true if the result is a result set
     * @throws SQLException
     *             if the statement or connection is closed, the SQL is empty, or the database reports an error
     */
    protected boolean run(final SqlText _st, final String _sql, final int _autoKeys, final String[] _keyColumns)
            throws SQLException {
        checkOpen();
        closeCurrent();
        m_warnings = null;
        if (_sql.isEmpty()) {
            throw new SQLException("Empty SQL statement", "42617");
        }
        final List<SQLWarning> ws = new ArrayList<SQLWarning>();
        try {
            if (_st.isQuery()) {
                final Engine.QueryResult qr = engine().query(_sql, m_maxRows, m_queryTimeout);
                addWarnings(qr.getWarnings());
                m_currentResult = new Db2ShellResultSet(this, qr.getColumns(), qr.getRows(), m_resultSetType, m_resultSetHoldability);
                return true;
            }
            if (Statement.RETURN_GENERATED_KEYS == _autoKeys &&"INSERT".equals(_st.getKeyword())) {
                runInsertWithKeys(_sql, _keyColumns);
                return false;
            }
            m_updateCount = engine().update(_sql, _st.getKeyword(), m_queryTimeout, ws);
            addWarnings(ws);
            return false;
        } finally {
            m_connection.collectWarnings();
        }
    }

    /**
     * Inserts using SELECT ... FROM FINAL TABLE (INSERT ...) so generated keys can be returned. When no key
     * columns are given, the target table's identity column is used; if there is none, the insert runs as a plain
     * update and an empty generated-keys result set is recorded.
     *
     * @param _sql
     *            the INSERT statement text with parameters bound
     * @param _keyColumns
     *            explicit generated-key column names, or null to use the table's identity column
     * @throws SQLException
     *             if the database reports an error
     */
    private void runInsertWithKeys(final String _sql, final String[] _keyColumns) throws SQLException {
        String[] cols = _keyColumns;
        if (null == cols || 0 == cols.length) {
            cols = identityColumns(_sql);
        }
        if (null == cols || 0 == cols.length) {
            final List<SQLWarning> ws = new ArrayList<SQLWarning>();
            m_updateCount = engine().update(_sql, "INSERT", m_queryTimeout, ws);
            addWarnings(ws);
            m_generatedKeys = emptyKeys();
            return;
        }
        final StringBuilder sel = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.length; i++) {
            final String c = cols[i];
            sel.append(i > 0 ? ", " : "").append(c.startsWith("\"") ? c : "\"" + c.replace("\"", "\"\"") + "\"");
        }
        sel.append(" FROM FINAL TABLE (").append(_sql).append(")");
        final Engine.QueryResult qr = engine().query(sel.toString(), 0, m_queryTimeout);
        addWarnings(qr.getWarnings());
        final List<Object[]> rows = new ArrayList<Object[]>();
        Object[] row;
        while (null != (row = qr.getRows().next())) {
            rows.add(row);
        }
        m_updateCount = rows.size();
        m_generatedKeys = new Db2ShellResultSet(this, qr.getColumns(), new RowSource.ListRowSource(rows),
                ResultSet.TYPE_FORWARD_ONLY, m_resultSetHoldability);
    }

    /**
     * Looks up the identity column(s) of the INSERT's target table.
     *
     * @param _sql
     *            the INSERT statement text
     * @return the identity column name in a one-element array, or null if the target cannot be determined or has
     *         no identity column
     * @throws SQLException
     *             if the catalog query fails
     */
    private String[] identityColumns(final String _sql) throws SQLException {
        final Matcher m = s_insertTarget.matcher(_sql);
        if (!m.matches()) {
            return null;
        }
        final String target = m.group(1);
        final String schema;
        final String table;
        final int dot = indexOfDotOutsideQuotes(target);
        if (dot > 0) {
            schema = target.substring(0, dot).trim();
            table = target.substring(dot + 1).trim();
        } else {
            schema = null;
            table = target;
        }
        final String sql = "SELECT COLUMN_NAME FROM QSYS2.SYSCOLUMNS WHERE TABLE_NAME = "
                + SqlLiterals.quote(identifierValue(table)) + " AND TABLE_SCHEMA = "
                + (null == schema ? "CURRENT SCHEMA" : SqlLiterals.quote(identifierValue(schema)))
                + " AND IS_IDENTITY = 'YES'";
        final String col = engine().queryValue(sql);
        return null == col ? null : new String[] { col };
    }

    /**
     * Finds the dot separating a schema from a table name, ignoring dots inside delimited identifiers.
     *
     * @param _s
     *            a possibly qualified table name
     * @return the index of the first dot outside double quotes, or -1 if there is none
     */
    private static int indexOfDotOutsideQuotes(final String _s) {
        boolean q = false;
        for (int i = 0; i < _s.length(); i++) {
            final char c = _s.charAt(i);
            if ('"' == c) {
                q = !q;
            } else if ('.' == c && !q) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Catalog value of an identifier: delimited identifiers keep their case, others fold to upper case.
     *
     * @param _ident
     *            an SQL identifier, optionally delimited with double quotes
     * @return the identifier as stored in the catalog
     */
    static String identifierValue(final String _ident) {
        final String s = _ident.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s.toUpperCase(Locale.ROOT);
    }

    private Db2ShellResultSet emptyKeys() {
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        cols.add(ColumnInfo.synthetic("GENERATED_KEY", Types.BIGINT));
        return new Db2ShellResultSet(this, cols, new RowSource.ListRowSource(null), ResultSet.TYPE_FORWARD_ONLY,
                m_resultSetHoldability);
    }

    // =================================================================================== Statement API

    /**
     * Executes a query.
     *
     * @param _sql
     *            the query text
     * @return the query's result set
     * @throws SQLException
     *             if the statement does not produce a result set, or execution fails
     */
    @Override
    public ResultSet executeQuery(final String _sql) throws SQLException {
        final SqlText st = parse(_sql);
        if (!run(st, st.getSql(), Statement.NO_GENERATED_KEYS, null)) {
            throw new SQLException("The statement did not return a result set", "02000");
        }
        return m_currentResult;
    }

    @Override
    public int executeUpdate(final String _sql) throws SQLException {
        return (int) executeLargeUpdate(_sql);
    }

    @Override
    public long executeLargeUpdate(final String _sql) throws SQLException {
        return largeUpdate(_sql, Statement.NO_GENERATED_KEYS, null);
    }

    /**
     * Common implementation of the executeUpdate/executeLargeUpdate variants.
     *
     * @param _sql
     *            the statement text
     * @param _keys
     *            a Statement.*_GENERATED_KEYS constant
     * @param _cols
     *            explicit generated-key column names, or null
     * @return the update count, never negative
     * @throws SQLException
     *             if the statement is a query, or execution fails
     */
    private long largeUpdate(final String _sql, final int _keys, final String[] _cols) throws SQLException {
        final SqlText st = parse(_sql);
        if (st.isQuery()) {
            throw new SQLException("executeUpdate cannot run a query; use executeQuery", "07003");
        }
        run(st, st.getSql(), _keys, _cols);
        return Math.max(0, m_updateCount);
    }

    @Override
    public boolean execute(final String _sql) throws SQLException {
        final SqlText st = parse(_sql);
        return run(st, st.getSql(), Statement.NO_GENERATED_KEYS, null);
    }

    @Override
    public int executeUpdate(final String _sql, final int _autoGeneratedKeys) throws SQLException {
        return (int) largeUpdate(_sql, _autoGeneratedKeys, null);
    }

    @Override
    public int executeUpdate(final String _sql, final int[] _columnIndexes) throws SQLException {
        return (int) largeUpdate(_sql, Statement.RETURN_GENERATED_KEYS, null);
    }

    @Override
    public int executeUpdate(final String _sql, final String[] _columnNames) throws SQLException {
        return (int) largeUpdate(_sql, Statement.RETURN_GENERATED_KEYS, _columnNames);
    }

    @Override
    public long executeLargeUpdate(final String _sql, final int _autoGeneratedKeys) throws SQLException {
        return largeUpdate(_sql, _autoGeneratedKeys, null);
    }

    @Override
    public long executeLargeUpdate(final String _sql, final int[] _columnIndexes) throws SQLException {
        return largeUpdate(_sql, Statement.RETURN_GENERATED_KEYS, null);
    }

    @Override
    public long executeLargeUpdate(final String _sql, final String[] _columnNames) throws SQLException {
        return largeUpdate(_sql, Statement.RETURN_GENERATED_KEYS, _columnNames);
    }

    @Override
    public boolean execute(final String _sql, final int _autoGeneratedKeys) throws SQLException {
        final SqlText st = parse(_sql);
        return run(st, st.getSql(), _autoGeneratedKeys, null);
    }

    @Override
    public boolean execute(final String _sql, final int[] _columnIndexes) throws SQLException {
        final SqlText st = parse(_sql);
        return run(st, st.getSql(), Statement.RETURN_GENERATED_KEYS, null);
    }

    @Override
    public boolean execute(final String _sql, final String[] _columnNames) throws SQLException {
        final SqlText st = parse(_sql);
        return run(st, st.getSql(), Statement.RETURN_GENERATED_KEYS, _columnNames);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return m_currentResult;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return (int) Math.min(Integer.MAX_VALUE, m_updateCount);
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return m_updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }

    /**
     * Moves to the next result. The db2 utility returns at most one result per statement, so there is never a
     * further result; the current result set is closed unless {@code KEEP_CURRENT_RESULT} is given.
     *
     * @param _current
     *            a Statement.*_RESULT constant saying what to do with the current result set
     * @return always false
     * @throws SQLException
     *             if the statement is closed
     */
    @Override
    public boolean getMoreResults(final int _current) throws SQLException {
        checkOpen();
        if (null != m_currentResult && Statement.KEEP_CURRENT_RESULT != _current) {
            m_currentResult.closeQuietlyFromStatement();
        }
        m_currentResult = null;
        m_updateCount = -1;
        return false;
    }

    /**
     * Returns the keys generated by the last execution, or an empty result set if there were none.
     *
     * @return the generated-keys result set
     * @throws SQLException
     *             if the statement is closed
     */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        if (null == m_generatedKeys) {
            m_generatedKeys = emptyKeys();
        }
        return m_generatedKeys;
    }

    // ------------------------------------------------------------------ batches

    @Override
    public void addBatch(final String _sql) throws SQLException {
        checkOpen();
        m_batch.add(_sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        m_batch.clear();
    }

    /**
     * Runs the batch; counts larger than {@code Integer.MAX_VALUE} are clamped.
     *
     * @return the update count of each statement in the batch
     * @throws SQLException
     *             if the statement is closed, or a {@link BatchUpdateException} if a statement in the batch fails
     */
    @Override
    public int[] executeBatch() throws SQLException {
        final long[] l = executeLargeBatch();
        final int[] r = new int[l.length];
        for (int i = 0; i < l.length; i++) {
            r[i] = (int) Math.min(Integer.MAX_VALUE, l[i]);
        }
        return r;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        final List<String> work = new ArrayList<String>(m_batch);
        m_batch.clear();
        return runBatch(work, true);
    }

    /**
     * Runs a list of statements, each either raw SQL (parsed here) or already-bound SQL.
     *
     * @param _sqls
     *            the statements to run, in order
     * @param _parse
     *            true if the statements are raw SQL to be parsed with escape processing, false if already bound
     * @return the update count of each statement
     * @throws SQLException
     *             a {@link BatchUpdateException} carrying the counts completed so far if a statement fails or is a
     *             query
     */
    protected long[] runBatch(final List<String> _sqls, final boolean _parse) throws SQLException {
        final long[] counts = new long[_sqls.size()];
        for (int i = 0; i < _sqls.size(); i++) {
            try {
                final SqlText st = _parse ? parse(_sqls.get(i)) : SqlText.parse(_sqls.get(i), engine().getCharset(), false);
                if (st.isQuery()) {
                    throw new SQLException("Queries are not allowed in a batch", "07003");
                }
                run(st, st.getSql(), Statement.NO_GENERATED_KEYS, null);
                counts[i] = Math.max(0, m_updateCount);
            } catch (final SQLException e) {
                final long[] done = java.util.Arrays.copyOf(counts, i);
                final BatchUpdateException bue = new BatchUpdateException(e.getMessage(), e.getSQLState(),
                        e.getErrorCode(), done, e);
                throw bue;
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ properties

    /**
     * Closes this statement and its open result sets and unregisters it from the connection. Closing an already
     * closed statement has no effect.
     *
     * @throws SQLException
     *             if closing a result set fails
     */
    @Override
    public void close() throws SQLException {
        if (m_closed) {
            return;
        }
        closeCurrent();
        m_closed = true;
        m_connection.unregister(this);
    }

    @Override
    public boolean isClosed() {
        return m_closed;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return m_maxFieldSize;
    }

    /**
     * Sets the maximum length to which result set column values are truncated.
     *
     * @param _max
     *            the limit, or 0 for no limit
     * @throws SQLException
     *             if the statement is closed or the limit is negative
     */
    @Override
    public void setMaxFieldSize(final int _max) throws SQLException {
        checkOpen();
        if (_max < 0) {
            throw new SQLException("maxFieldSize must not be negative", "HY024");
        }
        m_maxFieldSize = _max;
    }

    int maxFieldSize() {
        return m_maxFieldSize;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return (int) Math.min(Integer.MAX_VALUE, m_maxRows);
    }

    @Override
    public void setMaxRows(final int _max) throws SQLException {
        setLargeMaxRows(_max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        checkOpen();
        return m_maxRows;
    }

    /**
     * Sets the maximum number of rows a result set may contain.
     *
     * @param _max
     *            the row limit, or 0 for no limit
     * @throws SQLException
     *             if the statement is closed or the limit is negative
     */
    @Override
    public void setLargeMaxRows(final long _max) throws SQLException {
        checkOpen();
        if (_max < 0) {
            throw new SQLException("maxRows must not be negative", "HY024");
        }
        m_maxRows = _max;
    }

    @Override
    public void setEscapeProcessing(final boolean _enable) throws SQLException {
        checkOpen();
        m_escapeProcessing = _enable;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return m_queryTimeout;
    }

    /**
     * Sets the number of seconds to wait for a statement to complete.
     *
     * @param _seconds
     *            the timeout in seconds, or 0 for no timeout
     * @throws SQLException
     *             if the statement is closed or the timeout is negative
     */
    @Override
    public void setQueryTimeout(final int _seconds) throws SQLException {
        checkOpen();
        if (_seconds < 0) {
            throw new SQLException("queryTimeout must not be negative", "HY024");
        }
        m_queryTimeout = _seconds;
    }

    /**
     * Cancels a running statement. The db2 utility cannot interrupt a statement, so this ends the utility
     * process; the connection transparently starts a new session for the next statement (session state such as
     * the current schema is re-applied from the connection properties, but uncommitted work is lost).
     */
    @Override
    public void cancel() throws SQLException {
        checkOpen();
        engine().cancel();
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
    public void setCursorName(final String _name) throws SQLException {
        throw SqlErrors.unsupported("Positioned updates (setCursorName)");
    }

    @Override
    public void setFetchDirection(final int _direction) throws SQLException {
        checkOpen();
        m_fetchDirection = _direction;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return m_fetchDirection;
    }

    /**
     * Records the fetch size hint. The db2 utility returns all rows at once, so the hint has no effect.
     *
     * @param _rows
     *            the number of rows to fetch at a time, or 0 for the driver default
     * @throws SQLException
     *             if the statement is closed or the value is negative
     */
    @Override
    public void setFetchSize(final int _rows) throws SQLException {
        checkOpen();
        if (_rows < 0) {
            throw new SQLException("fetchSize must not be negative", "HY024");
        }
        m_fetchSize = _rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return m_fetchSize;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return m_resultSetConcurrency;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return m_resultSetType;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return m_resultSetHoldability;
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return m_connection;
    }

    @Override
    public void setPoolable(final boolean _poolable) throws SQLException {
        checkOpen();
        m_poolable = _poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return m_poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        m_closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return m_closeOnCompletion;
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
}
