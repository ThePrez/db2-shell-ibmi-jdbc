package io.github.db2shell.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/** A savepoint created with {@code SAVEPOINT name ON ROLLBACK RETAIN CURSORS}. */
final class Db2ShellSavepoint implements Savepoint {

    private final int m_id;
    private final String m_name;

    Db2ShellSavepoint(final int _id, final String _name) {
        m_id = _id;
        m_name = _name;
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (null != m_name) {
            throw new SQLException("This is a named savepoint", "3B000");
        }
        return m_id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (null == m_name) {
            throw new SQLException("This is an unnamed savepoint", "3B000");
        }
        return m_name;
    }

    String sqlName() {
        if (null == m_name) {
            return "JDBC_SAVEPOINT_" + m_id;
        }
        return "\"" + m_name.replace("\"", "\"\"") + "\"";
    }

    /**
     * Returns the SQL identifier for any savepoint, including ones not created by this driver: the quoted name
     * of a named savepoint, otherwise the generated {@code JDBC_SAVEPOINT_n} name.
     *
     * @param _sp
     *            the savepoint
     * @return the identifier to use in ROLLBACK TO SAVEPOINT / RELEASE SAVEPOINT
     * @throws SQLException
     *             if the savepoint is null
     */
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
            // An unnamed savepoint refuses getSavepointName(); that is expected, so fall back to its id.
            return "JDBC_SAVEPOINT_" + _sp.getSavepointId();
        }
    }

    @Override
    public String toString() {
        return null == m_name ? "Savepoint " + m_id : "Savepoint " + m_name;
    }
}
