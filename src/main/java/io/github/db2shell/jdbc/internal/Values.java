package io.github.db2shell.jdbc.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversions between the driver's internal value representation and the types JDBC callers ask for. */
public final class Values {

    private static final Pattern TS = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[- T](\\d{1,2})[.:](\\d{1,2})(?:[.:](\\d{1,2})(?:[.,](\\d{1,12}))?)?)?\\s*$");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})[.:](\\d{1,2})(?:[.:](\\d{1,2}))?(?:[.,]\\d*)?\\s*$");

    private Values() {
    }

    // =================================================================================== decoding from wire text

    /** Converts the text form of a typed-mode token into the internal Java value for the column kind. */
    public static Object decode(final ColumnInfo.Kind _kind, final String _text) throws SQLException {
        try {
            switch (_kind) {
            case STRING:
            case CLOB:
            case XML:
                return _text;
            case BINARY:
            case BLOB:
                return SqlLiterals.unhex(_text);
            case SMALLINT:
            case INTEGER:
                return Integer.valueOf(_text.trim());
            case BIGINT:
                return Long.valueOf(_text.trim());
            case DECIMAL:
                return new BigDecimal(_text.trim().replace(',', '.'));
            case REAL:
                return Float.valueOf(floatText(_text));
            case DOUBLE:
                return Double.valueOf(floatText(_text));
            case DECFLOAT: {
                final String t = _text.trim().replace(',', '.');
                try {
                    return new BigDecimal(t);
                } catch (final NumberFormatException e) {
                    // NaN, sNaN, Infinity cannot be represented by BigDecimal
                    return Double.valueOf(floatText(t));
                }
            }
            case DATE:
                return toDate(_text, null);
            case TIME:
                return toTime(_text, null);
            case TIMESTAMP:
                return toTimestamp(_text, null);
            case BOOLEAN:
                return Boolean.valueOf("1".equals(_text.trim()));
            default:
                return _text;
            }
        } catch (final NumberFormatException e) {
            throw new SQLDataException("Unable to decode " + _kind + " value '" + _text + "'", "22018", e);
        }
    }

    private static String floatText(final String _s) {
        final String t = _s.trim().replace(',', '.').toUpperCase(Locale.ROOT);
        if (t.endsWith("NAN")) {
            return "NaN";
        }
        if (t.equals("INFINITY") || t.equals("+INFINITY") || t.equals("INF")) {
            return "Infinity";
        }
        if (t.equals("-INFINITY") || t.equals("-INF")) {
            return "-Infinity";
        }
        return t;
    }

    // =================================================================================== getters

    public static String toString(final Object _v) {
        if (null == _v) {
            return null;
        }
        if (_v instanceof String) {
            return (String) _v;
        }
        if (_v instanceof byte[]) {
            return SqlLiterals.hex((byte[]) _v, 0, ((byte[]) _v).length);
        }
        if (_v instanceof BigDecimal) {
            return ((BigDecimal) _v).toPlainString();
        }
        if (_v instanceof Timestamp) {
            return _v.toString();
        }
        return _v.toString();
    }

    public static boolean toBoolean(final Object _v) throws SQLException {
        if (null == _v) {
            return false;
        }
        if (_v instanceof Boolean) {
            return (Boolean) _v;
        }
        if (_v instanceof Number) {
            return ((Number) _v).doubleValue() != 0;
        }
        final String s = toString(_v).trim();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("y") || s.equalsIgnoreCase("yes") || s.equals("1")
                || s.equalsIgnoreCase("t") || s.equalsIgnoreCase("on")) {
            return true;
        }
        if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("n") || s.equalsIgnoreCase("no") || s.equals("0")
                || s.equalsIgnoreCase("f") || s.equalsIgnoreCase("off") || s.isEmpty()) {
            return false;
        }
        try {
            return new BigDecimal(s).signum() != 0;
        } catch (final NumberFormatException e) {
            throw conversion(_v, "boolean");
        }
    }

    public static BigDecimal toBigDecimal(final Object _v) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_v instanceof BigDecimal) {
            return (BigDecimal) _v;
        }
        if (_v instanceof Integer || _v instanceof Long || _v instanceof Short || _v instanceof Byte) {
            return BigDecimal.valueOf(((Number) _v).longValue());
        }
        if (_v instanceof BigInteger) {
            return new BigDecimal((BigInteger) _v);
        }
        if (_v instanceof Number) {
            final double d = ((Number) _v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw conversion(_v, "BigDecimal");
            }
            return new BigDecimal(_v.toString());
        }
        if (_v instanceof Boolean) {
            return ((Boolean) _v) ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(toString(_v).trim().replace(',', '.'));
        } catch (final NumberFormatException e) {
            throw conversion(_v, "BigDecimal");
        }
    }

    public static double toDouble(final Object _v) throws SQLException {
        if (null == _v) {
            return 0;
        }
        if (_v instanceof Number) {
            return ((Number) _v).doubleValue();
        }
        if (_v instanceof Boolean) {
            return ((Boolean) _v) ? 1 : 0;
        }
        try {
            return Double.parseDouble(floatText(toString(_v)));
        } catch (final NumberFormatException e) {
            throw conversion(_v, "double");
        }
    }

    public static long toLong(final Object _v) throws SQLException {
        if (null == _v) {
            return 0;
        }
        if (_v instanceof Integer || _v instanceof Long || _v instanceof Short || _v instanceof Byte) {
            return ((Number) _v).longValue();
        }
        if (_v instanceof Boolean) {
            return ((Boolean) _v) ? 1 : 0;
        }
        final BigDecimal bd;
        if (_v instanceof Number) {
            final double d = ((Number) _v).doubleValue();
            if (d > Long.MAX_VALUE || d < Long.MIN_VALUE || Double.isNaN(d)) {
                throw new SQLDataException("Value " + _v + " out of range for long", "22003");
            }
            return (long) d;
        }
        bd = toBigDecimal(_v);
        try {
            return bd.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
        } catch (final ArithmeticException e) {
            throw new SQLDataException("Value " + _v + " out of range for long", "22003");
        }
    }

    public static int toInt(final Object _v) throws SQLException {
        final long l = toLong(_v);
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
            throw new SQLDataException("Value " + _v + " out of range for int", "22003");
        }
        return (int) l;
    }

    public static short toShort(final Object _v) throws SQLException {
        final long l = toLong(_v);
        if (l > Short.MAX_VALUE || l < Short.MIN_VALUE) {
            throw new SQLDataException("Value " + _v + " out of range for short", "22003");
        }
        return (short) l;
    }

    public static byte toByte(final Object _v) throws SQLException {
        final long l = toLong(_v);
        if (l > Byte.MAX_VALUE || l < Byte.MIN_VALUE) {
            throw new SQLDataException("Value " + _v + " out of range for byte", "22003");
        }
        return (byte) l;
    }

    public static byte[] toBytes(final Object _v) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_v instanceof byte[]) {
            return (byte[]) _v;
        }
        if (_v instanceof String) {
            return ((String) _v).getBytes(StandardCharsets.UTF_8);
        }
        throw conversion(_v, "byte[]");
    }

    // ------------------------------------------------------------------ date/time

    public static Date toDate(final Object _v, final Calendar _cal) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_v instanceof Date) {
            return null == _cal ? (Date) _v : shiftDate((Date) _v, _cal);
        }
        if (_v instanceof Timestamp) {
            final Timestamp t = (Timestamp) _v;
            final LocalDate ld = t.toLocalDateTime().toLocalDate();
            return null == _cal ? Date.valueOf(ld) : shiftDate(Date.valueOf(ld), _cal);
        }
        final Matcher m = TS.matcher(toString(_v).trim());
        if (!m.matches()) {
            throw conversion(_v, "Date");
        }
        final int y = Integer.parseInt(m.group(1));
        final int mo = Integer.parseInt(m.group(2));
        final int d = Integer.parseInt(m.group(3));
        if (null == _cal) {
            return Date.valueOf(LocalDate.of(y, mo, d));
        }
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Date(c.getTimeInMillis());
    }

    public static Time toTime(final Object _v, final Calendar _cal) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_v instanceof Time) {
            return (Time) _v;
        }
        if (_v instanceof Timestamp) {
            final LocalTime lt = ((Timestamp) _v).toLocalDateTime().toLocalTime();
            return makeTime(lt.getHour(), lt.getMinute(), lt.getSecond(), _cal);
        }
        final String s = toString(_v).trim();
        final Matcher m = TIME.matcher(s);
        if (m.matches()) {
            return makeTime(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    null == m.group(3) ? 0 : Integer.parseInt(m.group(3)), _cal);
        }
        final Matcher t = TS.matcher(s);
        if (t.matches() && null != t.group(4)) {
            return makeTime(Integer.parseInt(t.group(4)), Integer.parseInt(t.group(5)),
                    null == t.group(6) ? 0 : Integer.parseInt(t.group(6)), _cal);
        }
        throw conversion(_v, "Time");
    }

    private static Time makeTime(final int _h, final int _m, final int _s, final Calendar _cal) {
        if (null == _cal) {
            return Time.valueOf(LocalTime.of(_h == 24 ? 0 : _h, _m, _s));
        }
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(1970, 0, 1, _h, _m, _s);
        return new Time(c.getTimeInMillis());
    }

    public static Timestamp toTimestamp(final Object _v, final Calendar _cal) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_v instanceof Timestamp) {
            return null == _cal ? (Timestamp) _v : shiftTimestamp((Timestamp) _v, _cal);
        }
        if (_v instanceof Date) {
            return new Timestamp(((Date) _v).getTime());
        }
        if (_v instanceof Time) {
            return new Timestamp(((Time) _v).getTime());
        }
        final Matcher m = TS.matcher(toString(_v).trim());
        if (!m.matches()) {
            throw conversion(_v, "Timestamp");
        }
        final int y = Integer.parseInt(m.group(1));
        final int mo = Integer.parseInt(m.group(2));
        final int d = Integer.parseInt(m.group(3));
        int h = null == m.group(4) ? 0 : Integer.parseInt(m.group(4));
        final int mi = null == m.group(5) ? 0 : Integer.parseInt(m.group(5));
        final int s = null == m.group(6) ? 0 : Integer.parseInt(m.group(6));
        int nanos = 0;
        if (null != m.group(7)) {
            String f = m.group(7);
            if (f.length() > 9) {
                f = f.substring(0, 9);
            }
            while (f.length() < 9) {
                f = f + "0";
            }
            nanos = Integer.parseInt(f);
        }
        boolean nextDay = false;
        if (h == 24) {
            h = 0;
            nextDay = true;
        }
        LocalDateTime ldt = LocalDateTime.of(y, mo, d, h, mi, s, nanos);
        if (nextDay) {
            ldt = ldt.plusDays(1);
        }
        if (null == _cal) {
            return Timestamp.valueOf(ldt);
        }
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(ldt.getYear(), ldt.getMonthValue() - 1, ldt.getDayOfMonth(), ldt.getHour(), ldt.getMinute(),
                ldt.getSecond());
        final Timestamp ts = new Timestamp(c.getTimeInMillis());
        ts.setNanos(nanos);
        return ts;
    }

    private static Date shiftDate(final Date _d, final Calendar _cal) {
        final LocalDate ld = _d.toLocalDate();
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(ld.getYear(), ld.getMonthValue() - 1, ld.getDayOfMonth(), 0, 0, 0);
        return new Date(c.getTimeInMillis());
    }

    private static Timestamp shiftTimestamp(final Timestamp _t, final Calendar _cal) {
        final LocalDateTime ldt = _t.toLocalDateTime();
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(ldt.getYear(), ldt.getMonthValue() - 1, ldt.getDayOfMonth(), ldt.getHour(), ldt.getMinute(),
                ldt.getSecond());
        final Timestamp ts = new Timestamp(c.getTimeInMillis());
        ts.setNanos(_t.getNanos());
        return ts;
    }

    /** Converts to the class requested via {@code getObject(int, Class)}. */
    @SuppressWarnings("unchecked")
    public static <T> T toClass(final Object _v, final Class<T> _type) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_type.isInstance(_v) && !(_v instanceof java.util.Date && _type == java.util.Date.class)) {
            return (T) _v;
        }
        final Object r;
        if (_type == String.class) {
            r = toString(_v);
        } else if (_type == Integer.class || _type == int.class) {
            r = toInt(_v);
        } else if (_type == Long.class || _type == long.class) {
            r = toLong(_v);
        } else if (_type == Short.class || _type == short.class) {
            r = toShort(_v);
        } else if (_type == Byte.class || _type == byte.class) {
            r = toByte(_v);
        } else if (_type == Double.class || _type == double.class) {
            r = toDouble(_v);
        } else if (_type == Float.class || _type == float.class) {
            r = (float) toDouble(_v);
        } else if (_type == Boolean.class || _type == boolean.class) {
            r = toBoolean(_v);
        } else if (_type == BigDecimal.class) {
            r = toBigDecimal(_v);
        } else if (_type == BigInteger.class) {
            r = toBigDecimal(_v).toBigInteger();
        } else if (_type == byte[].class) {
            r = toBytes(_v);
        } else if (_type == Date.class) {
            r = toDate(_v, null);
        } else if (_type == Time.class) {
            r = toTime(_v, null);
        } else if (_type == Timestamp.class || _type == java.util.Date.class) {
            r = toTimestamp(_v, null);
        } else if (_type == LocalDate.class) {
            r = toDate(_v, null).toLocalDate();
        } else if (_type == LocalTime.class) {
            if (_v instanceof Timestamp) {
                r = ((Timestamp) _v).toLocalDateTime().toLocalTime();
            } else {
                r = toTime(_v, null).toLocalTime();
            }
        } else if (_type == LocalDateTime.class) {
            r = toTimestamp(_v, null).toLocalDateTime();
        } else if (_type == OffsetDateTime.class) {
            r = toTimestamp(_v, null).toLocalDateTime().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } else if (_type == java.time.Instant.class) {
            r = toTimestamp(_v, null).toInstant();
        } else if (_type == UUID.class) {
            r = UUID.fromString(toString(_v).trim());
        } else if (_type == Clob.class || _type == java.sql.NClob.class) {
            r = new Lobs.ClobImpl(toString(_v));
        } else if (_type == Blob.class) {
            r = new Lobs.BlobImpl(toBytes(_v));
        } else if (_type == java.sql.SQLXML.class) {
            r = new Lobs.SqlXmlImpl(toString(_v));
        } else if (_type == Reader.class) {
            r = new StringReader(toString(_v));
        } else if (_type == InputStream.class) {
            r = new ByteArrayInputStream(_v instanceof byte[] ? (byte[]) _v : toString(_v).getBytes(StandardCharsets.UTF_8));
        } else if (_type == Object.class) {
            r = _v;
        } else {
            throw conversion(_v, _type.getName());
        }
        return (T) r;
    }

    static SQLException conversion(final Object _v, final String _target) {
        String shown = String.valueOf(_v);
        if (shown.length() > 64) {
            shown = shown.substring(0, 64) + "...";
        }
        return new SQLDataException("Cannot convert value '" + shown + "' (" + _v.getClass().getSimpleName() + ") to "
                + _target, "22018");
    }
}
