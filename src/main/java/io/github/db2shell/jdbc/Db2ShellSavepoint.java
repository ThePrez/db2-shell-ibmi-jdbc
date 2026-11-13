package io.github.db2shell.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/** A savepoint created with {@code SAVEPOINT name ON ROLLBACK RETAIN CURSORS}. */
final class Db2ShellSavepoint implements Savepoint {

    private final int id;
    private final String name;

    Db2ShellSavepoint(final int _id, final String _name) {
        id = _id;
        name = _name;
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (null != name) {
            throw new SQLException("This is a named savepoint", "3B000");
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (null == name) {
            throw new SQLException("This is an unnamed savepoint", "3B000");
        }
        return name;
    }

    String sqlName() {
        if (null == name) {
            return "JDBC_SAVEPOINT_" + id;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    static String sqlName(final Savepoint _sp) throws SQLException {
        if (_sp instanceof Db2ShellSavepoint) {
            return ((Db2ShellSavepoint) _sp).sqlName();
        }
        if (null == _sp) {
            throw new SQLException("Savepoint is null", "3B001");
        }
        try {
            return "\"" + _sp.getSavepointName().replace("\"", "\"\"") + "\"";
        } catch (final SQLException e) {
            return "JDBC_SAVEPOINT_" + _sp.getSavepointId();
        }
    }

    @Override
    public String toString() {
        return null == name ? "Savepoint " + id : "Savepoint " + name;
    }
}
