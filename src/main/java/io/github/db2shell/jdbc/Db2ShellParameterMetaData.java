package io.github.db2shell.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Parameter metadata. Parameters are substituted into the statement text as literals, so the server never
 * describes them; types are reported from the values set so far (VARCHAR when unknown).
 */
public class Db2ShellParameterMetaData implements ParameterMetaData {

    private final int[] m_types;
    private final int[] m_modes;

    Db2ShellParameterMetaData(final int[] _types, final int[] _modes) {
        m_types = _types;
        m_modes = _modes;
    }

    private int check(final int _p) throws SQLException {
        if (_p < 1 || _p > m_types.length) {
            throw new SQLException("Parameter index " + _p + " out of range (1.." + m_types.length + ")", "07009");
        }
        return _p - 1;
    }

    @Override
    public int getParameterCount() {
        return m_types.length;
    }

    @Override
    public int isNullable(final int _p) throws SQLException {
        check(_p);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(final int _p) throws SQLException {
        final int t = m_types[check(_p)];
        return Types.INTEGER == t || Types.BIGINT == t || Types.SMALLINT == t || Types.DECIMAL == t
                || Types.NUMERIC == t || Types.DOUBLE == t || Types.REAL == t || Types.FLOAT == t;
    }

    @Override
    public int getPrecision(final int _p) throws SQLException {
        check(_p);
        return 0;
    }

    @Override
    public int getScale(final int _p) throws SQLException {
        check(_p);
        return 0;
    }

    @Override
    public int getParameterType(final int _p) throws SQLException {
        return m_types[check(_p)];
    }

    /**
     * Returns the SQL type name for the type of the value set for a parameter.
     *
     * @param _p
     *            the 1-based parameter index
     * @return the type name; {@code VARCHAR} when the type is unknown
     * @throws SQLException
     *             if the parameter index is out of range
     */
    @Override
    public String getParameterTypeName(final int _p) throws SQLException {
        switch (m_types[check(_p)]) {
        case Types.INTEGER: return "INTEGER";
        case Types.BIGINT: return "BIGINT";
        case Types.SMALLINT: return "SMALLINT";
        case Types.DECIMAL: return "DECIMAL";
        case Types.NUMERIC: return "NUMERIC";
        case Types.DOUBLE: return "DOUBLE";
        case Types.REAL: return "REAL";
        case Types.DATE: return "DATE";
        case Types.TIME: return "TIME";
        case Types.TIMESTAMP: return "TIMESTAMP";
        case Types.VARBINARY: return "VARBINARY";
        case Types.BOOLEAN: return "BOOLEAN";
        case Types.CLOB: return "CLOB";
        case Types.BLOB: return "BLOB";
        default: return "VARCHAR";
        }
    }

    /**
     * Returns the Java class name for the type of the value set for a parameter.
     *
     * @param _p
     *            the 1-based parameter index
     * @return the fully qualified class name; {@code java.lang.String} when the type is unknown
     * @throws SQLException
     *             if the parameter index is out of range
     */
    @Override
    public String getParameterClassName(final int _p) throws SQLException {
        switch (m_types[check(_p)]) {
        case Types.INTEGER:
        case Types.SMALLINT: return Integer.class.getName();
        case Types.BIGINT: return Long.class.getName();
        case Types.DECIMAL:
        case Types.NUMERIC: return java.math.BigDecimal.class.getName();
        case Types.DOUBLE: return Double.class.getName();
        case Types.REAL: return Float.class.getName();
        case Types.DATE: return java.sql.Date.class.getName();
        case Types.TIME: return java.sql.Time.class.getName();
        case Types.TIMESTAMP: return java.sql.Timestamp.class.getName();
        case Types.VARBINARY:
        case Types.BLOB: return byte[].class.getName();
        case Types.BOOLEAN: return Boolean.class.getName();
        default: return String.class.getName();
        }
    }

    @Override
    public int getParameterMode(final int _p) throws SQLException {
        return m_modes[check(_p)];
    }

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
