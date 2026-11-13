package io.github.db2shell.jdbc.internal;

import java.sql.ResultSetMetaData;
import java.sql.Types;

/**
 * Describes one result column. Built either from an SQL descriptor (typed mode) or from the utility's display
 * header (raw mode, where everything is VARCHAR).
 */
public final class ColumnInfo {

    /** How values of a column are transported and decoded. */
    public enum Kind {
        STRING, CLOB, BINARY, BLOB, XML, SMALLINT, INTEGER, BIGINT, DECIMAL, REAL, DOUBLE, DECFLOAT, DATE, TIME,
        TIMESTAMP, BOOLEAN
    }

    // SQL descriptor TYPE codes as reported by GET DESCRIPTOR on Db2 for i
    public static final int D_CHAR = 1;
    public static final int D_NUMERIC = 2;
    public static final int D_DECIMAL = 3;
    public static final int D_INTEGER = 4;
    public static final int D_SMALLINT = 5;
    public static final int D_FLOAT = 6;
    public static final int D_REAL = 7;
    public static final int D_DOUBLE = 8;
    public static final int D_DATETIME = 9;
    public static final int D_VARCHAR = 12;
    public static final int D_BOOLEAN = 16;
    public static final int D_BIGINT = 25;
    public static final int D_BLOB = 30;
    public static final int D_CLOB = 40;
    public static final int D_XML = 137;
    public static final int D_BINARY = -2;
    public static final int D_VARBINARY = -3;
    public static final int D_GRAPHIC = -95;
    public static final int D_VARGRAPHIC = -96;
    public static final int D_DBCLOB = -350;
    public static final int D_DECFLOAT = -360;

    private final String m_name;
    private final int m_jdbcType;
    private final String m_typeName;
    private final Kind m_kind;
    private final int m_precision;
    private final int m_scale;
    private final int m_displaySize;
    private final int m_nullable;
    private final int m_ccsid;
    private final int m_length;
    private final String m_baseSchema;
    private final String m_baseTable;
    private final String m_baseColumn;
    private final boolean m_signed;
    private final boolean m_caseSensitive;

    /**
     * Creates a column description. Signedness and case sensitivity are derived from the kind.
     *
     * @param _name
     *            the column name
     * @param _jdbcType
     *            the {@link Types} constant reported to JDBC callers
     * @param _typeName
     *            the SQL type name
     * @param _kind
     *            how values are transported and decoded
     * @param _precision
     *            the precision (JDBC semantics)
     * @param _scale
     *            the scale
     * @param _displaySize
     *            the display size in characters
     * @param _nullable
     *            a {@link ResultSetMetaData} nullability constant
     * @param _ccsid
     *            the CCSID (0 if not applicable)
     * @param _length
     *            the declared length
     * @param _baseSchema
     *            the base schema name (null is treated as empty)
     * @param _baseTable
     *            the base table name (null is treated as empty)
     * @param _baseColumn
     *            the base column name (null is treated as empty)
     */
    private ColumnInfo(final String _name, final int _jdbcType, final String _typeName, final Kind _kind,
            final int _precision, final int _scale, final int _displaySize, final int _nullable, final int _ccsid,
            final int _length, final String _baseSchema, final String _baseTable, final String _baseColumn) {
        m_name = _name;
        m_jdbcType = _jdbcType;
        m_typeName = _typeName;
        m_kind = _kind;
        m_precision = _precision;
        m_scale = _scale;
        m_displaySize = _displaySize;
        m_nullable = _nullable;
        m_ccsid = _ccsid;
        m_length = _length;
        m_baseSchema = null == _baseSchema ? "" : _baseSchema;
        m_baseTable = null == _baseTable ? "" : _baseTable;
        m_baseColumn = null == _baseColumn ? "" : _baseColumn;
        m_signed = Kind.SMALLINT == _kind || Kind.INTEGER == _kind || Kind.BIGINT == _kind || Kind.DECIMAL == _kind
                || Kind.REAL == _kind || Kind.DOUBLE == _kind || Kind.DECFLOAT == _kind;
        m_caseSensitive = Kind.STRING == _kind || Kind.CLOB == _kind || Kind.XML == _kind;
    }

    /** A raw-mode column: always VARCHAR. */
    public static ColumnInfo raw(final String _name, final int _width) {
        return new ColumnInfo(_name, Types.VARCHAR, "VARCHAR", Kind.STRING, _width, 0, _width,
                ResultSetMetaData.columnNullableUnknown, 0, _width, "", "", "");
    }

    /**
     * A column of a driver-generated result (e.g. DatabaseMetaData) with an explicit JDBC type.
     *
     * @param _name
     *            the column name
     * @param _jdbcType
     *            SMALLINT, INTEGER, BIGINT or BOOLEAN; any other type yields a VARCHAR(128) column
     * @return the column description
     */
    public static ColumnInfo synthetic(final String _name, final int _jdbcType) {
        switch (_jdbcType) {
        case Types.SMALLINT:
            return new ColumnInfo(_name, Types.SMALLINT, "SMALLINT", Kind.SMALLINT, 5, 0, 6,
                    ResultSetMetaData.columnNullable, 0, 2, "", "", "");
        case Types.INTEGER:
            return new ColumnInfo(_name, Types.INTEGER, "INTEGER", Kind.INTEGER, 10, 0, 11,
                    ResultSetMetaData.columnNullable, 0, 4, "", "", "");
        case Types.BIGINT:
            return new ColumnInfo(_name, Types.BIGINT, "BIGINT", Kind.BIGINT, 19, 0, 20,
                    ResultSetMetaData.columnNullable, 0, 8, "", "", "");
        case Types.BOOLEAN:
            return new ColumnInfo(_name, Types.BOOLEAN, "BOOLEAN", Kind.BOOLEAN, 1, 0, 5,
                    ResultSetMetaData.columnNullable, 0, 1, "", "", "");
        default:
            return new ColumnInfo(_name, Types.VARCHAR, "VARCHAR", Kind.STRING, 128, 0, 128,
                    ResultSetMetaData.columnNullable, 0, 128, "", "", "");
        }
    }

    /**
     * Builds a column from SQL descriptor values.
     *
     * @param _name descriptor NAME
     * @param _type descriptor TYPE
     * @param _length descriptor LENGTH (characters for strings, bytes for numerics)
     * @param _precision descriptor PRECISION
     * @param _scale descriptor SCALE
     * @param _nullable descriptor NULLABLE
     * @param _ccsid descriptor DB2_CCSID (65535 for bit data)
     * @param _dtCode DATETIME_INTERVAL_CODE (1 date, 2 time, 3 timestamp)
     * @param _baseSchema descriptor DB2_BASE_SCHEMA_NAME (may be null)
     * @param _baseTable descriptor DB2_BASE_TABLE_NAME (may be null)
     * @param _baseColumn descriptor DB2_BASE_COLUMN_NAME (may be null)
     * @return the column description
     */
    public static ColumnInfo fromDescriptor(final String _name, final int _type, final int _length, final int _precision,
            final int _scale, final boolean _nullable, final int _ccsid, final int _dtCode, final String _baseSchema,
            final String _baseTable, final String _baseColumn) {
        final int nul = _nullable ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
        final boolean bit = 65535 == _ccsid;
        final boolean unicode = 1200 == _ccsid || 13488 == _ccsid;
        final int jt;
        final String tn;
        final Kind k;
        int prec = _length;
        int scale = 0;
        int disp = _length;
        switch (_type) {
        case D_CHAR:
            if (bit) {
                jt = Types.BINARY; tn = "CHAR () FOR BIT DATA"; k = Kind.BINARY; disp = _length * 2;
            } else {
                jt = Types.CHAR; tn = "CHAR"; k = Kind.STRING;
            }
            break;
        case D_VARCHAR:
            if (bit) {
                jt = Types.VARBINARY; tn = "VARCHAR () FOR BIT DATA"; k = Kind.BINARY; disp = _length * 2;
            } else {
                jt = Types.VARCHAR; tn = "VARCHAR"; k = Kind.STRING;
            }
            break;
        case D_GRAPHIC:
            jt = unicode ? Types.NCHAR : Types.CHAR; tn = unicode ? "NCHAR" : "GRAPHIC"; k = Kind.STRING;
            break;
        case D_VARGRAPHIC:
            jt = unicode ? Types.NVARCHAR : Types.VARCHAR; tn = unicode ? "NVARCHAR" : "VARGRAPHIC"; k = Kind.STRING;
            break;
        case D_CLOB:
            jt = Types.CLOB; tn = "CLOB"; k = Kind.CLOB;
            break;
        case D_DBCLOB:
            jt = unicode ? Types.NCLOB : Types.CLOB; tn = unicode ? "NCLOB" : "DBCLOB"; k = Kind.CLOB;
            break;
        case D_BLOB:
            jt = Types.BLOB; tn = "BLOB"; k = Kind.BLOB; disp = _length * 2;
            break;
        case D_BINARY:
            jt = Types.BINARY; tn = "BINARY"; k = Kind.BINARY; disp = _length * 2;
            break;
        case D_VARBINARY:
            jt = Types.VARBINARY; tn = "VARBINARY"; k = Kind.BINARY; disp = _length * 2;
            break;
        case D_XML:
            jt = Types.SQLXML; tn = "XML"; k = Kind.XML; prec = 2147483647; disp = prec;
            break;
        case D_SMALLINT:
            jt = Types.SMALLINT; tn = "SMALLINT"; k = Kind.SMALLINT; prec = 5; disp = 6;
            break;
        case D_INTEGER:
            jt = Types.INTEGER; tn = "INTEGER"; k = Kind.INTEGER; prec = 10; disp = 11;
            break;
        case D_BIGINT:
            jt = Types.BIGINT; tn = "BIGINT"; k = Kind.BIGINT; prec = 19; disp = 20;
            break;
        case D_DECIMAL:
            jt = Types.DECIMAL; tn = "DECIMAL"; k = Kind.DECIMAL; prec = _precision; scale = _scale; disp = _precision + 2;
            break;
        case D_NUMERIC:
            jt = Types.NUMERIC; tn = "NUMERIC"; k = Kind.DECIMAL; prec = _precision; scale = _scale; disp = _precision + 2;
            break;
        case D_REAL:
            jt = Types.REAL; tn = "REAL"; k = Kind.REAL; prec = 24; disp = 13;
            break;
        case D_FLOAT:
        case D_DOUBLE:
            if (4 == _length) {
                jt = Types.REAL; tn = "REAL"; k = Kind.REAL; prec = 24; disp = 13;
            } else {
                jt = Types.DOUBLE; tn = "DOUBLE"; k = Kind.DOUBLE; prec = 53; disp = 22;
            }
            break;
        case D_DECFLOAT:
            jt = Types.DECIMAL; tn = "DECFLOAT"; k = Kind.DECFLOAT; prec = _precision > 0 ? _precision : 34;
            disp = 16 == prec ? 23 : 42;
            break;
        case D_BOOLEAN:
            jt = Types.BOOLEAN; tn = "BOOLEAN"; k = Kind.BOOLEAN; prec = 1; disp = 5;
            break;
        case D_DATETIME:
            if (1 == _dtCode) {
                jt = Types.DATE; tn = "DATE"; k = Kind.DATE; prec = 10; disp = 10;
            } else if (2 == _dtCode) {
                jt = Types.TIME; tn = "TIME"; k = Kind.TIME; prec = 8; disp = 8;
            } else {
                jt = Types.TIMESTAMP; tn = "TIMESTAMP"; k = Kind.TIMESTAMP;
                scale = _precision;
                prec = 19 + (_precision > 0 ? _precision + 1 : 0);
                disp = prec;
            }
            break;
        default:
            if (bit) {
                jt = Types.VARBINARY; tn = "VARBINARY"; k = Kind.BINARY; disp = _length * 2;
            } else {
                jt = Types.VARCHAR; tn = "VARCHAR"; k = Kind.STRING;
            }
            break;
        }
        return new ColumnInfo(_name, jt, tn, k, prec, scale, disp, nul, _ccsid, _length, _baseSchema, _baseTable,
                _baseColumn);
    }

    public String getName() {
        return m_name;
    }

    public int getJdbcType() {
        return m_jdbcType;
    }

    public String getTypeName() {
        return m_typeName;
    }

    public Kind getKind() {
        return m_kind;
    }

    public int getPrecision() {
        return m_precision;
    }

    public int getScale() {
        return m_scale;
    }

    public int getDisplaySize() {
        return m_displaySize;
    }

    public int getNullable() {
        return m_nullable;
    }

    public int getCcsid() {
        return m_ccsid;
    }

    /** Declared length: characters for character types, bytes for binary types. */
    public int getLength() {
        return m_length;
    }

    public String getBaseSchema() {
        return m_baseSchema;
    }

    public String getBaseTable() {
        return m_baseTable;
    }

    public String getBaseColumn() {
        return m_baseColumn;
    }

    public boolean isSigned() {
        return m_signed;
    }

    public boolean isCaseSensitive() {
        return m_caseSensitive;
    }

    /**
     * The name of the Java class {@code getObject} returns for this column, as reported by
     * {@link ResultSetMetaData#getColumnClassName(int)}.
     *
     * @return the fully qualified class name
     */
    public String getClassName() {
        switch (m_kind) {
        case STRING: return String.class.getName();
        case CLOB: return Types.NCLOB == m_jdbcType ?java.sql.NClob.class.getName() : java.sql.Clob.class.getName();
        case BINARY: return byte[].class.getName();
        case BLOB: return java.sql.Blob.class.getName();
        case XML: return java.sql.SQLXML.class.getName();
        case SMALLINT:
        case INTEGER: return Integer.class.getName();
        case BIGINT: return Long.class.getName();
        case DECIMAL:
        case DECFLOAT: return java.math.BigDecimal.class.getName();
        case REAL: return Float.class.getName();
        case DOUBLE: return Double.class.getName();
        case DATE: return java.sql.Date.class.getName();
        case TIME: return java.sql.Time.class.getName();
        case TIMESTAMP: return java.sql.Timestamp.class.getName();
        case BOOLEAN: return Boolean.class.getName();
        default: return Object.class.getName();
        }
    }

    @Override
    public String toString() {
        return m_name + " " + m_typeName + "(" + m_precision + "," + m_scale + ")" + (m_ccsid > 0 ? " CCSID " + m_ccsid : "");
    }
}
