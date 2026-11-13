package io.github.db2shell.jdbc.internal;

import java.util.List;

/**
 * Generates the SQL the driver runs on behalf of the application.
 *
 * <h2>Typed result stream</h2>
 * <p>Each row is serialized into a string {@code S} made of one token per column: {@code -} for NULL, otherwise
 * {@code <length>:<value>} where the value is the column converted to text (job CCSID), hex (binary, or all
 * character data in unicode mode) or a canonical date/number format. {@code S} is then emitted as one or more
 * fixed-width lines by joining with a numbers table. This avoids the utility's 4096-byte line limit, padding of
 * every value to its declared width, ambiguity between NULL and the string "-", and corruption by embedded
 * newlines.</p>
 */
public final class QueryBuilder {

    /** DGTT holding describe output. */
    public static final String META_TABLE = "SESSION.JDBC_META";
    /** DGTT holding the update count of the last wrapped DML statement. */
    public static final String RC_TABLE = "SESSION.JDBC_RC";
    /** DGTT holding the integers 1..n used to split rows into lines. */
    public static final String NUMS_TABLE = "SESSION.JDBC_NUMS";

    private static final String DGTT_OPTS = " ON COMMIT PRESERVE ROWS NOT LOGGED ON ROLLBACK PRESERVE ROWS WITH REPLACE";
    private static final int VARCHAR_MAX = 32000;
    private static final int HEX_BYTES_PIECE = 16000;
    private static final int UNICODE_CHARS_PIECE = 8000;

    private QueryBuilder() {
    }

    // =================================================================================== session tables

    public static String createMetaTable() {
        return "DECLARE GLOBAL TEMPORARY TABLE " + META_TABLE + " (ORD INTEGER, NAME VARCHAR(128), TYP INTEGER, "
                + "LEN INTEGER, PREC INTEGER, SCALE INTEGER, NUL INTEGER, CCSID INTEGER, DT INTEGER, "
                + "BS VARCHAR(128), BT VARCHAR(128), BC VARCHAR(128))" + DGTT_OPTS;
    }

    public static String createRcTable() {
        return "DECLARE GLOBAL TEMPORARY TABLE " + RC_TABLE + " (N BIGINT)" + DGTT_OPTS;
    }

    public static String initRcTable() {
        return "INSERT INTO " + RC_TABLE + " VALUES 0";
    }

    public static String createNumsTable() {
        return "DECLARE GLOBAL TEMPORARY TABLE " + NUMS_TABLE + " (K INTEGER NOT NULL)" + DGTT_OPTS;
    }

    public static String indexNumsTable() {
        return "CREATE INDEX SESSION.JDBC_NUMS_IX ON " + NUMS_TABLE + " (K)";
    }

    public static String fillNumsTable(final int _from, final int _to) {
        return "INSERT INTO " + NUMS_TABLE + " WITH R(K) AS (VALUES " + _from + " UNION ALL SELECT K + 1 FROM R WHERE K < "
                + _to + ") SELECT K FROM R";
    }

    // =================================================================================== describe

    /**
     * A dynamic compound statement that PREPAREs and DESCRIBEs a query into {@link #META_TABLE}, one row per
     * result column.
     *
     * @param _query
     *            normalized query text
     * @return the compound statement
     */
    public static String describe(final String _query) {
        final StringBuilder sb = new StringBuilder(_query.length() + 1200);
        sb.append("BEGIN DECLARE JDBC_N INTEGER DEFAULT 0; DECLARE JDBC_I INTEGER DEFAULT 1; ");
        sb.append("DECLARE JDBC_SQL ").append(sqlVarType(_query)).append("; ");
        sb.append("DECLARE V_NAME VARCHAR(128); DECLARE V_TYPE INTEGER; DECLARE V_LEN INTEGER; DECLARE V_PREC INTEGER; ");
        sb.append("DECLARE V_SCALE INTEGER; DECLARE V_NULL INTEGER; DECLARE V_CCSID INTEGER; DECLARE V_DT INTEGER; ");
        sb.append("DECLARE V_BS VARCHAR(128); DECLARE V_BT VARCHAR(128); DECLARE V_BC VARCHAR(128); ");
        sb.append("DELETE FROM ").append(META_TABLE).append("; ");
        sb.append("SET JDBC_SQL = ").append(SqlLiterals.concatenatedLiteral(_query)).append("; ");
        sb.append("ALLOCATE DESCRIPTOR 'JDBC_DESC' WITH MAX 8000; ");
        sb.append("PREPARE JDBC_STMT FROM JDBC_SQL; ");
        sb.append("DESCRIBE JDBC_STMT USING SQL DESCRIPTOR 'JDBC_DESC'; ");
        sb.append("GET DESCRIPTOR 'JDBC_DESC' JDBC_N = COUNT; ");
        sb.append("WHILE JDBC_I <= JDBC_N DO ");
        sb.append("GET DESCRIPTOR 'JDBC_DESC' VALUE JDBC_I V_NAME = NAME, V_TYPE = TYPE, V_LEN = LENGTH, ");
        sb.append("V_PREC = PRECISION, V_SCALE = SCALE, V_NULL = NULLABLE, V_CCSID = DB2_CCSID, ");
        sb.append("V_DT = DATETIME_INTERVAL_CODE, V_BS = DB2_BASE_SCHEMA_NAME, V_BT = DB2_BASE_TABLE_NAME, ");
        sb.append("V_BC = DB2_BASE_COLUMN_NAME; ");
        sb.append("INSERT INTO ").append(META_TABLE).append(" VALUES (JDBC_I, V_NAME, V_TYPE, V_LEN, V_PREC, V_SCALE, ");
        sb.append("V_NULL, V_CCSID, V_DT, V_BS, V_BT, V_BC); ");
        sb.append("SET JDBC_I = JDBC_I + 1; END WHILE; ");
        sb.append("DEALLOCATE DESCRIPTOR 'JDBC_DESC'; END");
        return sb.toString();
    }

    /** Selects the describe output. Column order matches {@link Engine}'s parser. */
    public static String selectMeta() {
        return "SELECT ORD, TYP, LEN, PREC, SCALE, NUL, CCSID, DT, NAME, BS, BT, BC FROM " + META_TABLE + " ORDER BY ORD";
    }

    private static String sqlVarType(final String _sql) {
        return _sql.length() > 30000 ? "CLOB(2097152)" : "VARCHAR(32000)";
    }

    // =================================================================================== update counts

    /** Runs a DML statement via EXECUTE IMMEDIATE and stores its row count in {@link #RC_TABLE}. */
    public static String updateWithCount(final String _sql) {
        return "BEGIN DECLARE JDBC_N BIGINT DEFAULT 0; DECLARE JDBC_SQL " + sqlVarType(_sql) + "; SET JDBC_SQL = "
                + SqlLiterals.concatenatedLiteral(_sql) + "; EXECUTE IMMEDIATE JDBC_SQL; "
                + "GET DIAGNOSTICS JDBC_N = ROW_COUNT; UPDATE " + RC_TABLE + " SET N = JDBC_N; END";
    }

    public static String selectCount() {
        return "SELECT N FROM " + RC_TABLE;
    }

    // =================================================================================== data stream

    /** The generated data query plus what is needed to read it. */
    public static final class StreamQuery {
        private final String m_sql;
        private final int m_lineWidth;
        private final int m_numsNeeded;

        StreamQuery(final String _sql, final int _lineWidth, final int _numsNeeded) {
            m_sql = _sql;
            m_lineWidth = _lineWidth;
            m_numsNeeded = _numsNeeded;
        }

        /** The SQL to run. */
        public String getSql() {
            return m_sql;
        }

        /** Width of each output line. */
        public int getLineWidth() {
            return m_lineWidth;
        }

        /** Highest line number needed in the numbers table (0 when rows are a single line). */
        public int getNumsNeeded() {
            return m_numsNeeded;
        }
    }

    /**
     * Wraps a query so that its rows come back as a typed token stream.
     *
     * @param _query normalized query text
     * @param _cols the query's columns (from describe)
     * @param _maxRows 0 for no limit
     * @param _lineWidth preferred width of each output line
     * @param _unicode hex-encode character data as UTF-16
     * @param _lobMax maximum characters/bytes returned from LOB and XML values
     * @param _sbcs true if the job charset is single-byte (one byte per character)
     * @return the wrapped query, its output line width and the numbers-table size it needs
     */
    public static StreamQuery stream(final String _query, final List<ColumnInfo> _cols, final long _maxRows,
            final int _lineWidth, final boolean _unicode, final int _lobMax, final boolean _sbcs) {
        final String[] parts = SqlText.splitQuery(_query);
        final String cte = parts[0];
        final String body = parts[1];
        final String tail = parts[2];

        final StringBuilder corr = new StringBuilder();
        final StringBuilder s = new StringBuilder();
        long total = 0;
        for (int i = 0; i < _cols.size(); i++) {
            final String ref = "J" + (i + 1);
            corr.append(i > 0 ? ", " : "").append(ref);
            final long[] len = new long[1];
            final String v = value(_cols.get(i), ref, _unicode, _lobMax, _sbcs, len);
            final long tokLen = len[0] + 12;
            final String tok = "COALESCE(VARCHAR(LENGTH(" + v + ")) CONCAT ':' CONCAT " + v + ", '-')";
            if (i > 0) {
                s.append(" CONCAT ");
            }
            s.append(tok);
            total += tokLen;
        }
        String rowExpr = s.toString();
        final boolean lob = total > VARCHAR_MAX;
        if (lob) {
            // promote the whole concatenation to a CLOB
            rowExpr = "CAST('' AS CLOB(" + Math.min(2147483647L, total + 16) + ")) CONCAT " + rowExpr;
        }
        final String from = "(" + body + ") AS X(" + corr + ")";
        final String limit = _maxRows > 0 ? " FETCH FIRST " + _maxRows + " ROWS ONLY" : "";
        final String tailSql = tail.isEmpty() ? "" : " " + tail;
        final String prefix = cte.isEmpty() ? "" : cte + " ";

        if (!lob && total <= _lineWidth * 2L) {
            final int w = (int) Math.max(8, total);
            final String sql = prefix + "SELECT CAST(" + rowExpr + " AS VARCHAR(" + w + ")) AS L FROM " + from
                    + " ORDER BY ORDER OF X" + limit + tailSql;
            return new StreamQuery(sql, w, 0);
        }
        final int w = _lineWidth;
        final long lines = (total + w - 1) / w;
        final String lineCount = "(LENGTH(Y.S) + " + (w - 1) + ") / " + w;
        // The line-count predicate is repeated outside the LATERAL: when S is a CLOB and only Z.L is selected, Db2
        // does not apply the inner predicate (every numbers-table row comes back, blank and out of order).
        final String sql = prefix + "SELECT Z.L FROM (SELECT " + rowExpr + " AS S FROM " + from + " ORDER BY ORDER OF X"
                + limit + ") AS Y, LATERAL (SELECT N.K, CAST(SUBSTRING(Y.S, (N.K - 1) * " + w + " + 1, " + w
                + ") AS VARCHAR(" + w + ")) AS L FROM " + NUMS_TABLE + " N WHERE N.K <= " + lineCount + ") AS Z WHERE Z.K <= "
                + lineCount + " ORDER BY ORDER OF Y, Z.K" + tailSql;
        return new StreamQuery(sql, w, (int) Math.min(Integer.MAX_VALUE, lines + 1));
    }

    /**
     * The text form of a column value inside the token stream.
     *
     * @param _c the column
     * @param _ref the correlation name referring to the column value
     * @param _unicode hex-encode character data as UTF-16
     * @param _lobMax maximum characters/bytes returned from LOB and XML values
     * @param _sbcs true if the job charset is single-byte (one byte per character)
     * @param _len receives the maximum length (bytes) of the text form
     * @return the SQL expression producing the text form
     */
    static String value(final ColumnInfo _c, final String _ref, final boolean _unicode, final int _lobMax,
            final boolean _sbcs, final long[] _len) {
        final int len = Math.max(1, _c.getLength());
        switch (_c.getKind()) {
        case STRING: {
            if (_unicode) {
                return unicodeHex(_ref, len, _len);
            }
            final long m = _sbcs ? len : len * 2L + 2;
            _len[0] = m;
            if (m > VARCHAR_MAX) {
                return "CAST(" + _ref + " AS CLOB(" + m + "))";
            }
            return "CAST(" + _ref + " AS VARCHAR(" + m + "))";
        }
        case CLOB:
        case XML: {
            final String src;
            final int m;
            if (ColumnInfo.Kind.XML == _c.getKind()) {
                src = "XMLSERIALIZE(" + _ref + " AS CLOB(2G))";
                m = _lobMax;
            } else {
                src = _ref;
                m = Math.min(len, _lobMax);
            }
            if (_unicode) {
                return unicodeHex(src, m, _len);
            }
            final String sub = m < len || ColumnInfo.Kind.XML == _c.getKind() ? "SUBSTRING(" + src + ", 1, " + m + ")" : src;
            final long bytes = _sbcs ? m : m * 2L + 2;
            _len[0] = bytes;
            if (bytes <= VARCHAR_MAX) {
                return "CAST(" + sub + " AS VARCHAR(" + bytes + "))";
            }
            return "CAST(" + sub + " AS CLOB(" + bytes + "))";
        }
        case BINARY:
        case BLOB: {
            final int m = ColumnInfo.Kind.BLOB == _c.getKind() ?Math.min(len, _lobMax) : len;
            _len[0] = m * 2L;
            if (m <= HEX_BYTES_PIECE) {
                if (ColumnInfo.Kind.BLOB == _c.getKind() && m < len) {
                    return "HEX(SUBSTRING(" + _ref + ", 1, " + m + "))";
                }
                return "HEX(" + _ref + ")";
            }
            return pieces("HEX(SUBSTRING(" + _ref + ", %d, " + HEX_BYTES_PIECE + "))", m, HEX_BYTES_PIECE, m * 2L);
        }
        case BOOLEAN:
            _len[0] = 1;
            return "CASE WHEN " + _ref + " IS NULL THEN NULL WHEN " + _ref + " THEN '1' ELSE '0' END";
        case DATE:
            _len[0] = 10;
            return "CHAR(" + _ref + ", ISO)";
        case TIME:
            _len[0] = 8;
            return "CHAR(" + _ref + ", JIS)";
        case TIMESTAMP:
            _len[0] = 32;
            return "VARCHAR(" + _ref + ")";
        case SMALLINT:
            _len[0] = 6;
            return "VARCHAR(" + _ref + ")";
        case INTEGER:
            _len[0] = 11;
            return "VARCHAR(" + _ref + ")";
        case BIGINT:
            _len[0] = 20;
            return "VARCHAR(" + _ref + ")";
        case DECIMAL:
            _len[0] = Math.max(1, _c.getPrecision()) + 3;
            return "VARCHAR(" + _ref + ")";
        case REAL:
        case DOUBLE:
            // VARCHAR() of floating point gives full precision, e.g. -1.2345678000000001E24
            _len[0] = 25;
            return "VARCHAR(" + _ref + ")";
        default:
            // DECFLOAT and anything else
            _len[0] = 45;
            return "VARCHAR(" + _ref + ")";
        }
    }

    /**
     * HEX of the UTF-16 form of a character value of at most {@code _chars} characters, split into pieces when
     * long.
     *
     * @param _src the SQL expression of the character value
     * @param _chars the maximum number of characters
     * @param _len receives the maximum length (bytes) of the hex text
     * @return the SQL expression producing the hex text
     */
    private static String unicodeHex(final String _src, final int _chars, final long[] _len) {
        _len[0] = _chars * 4L;
        if (_chars <= UNICODE_CHARS_PIECE) {
            return "HEX(CAST(" + _src + " AS VARGRAPHIC(" + _chars + ") CCSID 1200))";
        }
        return pieces("HEX(CAST(SUBSTRING(" + _src + ", %d, " + UNICODE_CHARS_PIECE + ") AS VARGRAPHIC("
                + UNICODE_CHARS_PIECE + ") CCSID 1200))", _chars, UNICODE_CHARS_PIECE, _chars * 4L);
    }

    /**
     * Concatenates {@code ceil(_total/_piece)} copies of a format with %d replaced by the 1-based start offset.
     *
     * @param _fmt the expression format, containing %d once or more
     * @param _total the total length to cover
     * @param _piece the length covered by each copy
     * @param _resultLen the maximum length of the result; above the VARCHAR limit the result is cast to CLOB
     * @return the parenthesized concatenation
     */
    private static String pieces(final String _fmt, final int _total, final int _piece, final long _resultLen) {
        final StringBuilder sb = new StringBuilder();
        for (long off = 1; off <= _total; off += _piece) {
            final String p = _fmt.replace("%d", Long.toString(off));
            if (1 == off) {
                if (_resultLen > VARCHAR_MAX) {
                    sb.append("CAST(").append(p).append(" AS CLOB(").append(_resultLen + 16).append("))");
                } else {
                    sb.append(p);
                }
            } else {
                sb.append(" CONCAT ").append(p);
            }
        }
        return "(" + sb + ")";
    }
}
