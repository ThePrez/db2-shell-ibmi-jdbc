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

    private static final Pattern s_timestampPattern = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[- T](\\d{1,2})[.:](\\d{1,2})(?:[.:](\\d{1,2})(?:[.,](\\d{1,12}))?)?)?\\s*$");
    private static final Pattern s_timePattern = Pattern.compile("^(\\d{1,2})[.:](\\d{1,2})(?:[.:](\\d{1,2}))?(?:[.,]\\d*)?\\s*$");

    private Values() {
    }

    // =================================================================================== decoding from wire text

    /**
     * Converts the text form of a typed-mode token into the internal Java value for the column kind.
     *
     * @param _kind
     *            the column kind
     * @param _text
     *            the token text
     * @return the value: String for character data, byte[] for binary data, Integer/Long/BigDecimal/Float/Double
     *         for numbers (Double for DECFLOAT special values), java.sql date/time types, or Boolean
     * @throws SQLException
     *             if the text is not valid for the kind
     */
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

    /**
     * Normalizes the text of a floating point value for {@link Double#parseDouble(String)}: decimal comma to
     * point, and the server's spellings of NaN and infinity to Java's.
     *
     * @param _s
     *            the text
     * @return the normalized text
     */
    private static String floatText(final String _s) {
        final String t = _s.trim().replace(',', '.').toUpperCase(Locale.ROOT);
        if (t.endsWith("NAN")) {
            return "NaN";
        }
        if ("INFINITY".equals(t) || "+INFINITY".equals(t) || "INF".equals(t)) {
            return "Infinity";
        }
        if ("-INFINITY".equals(t) || "-INF".equals(t)) {
            return "-Infinity";
        }
        return t;
    }

    // =================================================================================== getters

    /**
     * Converts a value to its string form: binary values as hex, decimals without exponent.
     *
     * @param _v
     *            the value (may be null)
     * @return the string form, or null for null
     */
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

    /**
     * Converts a value to a boolean. Numbers are true when non-zero; strings accept true/false, y/n, yes/no, t/f,
     * on/off, 1/0 (case-insensitive) or any number.
     *
     * @param _v
     *            the value (may be null)
     * @return the boolean value, false for null
     * @throws SQLException
     *             if the value cannot be interpreted as a boolean
     */
    public static boolean toBoolean(final Object _v) throws SQLException {
        if (null == _v) {
            return false;
        }
        if (_v instanceof Boolean) {
            return (Boolean) _v;
        }
        if (_v instanceof Number) {
            return 0 != ((Number) _v).doubleValue();
        }
        final String s = toString(_v).trim();
        if ("true".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s)
                || "t".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "n".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "0".equals(s)
                || "f".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s) || s.isEmpty()) {
            return false;
        }
        try {
            return 0 != new BigDecimal(s).signum();
        } catch (final NumberFormatException e) {
            throw conversion(_v, "boolean");
        }
    }

    /**
     * Converts a value to a BigDecimal.
     *
     * @param _v
     *            the value (may be null)
     * @return the value, or null for null
     * @throws SQLException
     *             if the value is NaN, infinite, or not a number
     */
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

    /**
     * Converts a value to a double.
     *
     * @param _v
     *            the value (may be null)
     * @return the value, 0 for null
     * @throws SQLException
     *             if the value is not a number
     */
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

    /**
     * Converts a value to a long, truncating any fraction.
     *
     * @param _v
     *            the value (may be null)
     * @return the value, 0 for null
     * @throws SQLException
     *             if the value is not a number or out of range for long
     */
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

    /**
     * Converts a value to an int, truncating any fraction.
     *
     * @param _v
     *            the value (may be null)
     * @return the value, 0 for null
     * @throws SQLException
     *             if the value is not a number or out of range for int
     */
    public static int toInt(final Object _v) throws SQLException {
        final long l = toLong(_v);
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
            throw new SQLDataException("Value " + _v + " out of range for int", "22003");
        }
        return (int) l;
    }

    /**
     * Converts a value to a short, truncating any fraction.
     *
     * @param _v
     *            the value (may be null)
     * @return the value, 0 for null
     * @throws SQLException
     *             if the value is not a number or out of range for short
     */
    public static short toShort(final Object _v) throws SQLException {
        final long l = toLong(_v);
        if (l > Short.MAX_VALUE || l < Short.MIN_VALUE) {
            throw new SQLDataException("Value " + _v + " out of range for short", "22003");
        }
        return (short) l;
    }

    /**
     * Converts a value to a byte, truncating any fraction.
     *
     * @param _v
     *            the value (may be null)
     * @return the value, 0 for null
     * @throws SQLException
     *             if the value is not a number or out of range for byte
     */
    public static byte toByte(final Object _v) throws SQLException {
        final long l = toLong(_v);
        if (l > Byte.MAX_VALUE || l < Byte.MIN_VALUE) {
            throw new SQLDataException("Value " + _v + " out of range for byte", "22003");
        }
        return (byte) l;
    }

    /**
     * Converts a value to bytes. Strings are encoded as UTF-8.
     *
     * @param _v
     *            the value (may be null)
     * @return the bytes, or null for null
     * @throws SQLException
     *             if the value is neither binary nor a string
     */
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

    /**
     * Converts a value to a date.
     *
     * @param _v
     *            a Date, a Timestamp, or the text of a date or timestamp (may be null)
     * @param _cal
     *            the calendar (time zone) to interpret the date in, or null for the default time zone
     * @return the date, or null for null
     * @throws SQLException
     *             if the value cannot be converted
     */
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
        final Matcher m = s_timestampPattern.matcher(toString(_v).trim());
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

    /**
     * Converts a value to a time.
     *
     * @param _v
     *            a Time, a Timestamp, or the text of a time or timestamp (may be null)
     * @param _cal
     *            the calendar (time zone) to interpret the time in, or null for the default time zone
     * @return the time, or null for null
     * @throws SQLException
     *             if the value cannot be converted
     */
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
        final Matcher m = s_timePattern.matcher(s);
        if (m.matches()) {
            return makeTime(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    null == m.group(3) ? 0 : Integer.parseInt(m.group(3)), _cal);
        }
        final Matcher t = s_timestampPattern.matcher(s);
        if (t.matches() && null != t.group(4)) {
            return makeTime(Integer.parseInt(t.group(4)), Integer.parseInt(t.group(5)),
                    null == t.group(6) ? 0 : Integer.parseInt(t.group(6)), _cal);
        }
        throw conversion(_v, "Time");
    }

    /**
     * Builds a time of day. Hour 24 (end of day) maps to midnight when no calendar is given.
     *
     * @param _h
     *            the hour
     * @param _m
     *            the minute
     * @param _s
     *            the second
     * @param _cal
     *            the calendar (time zone) to interpret the time in, or null for the default time zone
     * @return the time
     */
    private static Time makeTime(final int _h, final int _m, final int _s, final Calendar _cal) {
        if (null == _cal) {
            return Time.valueOf(LocalTime.of(24 == _h ? 0 : _h, _m, _s));
        }
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(1970, 0, 1, _h, _m, _s);
        return new Time(c.getTimeInMillis());
    }

    /**
     * Converts a value to a timestamp. Fractions beyond nanoseconds are truncated and hour 24 rolls over to the
     * next day.
     *
     * @param _v
     *            a Timestamp, Date, Time, or the text of a date or timestamp (may be null)
     * @param _cal
     *            the calendar (time zone) to interpret the timestamp in, or null for the default time zone
     * @return the timestamp, or null for null
     * @throws SQLException
     *             if the value cannot be converted
     */
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
        final Matcher m = s_timestampPattern.matcher(toString(_v).trim());
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
        if (24 == h) {
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

    /**
     * Reinterprets the local date of a date in the given calendar's time zone.
     *
     * @param _d
     *            the date
     * @param _cal
     *            the calendar
     * @return the shifted date
     */
    private static Date shiftDate(final Date _d, final Calendar _cal) {
        final LocalDate ld = _d.toLocalDate();
        final Calendar c = (Calendar) _cal.clone();
        c.clear();
        c.set(ld.getYear(), ld.getMonthValue() - 1, ld.getDayOfMonth(), 0, 0, 0);
        return new Date(c.getTimeInMillis());
    }

    /**
     * Reinterprets the local date and time of a timestamp in the given calendar's time zone, keeping the nanos.
     *
     * @param _t
     *            the timestamp
     * @param _cal
     *            the calendar
     * @return the shifted timestamp
     */
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

    /**
     * Converts to the class requested via {@code getObject(int, Class)}.
     *
     * @param <T>
     *            the requested type
     * @param _v
     *            the value (may be null)
     * @param _type
     *            the requested class
     * @return the converted value, or null for null
     * @throws SQLException
     *             if the value cannot be converted to the requested class
     */
    @SuppressWarnings("unchecked")
    public static <T> T toClass(final Object _v, final Class<T> _type) throws SQLException {
        if (null == _v) {
            return null;
        }
        if (_type.isInstance(_v) && !(_v instanceof java.util.Date && java.util.Date.class == _type)) {
            return (T) _v;
        }
        final Object r;
        if (String.class == _type) {
            r = toString(_v);
        } else if (Integer.class == _type || int.class == _type) {
            r = toInt(_v);
        } else if (Long.class == _type || long.class == _type) {
            r = toLong(_v);
        } else if (Short.class == _type || short.class == _type) {
            r = toShort(_v);
        } else if (Byte.class == _type || byte.class == _type) {
            r = toByte(_v);
        } else if (Double.class == _type || double.class == _type) {
            r = toDouble(_v);
        } else if (Float.class == _type || float.class == _type) {
            r = (float) toDouble(_v);
        } else if (Boolean.class == _type || boolean.class == _type) {
            r = toBoolean(_v);
        } else if (BigDecimal.class == _type) {
            r = toBigDecimal(_v);
        } else if (BigInteger.class == _type) {
            r = toBigDecimal(_v).toBigInteger();
        } else if (byte[].class == _type) {
            r = toBytes(_v);
        } else if (Date.class == _type) {
            r = toDate(_v, null);
        } else if (Time.class == _type) {
            r = toTime(_v, null);
        } else if (Timestamp.class == _type || java.util.Date.class == _type) {
            r = toTimestamp(_v, null);
        } else if (LocalDate.class == _type) {
            r = toDate(_v, null).toLocalDate();
        } else if (LocalTime.class == _type) {
            if (_v instanceof Timestamp) {
                r = ((Timestamp) _v).toLocalDateTime().toLocalTime();
            } else {
                r = toTime(_v, null).toLocalTime();
            }
        } else if (LocalDateTime.class == _type) {
            r = toTimestamp(_v, null).toLocalDateTime();
        } else if (OffsetDateTime.class == _type) {
            r = toTimestamp(_v, null).toLocalDateTime().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } else if (java.time.Instant.class == _type) {
            r = toTimestamp(_v, null).toInstant();
        } else if (UUID.class == _type) {
            r = UUID.fromString(toString(_v).trim());
        } else if (Clob.class == _type || java.sql.NClob.class == _type) {
            r = new Lobs.ClobImpl(toString(_v));
        } else if (Blob.class == _type) {
            r = new Lobs.BlobImpl(toBytes(_v));
        } else if (java.sql.SQLXML.class == _type) {
            r = new Lobs.SqlXmlImpl(toString(_v));
        } else if (Reader.class == _type) {
            r = new StringReader(toString(_v));
        } else if (InputStream.class == _type) {
            r = new ByteArrayInputStream(_v instanceof byte[] ? (byte[]) _v : toString(_v).getBytes(StandardCharsets.UTF_8));
        } else if (Object.class == _type) {
            r = _v;
        } else {
            throw conversion(_v, _type.getName());
        }
        return (T) r;
    }

    /**
     * Builds the exception for a failed conversion. Long values are abbreviated in the message.
     *
     * @param _v
     *            the value that could not be converted (not null)
     * @param _target
     *            the name of the target type
     * @return the exception (SQLSTATE 22018)
     */
    static SQLException conversion(final Object _v, final String _target) {
        String shown = String.valueOf(_v);
        if (shown.length() > 64) {
            shown = shown.substring(0, 64) + "...";
        }
        return new SQLDataException("Cannot convert value '" + shown + "' (" + _v.getClass().getSimpleName() + ") to "
                + _target, "22018");
    }
}
