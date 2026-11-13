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
        public final List<ColumnInfo> columns;
        public final RowSource rows;
        public final List<SQLWarning> warnings;

        QueryResult(final List<ColumnInfo> _columns, final RowSource _rows, final List<SQLWarning> _warnings) {
            columns = _columns;
            rows = _rows;
            warnings = _warnings;
        }
    }

    /** Facts about the server, fetched lazily. */
    public static final class ServerInfo {
        public final String rdbName;
        public final String user;
        public final String jobName;
        public final int version;
        public final int release;

        ServerInfo(final String _rdb, final String _user, final String _job, final int _v, final int _r) {
            rdbName = _rdb;
            user = _user;
            jobName = _job;
            version = _v;
            release = _r;
        }

        public String versionString() {
            return "V" + version + "R" + release + "M0";
        }
    }

    private final DriverConfig cfg;
    private final Tracer tracer;
    private Session session;
    private Charset charset;
    private int numsSize = 0;
    private boolean autoCommit;
    private int isolation;
    private boolean uowActive = false;
    private boolean closed = false;
    private ServerInfo serverInfo;
    private final List<SQLWarning> connectionWarnings = new ArrayList<SQLWarning>();
    private final Map<String, List<ColumnInfo>> describeCache;

    public Engine(final DriverConfig _cfg) {
        cfg = _cfg;
        tracer = Tracer.create(_cfg.isTrace(), _cfg.getTraceFile());
        charset = _cfg.getCharset();
        autoCommit = _cfg.isAutoCommit();
        isolation = _cfg.getIsolation();
        final int cacheSize = _cfg.getDescribeCacheSize();
        describeCache = new LinkedHashMap<String, List<ColumnInfo>>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, List<ColumnInfo>> _eldest) {
                return size() > cacheSize;
            }
        };
    }

    public DriverConfig getConfig() {
        return cfg;
    }

    public Tracer getTracer() {
        return tracer;
    }

    /** The charset of the utility's input/output (may differ from the configured one if auto-detected). */
    public synchronized Charset getCharset() {
        return charset;
    }

    // =================================================================================== lifecycle

    /** Starts the db2 utility and initializes the session. */
    public synchronized void open() throws SQLException {
        startSession();
    }

    private void startSession() throws SQLException {
        final Transport t;
        try {
            t = ProcessTransport.start(cfg, tracer);
        } catch (final IOException e) {
            throw new SQLNonTransientConnectionException("Unable to start " + cfg.getQshPath() + " -c "
                    + cfg.getDb2Path() + ": " + e.getMessage(), "08001", e);
        }
        final Session s = new Session(t, cfg.getCharset(), tracer);
        try {
            s.start(cfg.getStartupTimeout(), cfg.isCharsetExplicit());
        } catch (final SQLException e) {
            t.kill();
            throw e;
        }
        session = s;
        charset = s.getCharset();
        if (null != s.getEncodingNote()) {
            connectionWarnings.add(new SQLWarning(s.getEncodingNote(), "01000"));
        }
        numsSize = 0;
        uowActive = false;
        serverInfo = null;
        try {
            final String job = getServerInfo().jobName;
            s.setCanceller(new Runnable() {
                @Override
                public void run() {
                    ProcessTransport.cancelSql(cfg, job, tracer);
                }
            });
            initSession();
        } catch (final SQLException e) {
            s.close();
            session = null;
            throw e;
        }
    }

    private void initSession() throws SQLException {
        // Driver work tables. Created before any unit of work starts so a rollback cannot drop them.
        exec(QueryBuilder.createMetaTable());
        exec(QueryBuilder.createRcTable());
        exec(QueryBuilder.initRcTable());
        exec(QueryBuilder.createNumsTable());
        exec(QueryBuilder.indexNumsTable());
        ensureNums(256);
        if (null != cfg.getSchema()) {
            exec("SET SCHEMA " + cfg.getSchema());
        }
        for (final String lib : cfg.getLibraries()) {
            final Session.Response r = session.send("CALL QSYS2.QCMDEXC('ADDLIBLE LIB(" + lib.replace("'", "''")
                    + ") POSITION(*LAST)')", 0);
            if (r.isError()) {
                connectionWarnings.add(new SQLWarning("Could not add " + lib + " to the library list: "
                        + r.error.getMessage(), r.error.getSQLState(), r.error.getErrorCode()));
            }
        }
        if (null != cfg.getPath()) {
            exec("SET PATH " + cfg.getPath());
        }
        for (final String s : cfg.getInitSql()) {
            exec(SqlText.parse(s, charset, true).getSql());
        }
    }

    /** Restarts the session if it died (timeout, cancel, crash). */
    private void ensureSession() throws SQLException {
        if (closed) {
            throw SqlErrors.closed("Connection");
        }
        if (null == session || session.isDead() || !session.isProcessAlive()) {
            final boolean lostWork = uowActive;
            tracer.trace("session", "restarting db2 utility session");
            session = null;
            startSession();
            connectionWarnings.add(new SQLWarning("The db2 utility session ended (timeout, cancel or failure) and was "
                    + "restarted: QTEMP, global temporary tables and SET statements not in the connection properties "
                    + "were lost" + (lostWork ? ", and uncommitted work was rolled back" : ""), "01002"));
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (null != session) {
            session.close();
            session = null;
        }
    }

    /** Ends any in-flight command by killing the utility; the session restarts on next use. */
    public void cancel() {
        final Session s = session;
        if (null != s) {
            s.kill();
        }
    }

    public synchronized boolean isValid(final int _timeout) {
        if (closed) {
            return false;
        }
        try {
            ensureSession();
            final Session.Response r = session.send("VALUES 1", _timeout);
            if (r.isResult()) {
                session.discardRows();
            }
            return !r.isError();
        } catch (final SQLException e) {
            return false;
        }
    }

    public synchronized List<SQLWarning> takeConnectionWarnings() {
        final List<SQLWarning> ret = new ArrayList<SQLWarning>(connectionWarnings);
        connectionWarnings.clear();
        return ret;
    }

    // =================================================================================== primitives

    /** Runs an internal statement, throwing on error. */
    private Session.Response exec(final String _sql) throws SQLException {
        final Session.Response r = session.send(_sql, 0);
        if (r.isResult()) {
            session.discardRows();
        }
        if (r.isError()) {
            throw r.error;
        }
        return r;
    }

    /** Runs an internal query whose values are short, returning trimmed strings (null for NULL). */
    private List<String[]> rawRows(final String _sql, final int _timeout) throws SQLException {
        final Session.Response r = session.send(_sql, _timeout);
        if (r.isError()) {
            if (r.isResult()) {
                session.discardRows();
            }
            throw r.error;
        }
        final List<String[]> rows = new ArrayList<String[]>();
        if (!r.isResult()) {
            return rows;
        }
        byte[] line;
        while (null != (line = session.nextRowLine(r.width))) {
            final String[] row = new String[r.columns.length];
            for (int i = 0; i < row.length; i++) {
                final String v = RawRowSource.field(session, line, r.columns[i][0], r.columns[i][1]);
                row[i] = null == v ? null : v.trim();
            }
            rows.add(row);
        }
        return rows;
    }

    private void ensureNums(final int _needed) throws SQLException {
        if (_needed <= numsSize) {
            return;
        }
        int target = Math.max(256, numsSize);
        while (target < _needed) {
            target *= 2;
        }
        exec(QueryBuilder.fillNumsTable(numsSize + 1, target));
        numsSize = target;
    }

    // =================================================================================== transactions

    private void beginUow() throws SQLException {
        if (!autoCommit && !uowActive && isolation != Connection.TRANSACTION_NONE) {
            exec("SET TRANSACTION ISOLATION LEVEL " + isolationSql(isolation));
            uowActive = true;
        }
    }

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
        return autoCommit;
    }

    public synchronized void setAutoCommit(final boolean _on) throws SQLException {
        if (_on == autoCommit) {
            return;
        }
        if (_on && uowActive) {
            commit();
        }
        autoCommit = _on;
    }

    public synchronized int getIsolation() {
        return isolation;
    }

    /** Sets the isolation level used for subsequent units of work. */
    public synchronized void setIsolation(final int _level) {
        isolation = _level;
    }

    public synchronized void commit() throws SQLException {
        ensureSession();
        if (uowActive) {
            exec("COMMIT");
            uowActive = false;
        }
    }

    public synchronized void rollback() throws SQLException {
        ensureSession();
        if (uowActive) {
            exec("ROLLBACK");
            uowActive = false;
        }
    }

    public synchronized void savepoint(final String _name) throws SQLException {
        if (autoCommit) {
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

    public synchronized ServerInfo getServerInfo() throws SQLException {
        if (null != serverInfo) {
            return serverInfo;
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
            final List<String[]> rows = rawRows("SELECT CURRENT SERVER, USER FROM SYSIBM.SYSDUMMY1", 0);
            if (!rows.isEmpty()) {
                rdb = nz(rows.get(0)[0], rdb);
                user = nz(rows.get(0)[1], user);
            }
        } catch (final NumberFormatException e) {
            // keep defaults
        }
        serverInfo = new ServerInfo(rdb, user, job, v, r);
        return serverInfo;
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

    private List<ColumnInfo> describeInternal(final String _sql, final int _timeout, final boolean _useCache)
            throws SQLException {
        if (_useCache) {
            final List<ColumnInfo> c = describeCache.get(_sql);
            if (null != c) {
                return c;
            }
        }
        final Session.Response r = session.send(QueryBuilder.describe(_sql), _timeout);
        if (r.isResult()) {
            session.discardRows();
        }
        if (r.isError()) {
            throw r.error;
        }
        final List<String[]> rows = rawRows(QueryBuilder.selectMeta(), 0);
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>(rows.size());
        for (final String[] row : rows) {
            // ORD, TYP, LEN, PREC, SCALE, NUL, CCSID, DT, NAME, BS, BT, BC
            cols.add(ColumnInfo.fromDescriptor(nz(row[8], ""), i(row[1]), i(row[2]), i(row[3]), i(row[4]),
                    i(row[5]) != 0, i(row[6]), i(row[7]), row[9], row[10], row[11]));
        }
        final List<ColumnInfo> ret = Collections.unmodifiableList(cols);
        if (cfg.getDescribeCacheSize() > 0) {
            describeCache.put(_sql, ret);
        }
        return ret;
    }

    private static int i(final String _s) {
        if (null == _s) {
            return 0;
        }
        try {
            return Integer.parseInt(_s.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    // =================================================================================== queries

    /**
     * Runs a query.
     *
     * @param _sql normalized single-line query text (parameters already bound)
     * @param _maxRows 0 for no limit
     */
    public synchronized QueryResult query(final String _sql, final long _maxRows, final int _timeout) throws SQLException {
        ensureSession();
        beginUow();
        if (cfg.getResultMode() == DriverConfig.ResultMode.RAW) {
            return rawQuery(_sql, _maxRows, _timeout);
        }
        boolean cached = describeCache.containsKey(_sql);
        List<ColumnInfo> cols = describeInternal(_sql, _timeout, true);
        if (cols.isEmpty()) {
            // not a query after all: run it and return an empty result
            final Session.Response r = session.send(_sql, _timeout);
            if (r.isResult()) {
                return rawResult(r, _maxRows);
            }
            if (r.isError()) {
                throw r.error;
            }
            return new QueryResult(cols, new RowSource.ListRowSource(null), r.warnings);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            final QueryBuilder.StreamQuery q = QueryBuilder.stream(_sql, cols, _maxRows, cfg.getLineWidth(),
                    cfg.isUnicode(), cfg.getLobMaxLength(), isSingleByte());
            ensureNums(q.numsNeeded);
            final Session.Response r = session.send(q.sql, _timeout);
            if (r.isError() && !r.isResult()) {
                if (cached) {
                    // the cached description may be stale: describe again and retry
                    describeCache.remove(_sql);
                    cols = describeInternal(_sql, _timeout, false);
                    cached = false;
                    continue;
                }
                if (isWrapFailure(r.error.getSQLState())) {
                    tracer.trace("fallback", "typed wrapper failed (" + r.error.getMessage() + "); using raw output");
                    return rawQuery(_sql, _maxRows, _timeout);
                }
                throw r.error;
            }
            if (!r.isResult()) {
                throw SqlErrors.protocol("query produced no result table");
            }
            final TypedRowSource src = new TypedRowSource(this, session, cols, q.lineWidth, cfg.isUnicode());
            return new QueryResult(cols, src, r.warnings);
        }
        throw SqlErrors.protocol("unable to run query");
    }

    private boolean isSingleByte() {
        return charset.newEncoder().maxBytesPerChar() <= 1.0f;
    }

    /** Errors that indicate the wrapping (not the application's query) is the problem. */
    private static boolean isWrapFailure(final String _state) {
        return null != _state && (_state.startsWith("42") || _state.startsWith("0A") || _state.startsWith("54")
                || _state.startsWith("428") || _state.startsWith("429"));
    }

    private QueryResult rawQuery(final String _sql, final long _maxRows, final int _timeout) throws SQLException {
        final Session.Response r = session.send(_sql, _timeout);
        if (r.isError() && !r.isResult()) {
            throw r.error;
        }
        if (!r.isResult()) {
            return new QueryResult(new ArrayList<ColumnInfo>(), new RowSource.ListRowSource(null), r.warnings);
        }
        return rawResult(r, _maxRows);
    }

    private QueryResult rawResult(final Session.Response _r, final long _maxRows) {
        final String[] names = RawRowSource.headerNames(session, _r.header, _r.columns);
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        for (int c = 0; c < names.length; c++) {
            cols.add(ColumnInfo.raw(names[c], _r.columns[c][1]));
        }
        if (_r.width >= Session.MAX_OUTPUT_LINE) {
            _r.warnings.add(new SQLWarning("Result rows are wider than " + Session.MAX_OUTPUT_LINE
                    + " bytes; the db2 utility truncated them", "01004"));
        }
        final RawRowSource src = new RawRowSource(this, session, _r.columns, _r.width, _maxRows);
        return new QueryResult(cols, src, _r.warnings);
    }

    // =================================================================================== updates

    /**
     * Runs a statement that does not return rows.
     *
     * @return the update count (0 for DDL and other statements)
     */
    public synchronized long update(final String _sql, final String _keyword, final int _timeout,
            final List<SQLWarning> _warnings) throws SQLException {
        ensureSession();
        final boolean txnControl = "COMMIT".equals(_keyword) || ("ROLLBACK".equals(_keyword)
                && !_sql.toUpperCase(Locale.ROOT).matches("^ROLLBACK\\s+(WORK\\s+)?TO\\s+.*"));
        if (txnControl && !uowActive) {
            // without commitment control COMMIT/ROLLBACK fail with SQLSTATE 51009; nothing to do
            return 0;
        }
        if (!txnControl) {
            beginUow();
        }
        long count = 0;
        final boolean dml = "INSERT".equals(_keyword) || "UPDATE".equals(_keyword) || "DELETE".equals(_keyword)
                || "MERGE".equals(_keyword);
        if (dml && cfg.isUpdateCounts()) {
            final Session.Response r = session.send(QueryBuilder.updateWithCount(_sql), _timeout);
            if (r.isResult()) {
                session.discardRows();
            }
            if (r.isError()) {
                throw r.error;
            }
            _warnings.addAll(r.warnings);
            final List<String[]> rows = rawRows(QueryBuilder.selectCount(), 0);
            if (!rows.isEmpty() && null != rows.get(0)[0]) {
                count = Long.parseLong(rows.get(0)[0].trim());
            }
        } else {
            final Session.Response r = session.send(_sql, _timeout);
            if (r.isResult()) {
                session.discardRows();
            }
            if (r.isError()) {
                throw r.error;
            }
            _warnings.addAll(r.warnings);
        }
        if (txnControl) {
            uowActive = false;
        }
        if (isDdl(_keyword)) {
            describeCache.clear();
        }
        return count;
    }

    private static boolean isDdl(final String _kw) {
        return "CREATE".equals(_kw) || "ALTER".equals(_kw) || "DROP".equals(_kw) || "RENAME".equals(_kw)
                || "DECLARE".equals(_kw) || "COMMENT".equals(_kw) || "LABEL".equals(_kw) || "SET".equals(_kw)
                || "CALL".equals(_kw) || "BEGIN".equals(_kw) || "GRANT".equals(_kw) || "REVOKE".equals(_kw);
    }

    /** Runs a statement and returns whatever it produced: a result (if a table was printed) or null. */
    public synchronized QueryResult executeAny(final String _sql, final int _timeout, final List<SQLWarning> _warnings)
            throws SQLException {
        ensureSession();
        beginUow();
        describeCache.clear();
        final Session.Response r = session.send(_sql, _timeout);
        if (r.isError() && !r.isResult()) {
            throw r.error;
        }
        _warnings.addAll(r.warnings);
        if (r.isResult()) {
            return rawResult(r, 0);
        }
        return null;
    }

    /** Runs a batch of internal statements (used by callable statements), throwing on the first error. */
    public synchronized void execInternal(final String _sql, final int _timeout) throws SQLException {
        ensureSession();
        beginUow();
        final Session.Response r = session.send(_sql, _timeout);
        if (r.isResult()) {
            session.discardRows();
        }
        if (r.isError()) {
            throw r.error;
        }
    }
}
