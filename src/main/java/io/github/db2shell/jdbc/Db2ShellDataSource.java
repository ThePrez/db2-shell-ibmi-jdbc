package io.github.db2shell.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A simple {@link DataSource}. Every {@link #getConnection()} starts a new db2 utility process; use a pool if
 * connections are opened frequently.
 *
 * <pre>
 * Db2ShellDataSource ds = new Db2ShellDataSource();
 * ds.setProperty("schema", "MYLIB");
 * try (Connection c = ds.getConnection()) { ... }
 * </pre>
 */
public class Db2ShellDataSource implements DataSource {

    private String m_url = DriverConfig.URL_PREFIX;
    private final Properties m_properties = new Properties();
    private PrintWriter m_logWriter;
    private int m_loginTimeout = 0;

    public String getUrl() {
        return m_url;
    }

    /** Sets the full JDBC URL (properties may also be embedded in it). */
    public void setUrl(final String _url) {
        m_url = _url;
    }

    /**
     * Sets any driver property (see the README for the list).
     *
     * @param _name
     *            the property name
     * @param _value
     *            the property value, or null to remove the property
     */
    public void setProperty(final String _name, final String _value) {
        if (null == _value) {
            m_properties.remove(_name);
        } else {
            m_properties.setProperty(_name, _value);
        }
    }

    public String getProperty(final String _name) {
        return m_properties.getProperty(_name);
    }

    public void setUser(final String _user) {
        setProperty(DriverConfig.P_USER, _user);
    }

    public void setPassword(final String _password) {
        setProperty(DriverConfig.P_PASSWORD, _password);
    }

    public void setSchema(final String _schema) {
        setProperty(DriverConfig.P_SCHEMA, _schema);
    }

    public void setEncoding(final String _encoding) {
        setProperty(DriverConfig.P_ENCODING, _encoding);
    }

    /**
     * Opens a new connection (a new db2 utility process) using the configured URL and properties. A non-zero
     * login timeout is used as the startup timeout unless that property was set explicitly.
     *
     * @return the new connection
     * @throws SQLException
     *             if the configuration is invalid or the db2 utility cannot be started
     */
    @Override
    public Connection getConnection() throws SQLException {
        final Properties p = new Properties();
        p.putAll(m_properties);
        if (m_loginTimeout > 0 && null == p.getProperty(DriverConfig.P_STARTUP_TIMEOUT)) {
            p.setProperty(DriverConfig.P_STARTUP_TIMEOUT, Integer.toString(m_loginTimeout));
        }
        return Db2ShellConnection.open(DriverConfig.parse(m_url, p));
    }

    /**
     * Opens a new connection (a new db2 utility process) using the configured URL and properties, overriding
     * the user and password. Unlike {@link #getConnection()}, the login timeout is not applied.
     *
     * @param _user
     *            the user profile, or null to keep the configured one
     * @param _password
     *            the password, or null to keep the configured one
     * @return the new connection
     * @throws SQLException
     *             if the configuration is invalid or the db2 utility cannot be started
     */
    @Override
    public Connection getConnection(final String _user, final String _password) throws SQLException {
        final Properties p = new Properties();
        p.putAll(m_properties);
        if (null != _user) {
            p.setProperty(DriverConfig.P_USER, _user);
        }
        if (null != _password) {
            p.setProperty(DriverConfig.P_PASSWORD, _password);
        }
        return Db2ShellConnection.open(DriverConfig.parse(m_url, p));
    }

    @Override
    public PrintWriter getLogWriter() {
        return m_logWriter;
    }

    @Override
    public void setLogWriter(final PrintWriter _out) {
        m_logWriter = _out;
    }

    @Override
    public void setLoginTimeout(final int _seconds) {
        m_loginTimeout = _seconds;
    }

    @Override
    public int getLoginTimeout() {
        return m_loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
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
