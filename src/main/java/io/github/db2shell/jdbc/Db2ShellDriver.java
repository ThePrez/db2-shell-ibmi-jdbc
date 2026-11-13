package io.github.db2shell.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for IBM i that runs SQL through the {@code db2} command line utility in QSH/PASE.
 *
 * <p>URL: {@code jdbc:db2shell:[//][RDBNAME][;property=value]...}. Examples:</p>
 * <pre>
 * jdbc:db2shell:
 * jdbc:db2shell:;schema=MYLIB;trace=true
 * jdbc:db2shell://*LOCAL;user=BOB;password=secret
 * </pre>
 */
public final class Db2ShellDriver implements Driver {

    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 0;
    public static final String NAME = "db2shell JDBC driver (db2 utility)";

    static {
        try {
            DriverManager.registerDriver(new Db2ShellDriver());
        } catch (final SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(final String _url, final Properties _info) throws SQLException {
        if (!acceptsURL(_url)) {
            return null; // per the JDBC contract
        }
        final DriverConfig cfg = DriverConfig.parse(_url, _info);
        return Db2ShellConnection.open(cfg);
    }

    @Override
    public boolean acceptsURL(final String _url) {
        return DriverConfig.acceptsUrl(_url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(final String _url, final Properties _info) {
        return DriverConfig.propertyInfo(_url, _info);
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    /** Not JDBC compliant: the db2 utility cannot bind parameters, return result sets from procedures, etc. */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used; see the 'trace' property");
    }

    /** Version string, e.g. "1.0". */
    public static String version() {
        return MAJOR_VERSION + "." + MINOR_VERSION;
    }
}
