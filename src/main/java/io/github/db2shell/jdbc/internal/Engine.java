package io.github.db2shell.jdbc.internal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.db2shell.jdbc.DriverConfig;

/**
 * Executes SQL through a {@link Session}, implementing typed queries, update counts, transactions and session
 * recovery. All public methods are synchronized on the engine.
 */
public final class Engine {

    /** Result of a query. */
    public static final class QueryResult {
        private final List<ColumnInfo> m_columns;
        private final RowSource m_rows;
        private final List<SQLWarning> m_warnings;

        QueryResult(final List<ColumnInfo> _columns, final RowSource _rows, final List<SQLWarning> _warnings) {
            m_columns = _columns;
            m_rows = _rows;
            m_warnings = _warnings;
        }

        public List<ColumnInfo> getColumns() {
            return m_columns;
        }

        public RowSource getRows() {
            return m_rows;
        }

        public List<SQLWarning> getWarnings() {
            return m_warnings;
        }
    }

    /** Facts about the server, fetched lazily. */
    public static final class ServerInfo {
        private final String m_rdbName;
        private final String m_user;
        private final String m_jobName;
        private final int m_version;
        private final int m_release;

        /**
         * Creates the server facts.
         *
         * @param _rdbName
         *            the relational database name (CURRENT SERVER)
         * @param _user
         *            the current user
         * @param _jobName
         *            the qualified name of the job running the db2 utility (may be empty)
         * @param _version
         *            the IBM i version
         * @param _release
         *            the IBM i release
         */
        ServerInfo(final String _rdbName, final String _user, final String _jobName, final int _version,
                final int _release) {
            m_rdbName = _rdbName;
            m_user = _user;
            m_jobName = _jobName;
            m_version = _version;
            m_release = _release;
        }

        /** The relational database name (CURRENT SERVER). */
        public String getRdbName() {
            return m_rdbName;
        }

        /** The current user (USER special register). */
        public String getUser() {
            return m_user;
        }

        /** The qualified name of the job running the db2 utility, e.g. 123456/QUSER/QP0ZSPWP. */
        public String getJobName() {
            return m_jobName;
        }

        /** The IBM i version, e.g. 7. */
        public int getVersion() {
            return m_version;
        }

        /** The IBM i release, e.g. 5. */
        public int getRelease() {
            return m_release;
        }

        /** The release in VxRyMz form, e.g. V7R5M0. */
        public String getVersionString() {
            return "V" + m_version + "R" + m_release + "M0";
        }
    }

    private final DriverConfig m_cfg;
    private final Tracer m_tracer;
    private Session m_session;
    private Charset m_charset;
    private int m_numsSize = 0;
    private boolean m_autoCommit;
    private int m_isolation;
    private boolean m_uowActive = false;
    private boolean m_closed = false;
    private ServerInfo m_serverInfo;
    private final List<SQLWarning> m_connectionWarnings = new ArrayList<SQLWarning>();
    private final Map<String, List<ColumnInfo>> m_describeCache;

    /**
     * Creates an engine for the given configuration. No session is started until {@link #open()} is called.
     *
     * @param _cfg
     *            the driver configuration (charset, transaction defaults, tracing, describe cache size, ...)
     */
    public Engine(final DriverConfig _cfg) {
        m_cfg = _cfg;
        m_tracer = Tracer.create(_cfg.isTrace(), _cfg.getTraceFile());
        m_charset = _cfg.getCharset();
        m_autoCommit = _cfg.isAutoCommit();
        m_isolation = _cfg.getIsolation();
        final int cacheSize = _cfg.getDescribeCacheSize();
        m_describeCache = new LinkedHashMap<String, List<ColumnInfo>>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, List<ColumnInfo>> _eldest) {
                return size() > cacheSize;
            }
        };
    }

    public DriverConfig getConfig() {
        return m_cfg;
    }

    public Tracer getTracer() {
        return m_tracer;
    }

    /** The charset of the utility's input/output (may differ from the configured one if auto-detected). */
    public synchronized Charset getCharset() {
        return m_charset;
    }

    // =================================================================================== lifecycle

    /** Starts the db2 utility and initializes the session. */
    public synchronized void open() throws SQLException {
        startSession();
    }

    /**
     * Starts a new db2 utility process, performs the session handshake, installs the SQL canceller and runs the
     * session initialization. On failure the new process is terminated and no session is left installed.
     *
     * @throws SQLException
     *             if the utility cannot be started or the session cannot be initialized
     */
    private void startSession() throws SQLException {
        final Transport t;
        try {
            t = ProcessTransport.start(m_cfg, m_tracer);
        } catch (final IOException e) {
            throw new SQLNonTransientConnectionException("Unable to start " + m_cfg.getQshPath() + " -c "
                    + m_cfg.getDb2Path() + ": " + e.getMessage(), "08001", e);
        }
        final Session s = new Session(t, m_cfg.getCharset(), m_tracer);
        try {
            s.start(m_cfg.getStartupTimeout(), m_cfg.isCharsetExplicit());
        } catch (final SQLException e) {
            t.kill();
            throw e;
        }
        m_session = s;
        m_charset = s.getCharset();
        if (null != s.getEncodingNote()) {
            m_connectionWarnings.add(new SQLWarning(s.getEncodingNote(), "01000"));
        }
        m_numsSize = 0;
        m_uowActive = false;
        m_serverInfo = null;
        try {
            final String job = getServerInfo().getJobName();
            s.setCanceller(new Runnable() {
                @Override
                public void run() {
                    ProcessTransport.cancelSql(m_cfg, job, m_tracer);
                }
            });
            initSession();
        } catch (final SQLException e) {
            s.close();
            m_session = null;
            throw e;
        }
    }

    /**
     * Prepares a freshly started session: creates the driver work tables and applies the configured schema,
     * library list, path and initial SQL statements. Library list failures are reported as connection warnings.
     *
     * @throws SQLException
     *             if any of the required statements fails
     */
    private void initSession() throws SQLException {
        // Driver work tables. Created before any unit of work starts so a rollback cannot drop them.
        exec(QueryBuilder.createMetaTable());
        exec(QueryBuilder.createRcTable());
        exec(QueryBuilder.initRcTable());
        exec(QueryBuilder.createNumsTable());
        exec(QueryBuilder.indexNumsTable());
        ensureNums(256);
        if (null != m_cfg.getSchema()) {
            exec("SET SCHEMA " + m_cfg.getSchema());
        }
        for (final String lib : m_cfg.getLibraries()) {
            final Session.Response r = m_session.send("CALL QSYS2.QCMDEXC('ADDLIBLE LIB(" + lib.replace("'", "''")
                    + ") POSITION(*LAST)')", 0);
            if (r.isError()) {
                m_connectionWarnings.add(new SQLWarning("Could not add " + lib + " to the library list: "
                        + r.getError().getMessage(), r.getError().getSQLState(), r.getError().getErrorCode()));
            }
        }
        if (null != m_cfg.getPath()) {
            exec("SET PATH " + m_cfg.getPath());
        }
        for (final String s : m_cfg.getInitSql()) {
            exec(SqlText.parse(s, m_charset, true).getSql());
        }
    }

    /**
     * Restarts the session if it died (timeout, cancel, crash). A restart is reported as a connection warning,
     * since session state (QTEMP, SET statements, uncommitted work) is lost.
     *
     * @throws SQLException
     *             if the engine is closed or the session cannot be restarted
     */
    private void ensureSession() throws SQLException {
        if (m_closed) {
            throw SqlErrors.closed("Connection");
        }
        if (null == m_session || m_session.isDead() || !m_session.isProcessAlive()) {
            final boolean lostWork = m_uowActive;
            m_tracer.trace("session", "restarting db2 utility session");
            m_session = null;
            startSession();
            m_connectionWarnings.add(new SQLWarning("The db2 utility session ended (timeout, cancel or failure) and was "
                    + "restarted: QTEMP, global temporary tables and SET statements not in the connection properties "
                    + "were lost" + (lostWork ? ", and uncommitted work was rolled back" : ""), "01002"));
        }
    }

    public synchronized boolean isClosed() {
        return m_closed;
    }

    /**
     * Closes the engine and its session. Calling this more than once has no further effect.
     */
    public synchronized void close() {
        if (m_closed) {
            return;
        }
        m_closed = true;
        if (null != m_session) {
            m_session.close();
            m_session = null;
        }
    }

    /** Ends any in-flight command by killing the utility; the session restarts on next use. */
    public void cancel() {
        final Session s = m_session;
        if (null != s) {
            s.kill();
        }
    }

    /**
     * Checks that the connection is usable by running a trivial query, restarting the session first if needed.
     *
     * @param _timeout
     *            timeout in seconds for the check (0 for none)
     * @return true if the query succeeded, false if the engine is closed or the check failed
     */
    public synchronized boolean isValid(final int _timeout) {
        if (m_closed) {
            return false;
        }
        try {
            ensureSession();
            final Session.Response r = m_session.send("VALUES 1", _timeout);
            if (r.isResult()) {
                m_session.discardRows();
            }
            return !r.isError();
        } catch (final SQLException e) {
            // an unusable connection is reported as invalid rather than as an error
            m_tracer.trace("isValid", "validity check failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<SQLWarning> takeConnectionWarnings() {
        final List<SQLWarning> ret = new ArrayList<SQLWarning>(m_connectionWarnings);
        m_connectionWarnings.clear();
        return ret;
    }

    // =================================================================================== primitives

    /**
     * Runs an internal statement, throwing on error. Any printed result table is discarded.
     *
     * @param _sql
     *            the statement text
     * @return the response
     * @throws SQLException
     *             if the statement fails
     */
    private Session.Response exec(final String _sql) throws SQLException {
        final Session.Response r = m_session.send(_sql, 0);
        if (r.isResult()) {
            m_session.discardRows();
        }
        if (r.isError()) {
            throw r.getError();
        }
        return r;
    }

    /**
     * Runs an internal query whose values are short, returning trimmed strings (null for NULL).
     *
     * @param _sql
     *            the query text
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @return the rows (empty if the statement printed no result table)
     * @throws SQLException
     *             if the query fails
     */
    private List<String[]> rawRows(final String _sql, final int _timeout) throws SQLException {
        final Session.Response r = m_session.send(_sql, _timeout);
        if (r.isError()) {
            if (r.isResult()) {
                m_session.discardRows();
            }
            throw r.getError();
        }
        final List<String[]> rows = new ArrayList<String[]>();
        if (!r.isResult()) {
            return rows;
        }
        byte[] line;
        while (null != (line = m_session.nextRowLine(r.getWidth()))) {
            final String[] row = new String[r.getColumns().length];
            for (int i = 0; i < row.length; i++) {
                final String v = RawRowSource.field(m_session, line, r.getColumns()[i][0], r.getColumns()[i][1]);
                row[i] = null == v ? null : v.trim();
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Makes sure the numbers table holds at least the integers 1.._needed, growing it in powers of two.
     *
     * @param _needed
     *            the highest number required
     * @throws SQLException
     *             if the table cannot be filled
     */
    private void ensureNums(final int _needed) throws SQLException {
        if (_needed <= m_numsSize) {
            return;
        }
        int target = Math.max(256, m_numsSize);
        while (target < _needed) {
            target *= 2;
        }
        exec(QueryBuilder.fillNumsTable(m_numsSize + 1, target));
        m_numsSize = target;
    }

    // =================================================================================== transactions

    private void beginUow() throws SQLException {
        if (!m_autoCommit && !m_uowActive && Connection.TRANSACTION_NONE != m_isolation) {
            exec("SET TRANSACTION ISOLATION LEVEL " + isolationSql(m_isolation));
            m_uowActive = true;
        }
    }

    /**
     * Maps a JDBC isolation level to the SQL isolation clause.
     *
     * @param _level
     *            a {@link Connection} TRANSACTION_* constant
     * @return the isolation clause (READ COMMITTED for unknown levels)
     */
    static String isolationSql(final int _level) {
        switch (_level) {
        case Connection.TRANSACTION_READ_UNCOMMITTED:
            return "READ UNCOMMITTED";
        case Connection.TRANSACTION_REPEATABLE_READ:
            return "REPEATABLE READ";
        case Connection.TRANSACTION_SERIALIZABLE:
            return "SERIALIZABLE";
        case Connection.TRANSACTION_NONE:
            return "NO COMMIT";
        default:
            return "READ COMMITTED";
        }
    }

    public synchronized boolean getAutoCommit() {
        return m_autoCommit;
    }

    /**
     * Switches auto-commit mode. Turning auto-commit on commits any active unit of work, as JDBC requires.
     *
     * @param _on
     *            the new auto-commit mode
     * @throws SQLException
     *             if the implicit commit fails
     */
    public synchronized void setAutoCommit(final boolean _on) throws SQLException {
        if (_on == m_autoCommit) {
            return;
        }
        if (_on && m_uowActive) {
            commit();
        }
        m_autoCommit = _on;
    }

    public synchronized int getIsolation() {
        return m_isolation;
    }

    /** Sets the isolation level used for subsequent units of work. */
    public synchronized void setIsolation(final int _level) {
        m_isolation = _level;
    }

    /**
     * Commits the active unit of work, if any.
     *
     * @throws SQLException
     *             if the engine is closed or the commit fails
     */
    public synchronized void commit() throws SQLException {
        ensureSession();
        if (m_uowActive) {
            exec("COMMIT");
            m_uowActive = false;
        }
    }

    /**
     * Rolls back the active unit of work, if any.
     *
     * @throws SQLException
     *             if the engine is closed or the rollback fails
     */
    public synchronized void rollback() throws SQLException {
        ensureSession();
        if (m_uowActive) {
            exec("ROLLBACK");
            m_uowActive = false;
        }
    }

    /**
     * Sets a savepoint, starting a unit of work if necessary.
     *
     * @param _name
     *            the savepoint name (an SQL identifier)
     * @throws SQLException
     *             if auto-commit is on, the engine is closed, or the statement fails
     */
    public synchronized void savepoint(final String _name) throws SQLException {
        if (m_autoCommit) {
            throw new SQLException("Savepoints require auto-commit to be off", "3B001");
        }
        ensureSession();
        beginUow();
        exec("SAVEPOINT " + _name + " ON ROLLBACK RETAIN CURSORS");
    }

    public synchronized void rollbackTo(final String _name) throws SQLException {
        ensureSession();
        exec("ROLLBACK TO SAVEPOINT " + _name);
    }

    public synchronized void releaseSavepoint(final String _name) throws SQLException {
        ensureSession();
        exec("RELEASE SAVEPOINT " + _name);
    }

    // =================================================================================== server info

    /**
     * Returns facts about the server, querying them on first use. If the full query fails (for example because
     * SYSIBMADM.ENV_SYS_INFO is not available) a reduced query is used and defaults fill the gaps.
     *
     * @return the server facts (cached until the session restarts)
     * @throws SQLException
     *             if the engine is closed or even the reduced query fails
     */
    public synchronized ServerInfo getServerInfo() throws SQLException {
        if (null != m_serverInfo) {
            return m_serverInfo;
        }
        ensureSession();
        String rdb = "*LOCAL";
        String user = "";
        String job = "";
        int v = 7;
        int r = 1;
        try {
            final List<String[]> rows = rawRows("SELECT CURRENT SERVER, USER, QSYS2.JOB_NAME, "
                    + "(SELECT OS_VERSION CONCAT '.' CONCAT OS_RELEASE FROM SYSIBMADM.ENV_SYS_INFO FETCH FIRST 1 ROW ONLY)"
                    + " FROM SYSIBM.SYSDUMMY1", 0);
            if (!rows.isEmpty()) {
                final String[] row = rows.get(0);
                rdb = nz(row[0], rdb);
                user = nz(row[1], user);
                job = nz(row[2], job);
                final String vr = row[3];
                if (null != vr && vr.contains(".")) {
                    v = Integer.parseInt(vr.substring(0, vr.indexOf('.')).trim());
                    r = Integer.parseInt(vr.substring(vr.indexOf('.') + 1).trim());
                }
            }
        } catch (final SQLException e) {
            // fall back to the special registers every release supports
            m_tracer.trace("serverInfo", "full server info query failed, using reduced query: " + e.getMessage());
            final List<String[]> rows = rawRows("SELECT CURRENT SERVER, USER FROM SYSIBM.SYSDUMMY1", 0);
            if (!rows.isEmpty()) {
                rdb = nz(rows.get(0)[0], rdb);
                user = nz(rows.get(0)[1], user);
            }
        } catch (final NumberFormatException e) {
            // unparseable OS version: keep the default version/release
            m_tracer.trace("serverInfo", "unable to parse OS version: " + e.getMessage());
        }
        m_serverInfo = new ServerInfo(rdb, user, job, v, r);
        return m_serverInfo;
    }

    private static String nz(final String _s, final String _d) {
        return null == _s ? _d : _s;
    }

    /** A single value from an internal query, or null. */
    public synchronized String queryValue(final String _sql) throws SQLException {
        ensureSession();
        beginUow();
        final List<String[]> rows = rawRows(_sql, 0);
        return rows.isEmpty() ? null : rows.get(0)[0];
    }

    // =================================================================================== describe

    /** Describes a query (columns of its result) without running it. */
    public synchronized List<ColumnInfo> describe(final String _sql, final int _timeout) throws SQLException {
        ensureSession();
        beginUow();
        return describeInternal(_sql, _timeout, true);
    }

    /**
     * Describes a query by preparing it on the server and reading the descriptor back from the meta table.
     *
     * @param _sql
     *            normalized query text
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @param _useCache
     *            whether a cached description may be returned
     * @return the columns of the query's result (empty if the statement is not a query)
     * @throws SQLException
     *             if the statement cannot be prepared or described
     */
    private List<ColumnInfo> describeInternal(final String _sql, final int _timeout, final boolean _useCache)
            throws SQLException {
        if (_useCache) {
            final List<ColumnInfo> c = m_describeCache.get(_sql);
            if (null != c) {
                return c;
            }
        }
        final Session.Response r = m_session.send(QueryBuilder.describe(_sql), _timeout);
        if (r.isResult()) {
            m_session.discardRows();
        }
        if (r.isError()) {
            throw r.getError();
        }
        final List<String[]> rows = rawRows(QueryBuilder.selectMeta(), 0);
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>(rows.size());
        for (final String[] row : rows) {
            // ORD, TYP, LEN, PREC, SCALE, NUL, CCSID, DT, NAME, BS, BT, BC
            cols.add(ColumnInfo.fromDescriptor(nz(row[8], ""), i(row[1]), i(row[2]), i(row[3]), i(row[4]),
                    0 != i(row[5]), i(row[6]), i(row[7]), row[9], row[10], row[11]));
        }
        final List<ColumnInfo> ret = Collections.unmodifiableList(cols);
        if (m_cfg.getDescribeCacheSize() > 0) {
            m_describeCache.put(_sql, ret);
        }
        return ret;
    }

    /**
     * Parses an integer descriptor value.
     *
     * @param _s
     *            the value text (may be null)
     * @return the value, or 0 if it is null or not a number
     */
    private static int i(final String _s) {
        if (null == _s) {
            return 0;
        }
        try {
            return Integer.parseInt(_s.trim());
        } catch (final NumberFormatException e) {
            // descriptor fields that are not numbers are treated as absent
            return 0;
        }
    }

    // =================================================================================== queries

    /**
     * Runs a query. In typed mode the query is described and wrapped so rows arrive as a typed token stream; a
     * stale cached description is refreshed once, and a wrapper the server rejects falls back to raw output.
     *
     * @param _sql
     *            normalized single-line query text (parameters already bound)
     * @param _maxRows
     *            0 for no limit
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @return the result columns and rows
     * @throws SQLException
     *             if the query fails
     */
    public synchronized QueryResult query(final String _sql, final long _maxRows, final int _timeout) throws SQLException {
        ensureSession();
        beginUow();
        if (DriverConfig.ResultMode.RAW == m_cfg.getResultMode()) {
            return rawQuery(_sql, _maxRows, _timeout);
        }
        boolean cached = m_describeCache.containsKey(_sql);
        List<ColumnInfo> cols = describeInternal(_sql, _timeout, true);
        if (cols.isEmpty()) {
            // not a query after all: run it and return an empty result
            final Session.Response r = m_session.send(_sql, _timeout);
            if (r.isResult()) {
                return rawResult(r, _maxRows);
            }
            if (r.isError()) {
                throw r.getError();
            }
            return new QueryResult(cols, new RowSource.ListRowSource(null), r.getWarnings());
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            final QueryBuilder.StreamQuery q = QueryBuilder.stream(_sql, cols, _maxRows, m_cfg.getLineWidth(),
                    m_cfg.isUnicode(), m_cfg.getLobMaxLength(), isSingleByte());
            ensureNums(q.getNumsNeeded());
            final Session.Response r = m_session.send(q.getSql(), _timeout);
            if (r.isError() && !r.isResult()) {
                if (cached) {
                    // the cached description may be stale: describe again and retry
                    m_describeCache.remove(_sql);
                    cols = describeInternal(_sql, _timeout, false);
                    cached = false;
                    continue;
                }
                if (isWrapFailure(r.getError().getSQLState())) {
                    m_tracer.trace("fallback", "typed wrapper failed (" + r.getError().getMessage() + "); using raw output");
                    return rawQuery(_sql, _maxRows, _timeout);
                }
                throw r.getError();
            }
            if (!r.isResult()) {
                throw SqlErrors.protocol("query produced no result table");
            }
            final TypedRowSource src = new TypedRowSource(this, m_session, cols, q.getLineWidth(), m_cfg.isUnicode());
            return new QueryResult(cols, src, r.getWarnings());
        }
        throw SqlErrors.protocol("unable to run query");
    }

    private boolean isSingleByte() {
        return m_charset.newEncoder().maxBytesPerChar() <= 1.0f;
    }

    /** Errors that indicate the wrapping (not the application's query) is the problem. */
    private static boolean isWrapFailure(final String _state) {
        return null != _state && (_state.startsWith("42") || _state.startsWith("0A") || _state.startsWith("54")
                || _state.startsWith("428") || _state.startsWith("429"));
    }

    /**
     * Runs a query without wrapping it, parsing the utility's display output.
     *
     * @param _sql
     *            the query text
     * @param _maxRows
     *            0 for no limit
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @return the result (with no columns if the statement printed no result table)
     * @throws SQLException
     *             if the statement fails
     */
    private QueryResult rawQuery(final String _sql, final long _maxRows, final int _timeout) throws SQLException {
        final Session.Response r = m_session.send(_sql, _timeout);
        if (r.isError() && !r.isResult()) {
            throw r.getError();
        }
        if (!r.isResult()) {
            return new QueryResult(new ArrayList<ColumnInfo>(), new RowSource.ListRowSource(null), r.getWarnings());
        }
        return rawResult(r, _maxRows);
    }

    /**
     * Builds a raw-mode result (all columns VARCHAR) over a response that printed a result table. Adds a warning
     * if the rows were truncated by the utility's output line limit.
     *
     * @param _r
     *            the response, positioned at the first row
     * @param _maxRows
     *            0 for no limit
     * @return the result
     */
    private QueryResult rawResult(final Session.Response _r, final long _maxRows) {
        final String[] names = RawRowSource.headerNames(m_session, _r.getHeader(), _r.getColumns());
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        for (int c = 0; c < names.length; c++) {
            cols.add(ColumnInfo.raw(names[c], _r.getColumns()[c][1]));
        }
        if (_r.getWidth() >= Session.MAX_OUTPUT_LINE) {
            _r.getWarnings().add(new SQLWarning("Result rows are wider than " + Session.MAX_OUTPUT_LINE
                    + " bytes; the db2 utility truncated them", "01004"));
        }
        final RawRowSource src = new RawRowSource(this, m_session, _r.getColumns(), _r.getWidth(), _maxRows);
        return new QueryResult(cols, src, _r.getWarnings());
    }

    // =================================================================================== updates

    /**
     * Runs a statement that does not return rows. DML is wrapped to capture its row count when update counts are
     * enabled; COMMIT/ROLLBACK without an active unit of work are skipped; DDL invalidates the describe cache.
     *
     * @param _sql
     *            normalized statement text (parameters already bound)
     * @param _keyword
     *            the statement's first keyword, upper case
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @param _warnings
     *            receives the warnings reported by the statement
     * @return the update count (0 for DDL and other statements)
     * @throws SQLException
     *             if the statement fails
     */
    public synchronized long update(final String _sql, final String _keyword, final int _timeout,
            final List<SQLWarning> _warnings) throws SQLException {
        ensureSession();
        final boolean txnControl = "COMMIT".equals(_keyword) || ("ROLLBACK".equals(_keyword)
                && !_sql.toUpperCase(Locale.ROOT).matches("^ROLLBACK\\s+(WORK\\s+)?TO\\s+.*"));
        if (txnControl && !m_uowActive) {
            // without commitment control COMMIT/ROLLBACK fail with SQLSTATE 51009; nothing to do
            return 0;
        }
        if (!txnControl) {
            beginUow();
        }
        long count = 0;
        final boolean dml = "INSERT".equals(_keyword) || "UPDATE".equals(_keyword) || "DELETE".equals(_keyword)
                || "MERGE".equals(_keyword);
        if (dml && m_cfg.isUpdateCounts()) {
            final Session.Response r = m_session.send(QueryBuilder.updateWithCount(_sql), _timeout);
            if (r.isResult()) {
                m_session.discardRows();
            }
            if (r.isError()) {
                throw r.getError();
            }
            _warnings.addAll(r.getWarnings());
            final List<String[]> rows = rawRows(QueryBuilder.selectCount(), 0);
            if (!rows.isEmpty() && null != rows.get(0)[0]) {
                count = Long.parseLong(rows.get(0)[0].trim());
            }
        } else {
            final Session.Response r = m_session.send(_sql, _timeout);
            if (r.isResult()) {
                m_session.discardRows();
            }
            if (r.isError()) {
                throw r.getError();
            }
            _warnings.addAll(r.getWarnings());
        }
        if (txnControl) {
            m_uowActive = false;
        }
        if (isDdl(_keyword)) {
            m_describeCache.clear();
        }
        return count;
    }

    private static boolean isDdl(final String _kw) {
        return "CREATE".equals(_kw) || "ALTER".equals(_kw) || "DROP".equals(_kw) || "RENAME".equals(_kw)
                || "DECLARE".equals(_kw) || "COMMENT".equals(_kw) || "LABEL".equals(_kw) || "SET".equals(_kw)
                || "CALL".equals(_kw) || "BEGIN".equals(_kw) || "GRANT".equals(_kw) || "REVOKE".equals(_kw);
    }

    /**
     * Runs a statement and returns whatever it produced: a result (if a table was printed) or null. The describe
     * cache is cleared since the statement may change object definitions.
     *
     * @param _sql
     *            normalized statement text
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @param _warnings
     *            receives the warnings reported by the statement
     * @return a raw-mode result, or null if the statement printed no result table
     * @throws SQLException
     *             if the statement fails
     */
    public synchronized QueryResult executeAny(final String _sql, final int _timeout, final List<SQLWarning> _warnings)
            throws SQLException {
        ensureSession();
        beginUow();
        m_describeCache.clear();
        final Session.Response r = m_session.send(_sql, _timeout);
        if (r.isError() && !r.isResult()) {
            throw r.getError();
        }
        _warnings.addAll(r.getWarnings());
        if (r.isResult()) {
            return rawResult(r, 0);
        }
        return null;
    }

    /**
     * Runs a batch of internal statements (used by callable statements), throwing on the first error.
     *
     * @param _sql
     *            the statement text
     * @param _timeout
     *            timeout in seconds (0 for none)
     * @throws SQLException
     *             if the statement fails
     */
    public synchronized void execInternal(final String _sql, final int _timeout) throws SQLException {
        ensureSession();
        beginUow();
        final Session.Response r = m_session.send(_sql, _timeout);
        if (r.isResult()) {
            m_session.discardRows();
        }
        if (r.isError()) {
            throw r.getError();
        }
    }
}
