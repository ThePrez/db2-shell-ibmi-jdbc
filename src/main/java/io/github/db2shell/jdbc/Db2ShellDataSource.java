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

    private String url = DriverConfig.URL_PREFIX;
    private final Properties properties = new Properties();
    private PrintWriter logWriter;
    private int loginTimeout = 0;

    public String getUrl() {
        return url;
    }

    /** Sets the full JDBC URL (properties may also be embedded in it). */
    public void setUrl(final String _url) {
        url = _url;
    }

    /** Sets any driver property (see the README for the list). */
    public void setProperty(final String _name, final String _value) {
        if (null == _value) {
            properties.remove(_name);
        } else {
            properties.setProperty(_name, _value);
        }
    }

    public String getProperty(final String _name) {
        return properties.getProperty(_name);
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

    @Override
    public Connection getConnection() throws SQLException {
        final Properties p = new Properties();
        p.putAll(properties);
        if (loginTimeout > 0 && null == p.getProperty(DriverConfig.P_STARTUP_TIMEOUT)) {
            p.setProperty(DriverConfig.P_STARTUP_TIMEOUT, Integer.toString(loginTimeout));
        }
        return Db2ShellConnection.open(DriverConfig.parse(url, p));
    }

    @Override
    public Connection getConnection(final String _user, final String _password) throws SQLException {
        final Properties p = new Properties();
        p.putAll(properties);
        if (null != _user) {
            p.setProperty(DriverConfig.P_USER, _user);
        }
        if (null != _password) {
            p.setProperty(DriverConfig.P_PASSWORD, _password);
        }
        return Db2ShellConnection.open(DriverConfig.parse(url, p));
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(final PrintWriter _out) {
        logWriter = _out;
    }

    @Override
    public void setLoginTimeout(final int _seconds) {
        loginTimeout = _seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
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
