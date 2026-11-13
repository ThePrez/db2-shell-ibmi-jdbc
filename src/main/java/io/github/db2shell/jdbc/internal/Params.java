package io.github.db2shell.jdbc.internal;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Locale;

/**
 * Turns Java parameter values into SQL literals. The db2 utility cannot bind parameters, so prepared statements
 * substitute literals for their parameter markers.
 */
public final class Params {

    /** No target SQL type given. */
    public static final int UNSPECIFIED = Integer.MIN_VALUE;

    private Params() {
    }

    /** The literal for SQL NULL of the given JDBC type. */
    public static String nullLiteral(final int _sqlType, final boolean _booleanSupported) {
        final String t = castType(_sqlType, _booleanSupported);
        return null == t ? "NULL" : "CAST(NULL AS " + t + ")";
    }

    /**
     * The SQL type to cast an untyped NULL to for the given JDBC type.
     *
     * @param _sqlType
     *            a {@link Types} constant
     * @param _booleanSupported
     *            whether the server has a BOOLEAN type (otherwise SMALLINT is used)
     * @return the SQL type, or null if a plain NULL should be used
     */
    static String castType(final int _sqlType, final boolean _booleanSupported) {
        switch (_sqlType) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
            return "VARCHAR(1)";
        case Types.NCHAR:
        case Types.NVARCHAR:
        case Types.LONGNVARCHAR:
            return "VARGRAPHIC(1) CCSID 1200";
        case Types.CLOB:
            return "CLOB(1)";
        case Types.NCLOB:
            return "DBCLOB(1) CCSID 1200";
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
            return "VARBINARY(1)";
        case Types.BLOB:
            return "BLOB(1)";
        case Types.TINYINT:
        case Types.SMALLINT:
            return "SMALLINT";
        case Types.INTEGER:
            return "INTEGER";
        case Types.BIGINT:
            return "BIGINT";
        case Types.DECIMAL:
        case Types.NUMERIC:
            return "DECIMAL(31, 0)";
        case Types.REAL:
            return "REAL";
        case Types.FLOAT:
        case Types.DOUBLE:
            return "DOUBLE";
        case Types.DATE:
            return "DATE";
        case Types.TIME:
            return "TIME";
        case Types.TIMESTAMP:
            return "TIMESTAMP";
        case Types.BIT:
        case Types.BOOLEAN:
            return _booleanSupported ? "BOOLEAN" : "SMALLINT";
        case Types.SQLXML:
            return "XML";
        default:
            return null;
        }
    }

    /**
     * Builds the literal for a value.
     *
     * @param _v the value (may be null)
     * @param _targetType a {@link Types} constant or {@link #UNSPECIFIED}
     * @param _scale scale for DECIMAL/NUMERIC targets, or -1
     * @param _cs the job charset
     * @param _booleanSupported whether the server has a BOOLEAN type (IBM i 7.5+)
     * @return the SQL literal (or expression) to substitute for the parameter marker
     * @throws SQLException if the value cannot be converted to the target type or its type is not supported
     */
    public static String literal(final Object _v, final int _targetType, final int _scale, final Charset _cs,
            final boolean _booleanSupported) throws SQLException {
        if (null == _v) {
            return UNSPECIFIED == _targetType ? "NULL" : nullLiteral(_targetType, _booleanSupported);
        }
        if (UNSPECIFIED != _targetType) {
            return literalForTarget(_v, _targetType, _scale, _cs, _booleanSupported);
        }
        return literalForValue(_v, _cs, _booleanSupported);
    }

    /**
     * Builds the literal for a value with no target type, choosing the SQL type from the Java type. LOBs and
     * streams are read fully; types without a dedicated mapping are sent as their string form.
     *
     * @param _v
     *            the value (not null)
     * @param _cs
     *            the job charset
     * @param _bool
     *            whether the server has a BOOLEAN type
     * @return the SQL literal
     * @throws SQLException
     *             if a LOB or stream cannot be read, or the value is an unsupported ARRAY, STRUCT or REF
     */
    private static String literalForValue(final Object _v, final Charset _cs, final boolean _bool) throws SQLException {
        if (_v instanceof String) {
            return SqlLiterals.string((String) _v, _cs);
        }
        if (_v instanceof Character) {
            return SqlLiterals.string(_v.toString(), _cs);
        }
        if (_v instanceof Integer || _v instanceof Long || _v instanceof Short || _v instanceof Byte
                || _v instanceof BigInteger) {
            return _v.toString();
        }
        if (_v instanceof BigDecimal) {
            return decimal((BigDecimal) _v);
        }
        if (_v instanceof Double) {
            return doubleLiteral((Double) _v);
        }
        if (_v instanceof Float) {
            return "CAST(" + doubleLiteral(((Float) _v).doubleValue()) + " AS REAL)";
        }
        if (_v instanceof Number) {
            return decimal(new BigDecimal(_v.toString()));
        }
        if (_v instanceof Boolean) {
            return booleanLiteral((Boolean) _v, _bool);
        }
        if (_v instanceof byte[]) {
            return SqlLiterals.binary((byte[]) _v);
        }
        if (_v instanceof Date) {
            return dateLiteral(((Date) _v).toLocalDate());
        }
        if (_v instanceof Time) {
            return timeLiteral(((Time) _v).toLocalTime());
        }
        if (_v instanceof Timestamp) {
            return timestampLiteral(((Timestamp) _v).toLocalDateTime());
        }
        if (_v instanceof java.util.Date) {
            return timestampLiteral(new Timestamp(((java.util.Date) _v).getTime()).toLocalDateTime());
        }
        if (_v instanceof Calendar) {
            return timestampLiteral(new Timestamp(((Calendar) _v).getTimeInMillis()).toLocalDateTime());
        }
        if (_v instanceof LocalDate) {
            return dateLiteral((LocalDate) _v);
        }
        if (_v instanceof LocalTime) {
            return timeLiteral((LocalTime) _v);
        }
        if (_v instanceof LocalDateTime) {
            return timestampLiteral((LocalDateTime) _v);
        }
        if (_v instanceof OffsetDateTime) {
            return timestampLiteral(((OffsetDateTime) _v).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (_v instanceof ZonedDateTime) {
            return timestampLiteral(((ZonedDateTime) _v).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (_v instanceof OffsetTime) {
            return timeLiteral(((OffsetTime) _v).toLocalTime());
        }
        if (_v instanceof Instant) {
            return timestampLiteral(LocalDateTime.ofInstant((Instant) _v, ZoneId.systemDefault()));
        }
        if (_v instanceof Clob) {
            final Clob c = (Clob) _v;
            return SqlLiterals.string(c.getSubString(1, (int) c.length()), _cs);
        }
        if (_v instanceof Blob) {
            final Blob b = (Blob) _v;
            return SqlLiterals.binary(b.getBytes(1, (int) b.length()));
        }
        if (_v instanceof SQLXML) {
            return SqlLiterals.string(((SQLXML) _v).getString(), _cs);
        }
        if (_v instanceof Reader) {
            return SqlLiterals.string(Lobs.readAll((Reader) _v, -1), _cs);
        }
        if (_v instanceof InputStream) {
            return SqlLiterals.binary(Lobs.readAll((InputStream) _v, -1));
        }
        if (_v instanceof RowId) {
            return SqlLiterals.binary(((RowId) _v).getBytes());
        }
        if (_v instanceof Array || _v instanceof Struct || _v instanceof Ref) {
            throw SqlErrors.unsupported("Parameters of type " + _v.getClass().getSimpleName());
        }
        // UUID, enums, anything else: its string form
        return SqlLiterals.string(_v.toString(), _cs);
    }

    /**
     * Builds the literal for a value converted to the given target type.
     *
     * @param _v
     *            the value (not null)
     * @param _t
     *            the target {@link Types} constant
     * @param _scale
     *            scale for DECIMAL/NUMERIC targets, or -1 to keep the value's scale
     * @param _cs
     *            the job charset
     * @param _bool
     *            whether the server has a BOOLEAN type
     * @return the SQL literal
     * @throws SQLException
     *             if the value cannot be converted to the target type
     */
    private static String literalForTarget(final Object _v, final int _t, final int _scale, final Charset _cs,
            final boolean _bool) throws SQLException {
        switch (_t) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.NCHAR:
        case Types.NVARCHAR:
        case Types.LONGNVARCHAR:
        case Types.CLOB:
        case Types.NCLOB:
        case Types.SQLXML:
            if (_v instanceof String || _v instanceof Reader || _v instanceof Clob || _v instanceof SQLXML) {
                return literalForValue(_v, _cs, _bool);
            }
            if (_v instanceof byte[]) {
                return literalForValue(_v, _cs, _bool);
            }
            if (_v instanceof Boolean) {
                return SqlLiterals.quote(((Boolean) _v) ? "1" : "0");
            }
            return SqlLiterals.string(Values.toString(_v instanceof java.util.Date || _v instanceof java.time.temporal.Temporal
                    ? stripCast(literalForValue(_v, _cs, _bool)) : _v), _cs);
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
            if (_v instanceof Boolean) {
                return ((Boolean) _v) ? "1" : "0";
            }
            return Long.toString(Values.toLong(_v));
        case Types.DECIMAL:
        case Types.NUMERIC: {
            BigDecimal bd = Values.toBigDecimal(_v);
            if (_scale >= 0) {
                bd = bd.setScale(_scale, java.math.RoundingMode.HALF_UP);
            }
            return decimal(bd);
        }
        case Types.REAL:
            return "CAST(" + doubleLiteral(Values.toDouble(_v)) + " AS REAL)";
        case Types.FLOAT:
        case Types.DOUBLE:
            return doubleLiteral(Values.toDouble(_v));
        case Types.BIT:
        case Types.BOOLEAN:
            return booleanLiteral(Values.toBoolean(_v), _bool);
        case Types.DATE:
            return dateLiteral(Values.toDate(asTemporalValue(_v), null).toLocalDate());
        case Types.TIME:
            return timeLiteral(Values.toTime(asTemporalValue(_v), null).toLocalTime());
        case Types.TIMESTAMP:
        case Types.TIMESTAMP_WITH_TIMEZONE:
            return timestampLiteral(Values.toTimestamp(asTemporalValue(_v), null).toLocalDateTime());
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
            if (_v instanceof String) {
                return SqlLiterals.binary(((String) _v).getBytes(_cs));
            }
            return literalForValue(_v, _cs, _bool);
        case Types.NULL:
            return "NULL";
        default:
            return literalForValue(_v, _cs, _bool);
        }
    }

    /**
     * Converts java.time and plain java.util.Date values to the java.sql type {@link Values} understands.
     *
     * @param _v
     *            the value
     * @return the java.sql date/time value, or the value itself if no conversion applies
     */
    private static Object asTemporalValue(final Object _v) {
        if (_v instanceof LocalDate) {
            return Date.valueOf((LocalDate) _v);
        }
        if (_v instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) _v);
        }
        if (_v instanceof LocalTime) {
            return Time.valueOf((LocalTime) _v);
        }
        if (_v instanceof OffsetDateTime) {
            return Timestamp.valueOf(((OffsetDateTime) _v).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (_v instanceof Instant) {
            return Timestamp.from((Instant) _v);
        }
        if (_v instanceof java.util.Date && !(_v instanceof Date) && !(_v instanceof Time) && !(_v instanceof Timestamp)) {
            return new Timestamp(((java.util.Date) _v).getTime());
        }
        return _v;
    }

    /** DATE('x') -> x */
    private static String stripCast(final String _lit) {
        final int q = _lit.indexOf('\'');
        final int e = _lit.lastIndexOf('\'');
        return q >= 0 && e > q ? _lit.substring(q + 1, e) : _lit;
    }

    /**
     * A decimal literal, or a DECFLOAT expression when the value has more digits than a DECIMAL literal allows.
     *
     * @param _bd
     *            the value
     * @return the SQL literal
     */
    static String decimal(final BigDecimal _bd) {
        final String s = _bd.toPlainString();
        // DECIMAL literals are limited to 63 digits; fall back to DECFLOAT for anything larger
        if (_bd.precision() > 63) {
            return "DECFLOAT('" + _bd.toString() + "')";
        }
        return s;
    }

    /**
     * A floating point literal (always with an exponent, so the server treats it as DOUBLE).
     *
     * @param _d
     *            the value
     * @return the SQL literal
     * @throws SQLException
     *             if the value is NaN or infinite, which have no SQL literal
     */
    static String doubleLiteral(final double _d) throws SQLException {
        if (Double.isNaN(_d) || Double.isInfinite(_d)) {
            throw new SQLDataException("Cannot send " + _d + " as a DOUBLE parameter", "22003");
        }
        String s = Double.toString(_d).toUpperCase(Locale.ROOT);
        if (!s.contains("E")) {
            s = s + "E0";
        }
        return s;
    }

    static String booleanLiteral(final boolean _b, final boolean _supported) {
        if (_supported) {
            return _b ? "TRUE" : "FALSE";
        }
        return _b ? "1" : "0";
    }

    static String dateLiteral(final LocalDate _d) {
        return "DATE('" + String.format(Locale.ROOT, "%04d-%02d-%02d", _d.getYear(), _d.getMonthValue(),
                _d.getDayOfMonth()) + "')";
    }

    static String timeLiteral(final LocalTime _t) {
        return "TIME('" + String.format(Locale.ROOT, "%02d:%02d:%02d", _t.getHour(), _t.getMinute(), _t.getSecond())
                + "')";
    }

    static String timestampLiteral(final LocalDateTime _t) {
        return "TIMESTAMP('" + String.format(Locale.ROOT, "%04d-%02d-%02d-%02d.%02d.%02d.%09d", _t.getYear(),
                _t.getMonthValue(), _t.getDayOfMonth(), _t.getHour(), _t.getMinute(), _t.getSecond(), _t.getNano())
                + "')";
    }
}
