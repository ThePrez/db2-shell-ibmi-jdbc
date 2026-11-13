package io.github.db2shell.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import io.github.db2shell.jdbc.internal.ColumnInfo;
import io.github.db2shell.jdbc.internal.Engine;
import io.github.db2shell.jdbc.internal.SqlLiterals;

/**
 * Database metadata. Catalog information comes from the ODBC/JDBC catalog views in SYSIBM (SQLTABLES,
 * SQLCOLUMNS, SQLPRIMARYKEYS, ...), queried through the db2 utility like any other query.
 */
public class Db2ShellDatabaseMetaData implements DatabaseMetaData {

    private final Db2ShellConnection connection;

    Db2ShellDatabaseMetaData(final Db2ShellConnection _conn) {
        connection = _conn;
    }

    private Engine.ServerInfo info() throws SQLException {
        return connection.engine().getServerInfo();
    }

    // =================================================================================== query helpers

    private ResultSet query(final String _sql) throws SQLException {
        final Db2ShellStatement s = (Db2ShellStatement) connection.createStatement();
        s.closeOnCompletion();
        return s.executeQuery(_sql);
    }

    /** A filter for a JDBC search pattern ({@code %}, {@code _}, escape {@code \}). Empty if it matches all. */
    static String pattern(final String _col, final String _pattern) {
        if (null == _pattern || _pattern.equals("%")) {
            return "";
        }
        boolean wildcard = false;
        final StringBuilder plain = new StringBuilder();
        for (int i = 0; i < _pattern.length(); i++) {
            final char c = _pattern.charAt(i);
            if (c == '\\' && i + 1 < _pattern.length()) {
                plain.append(_pattern.charAt(++i));
            } else if (c == '%' || c == '_') {
                wildcard = true;
                break;
            } else {
                plain.append(c);
            }
        }
        if (wildcard) {
            return " AND " + _col + " LIKE " + SqlLiterals.quote(_pattern) + " ESCAPE '\\'";
        }
        return " AND " + _col + " = " + SqlLiterals.quote(plain.toString());
    }

    /** A filter for an exact name; empty if null. */
    static String exact(final String _col, final String _value) {
        if (null == _value) {
            return "";
        }
        return " AND " + _col + " = " + SqlLiterals.quote(_value);
    }

    private ResultSet empty(final Object... _cols) throws SQLException {
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        for (int i = 0; i < _cols.length; i += 2) {
            cols.add(ColumnInfo.synthetic((String) _cols[i], (Integer) _cols[i + 1]));
        }
        return Db2ShellResultSet.ofRows(null, cols, new ArrayList<Object[]>());
    }

    private static final int V = Types.VARCHAR;
    private static final int I = Types.INTEGER;
    private static final int S = Types.SMALLINT;

    // =================================================================================== catalog queries

    @Override
    public ResultSet getTables(final String _catalog, final String _schemaPattern, final String _tablePattern,
            final String[] _types) throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT TABLE_CAT, TABLE_SCHEM, TABLE_NAME, TABLE_TYPE, REMARKS, "
                + "TYPE_CAT, TYPE_SCHEM, TYPE_NAME, SELF_REFERENCING_COL_NAME, REF_GENERATION FROM SYSIBM.SQLTABLES "
                + "WHERE 1 = 1");
        sql.append(pattern("TABLE_SCHEM", _schemaPattern)).append(pattern("TABLE_NAME", _tablePattern));
        if (null != _types && _types.length > 0) {
            sql.append(" AND TABLE_TYPE IN (");
            for (int i = 0; i < _types.length; i++) {
                sql.append(i > 0 ? ", " : "").append(SqlLiterals.quote(_types[i]));
            }
            sql.append(')');
        }
        sql.append(" ORDER BY TABLE_TYPE, TABLE_CAT, TABLE_SCHEM, TABLE_NAME");
        return query(sql.toString());
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(final String _catalog, final String _schemaPattern) throws SQLException {
        return query("SELECT TABLE_SCHEM, TABLE_CAT AS TABLE_CATALOG FROM SYSIBM.SQLSCHEMAS WHERE 1 = 1"
                + pattern("TABLE_SCHEM", _schemaPattern) + " ORDER BY TABLE_CAT, TABLE_SCHEM");
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return query("SELECT CURRENT SERVER AS TABLE_CAT FROM SYSIBM.SYSDUMMY1");
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return query("SELECT TABLE_TYPE FROM SYSIBM.SQLTABLETYPES ORDER BY TABLE_TYPE");
    }

    @Override
    public ResultSet getColumns(final String _catalog, final String _schemaPattern, final String _tablePattern,
            final String _columnPattern) throws SQLException {
        return query("SELECT C.TABLE_CAT, C.TABLE_SCHEM, C.TABLE_NAME, C.COLUMN_NAME, C.JDBC_DATA_TYPE AS DATA_TYPE, "
                + "C.TYPE_NAME, C.COLUMN_SIZE, C.BUFFER_LENGTH, C.DECIMAL_DIGITS, C.NUM_PREC_RADIX, C.NULLABLE, "
                + "C.REMARKS, C.COLUMN_DEF, C.SQL_DATA_TYPE, C.SQL_DATETIME_SUB, C.CHAR_OCTET_LENGTH, "
                + "C.ORDINAL_POSITION, C.IS_NULLABLE, C.SCOPE_CATALOG, C.SCOPE_SCHEMA, C.SCOPE_TABLE, "
                + "C.SOURCE_DATA_TYPE, "
                + "CAST(CASE WHEN S.IS_IDENTITY = 'YES' THEN 'YES' ELSE 'NO' END AS VARCHAR(3)) AS IS_AUTOINCREMENT, "
                + "CAST(CASE WHEN S.IS_IDENTITY = 'YES' OR S.HAS_DEFAULT IN ('E', 'I') THEN 'YES' ELSE 'NO' END "
                + "AS VARCHAR(3)) AS IS_GENERATEDCOLUMN "
                + "FROM SYSIBM.SQLCOLUMNS C LEFT JOIN QSYS2.SYSCOLUMNS S ON S.TABLE_SCHEMA = C.TABLE_SCHEM "
                + "AND S.TABLE_NAME = C.TABLE_NAME AND S.COLUMN_NAME = C.COLUMN_NAME WHERE 1 = 1"
                + pattern("C.TABLE_SCHEM", _schemaPattern) + pattern("C.TABLE_NAME", _tablePattern)
                + pattern("C.COLUMN_NAME", _columnPattern)
                + " ORDER BY C.TABLE_CAT, C.TABLE_SCHEM, C.TABLE_NAME, C.ORDINAL_POSITION");
    }

    @Override
    public ResultSet getPrimaryKeys(final String _catalog, final String _schema, final String _table)
            throws SQLException {
        return query("SELECT TABLE_CAT, TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, KEY_SEQ, PK_NAME "
                + "FROM SYSIBM.SQLPRIMARYKEYS WHERE 1 = 1" + exact("TABLE_SCHEM", _schema)
                + exact("TABLE_NAME", _table) + " ORDER BY COLUMN_NAME");
    }

    private static final String FK_COLS = "SELECT PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, PKCOLUMN_NAME, "
            + "FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, FKCOLUMN_NAME, KEY_SEQ, UPDATE_RULE, DELETE_RULE, FK_NAME, "
            + "PK_NAME, DEFERRABILITY FROM SYSIBM.SQLFOREIGNKEYS WHERE 1 = 1";

    @Override
    public ResultSet getImportedKeys(final String _catalog, final String _schema, final String _table)
            throws SQLException {
        return query(FK_COLS + exact("FKTABLE_SCHEM", _schema) + exact("FKTABLE_NAME", _table)
                + " ORDER BY PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, KEY_SEQ");
    }

    @Override
    public ResultSet getExportedKeys(final String _catalog, final String _schema, final String _table)
            throws SQLException {
        return query(FK_COLS + exact("PKTABLE_SCHEM", _schema) + exact("PKTABLE_NAME", _table)
                + " ORDER BY FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, KEY_SEQ");
    }

    @Override
    public ResultSet getCrossReference(final String _parentCatalog, final String _parentSchema,
            final String _parentTable, final String _foreignCatalog, final String _foreignSchema,
            final String _foreignTable) throws SQLException {
        return query(FK_COLS + exact("PKTABLE_SCHEM", _parentSchema) + exact("PKTABLE_NAME", _parentTable)
                + exact("FKTABLE_SCHEM", _foreignSchema) + exact("FKTABLE_NAME", _foreignTable)
                + " ORDER BY FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, KEY_SEQ");
    }

    @Override
    public ResultSet getIndexInfo(final String _catalog, final String _schema, final String _table,
            final boolean _unique, final boolean _approximate) throws SQLException {
        return query("SELECT TABLE_CAT, TABLE_SCHEM, TABLE_NAME, NON_UNIQUE, INDEX_QUALIFIER, INDEX_NAME, TYPE, "
                + "ORDINAL_POSITION, COLUMN_NAME, ASC_OR_DESC, CARDINALITY, PAGES, FILTER_CONDITION "
                + "FROM SYSIBM.SQLSTATISTICS WHERE 1 = 1" + exact("TABLE_SCHEM", _schema) + exact("TABLE_NAME", _table)
                + (_unique ? " AND NON_UNIQUE = 0" : "")
                + " ORDER BY NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION");
    }

    @Override
    public ResultSet getBestRowIdentifier(final String _catalog, final String _schema, final String _table,
            final int _scope, final boolean _nullable) throws SQLException {
        return query("SELECT SCOPE, COLUMN_NAME, JDBC_DATA_TYPE AS DATA_TYPE, TYPE_NAME, COLUMN_SIZE, BUFFER_LENGTH, "
                + "DECIMAL_DIGITS, PSEUDO_COLUMN FROM SYSIBM.SQLSPECIALCOLUMNS WHERE 1 = 1"
                + exact("TABLE_SCHEM", _schema) + exact("TABLE_NAME", _table) + " AND SCOPE >= " + _scope
                + (_nullable ? "" : " AND NULLABLE = 0") + " ORDER BY SCOPE");
    }

    @Override
    public ResultSet getVersionColumns(final String _catalog, final String _schema, final String _table)
            throws SQLException {
        return empty("SCOPE", S, "COLUMN_NAME", V, "DATA_TYPE", I, "TYPE_NAME", V, "COLUMN_SIZE", I, "BUFFER_LENGTH", I,
                "DECIMAL_DIGITS", S, "PSEUDO_COLUMN", S);
    }

    @Override
    public ResultSet getProcedures(final String _catalog, final String _schemaPattern, final String _procPattern)
            throws SQLException {
        return query("SELECT PROCEDURE_CAT, PROCEDURE_SCHEM, PROCEDURE_NAME, NUM_INPUT_PARAMS, NUM_OUTPUT_PARAMS, "
                + "NUM_RESULT_SETS, REMARKS, PROCEDURE_TYPE, SPECIFIC_NAME FROM SYSIBM.SQLPROCEDURES WHERE 1 = 1"
                + pattern("PROCEDURE_SCHEM", _schemaPattern) + pattern("PROCEDURE_NAME", _procPattern)
                + " ORDER BY PROCEDURE_CAT, PROCEDURE_SCHEM, PROCEDURE_NAME, SPECIFIC_NAME");
    }

    @Override
    public ResultSet getProcedureColumns(final String _catalog, final String _schemaPattern,
            final String _procPattern, final String _columnPattern) throws SQLException {
        return query("SELECT PROCEDURE_CAT, PROCEDURE_SCHEM, PROCEDURE_NAME, COLUMN_NAME, COLUMN_TYPE, "
                + "JDBC_DATA_TYPE AS DATA_TYPE, TYPE_NAME, COLUMN_SIZE AS PRECISION, BUFFER_LENGTH AS LENGTH, "
                + "DECIMAL_DIGITS AS SCALE, NUM_PREC_RADIX AS RADIX, NULLABLE, REMARKS, COLUMN_DEF, SQL_DATA_TYPE, "
                + "SQL_DATETIME_SUB, CHAR_OCTET_LENGTH, ORDINAL_POSITION, IS_NULLABLE, SPECIFIC_NAME "
                + "FROM SYSIBM.SQLPROCEDURECOLS WHERE 1 = 1" + pattern("PROCEDURE_SCHEM", _schemaPattern)
                + pattern("PROCEDURE_NAME", _procPattern) + pattern("COLUMN_NAME", _columnPattern)
                + " ORDER BY PROCEDURE_CAT, PROCEDURE_SCHEM, PROCEDURE_NAME, SPECIFIC_NAME, ORDINAL_POSITION");
    }

    @Override
    public ResultSet getFunctions(final String _catalog, final String _schemaPattern, final String _funcPattern)
            throws SQLException {
        return query("SELECT FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME, REMARKS, FUNCTION_TYPE, SPECIFIC_NAME "
                + "FROM SYSIBM.SQLFUNCTIONS WHERE 1 = 1" + pattern("FUNCTION_SCHEM", _schemaPattern)
                + pattern("FUNCTION_NAME", _funcPattern)
                + " ORDER BY FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME, SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctionColumns(final String _catalog, final String _schemaPattern,
            final String _funcPattern, final String _columnPattern) throws SQLException {
        return query("SELECT FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME, COLUMN_NAME, COLUMN_TYPE, "
                + "JDBC_DATA_TYPE AS DATA_TYPE, TYPE_NAME, COLUMN_SIZE AS PRECISION, BUFFER_LENGTH AS LENGTH, "
                + "DECIMAL_DIGITS AS SCALE, NUM_PREC_RADIX AS RADIX, NULLABLE, REMARKS, CHAR_OCTET_LENGTH, "
                + "ORDINAL_POSITION, IS_NULLABLE, SPECIFIC_NAME FROM SYSIBM.SQLFUNCTIONCOLS WHERE 1 = 1"
                + pattern("FUNCTION_SCHEM", _schemaPattern) + pattern("FUNCTION_NAME", _funcPattern)
                + pattern("COLUMN_NAME", _columnPattern)
                + " ORDER BY FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME, SPECIFIC_NAME, ORDINAL_POSITION");
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return query("SELECT TYPE_NAME, JDBC_DATA_TYPE AS DATA_TYPE, COLUMN_SIZE AS PRECISION, LITERAL_PREFIX, "
                + "LITERAL_SUFFIX, CREATE_PARAMS, NULLABLE, CASE_SENSITIVE, SEARCHABLE, UNSIGNED_ATTRIBUTE, "
                + "FIXED_PREC_SCALE, AUTO_UNIQUE_VALUE AS AUTO_INCREMENT, LOCAL_TYPE_NAME, MINIMUM_SCALE, "
                + "MAXIMUM_SCALE, SQL_DATA_TYPE, SQL_DATETIME_SUB, NUM_PREC_RADIX FROM SYSIBM.SQLTYPEINFO "
                + "ORDER BY JDBC_DATA_TYPE, TYPE_NAME");
    }

    @Override
    public ResultSet getUDTs(final String _catalog, final String _schemaPattern, final String _typePattern,
            final int[] _types) throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT TYPE_CAT, TYPE_SCHEM, TYPE_NAME, CLASS_NAME, DATA_TYPE, "
                + "REMARKS, BASE_TYPE FROM SYSIBM.SQLUDTS WHERE 1 = 1");
        sql.append(pattern("TYPE_SCHEM", _schemaPattern)).append(pattern("TYPE_NAME", _typePattern));
        if (null != _types && _types.length > 0) {
            sql.append(" AND DATA_TYPE IN (");
            for (int i = 0; i < _types.length; i++) {
                sql.append(i > 0 ? ", " : "").append(_types[i]);
            }
            sql.append(')');
        }
        sql.append(" ORDER BY DATA_TYPE, TYPE_CAT, TYPE_SCHEM, TYPE_NAME");
        return query(sql.toString());
    }

    @Override
    public ResultSet getTablePrivileges(final String _catalog, final String _schemaPattern,
            final String _tablePattern) throws SQLException {
        return query("SELECT TABLE_CAT, TABLE_SCHEM, TABLE_NAME, GRANTOR, GRANTEE, PRIVILEGE, IS_GRANTABLE "
                + "FROM SYSIBM.SQLTABLEPRIVILEGES WHERE 1 = 1" + pattern("TABLE_SCHEM", _schemaPattern)
                + pattern("TABLE_NAME", _tablePattern) + " ORDER BY TABLE_CAT, TABLE_SCHEM, TABLE_NAME, PRIVILEGE");
    }

    @Override
    public ResultSet getColumnPrivileges(final String _catalog, final String _schema, final String _table,
            final String _columnPattern) throws SQLException {
        return query("SELECT TABLE_CAT, TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, GRANTOR, GRANTEE, PRIVILEGE, IS_GRANTABLE "
                + "FROM SYSIBM.SQLCOLPRIVILEGES WHERE 1 = 1" + exact("TABLE_SCHEM", _schema)
                + exact("TABLE_NAME", _table) + pattern("COLUMN_NAME", _columnPattern)
                + " ORDER BY COLUMN_NAME, PRIVILEGE");
    }

    @Override
    public ResultSet getSuperTypes(final String _catalog, final String _schemaPattern, final String _typePattern)
            throws SQLException {
        return empty("TYPE_CAT", V, "TYPE_SCHEM", V, "TYPE_NAME", V, "SUPERTYPE_CAT", V, "SUPERTYPE_SCHEM", V,
                "SUPERTYPE_NAME", V);
    }

    @Override
    public ResultSet getSuperTables(final String _catalog, final String _schemaPattern, final String _tablePattern)
            throws SQLException {
        return empty("TABLE_CAT", V, "TABLE_SCHEM", V, "TABLE_NAME", V, "SUPERTABLE_NAME", V);
    }

    @Override
    public ResultSet getAttributes(final String _catalog, final String _schemaPattern, final String _typePattern,
            final String _attributePattern) throws SQLException {
        return empty("TYPE_CAT", V, "TYPE_SCHEM", V, "TYPE_NAME", V, "ATTR_NAME", V, "DATA_TYPE", I, "ATTR_TYPE_NAME", V,
                "ATTR_SIZE", I, "DECIMAL_DIGITS", I, "NUM_PREC_RADIX", I, "NULLABLE", I, "REMARKS", V, "ATTR_DEF", V,
                "SQL_DATA_TYPE", I, "SQL_DATETIME_SUB", I, "CHAR_OCTET_LENGTH", I, "ORDINAL_POSITION", I,
                "IS_NULLABLE", V, "SCOPE_CATALOG", V, "SCOPE_SCHEMA", V, "SCOPE_TABLE", V, "SOURCE_DATA_TYPE", S);
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty("NAME", V, "MAX_LEN", I, "DEFAULT_VALUE", V, "DESCRIPTION", V);
    }

    @Override
    public ResultSet getPseudoColumns(final String _catalog, final String _schemaPattern, final String _tablePattern,
            final String _columnPattern) throws SQLException {
        return empty("TABLE_CAT", V, "TABLE_SCHEM", V, "TABLE_NAME", V, "COLUMN_NAME", V, "DATA_TYPE", I,
                "COLUMN_SIZE", I, "DECIMAL_DIGITS", I, "NUM_PREC_RADIX", I, "COLUMN_USAGE", V, "REMARKS", V,
                "CHAR_OCTET_LENGTH", I, "IS_NULLABLE", V);
    }

    // =================================================================================== product information

    @Override
    public String getURL() {
        return connection.config().getUrl();
    }

    @Override
    public String getUserName() throws SQLException {
        return info().user;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    /** Same product name as IBM Toolbox for Java, so tools and ORMs pick the IBM i dialect. */
    @Override
    public String getDatabaseProductName() {
        return "DB2 UDB for AS/400";
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        final Engine.ServerInfo si = info();
        return String.format("%02d.%02d.0000 V%dR%dm0", si.version, si.release, si.version, si.release);
    }

    @Override
    public String getDriverName() {
        return Db2ShellDriver.NAME;
    }

    @Override
    public String getDriverVersion() {
        return Db2ShellDriver.version();
    }

    @Override
    public int getDriverMajorVersion() {
        return Db2ShellDriver.MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return Db2ShellDriver.MINOR_VERSION;
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return info().version;
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return info().release;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    // =================================================================================== capabilities

    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean allTablesAreSelectable() { return false; }
    @Override public boolean nullsAreSortedHigh() { return true; }
    @Override public boolean nullsAreSortedLow() { return false; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }
    @Override public boolean usesLocalFiles() { return false; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return false; }
    @Override public boolean storesUpperCaseIdentifiers() { return true; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean storesMixedCaseIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public String getIdentifierQuoteString() { return "\""; }

    @Override
    public String getSQLKeywords() {
        return "AFTER,ALIAS,ALLOW,APPLICATION,ASSOCIATE,ASUTIME,AUDIT,AUX,AUXILIARY,BEFORE,BINARY,BUFFERPOOL,CACHE,"
                + "CALL,CALLED,CAPTURE,CARDINALITY,CCSID,CLUSTER,COLLECTION,COLLID,COMMENT,CONCAT,CONDITION,CONTAINS,"
                + "COUNT_BIG,CURRENT_LC_CTYPE,CURRENT_PATH,CURRENT_SERVER,CURRENT_TIMEZONE,CYCLE,DATA,DATABASE,DAYS,"
                + "DB2GENERAL,DB2GENRL,DB2SQL,DBINFO,DEFAULTS,DEFINITION,DETERMINISTIC,DISALLOW,DO,DSNHATTR,DSSIZE,"
                + "DYNAMIC,EACH,EDITPROC,ELSEIF,ENCODING,END-EXEC1,ERASE,EXCLUDING,EXIT,FENCED,FIELDPROC,FILE,FINAL,"
                + "FREE,FUNCTION,GENERAL,GENERATED,GRAPHIC,HANDLER,HOLD,HOURS,IF,INCLUDING,INCREMENT,INDEX,INHERIT,"
                + "INOUT,INTEGRITY,ISOBID,ITERATE,JAR,JAVA,LABEL,LC_CTYPE,LEAVE,LINKTYPE,LOCALE,LOCATOR,LOCATORS,LOCK,"
                + "LOCKMAX,LOCKSIZE,LONG,LOOP,MAXVALUE,MICROSECOND,MICROSECONDS,MINUTES,MINVALUE,MODE,MODIFIES,MONTHS,"
                + "NEW,NEW_TABLE,NOCACHE,NOCYCLE,NODENAME,NODENUMBER,NOMAXVALUE,NOMINVALUE,NOORDER,NULLS,NUMPARTS,"
                + "OBID,OLD,OLD_TABLE,OPTIMIZATION,OPTIMIZE,OUT,OVERRIDING,PACKAGE,PARAMETER,PART,PARTITION,PATH,PIECESIZE,"
                + "PLAN,PRIQTY,PROGRAM,PSID,QUERYNO,READS,RECOVERY,REFERENCING,RELEASE,RENAME,REPEAT,RESET,RESIGNAL,"
                + "RESTART,RESULT,RESULT_SET_LOCATOR,RETURN,RETURNS,ROUTINE,ROW,RRN,RUN,SAVEPOINT,SCRATCHPAD,SECONDS,"
                + "SECQTY,SECURITY,SENSITIVE,SIGNAL,SIMPLE,SOURCE,SPECIFIC,SQLID,STANDARD,START,STATIC,STAY,STOGROUP,"
                + "STORES,STYLE,SUBPAGES,SYNONYM,SYSFUN,SYSIBM,SYSPROC,SYSTEM,TABLESPACE,TRIGGER,TYPE,UNDO,UNTIL,"
                + "VALIDPROC,VARIABLE,VARIANT,VCAT,VOLUMES,WHILE,WLM,YEARS";
    }

    @Override
    public String getNumericFunctions() {
        return "ABS,ACOS,ASIN,ATAN,ATAN2,CEILING,COS,COT,DEGREES,EXP,FLOOR,LOG,LOG10,MOD,PI,POWER,RADIANS,RAND,ROUND,"
                + "SIGN,SIN,SQRT,TAN,TRUNCATE";
    }

    @Override
    public String getStringFunctions() {
        return "ASCII,CHAR,CONCAT,DIFFERENCE,INSERT,LCASE,LEFT,LENGTH,LOCATE,LTRIM,REPEAT,REPLACE,RIGHT,RTRIM,SOUNDEX,"
                + "SPACE,SUBSTRING,UCASE";
    }

    @Override
    public String getSystemFunctions() {
        return "DATABASE,IFNULL,USER";
    }

    @Override
    public String getTimeDateFunctions() {
        return "CURDATE,CURTIME,DAYNAME,DAYOFMONTH,DAYOFWEEK,DAYOFYEAR,HOUR,MINUTE,MONTH,MONTHNAME,NOW,QUARTER,SECOND,"
                + "TIMESTAMPADD,TIMESTAMPDIFF,WEEK,YEAR";
    }

    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return "@#$"; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return true; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(final int _from, final int _to) { return false; }
    @Override public boolean supportsTableCorrelationNames() { return true; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsGroupByUnrelated() { return true; }
    @Override public boolean supportsGroupByBeyondSelect() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return true; }
    @Override public boolean supportsMultipleResultSets() { return false; }
    @Override public boolean supportsMultipleTransactions() { return true; }
    @Override public boolean supportsNonNullableColumns() { return true; }
    @Override public boolean supportsMinimumSQLGrammar() { return true; }
    @Override public boolean supportsCoreSQLGrammar() { return true; }
    @Override public boolean supportsExtendedSQLGrammar() { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return true; }
    @Override public boolean supportsANSI92IntermediateSQL() { return false; }
    @Override public boolean supportsANSI92FullSQL() { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return true; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return true; }
    @Override public boolean supportsLimitedOuterJoins() { return true; }
    @Override public String getSchemaTerm() { return "schema"; }
    @Override public String getProcedureTerm() { return "procedure"; }
    @Override public String getCatalogTerm() { return "database"; }
    @Override public boolean isCatalogAtStart() { return true; }
    @Override public String getCatalogSeparator() { return "."; }
    @Override public boolean supportsSchemasInDataManipulation() { return true; }
    @Override public boolean supportsSchemasInProcedureCalls() { return true; }
    @Override public boolean supportsSchemasInTableDefinitions() { return true; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return true; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return true; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return true; }
    @Override public boolean supportsSubqueriesInComparisons() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return true; }
    @Override public boolean supportsCorrelatedSubqueries() { return true; }
    @Override public boolean supportsUnion() { return true; }
    @Override public boolean supportsUnionAll() { return true; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return true; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return true; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return true; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return true; }
    @Override public int getMaxBinaryLiteralLength() { return 32739; }
    @Override public int getMaxCharLiteralLength() { return 32739; }
    @Override public int getMaxColumnNameLength() { return 128; }
    @Override public int getMaxColumnsInGroupBy() { return 8000; }
    @Override public int getMaxColumnsInIndex() { return 120; }
    @Override public int getMaxColumnsInOrderBy() { return 10000; }
    @Override public int getMaxColumnsInSelect() { return 8000; }
    @Override public int getMaxColumnsInTable() { return 8000; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 128; }
    @Override public int getMaxIndexLength() { return 32767; }
    @Override public int getMaxSchemaNameLength() { return 128; }
    @Override public int getMaxProcedureNameLength() { return 128; }
    @Override public int getMaxCatalogNameLength() { return 18; }
    @Override public int getMaxRowSize() { return 32766; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 2097152; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 128; }
    @Override public int getMaxTablesInSelect() { return 1000; }
    @Override public int getMaxUserNameLength() { return 10; }

    @Override
    public int getDefaultTransactionIsolation() {
        return connection.config().getIsolation();
    }

    @Override public boolean supportsTransactions() { return true; }
    @Override public boolean supportsTransactionIsolationLevel(final int _level) { return true; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }

    @Override
    public boolean supportsResultSetType(final int _type) {
        return _type == ResultSet.TYPE_FORWARD_ONLY || _type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public boolean supportsResultSetConcurrency(final int _type, final int _concurrency) {
        return supportsResultSetType(_type) && _concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override public boolean ownUpdatesAreVisible(final int _type) { return false; }
    @Override public boolean ownDeletesAreVisible(final int _type) { return false; }
    @Override public boolean ownInsertsAreVisible(final int _type) { return false; }
    @Override public boolean othersUpdatesAreVisible(final int _type) { return false; }
    @Override public boolean othersDeletesAreVisible(final int _type) { return false; }
    @Override public boolean othersInsertsAreVisible(final int _type) { return false; }
    @Override public boolean updatesAreDetected(final int _type) { return false; }
    @Override public boolean deletesAreDetected(final int _type) { return false; }
    @Override public boolean insertsAreDetected(final int _type) { return false; }
    @Override public boolean supportsBatchUpdates() { return true; }
    @Override public boolean supportsSavepoints() { return true; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean supportsGetGeneratedKeys() { return true; }

    @Override
    public boolean supportsResultSetHoldability(final int _holdability) {
        return _holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override public int getResultSetHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getSQLStateType() { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() { return true; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return true; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }

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
