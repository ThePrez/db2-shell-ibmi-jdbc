package io.github.db2shell.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import io.github.db2shell.jdbc.internal.ColumnInfo;

/** Column metadata from the query's SQL descriptor (typed mode) or display header (raw mode). */
public class Db2ShellResultSetMetaData implements ResultSetMetaData {

    private final List<ColumnInfo> m_columns;

    Db2ShellResultSetMetaData(final List<ColumnInfo> _columns) {
        m_columns = _columns;
    }

    private ColumnInfo col(final int _i) throws SQLException {
        if (_i < 1 || _i > m_columns.size()) {
            throw new SQLException("Column index " + _i + " out of range (1.." + m_columns.size() + ")", "07009");
        }
        return m_columns.get(_i - 1);
    }

    @Override
    public int getColumnCount() {
        return m_columns.size();
    }

    @Override
    public boolean isAutoIncrement(final int _c) throws SQLException {
        col(_c);
        return false;
    }

    @Override
    public boolean isCaseSensitive(final int _c) throws SQLException {
        return col(_c).isCaseSensitive();
    }

    @Override
    public boolean isSearchable(final int _c) throws SQLException {
        final ColumnInfo.Kind k = col(_c).getKind();
        return ColumnInfo.Kind.BLOB != k && ColumnInfo.Kind.CLOB != k && ColumnInfo.Kind.XML != k;
    }

    @Override
    public boolean isCurrency(final int _c) throws SQLException {
        col(_c);
        return false;
    }

    @Override
    public int isNullable(final int _c) throws SQLException {
        return col(_c).getNullable();
    }

    @Override
    public boolean isSigned(final int _c) throws SQLException {
        return col(_c).isSigned();
    }

    @Override
    public int getColumnDisplaySize(final int _c) throws SQLException {
        return col(_c).getDisplaySize();
    }

    @Override
    public String getColumnLabel(final int _c) throws SQLException {
        return col(_c).getName();
    }

    @Override
    public String getColumnName(final int _c) throws SQLException {
        return col(_c).getName();
    }

    @Override
    public String getSchemaName(final int _c) throws SQLException {
        return col(_c).getBaseSchema();
    }

    @Override
    public int getPrecision(final int _c) throws SQLException {
        return col(_c).getPrecision();
    }

    @Override
    public int getScale(final int _c) throws SQLException {
        return col(_c).getScale();
    }

    @Override
    public String getTableName(final int _c) throws SQLException {
        return col(_c).getBaseTable();
    }

    @Override
    public String getCatalogName(final int _c) throws SQLException {
        col(_c);
        return "";
    }

    @Override
    public int getColumnType(final int _c) throws SQLException {
        return col(_c).getJdbcType();
    }

    @Override
    public String getColumnTypeName(final int _c) throws SQLException {
        return col(_c).getTypeName();
    }

    @Override
    public boolean isReadOnly(final int _c) throws SQLException {
        col(_c);
        return true;
    }

    @Override
    public boolean isWritable(final int _c) throws SQLException {
        col(_c);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(final int _c) throws SQLException {
        col(_c);
        return false;
    }

    @Override
    public String getColumnClassName(final int _c) throws SQLException {
        return col(_c).getClassName();
    }

    /** The column's base column name (when it maps directly to a table column), or empty. */
    public String getBaseColumnName(final int _c) throws SQLException {
        return col(_c).getBaseColumn();
    }

    /** The column's CCSID (0 for numeric types, 65535 for binary). */
    public int getCcsid(final int _c) throws SQLException {
        return col(_c).getCcsid();
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
