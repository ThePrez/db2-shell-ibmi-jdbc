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

    private final String name;
    private final int jdbcType;
    private final String typeName;
    private final Kind kind;
    private final int precision;
    private final int scale;
    private final int displaySize;
    private final int nullable;
    private final int ccsid;
    private final int length;
    private final String baseSchema;
    private final String baseTable;
    private final String baseColumn;
    private final boolean signed;
    private final boolean caseSensitive;

    private ColumnInfo(final String _name, final int _jdbcType, final String _typeName, final Kind _kind,
            final int _precision, final int _scale, final int _displaySize, final int _nullable, final int _ccsid,
            final int _length, final String _baseSchema, final String _baseTable, final String _baseColumn) {
        name = _name;
        jdbcType = _jdbcType;
        typeName = _typeName;
        kind = _kind;
        precision = _precision;
        scale = _scale;
        displaySize = _displaySize;
        nullable = _nullable;
        ccsid = _ccsid;
        length = _length;
        baseSchema = null == _baseSchema ? "" : _baseSchema;
        baseTable = null == _baseTable ? "" : _baseTable;
        baseColumn = null == _baseColumn ? "" : _baseColumn;
        signed = _kind == Kind.SMALLINT || _kind == Kind.INTEGER || _kind == Kind.BIGINT || _kind == Kind.DECIMAL
                || _kind == Kind.REAL || _kind == Kind.DOUBLE || _kind == Kind.DECFLOAT;
        caseSensitive = _kind == Kind.STRING || _kind == Kind.CLOB || _kind == Kind.XML;
    }

    /** A raw-mode column: always VARCHAR. */
    public static ColumnInfo raw(final String _name, final int _width) {
        return new ColumnInfo(_name, Types.VARCHAR, "VARCHAR", Kind.STRING, _width, 0, _width,
                ResultSetMetaData.columnNullableUnknown, 0, _width, "", "", "");
    }

    /** A column of a driver-generated result (e.g. DatabaseMetaData) with an explicit JDBC type. */
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
     * @param _type descriptor TYPE
     * @param _length descriptor LENGTH (characters for strings, bytes for numerics)
     * @param _dtCode DATETIME_INTERVAL_CODE (1 date, 2 time, 3 timestamp)
     */
    public static ColumnInfo fromDescriptor(final String _name, final int _type, final int _length, final int _precision,
            final int _scale, final boolean _nullable, final int _ccsid, final int _dtCode, final String _baseSchema,
            final String _baseTable, final String _baseColumn) {
        final int nul = _nullable ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
        final boolean bit = _ccsid == 65535;
        final boolean unicode = _ccsid == 1200 || _ccsid == 13488;
        int jt;
        String tn;
        Kind k;
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
            if (_length == 4) {
                jt = Types.REAL; tn = "REAL"; k = Kind.REAL; prec = 24; disp = 13;
            } else {
                jt = Types.DOUBLE; tn = "DOUBLE"; k = Kind.DOUBLE; prec = 53; disp = 22;
            }
            break;
        case D_DECFLOAT:
            jt = Types.DECIMAL; tn = "DECFLOAT"; k = Kind.DECFLOAT; prec = _precision > 0 ? _precision : 34;
            disp = prec == 16 ? 23 : 42;
            break;
        case D_BOOLEAN:
            jt = Types.BOOLEAN; tn = "BOOLEAN"; k = Kind.BOOLEAN; prec = 1; disp = 5;
            break;
        case D_DATETIME:
            if (_dtCode == 1) {
                jt = Types.DATE; tn = "DATE"; k = Kind.DATE; prec = 10; disp = 10;
            } else if (_dtCode == 2) {
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

    public String getName() { return name; }
    public int getJdbcType() { return jdbcType; }
    public String getTypeName() { return typeName; }
    public Kind getKind() { return kind; }
    public int getPrecision() { return precision; }
    public int getScale() { return scale; }
    public int getDisplaySize() { return displaySize; }
    public int getNullable() { return nullable; }
    public int getCcsid() { return ccsid; }
    /** Declared length: characters for character types, bytes for binary types. */
    public int getLength() { return length; }
    public String getBaseSchema() { return baseSchema; }
    public String getBaseTable() { return baseTable; }
    public String getBaseColumn() { return baseColumn; }
    public boolean isSigned() { return signed; }
    public boolean isCaseSensitive() { return caseSensitive; }

    public String getClassName() {
        switch (kind) {
        case STRING: return String.class.getName();
        case CLOB: return jdbcType == Types.NCLOB ? java.sql.NClob.class.getName() : java.sql.Clob.class.getName();
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
        return name + " " + typeName + "(" + precision + "," + scale + ")" + (ccsid > 0 ? " CCSID " + ccsid : "");
    }
}
