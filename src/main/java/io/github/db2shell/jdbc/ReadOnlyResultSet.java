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

    @Override
    public boolean rowUpdated() throws SQLException { return false; }
    @Override
    public boolean rowInserted() throws SQLException { return false; }
    @Override
    public boolean rowDeleted() throws SQLException { return false; }
    @Override
    public void insertRow() throws SQLException { throw readOnly(); }
    @Override
    public void updateRow() throws SQLException { throw readOnly(); }
    @Override
    public void deleteRow() throws SQLException { throw readOnly(); }
    @Override
    public void refreshRow() throws SQLException { throw readOnly(); }
    @Override
    public void cancelRowUpdates() throws SQLException { throw readOnly(); }
    @Override
    public void moveToInsertRow() throws SQLException { throw readOnly(); }
    @Override
    public void moveToCurrentRow() throws SQLException { throw readOnly(); }

    @Override
    public void updateNull(final int _c) throws SQLException { throw readOnly(); }
    @Override
    public void updateNull(final String _l) throws SQLException { throw readOnly(); }
    @Override
    public void updateBoolean(final int _c, final boolean _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBoolean(final String _l, final boolean _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateByte(final int _c, final byte _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateByte(final String _l, final byte _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateShort(final int _c, final short _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateShort(final String _l, final short _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateInt(final int _c, final int _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateInt(final String _l, final int _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateLong(final int _c, final long _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateLong(final String _l, final long _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateFloat(final int _c, final float _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateFloat(final String _l, final float _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateDouble(final int _c, final double _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateDouble(final String _l, final double _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBigDecimal(final int _c, final BigDecimal _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBigDecimal(final String _l, final BigDecimal _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateString(final int _c, final String _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateString(final String _l, final String _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBytes(final int _c, final byte[] _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBytes(final String _l, final byte[] _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateDate(final int _c, final Date _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateDate(final String _l, final Date _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateTime(final int _c, final Time _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateTime(final String _l, final Time _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateTimestamp(final int _c, final Timestamp _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateTimestamp(final String _l, final Timestamp _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateAsciiStream(final int _c, final InputStream _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateAsciiStream(final String _l, final InputStream _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateBinaryStream(final int _c, final InputStream _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateBinaryStream(final String _l, final InputStream _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateCharacterStream(final int _c, final Reader _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateCharacterStream(final String _l, final Reader _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateObject(final int _c, final Object _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateObject(final String _l, final Object _x, final int _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateObject(final int _c, final Object _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateObject(final String _l, final Object _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateRef(final int _c, final Ref _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateRef(final String _l, final Ref _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBlob(final int _c, final Blob _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBlob(final String _l, final Blob _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateClob(final int _c, final Clob _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateClob(final String _l, final Clob _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateArray(final int _c, final Array _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateArray(final String _l, final Array _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateRowId(final int _c, final RowId _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateRowId(final String _l, final RowId _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNString(final int _c, final String _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNString(final String _l, final String _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNClob(final int _c, final NClob _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNClob(final String _l, final NClob _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateSQLXML(final int _c, final SQLXML _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateSQLXML(final String _l, final SQLXML _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNCharacterStream(final int _c, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateNCharacterStream(final String _l, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateAsciiStream(final int _c, final InputStream _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateAsciiStream(final String _l, final InputStream _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateBinaryStream(final int _c, final InputStream _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateBinaryStream(final String _l, final InputStream _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateCharacterStream(final int _c, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateCharacterStream(final String _l, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateBlob(final int _c, final InputStream _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateBlob(final String _l, final InputStream _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateClob(final int _c, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateClob(final String _l, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateNClob(final int _c, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateNClob(final String _l, final Reader _x, final long _n) throws SQLException { throw readOnly(); }
    @Override
    public void updateNCharacterStream(final int _c, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNCharacterStream(final String _l, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateAsciiStream(final int _c, final InputStream _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateAsciiStream(final String _l, final InputStream _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBinaryStream(final int _c, final InputStream _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBinaryStream(final String _l, final InputStream _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateCharacterStream(final int _c, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateCharacterStream(final String _l, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBlob(final int _c, final InputStream _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateBlob(final String _l, final InputStream _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateClob(final int _c, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateClob(final String _l, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNClob(final int _c, final Reader _x) throws SQLException { throw readOnly(); }
    @Override
    public void updateNClob(final String _l, final Reader _x) throws SQLException { throw readOnly(); }
}
