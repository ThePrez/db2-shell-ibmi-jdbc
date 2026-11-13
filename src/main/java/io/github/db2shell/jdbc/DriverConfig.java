package io.github.db2shell.jdbc;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Immutable, parsed driver configuration built from a JDBC URL plus connection properties.
 *
 * <p>URL format: {@code jdbc:db2shell:[//][RDBNAME][;property=value]...[?property=value&...]}</p>
 */
public final class DriverConfig {

    public static final String URL_PREFIX = "jdbc:db2shell:";

    /** How query results are retrieved from the db2 utility. */
    public enum ResultMode {
        /** Describe the query first and retrieve fully typed, lossless values (default). */
        TYPED,
        /** Parse the utility's native fixed-width output; all columns are reported as VARCHAR. */
        RAW
    }

    // ---------------------------------------------------------------- property names
    public static final String P_USER = "user";
    public static final String P_PASSWORD = "password";
    public static final String P_RDB = "databaseName";
    public static final String P_ENCODING = "encoding";
    public static final String P_CCSID = "ccsid";
    public static final String P_SCHEMA = "schema";
    public static final String P_LIBRARIES = "libraries";
    public static final String P_PATH = "path";
    public static final String P_INIT_SQL = "initSql";
    public static final String P_QSH = "qshPath";
    public static final String P_DB2 = "db2Path";
    public static final String P_RESULT_MODE = "resultMode";
    public static final String P_UNICODE = "unicode";
    public static final String P_LINE_WIDTH = "lineWidth";
    public static final String P_LOB_MAX = "lobMaxLength";
    public static final String P_UPDATE_COUNTS = "updateCounts";
    public static final String P_AUTOCOMMIT = "autoCommit";
    public static final String P_ISOLATION = "transactionIsolation";
    public static final String P_QUERY_TIMEOUT = "queryTimeout";
    public static final String P_STARTUP_TIMEOUT = "startupTimeout";
    public static final String P_DESCRIBE_CACHE = "describeCacheSize";
    public static final String P_TRACE = "trace";
    public static final String P_TRACE_FILE = "traceFile";

    private static final Map<String, String[]> PROPERTY_DOCS = new LinkedHashMap<String, String[]>();

    static {
        doc(P_USER, null, "User profile to connect as (passed to db2 -u). Defaults to the job's user.");
        doc(P_PASSWORD, null, "Password for 'user' (passed to db2 -p). Note: visible in the process list.");
        doc(P_RDB, null, "Relational database name (passed to db2 -r). Defaults to *LOCAL when a user is given.");
        doc(P_ENCODING, null, "Java charset of the db2 utility's stdin/stdout. Defaults to the "
                + "os400.job.file.encoding system property (or Cp037 if unset).");
        doc(P_CCSID, null, "Alternative to 'encoding': an IBM CCSID number, e.g. 37, 273, 1208.");
        doc(P_SCHEMA, null, "Default schema (SET SCHEMA) for the connection.");
        doc(P_LIBRARIES, null, "Comma-separated libraries to add to the job's library list.");
        doc(P_PATH, null, "SQL path (SET PATH) for unqualified functions, procedures and types.");
        doc(P_INIT_SQL, null, "SQL statement(s) to run when the connection starts, separated by ';;'.");
        doc(P_QSH, "/usr/bin/qsh", "Path of the QSH shell used to launch the db2 utility.");
        doc(P_DB2, "/usr/bin/db2", "Path of the db2 command line utility.");
        doc(P_RESULT_MODE, "typed", "'typed' (describe and retrieve typed, lossless values) or 'raw' "
                + "(parse the utility's display output; every column is VARCHAR).");
        doc(P_UNICODE, "false", "Hex-encode character data as UTF-16 in transit so that characters that "
                + "cannot be represented in the job CCSID survive (costs ~4x bandwidth for strings).");
        doc(P_LINE_WIDTH, "128", "Width (bytes) of each line of typed result output (32-4000).");
        doc(P_LOB_MAX, "1048576", "Maximum number of characters/bytes retrieved from a LOB or XML value.");
        doc(P_UPDATE_COUNTS, "true", "Wrap INSERT/UPDATE/DELETE/MERGE so that accurate update counts are returned.");
        doc(P_AUTOCOMMIT, "true", "Initial auto-commit mode.");
        doc(P_ISOLATION, "read committed", "Isolation level used when auto-commit is off: 'read uncommitted', "
                + "'read committed', 'repeatable read' or 'serializable'.");
        doc(P_QUERY_TIMEOUT, "0", "Default query timeout in seconds (0 = none).");
        doc(P_STARTUP_TIMEOUT, "60", "Seconds to wait for the db2 utility to start.");
        doc(P_DESCRIBE_CACHE, "100", "Number of query descriptions to cache per connection (0 disables).");
        doc(P_TRACE, "false", "Trace all traffic to and from the db2 utility.");
        doc(P_TRACE_FILE, null, "File to write trace output to (default: DriverManager log writer or stderr).");
    }

    private static void doc(final String name, final String dflt, final String description) {
        PROPERTY_DOCS.put(name, new String[] { dflt, description });
    }

    // ---------------------------------------------------------------- values
    private final String url;
    private final String rdb;
    private final String user;
    private final String password;
    private final Charset charset;
    private final boolean charsetExplicit;
    private final String schema;
    private final List<String> libraries;
    private final String path;
    private final List<String> initSql;
    private final String qshPath;
    private final String db2Path;
    private final ResultMode resultMode;
    private final boolean unicode;
    private final int lineWidth;
    private final int lobMaxLength;
    private final boolean updateCounts;
    private final boolean autoCommit;
    private final int isolation;
    private final int queryTimeout;
    private final int startupTimeout;
    private final int describeCacheSize;
    private final boolean trace;
    private final String traceFile;

    private DriverConfig(final String _url, final Map<String, String> _p) throws SQLException {
        url = _url;
        user = blankToNull(_p.get(P_USER));
        password = _p.get(P_PASSWORD);
        String r = blankToNull(_p.get(P_RDB));
        if (null == r && null != user) {
            r = "*LOCAL";
        }
        rdb = r;
        final String enc = blankToNull(_p.get(P_ENCODING));
        final String ccsid = blankToNull(_p.get(P_CCSID));
        if (null != enc) {
            charset = lookupCharset(enc);
            charsetExplicit = true;
        } else if (null != ccsid) {
            charset = charsetForCcsid(ccsid);
            charsetExplicit = true;
        } else {
            charset = lookupCharset(System.getProperty("os400.job.file.encoding", "Cp037"));
            charsetExplicit = false;
        }
        schema = blankToNull(_p.get(P_SCHEMA));
        libraries = splitList(_p.get(P_LIBRARIES), ",");
        path = blankToNull(_p.get(P_PATH));
        initSql = splitList(_p.get(P_INIT_SQL), ";;");
        qshPath = dflt(_p.get(P_QSH), "/usr/bin/qsh");
        db2Path = dflt(_p.get(P_DB2), "/usr/bin/db2");
        final String mode = dflt(_p.get(P_RESULT_MODE), "typed").trim().toUpperCase(Locale.ROOT);
        try {
            resultMode = ResultMode.valueOf(mode);
        } catch (final IllegalArgumentException e) {
            throw new SQLNonTransientConnectionException("Invalid value for " + P_RESULT_MODE + ": " + mode, "08001");
        }
        unicode = bool(_p, P_UNICODE, false);
        lineWidth = Math.max(32, Math.min(4000, integer(_p, P_LINE_WIDTH, 128)));
        lobMaxLength = Math.max(1, integer(_p, P_LOB_MAX, 1048576));
        updateCounts = bool(_p, P_UPDATE_COUNTS, true);
        autoCommit = bool(_p, P_AUTOCOMMIT, true);
        isolation = parseIsolation(dflt(_p.get(P_ISOLATION), "read committed"));
        queryTimeout = Math.max(0, integer(_p, P_QUERY_TIMEOUT, 0));
        startupTimeout = Math.max(1, integer(_p, P_STARTUP_TIMEOUT, 60));
        describeCacheSize = Math.max(0, integer(_p, P_DESCRIBE_CACHE, 100));
        trace = bool(_p, P_TRACE, false);
        traceFile = blankToNull(_p.get(P_TRACE_FILE));
    }

    /** Returns true if the given URL is handled by this driver. */
    public static boolean acceptsUrl(final String _url) {
        return null != _url && _url.regionMatches(true, 0, URL_PREFIX, 0, URL_PREFIX.length());
    }

    /**
     * Parses a URL and connection properties. Properties given in the URL override those in {@code _info}.
     */
    public static DriverConfig parse(final String _url, final Properties _info) throws SQLException {
        if (!acceptsUrl(_url)) {
            throw new SQLNonTransientConnectionException("Not a db2shell URL: " + _url, "08001");
        }
        final Map<String, String> props = new LinkedHashMap<String, String>();
        if (null != _info) {
            for (final String name : _info.stringPropertyNames()) {
                props.put(canonicalName(name), _info.getProperty(name));
            }
        }
        String rest = _url.substring(URL_PREFIX.length());
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        String query = null;
        final int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        final String[] parts = rest.split(";");
        final String host = parts[0].trim();
        if (host.endsWith("/")) {
            // tolerate jdbc:db2shell://RDB/
            parts[0] = host.substring(0, host.length() - 1);
        }
        if (!parts[0].trim().isEmpty() && !parts[0].trim().equalsIgnoreCase("localhost")) {
            props.put(P_RDB, parts[0].trim());
        }
        for (int i = 1; i < parts.length; i++) {
            addKeyValue(props, parts[i]);
        }
        if (null != query) {
            for (final String kv : query.split("&")) {
                addKeyValue(props, kv);
            }
        }
        return new DriverConfig(_url, props);
    }

    private static void addKeyValue(final Map<String, String> _props, final String _kv) {
        final String kv = _kv.trim();
        if (kv.isEmpty()) {
            return;
        }
        final int eq = kv.indexOf('=');
        if (eq < 0) {
            _props.put(canonicalName(kv), "true");
        } else {
            _props.put(canonicalName(kv.substring(0, eq).trim()), kv.substring(eq + 1).trim());
        }
    }

    private static String canonicalName(final String _name) {
        for (final String known : PROPERTY_DOCS.keySet()) {
            if (known.equalsIgnoreCase(_name)) {
                return known;
            }
        }
        if ("rdb".equalsIgnoreCase(_name) || "database".equalsIgnoreCase(_name)) {
            return P_RDB;
        }
        if ("libraryList".equalsIgnoreCase(_name)) {
            return P_LIBRARIES;
        }
        if ("currentSchema".equalsIgnoreCase(_name) || "defaultSchema".equalsIgnoreCase(_name)) {
            return P_SCHEMA;
        }
        return _name;
    }

    static DriverPropertyInfo[] propertyInfo(final String _url, final Properties _info) {
        final List<DriverPropertyInfo> ret = new ArrayList<DriverPropertyInfo>();
        for (final Map.Entry<String, String[]> e : PROPERTY_DOCS.entrySet()) {
            final String given = null == _info ? null : _info.getProperty(e.getKey());
            final DriverPropertyInfo dpi = new DriverPropertyInfo(e.getKey(), null != given ? given : e.getValue()[0]);
            dpi.description = e.getValue()[1];
            dpi.required = false;
            if (P_RESULT_MODE.equals(e.getKey())) {
                dpi.choices = new String[] { "typed", "raw" };
            } else if (P_ISOLATION.equals(e.getKey())) {
                dpi.choices = new String[] { "read uncommitted", "read committed", "repeatable read", "serializable" };
            } else if ("false".equals(e.getValue()[0]) || "true".equals(e.getValue()[0])) {
                dpi.choices = new String[] { "true", "false" };
            }
            ret.add(dpi);
        }
        return ret.toArray(new DriverPropertyInfo[0]);
    }

    // ---------------------------------------------------------------- helpers
    static Charset lookupCharset(final String _name) throws SQLException {
        try {
            return Charset.forName(_name.trim());
        } catch (final RuntimeException e) {
            // OpenJDK names IBM code pages "IBM037"; IBM JDKs also know "Cp037". Try both spellings.
            final String n = _name.trim();
            if (n.toUpperCase(Locale.ROOT).startsWith("CP")) {
                try {
                    return Charset.forName("IBM" + n.substring(2));
                } catch (final RuntimeException e2) {
                    // fall through
                }
            }
            throw new SQLNonTransientConnectionException("Unsupported encoding: " + _name, "08001", e);
        }
    }

    static Charset charsetForCcsid(final String _ccsid) throws SQLException {
        final String c = _ccsid.trim();
        if ("1208".equals(c)) {
            return Charset.forName("UTF-8");
        }
        if ("819".equals(c)) {
            return Charset.forName("ISO-8859-1");
        }
        if ("1200".equals(c) || "13488".equals(c)) {
            throw new SQLNonTransientConnectionException("UTF-16 cannot be used as the db2 utility encoding", "08001");
        }
        String padded = c;
        while (padded.length() < 3) {
            padded = "0" + padded;
        }
        for (final String candidate : new String[] { "Cp" + padded, "IBM" + padded, "IBM-" + padded, "x-IBM" + padded }) {
            try {
                return Charset.forName(candidate);
            } catch (final RuntimeException e) {
                // try next
            }
        }
        throw new SQLNonTransientConnectionException("No Java charset found for CCSID " + c, "08001");
    }

    static int parseIsolation(final String _s) throws SQLException {
        final String s = _s.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        if (s.equals("read uncommitted") || s.equals("ur") || s.equals("chg") || s.equals("*chg")) {
            return Connection.TRANSACTION_READ_UNCOMMITTED;
        }
        if (s.equals("read committed") || s.equals("cs") || s.equals("*cs")) {
            return Connection.TRANSACTION_READ_COMMITTED;
        }
        if (s.equals("repeatable read") || s.equals("rs") || s.equals("all") || s.equals("*all")) {
            return Connection.TRANSACTION_REPEATABLE_READ;
        }
        if (s.equals("serializable") || s.equals("rr")) {
            return Connection.TRANSACTION_SERIALIZABLE;
        }
        if (s.equals("none") || s.equals("nc") || s.equals("*none")) {
            return Connection.TRANSACTION_NONE;
        }
        throw new SQLNonTransientConnectionException("Invalid transaction isolation: " + _s, "08001");
    }

    private static List<String> splitList(final String _s, final String _sep) {
        final List<String> ret = new ArrayList<String>();
        if (null == _s) {
            return ret;
        }
        for (final String part : _s.split(java.util.regex.Pattern.quote(_sep))) {
            if (!part.trim().isEmpty()) {
                ret.add(part.trim());
            }
        }
        return ret;
    }

    private static boolean bool(final Map<String, String> _p, final String _key, final boolean _dflt) {
        final String v = blankToNull(_p.get(_key));
        if (null == v) {
            return _dflt;
        }
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1") || v.equalsIgnoreCase("on");
    }

    private static int integer(final Map<String, String> _p, final String _key, final int _dflt) throws SQLException {
        final String v = blankToNull(_p.get(_key));
        if (null == v) {
            return _dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            throw new SQLNonTransientConnectionException("Invalid integer for property " + _key + ": " + v, "08001");
        }
    }

    private static String blankToNull(final String _s) {
        return null == _s || _s.trim().isEmpty() ? null : _s;
    }

    private static String dflt(final String _s, final String _dflt) {
        return null == blankToNull(_s) ? _dflt : _s.trim();
    }

    // ---------------------------------------------------------------- getters
    public String getUrl() { return url; }
    public String getRdb() { return rdb; }
    public String getUser() { return user; }
    public String getPassword() { return password; }
    public Charset getCharset() { return charset; }
    public boolean isCharsetExplicit() { return charsetExplicit; }
    public String getSchema() { return schema; }
    public List<String> getLibraries() { return libraries; }
    public String getPath() { return path; }
    public List<String> getInitSql() { return initSql; }
    public String getQshPath() { return qshPath; }
    public String getDb2Path() { return db2Path; }
    public ResultMode getResultMode() { return resultMode; }
    public boolean isUnicode() { return unicode; }
    public int getLineWidth() { return lineWidth; }
    public int getLobMaxLength() { return lobMaxLength; }
    public boolean isUpdateCounts() { return updateCounts; }
    public boolean isAutoCommit() { return autoCommit; }
    public int getIsolation() { return isolation; }
    public int getQueryTimeout() { return queryTimeout; }
    public int getStartupTimeout() { return startupTimeout; }
    public int getDescribeCacheSize() { return describeCacheSize; }
    public boolean isTrace() { return trace; }
    public String getTraceFile() { return traceFile; }
}
