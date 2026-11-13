package io.github.db2shell.jdbc.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLXML;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/** In-memory LOB implementations. LOB values are fully materialized (up to the lobMaxLength property). */
public final class Lobs {

    private Lobs() {
    }

    /**
     * Reads a whole character stream. The reader is not closed.
     *
     * @param _r
     *            the reader
     * @param _max
     *            the maximum number of characters to read, or -1 for no limit
     * @return the characters read
     * @throws SQLException
     *             if reading fails
     */
    public static String readAll(final Reader _r, final long _max) throws SQLException {
        final StringBuilder sb = new StringBuilder();
        final char[] buf = new char[8192];
        try {
            int n;
            while ((n = _r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (_max >= 0 && sb.length() >= _max) {
                    sb.setLength((int) _max);
                    break;
                }
            }
        } catch (final IOException e) {
            throw new SQLException("Error reading character stream", "HY000", e);
        }
        return sb.toString();
    }

    /**
     * Reads a whole byte stream. The stream is not closed.
     *
     * @param _in
     *            the stream
     * @param _max
     *            the maximum number of bytes to read, or -1 for no limit
     * @return the bytes read
     * @throws SQLException
     *             if reading fails
     */
    public static byte[] readAll(final InputStream _in, final long _max) throws SQLException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = _in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (_max >= 0 && bos.size() >= _max) {
                    break;
                }
            }
        } catch (final IOException e) {
            throw new SQLException("Error reading binary stream", "HY000", e);
        }
        final byte[] b = bos.toByteArray();
        if (_max >= 0 && b.length > _max) {
            return java.util.Arrays.copyOf(b, (int) _max);
        }
        return b;
    }

    /** A CLOB/NCLOB backed by a String. */
    public static final class ClobImpl implements NClob {
        private StringBuilder m_data;

        public ClobImpl(final String _s) {
            m_data = new StringBuilder(null == _s ? "" : _s);
        }

        private StringBuilder d() throws SQLException {
            if (null == m_data) {
                throw SqlErrors.closed("Clob");
            }
            return m_data;
        }

        @Override
        public long length() throws SQLException {
            return d().length();
        }

        /**
         * Returns part of the value.
         *
         * @param _pos
         *            1-based position of the first character
         * @param _length
         *            the maximum number of characters
         * @return the characters (fewer if the value ends first)
         * @throws SQLException
         *             if the position is outside the value or the CLOB has been freed
         */
        @Override
        public String getSubString(final long _pos, final int _length) throws SQLException {
            final StringBuilder sb = d();
            final int start = (int) (_pos - 1);
            if (start < 0 || start > sb.length()) {
                throw new SQLException("Invalid position " + _pos, "22011");
            }
            return sb.substring(start, Math.min(sb.length(), start + Math.max(0, _length)));
        }

        @Override
        public Reader getCharacterStream() throws SQLException {
            return new StringReader(d().toString());
        }

        @Override
        public InputStream getAsciiStream() throws SQLException {
            return new ByteArrayInputStream(d().toString().getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public long position(final String _search, final long _start) throws SQLException {
            final int i = d().indexOf(_search, (int) (_start - 1));
            return i < 0 ? -1 : i + 1;
        }

        @Override
        public long position(final java.sql.Clob _search, final long _start) throws SQLException {
            return position(_search.getSubString(1, (int) _search.length()), _start);
        }

        @Override
        public int setString(final long _pos, final String _str) throws SQLException {
            return setString(_pos, _str, 0, _str.length());
        }

        /**
         * Overwrites characters starting at a position, padding with blanks if the position is past the end.
         *
         * @param _pos
         *            1-based position of the first character to write
         * @param _str
         *            the source string
         * @param _offset
         *            offset of the first character in the source string
         * @param _len
         *            the number of characters to write
         * @return the number of characters written
         * @throws SQLException
         *             if the CLOB has been freed
         */
        @Override
        public int setString(final long _pos, final String _str, final int _offset, final int _len) throws SQLException {
            final StringBuilder sb = d();
            final int start = (int) (_pos - 1);
            while (sb.length() < start) {
                sb.append(' ');
            }
            final String part = _str.substring(_offset, _offset + _len);
            final int end = Math.min(sb.length(), start + part.length());
            sb.replace(start, end, part);
            return part.length();
        }

        @Override
        public OutputStream setAsciiStream(final long _pos) throws SQLException {
            throw SqlErrors.unsupported("Clob.setAsciiStream");
        }

        @Override
        public Writer setCharacterStream(final long _pos) throws SQLException {
            throw SqlErrors.unsupported("Clob.setCharacterStream");
        }

        @Override
        public void truncate(final long _len) throws SQLException {
            d().setLength((int) _len);
        }

        @Override
        public void free() {
            m_data = null;
        }

        @Override
        public Reader getCharacterStream(final long _pos, final long _length) throws SQLException {
            return new StringReader(getSubString(_pos, (int) _length));
        }

        @Override
        public String toString() {
            return null == m_data ? "" : m_data.toString();
        }
    }

    /** A BLOB backed by a byte array. */
    public static final class BlobImpl implements Blob {
        private byte[] m_data;

        public BlobImpl(final byte[] _b) {
            m_data = null == _b ? new byte[0] : _b;
        }

        private byte[] d() throws SQLException {
            if (null == m_data) {
                throw SqlErrors.closed("Blob");
            }
            return m_data;
        }

        @Override
        public long length() throws SQLException {
            return d().length;
        }

        /**
         * Returns part of the value.
         *
         * @param _pos
         *            1-based position of the first byte
         * @param _length
         *            the maximum number of bytes
         * @return the bytes (fewer if the value ends first)
         * @throws SQLException
         *             if the position is outside the value or the BLOB has been freed
         */
        @Override
        public byte[] getBytes(final long _pos, final int _length) throws SQLException {
            final byte[] b = d();
            final int start = (int) (_pos - 1);
            if (start < 0 || start > b.length) {
                throw new SQLException("Invalid position " + _pos, "22011");
            }
            return java.util.Arrays.copyOfRange(b, start, Math.min(b.length, start + Math.max(0, _length)));
        }

        @Override
        public InputStream getBinaryStream() throws SQLException {
            return new ByteArrayInputStream(d());
        }

        /**
         * Finds a byte pattern.
         *
         * @param _pattern
         *            the bytes to search for
         * @param _start
         *            1-based position to start searching at
         * @return the 1-based position of the first match, or -1 if there is none
         * @throws SQLException
         *             if the BLOB has been freed
         */
        @Override
        public long position(final byte[] _pattern, final long _start) throws SQLException {
            final byte[] b = d();
            outer: for (int i = (int) (_start - 1); i <= b.length - _pattern.length; i++) {
                for (int j = 0; j < _pattern.length; j++) {
                    if (b[i + j] != _pattern[j]) {
                        continue outer;
                    }
                }
                return i + 1;
            }
            return -1;
        }

        @Override
        public long position(final Blob _pattern, final long _start) throws SQLException {
            return position(_pattern.getBytes(1, (int) _pattern.length()), _start);
        }

        @Override
        public int setBytes(final long _pos, final byte[] _bytes) throws SQLException {
            return setBytes(_pos, _bytes, 0, _bytes.length);
        }

        /**
         * Overwrites bytes starting at a position, extending the value with zero bytes if needed.
         *
         * @param _pos
         *            1-based position of the first byte to write
         * @param _bytes
         *            the source bytes
         * @param _offset
         *            offset of the first byte in the source array
         * @param _len
         *            the number of bytes to write
         * @return the number of bytes written
         * @throws SQLException
         *             if the BLOB has been freed
         */
        @Override
        public int setBytes(final long _pos, final byte[] _bytes, final int _offset, final int _len) throws SQLException {
            final int start = (int) (_pos - 1);
            final byte[] b = d();
            final byte[] n = java.util.Arrays.copyOf(b, Math.max(b.length, start + _len));
            System.arraycopy(_bytes, _offset, n, start, _len);
            m_data = n;
            return _len;
        }

        @Override
        public OutputStream setBinaryStream(final long _pos) throws SQLException {
            throw SqlErrors.unsupported("Blob.setBinaryStream");
        }

        @Override
        public void truncate(final long _len) throws SQLException {
            m_data = java.util.Arrays.copyOf(d(), (int) _len);
        }

        @Override
        public void free() {
            m_data = null;
        }

        @Override
        public InputStream getBinaryStream(final long _pos, final long _length) throws SQLException {
            return new ByteArrayInputStream(getBytes(_pos, (int) _length));
        }
    }

    /** An SQLXML value backed by a String. */
    public static final class SqlXmlImpl implements SQLXML {
        private String m_data;
        private StringWriter m_writer;

        public SqlXmlImpl(final String _s) {
            m_data = _s;
        }

        /**
         * The current value, first taking over anything written through a writer handed out earlier.
         *
         * @return the value
         * @throws SQLException
         *             if the value has been freed
         */
        private String d() throws SQLException {
            if (null != m_writer) {
                m_data = m_writer.toString();
                m_writer = null;
            }
            if (null == m_data) {
                throw SqlErrors.closed("SQLXML");
            }
            return m_data;
        }

        @Override
        public void free() {
            m_data = null;
        }

        @Override
        public InputStream getBinaryStream() throws SQLException {
            return new ByteArrayInputStream(d().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream setBinaryStream() throws SQLException {
            throw SqlErrors.unsupported("SQLXML.setBinaryStream");
        }

        @Override
        public Reader getCharacterStream() throws SQLException {
            return new StringReader(d());
        }

        @Override
        public Writer setCharacterStream() {
            m_writer = new StringWriter();
            return m_writer;
        }

        @Override
        public String getString() throws SQLException {
            return d();
        }

        @Override
        public void setString(final String _value) {
            m_data = _value;
            m_writer = null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Source> T getSource(final Class<T> _sourceClass) throws SQLException {
            if (null == _sourceClass || StreamSource.class == _sourceClass || Source.class == _sourceClass) {
                return (T) new StreamSource(new StringReader(d()));
            }
            throw SqlErrors.unsupported("SQLXML source " + _sourceClass.getName());
        }

        /**
         * Returns a result that replaces the value with whatever is written to it. Only stream results are
         * supported.
         *
         * @param <T>
         *            the result type
         * @param _resultClass
         *            StreamResult, Result, or null
         * @return the result
         * @throws SQLException
         *             if another result class is requested
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T extends Result> T setResult(final Class<T> _resultClass) throws SQLException {
            if (null == _resultClass || StreamResult.class == _resultClass || Result.class == _resultClass) {
                m_writer = new StringWriter();
                return (T) new StreamResult(m_writer);
            }
            throw SqlErrors.unsupported("SQLXML result " + _resultClass.getName());
        }

        @Override
        public String toString() {
            return null == m_writer ? m_data : m_writer.toString();
        }
    }
}
