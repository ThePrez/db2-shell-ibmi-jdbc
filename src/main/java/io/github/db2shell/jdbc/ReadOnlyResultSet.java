package io.github.db2shell.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Base class providing the update methods of {@link ResultSet}. The driver's result sets are read-only
 * ({@code CONCUR_READ_ONLY}); data is changed by executing INSERT/UPDATE/DELETE/MERGE statements.
 * (Generated boilerplate.)
 */
abstract class ReadOnlyResultSet implements ResultSet {

    private static SQLFeatureNotSupportedException readOnly() {
        return new SQLFeatureNotSupportedException("Result sets are read-only (CONCUR_READ_ONLY); "
                + "use an UPDATE/INSERT/DELETE statement instead", "0A000");
    }

    @Override public boolean rowUpdated() throws SQLException { return false; }
    @Override public boolean rowInserted() throws SQLException { return false; }
    @Override public boolean rowDeleted() throws SQLException { return false; }
    @Override public void insertRow() throws SQLException { throw readOnly(); }
    @Override public void updateRow() throws SQLException { throw readOnly(); }
    @Override public void deleteRow() throws SQLException { throw readOnly(); }
    @Override public void refreshRow() throws SQLException { throw readOnly(); }
    @Override public void cancelRowUpdates() throws SQLException { throw readOnly(); }
    @Override public void moveToInsertRow() throws SQLException { throw readOnly(); }
    @Override public void moveToCurrentRow() throws SQLException { throw readOnly(); }

    @Override public void updateNull(int _c) throws SQLException { throw readOnly(); }
    @Override public void updateNull(String _l) throws SQLException { throw readOnly(); }
    @Override public void updateBoolean(int _c, boolean _x) throws SQLException { throw readOnly(); }
    @Override public void updateBoolean(String _l, boolean _x) throws SQLException { throw readOnly(); }
    @Override public void updateByte(int _c, byte _x) throws SQLException { throw readOnly(); }
    @Override public void updateByte(String _l, byte _x) throws SQLException { throw readOnly(); }
    @Override public void updateShort(int _c, short _x) throws SQLException { throw readOnly(); }
    @Override public void updateShort(String _l, short _x) throws SQLException { throw readOnly(); }
    @Override public void updateInt(int _c, int _x) throws SQLException { throw readOnly(); }
    @Override public void updateInt(String _l, int _x) throws SQLException { throw readOnly(); }
    @Override public void updateLong(int _c, long _x) throws SQLException { throw readOnly(); }
    @Override public void updateLong(String _l, long _x) throws SQLException { throw readOnly(); }
    @Override public void updateFloat(int _c, float _x) throws SQLException { throw readOnly(); }
    @Override public void updateFloat(String _l, float _x) throws SQLException { throw readOnly(); }
    @Override public void updateDouble(int _c, double _x) throws SQLException { throw readOnly(); }
    @Override public void updateDouble(String _l, double _x) throws SQLException { throw readOnly(); }
    @Override public void updateBigDecimal(int _c, BigDecimal _x) throws SQLException { throw readOnly(); }
    @Override public void updateBigDecimal(String _l, BigDecimal _x) throws SQLException { throw readOnly(); }
    @Override public void updateString(int _c, String _x) throws SQLException { throw readOnly(); }
    @Override public void updateString(String _l, String _x) throws SQLException { throw readOnly(); }
    @Override public void updateBytes(int _c, byte[] _x) throws SQLException { throw readOnly(); }
    @Override public void updateBytes(String _l, byte[] _x) throws SQLException { throw readOnly(); }
    @Override public void updateDate(int _c, Date _x) throws SQLException { throw readOnly(); }
    @Override public void updateDate(String _l, Date _x) throws SQLException { throw readOnly(); }
    @Override public void updateTime(int _c, Time _x) throws SQLException { throw readOnly(); }
    @Override public void updateTime(String _l, Time _x) throws SQLException { throw readOnly(); }
    @Override public void updateTimestamp(int _c, Timestamp _x) throws SQLException { throw readOnly(); }
    @Override public void updateTimestamp(String _l, Timestamp _x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(int _c, InputStream _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(String _l, InputStream _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(int _c, InputStream _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(String _l, InputStream _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(int _c, Reader _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(String _l, Reader _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateObject(int _c, Object _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateObject(String _l, Object _x, int _n) throws SQLException { throw readOnly(); }
    @Override public void updateObject(int _c, Object _x) throws SQLException { throw readOnly(); }
    @Override public void updateObject(String _l, Object _x) throws SQLException { throw readOnly(); }
    @Override public void updateRef(int _c, Ref _x) throws SQLException { throw readOnly(); }
    @Override public void updateRef(String _l, Ref _x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(int _c, Blob _x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(String _l, Blob _x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(int _c, Clob _x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(String _l, Clob _x) throws SQLException { throw readOnly(); }
    @Override public void updateArray(int _c, Array _x) throws SQLException { throw readOnly(); }
    @Override public void updateArray(String _l, Array _x) throws SQLException { throw readOnly(); }
    @Override public void updateRowId(int _c, RowId _x) throws SQLException { throw readOnly(); }
    @Override public void updateRowId(String _l, RowId _x) throws SQLException { throw readOnly(); }
    @Override public void updateNString(int _c, String _x) throws SQLException { throw readOnly(); }
    @Override public void updateNString(String _l, String _x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(int _c, NClob _x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(String _l, NClob _x) throws SQLException { throw readOnly(); }
    @Override public void updateSQLXML(int _c, SQLXML _x) throws SQLException { throw readOnly(); }
    @Override public void updateSQLXML(String _l, SQLXML _x) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(int _c, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(String _l, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(int _c, InputStream _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(String _l, InputStream _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(int _c, InputStream _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(String _l, InputStream _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(int _c, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(String _l, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(int _c, InputStream _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(String _l, InputStream _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateClob(int _c, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateClob(String _l, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(int _c, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(String _l, Reader _x, long _n) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(int _c, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateNCharacterStream(String _l, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(int _c, InputStream _x) throws SQLException { throw readOnly(); }
    @Override public void updateAsciiStream(String _l, InputStream _x) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(int _c, InputStream _x) throws SQLException { throw readOnly(); }
    @Override public void updateBinaryStream(String _l, InputStream _x) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(int _c, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateCharacterStream(String _l, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(int _c, InputStream _x) throws SQLException { throw readOnly(); }
    @Override public void updateBlob(String _l, InputStream _x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(int _c, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateClob(String _l, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(int _c, Reader _x) throws SQLException { throw readOnly(); }
    @Override public void updateNClob(String _l, Reader _x) throws SQLException { throw readOnly(); }
}
