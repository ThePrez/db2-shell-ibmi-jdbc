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

    private static final Pattern INSERT_TARGET = Pattern.compile(
            "(?is)^\\s*INSERT\\s+INTO\\s+((?:\"(?:[^\"]|\"\")+\"|[\\w$#@]+)(?:\\s*\\.\\s*(?:\"(?:[^\"]|\"\")+\"|[\\w$#@]+))?).*");

    protected final Db2ShellConnection connection;
    protected final int resultSetType;
    protected final int resultSetConcurrency;
    protected final int resultSetHoldability;
    private Db2ShellResultSet currentResult;
    private long updateCount = -1;
    private Db2ShellResultSet generatedKeys;
    private SQLWarning warnings;
    private boolean closed = false;
    private long maxRows = 0;
    private int maxFieldSize = 0;
    private int fetchSize = 0;
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    private int queryTimeout;
    protected boolean escapeProcessing = true;
    private boolean poolable = false;
    private boolean closeOnCompletion = false;
    private final List<String> batch = new ArrayList<String>();

    Db2ShellStatement(final Db2ShellConnection _conn, final int _type, final int _concurrency, final int _holdability) {
        connection = _conn;
        resultSetType = _type;
        resultSetConcurrency = _concurrency;
        resultSetHoldability = _holdability;
        queryTimeout = _conn.config().getQueryTimeout();
    }

    // =================================================================================== helpers

    protected void checkOpen() throws SQLException {
        if (closed) {
            throw SqlErrors.closed("Statement");
        }
        connection.checkOpen();
    }

    protected Engine engine() {
        return connection.engine();
    }

    protected SqlText parse(final String _sql) throws SQLException {
        return SqlText.parse(_sql, engine().getCharset(), escapeProcessing);
    }

    void addWarnings(final List<SQLWarning> _ws) {
        for (final SQLWarning w : _ws) {
            if (null == warnings) {
                warnings = w;
            } else {
                warnings.setNextWarning(w);
            }
        }
    }

    private void closeCurrent() throws SQLException {
        if (null != currentResult) {
            currentResult.closeQuietlyFromStatement();
            currentResult = null;
        }
        if (null != generatedKeys) {
            generatedKeys.closeQuietlyFromStatement();
            generatedKeys = null;
        }
        updateCount = -1;
    }

    /** Called by a result set when it closes. */
    void resultClosed(final Db2ShellResultSet _rs) {
        if (_rs == currentResult && closeOnCompletion) {
            try {
                close();
            } catch (final SQLException e) {
                // ignore
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
     */
    protected boolean run(final SqlText _st, final String _sql, final int _autoKeys, final String[] _keyColumns)
            throws SQLException {
        checkOpen();
        closeCurrent();
        warnings = null;
        if (_sql.isEmpty()) {
            throw new SQLException("Empty SQL statement", "42617");
        }
        final List<SQLWarning> ws = new ArrayList<SQLWarning>();
        try {
            if (_st.isQuery()) {
                final Engine.QueryResult qr = engine().query(_sql, maxRows, queryTimeout);
                addWarnings(qr.warnings);
                currentResult = new Db2ShellResultSet(this, qr.columns, qr.rows, resultSetType, resultSetHoldability);
                return true;
            }
            if (_autoKeys == Statement.RETURN_GENERATED_KEYS && "INSERT".equals(_st.getKeyword())) {
                runInsertWithKeys(_sql, _keyColumns);
                return false;
            }
            updateCount = engine().update(_sql, _st.getKeyword(), queryTimeout, ws);
            addWarnings(ws);
            return false;
        } finally {
            connection.collectWarnings();
        }
    }

    /** Inserts using SELECT ... FROM FINAL TABLE (INSERT ...) so generated keys can be returned. */
    private void runInsertWithKeys(final String _sql, final String[] _keyColumns) throws SQLException {
        String[] cols = _keyColumns;
        if (null == cols || cols.length == 0) {
            cols = identityColumns(_sql);
        }
        if (null == cols || cols.length == 0) {
            final List<SQLWarning> ws = new ArrayList<SQLWarning>();
            updateCount = engine().update(_sql, "INSERT", queryTimeout, ws);
            addWarnings(ws);
            generatedKeys = emptyKeys();
            return;
        }
        final StringBuilder sel = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.length; i++) {
            final String c = cols[i];
            sel.append(i > 0 ? ", " : "").append(c.startsWith("\"") ? c : "\"" + c.replace("\"", "\"\"") + "\"");
        }
        sel.append(" FROM FINAL TABLE (").append(_sql).append(")");
        final Engine.QueryResult qr = engine().query(sel.toString(), 0, queryTimeout);
        addWarnings(qr.warnings);
        final List<Object[]> rows = new ArrayList<Object[]>();
        Object[] row;
        while (null != (row = qr.rows.next())) {
            rows.add(row);
        }
        updateCount = rows.size();
        generatedKeys = new Db2ShellResultSet(this, qr.columns, new RowSource.ListRowSource(rows),
                ResultSet.TYPE_FORWARD_ONLY, resultSetHoldability);
    }

    /** Looks up the identity column(s) of the INSERT's target table. */
    private String[] identityColumns(final String _sql) throws SQLException {
        final Matcher m = INSERT_TARGET.matcher(_sql);
        if (!m.matches()) {
            return null;
        }
        final String target = m.group(1);
        String schema = null;
        String table = target;
        final int dot = indexOfDotOutsideQuotes(target);
        if (dot > 0) {
            schema = target.substring(0, dot).trim();
            table = target.substring(dot + 1).trim();
        }
        final String sql = "SELECT COLUMN_NAME FROM QSYS2.SYSCOLUMNS WHERE TABLE_NAME = "
                + SqlLiterals.quote(identifierValue(table)) + " AND TABLE_SCHEMA = "
                + (null == schema ? "CURRENT SCHEMA" : SqlLiterals.quote(identifierValue(schema)))
                + " AND IS_IDENTITY = 'YES'";
        final String col = engine().queryValue(sql);
        return null == col ? null : new String[] { col };
    }

    private static int indexOfDotOutsideQuotes(final String _s) {
        boolean q = false;
        for (int i = 0; i < _s.length(); i++) {
            final char c = _s.charAt(i);
            if (c == '"') {
                q = !q;
            } else if (c == '.' && !q) {
                return i;
            }
        }
        return -1;
    }

    /** Catalog value of an identifier: delimited identifiers keep their case, others fold to upper case. */
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
                resultSetHoldability);
    }

    // =================================================================================== Statement API

    @Override
    public ResultSet executeQuery(final String _sql) throws SQLException {
        final SqlText st = parse(_sql);
        if (!run(st, st.getSql(), Statement.NO_GENERATED_KEYS, null)) {
            throw new SQLException("The statement did not return a result set", "02000");
        }
        return currentResult;
    }

    @Override
    public int executeUpdate(final String _sql) throws SQLException {
        return (int) executeLargeUpdate(_sql);
    }

    @Override
    public long executeLargeUpdate(final String _sql) throws SQLException {
        return largeUpdate(_sql, Statement.NO_GENERATED_KEYS, null);
    }

    private long largeUpdate(final String _sql, final int _keys, final String[] _cols) throws SQLException {
        final SqlText st = parse(_sql);
        if (st.isQuery()) {
            throw new SQLException("executeUpdate cannot run a query; use executeQuery", "07003");
        }
        run(st, st.getSql(), _keys, _cols);
        return Math.max(0, updateCount);
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
        return currentResult;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return (int) Math.min(Integer.MAX_VALUE, updateCount);
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }

    @Override
    public boolean getMoreResults(final int _current) throws SQLException {
        checkOpen();
        if (null != currentResult && _current != Statement.KEEP_CURRENT_RESULT) {
            currentResult.closeQuietlyFromStatement();
        }
        currentResult = null;
        updateCount = -1;
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        if (null == generatedKeys) {
            generatedKeys = emptyKeys();
        }
        return generatedKeys;
    }

    // ------------------------------------------------------------------ batches

    @Override
    public void addBatch(final String _sql) throws SQLException {
        checkOpen();
        batch.add(_sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

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
        final List<String> work = new ArrayList<String>(batch);
        batch.clear();
        return runBatch(work, true);
    }

    /** Runs a list of statements, each either raw SQL (parsed here) or already-bound SQL. */
    protected long[] runBatch(final List<String> _sqls, final boolean _parse) throws SQLException {
        final long[] counts = new long[_sqls.size()];
        for (int i = 0; i < _sqls.size(); i++) {
            try {
                final SqlText st = _parse ? parse(_sqls.get(i)) : SqlText.parse(_sqls.get(i), engine().getCharset(), false);
                if (st.isQuery()) {
                    throw new SQLException("Queries are not allowed in a batch", "07003");
                }
                run(st, st.getSql(), Statement.NO_GENERATED_KEYS, null);
                counts[i] = Math.max(0, updateCount);
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

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closeCurrent();
        closed = true;
        connection.unregister(this);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return maxFieldSize;
    }

    @Override
    public void setMaxFieldSize(final int _max) throws SQLException {
        checkOpen();
        if (_max < 0) {
            throw new SQLException("maxFieldSize must not be negative", "HY024");
        }
        maxFieldSize = _max;
    }

    int maxFieldSize() {
        return maxFieldSize;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return (int) Math.min(Integer.MAX_VALUE, maxRows);
    }

    @Override
    public void setMaxRows(final int _max) throws SQLException {
        setLargeMaxRows(_max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setLargeMaxRows(final long _max) throws SQLException {
        checkOpen();
        if (_max < 0) {
            throw new SQLException("maxRows must not be negative", "HY024");
        }
        maxRows = _max;
    }

    @Override
    public void setEscapeProcessing(final boolean _enable) throws SQLException {
        checkOpen();
        escapeProcessing = _enable;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(final int _seconds) throws SQLException {
        checkOpen();
        if (_seconds < 0) {
            throw new SQLException("queryTimeout must not be negative", "HY024");
        }
        queryTimeout = _seconds;
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
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public void setCursorName(final String _name) throws SQLException {
        throw SqlErrors.unsupported("Positioned updates (setCursorName)");
    }

    @Override
    public void setFetchDirection(final int _direction) throws SQLException {
        checkOpen();
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
        if (_rows < 0) {
            throw new SQLException("fetchSize must not be negative", "HY024");
        }
        fetchSize = _rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return resultSetConcurrency;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return resultSetType;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return resultSetHoldability;
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public void setPoolable(final boolean _poolable) throws SQLException {
        checkOpen();
        poolable = _poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
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
