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

    /** Reads a whole character stream. */
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

    /** Reads a whole byte stream. */
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
        private StringBuilder data;

        public ClobImpl(final String _s) {
            data = new StringBuilder(null == _s ? "" : _s);
        }

        private StringBuilder d() throws SQLException {
            if (null == data) {
                throw SqlErrors.closed("Clob");
            }
            return data;
        }

        @Override
        public long length() throws SQLException {
            return d().length();
        }

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
            data = null;
        }

        @Override
        public Reader getCharacterStream(final long _pos, final long _length) throws SQLException {
            return new StringReader(getSubString(_pos, (int) _length));
        }

        @Override
        public String toString() {
            return null == data ? "" : data.toString();
        }
    }

    /** A BLOB backed by a byte array. */
    public static final class BlobImpl implements Blob {
        private byte[] data;

        public BlobImpl(final byte[] _b) {
            data = null == _b ? new byte[0] : _b;
        }

        private byte[] d() throws SQLException {
            if (null == data) {
                throw SqlErrors.closed("Blob");
            }
            return data;
        }

        @Override
        public long length() throws SQLException {
            return d().length;
        }

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

        @Override
        public int setBytes(final long _pos, final byte[] _bytes, final int _offset, final int _len) throws SQLException {
            final int start = (int) (_pos - 1);
            final byte[] b = d();
            final byte[] n = java.util.Arrays.copyOf(b, Math.max(b.length, start + _len));
            System.arraycopy(_bytes, _offset, n, start, _len);
            data = n;
            return _len;
        }

        @Override
        public OutputStream setBinaryStream(final long _pos) throws SQLException {
            throw SqlErrors.unsupported("Blob.setBinaryStream");
        }

        @Override
        public void truncate(final long _len) throws SQLException {
            data = java.util.Arrays.copyOf(d(), (int) _len);
        }

        @Override
        public void free() {
            data = null;
        }

        @Override
        public InputStream getBinaryStream(final long _pos, final long _length) throws SQLException {
            return new ByteArrayInputStream(getBytes(_pos, (int) _length));
        }
    }

    /** An SQLXML value backed by a String. */
    public static final class SqlXmlImpl implements SQLXML {
        private String data;
        private StringWriter writer;

        public SqlXmlImpl(final String _s) {
            data = _s;
        }

        private String d() throws SQLException {
            if (null != writer) {
                data = writer.toString();
                writer = null;
            }
            if (null == data) {
                throw SqlErrors.closed("SQLXML");
            }
            return data;
        }

        @Override
        public void free() {
            data = null;
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
            writer = new StringWriter();
            return writer;
        }

        @Override
        public String getString() throws SQLException {
            return d();
        }

        @Override
        public void setString(final String _value) {
            data = _value;
            writer = null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Source> T getSource(final Class<T> _sourceClass) throws SQLException {
            if (null == _sourceClass || _sourceClass == StreamSource.class || _sourceClass == Source.class) {
                return (T) new StreamSource(new StringReader(d()));
            }
            throw SqlErrors.unsupported("SQLXML source " + _sourceClass.getName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Result> T setResult(final Class<T> _resultClass) throws SQLException {
            if (null == _resultClass || _resultClass == StreamResult.class || _resultClass == Result.class) {
                writer = new StringWriter();
                return (T) new StreamResult(writer);
            }
            throw SqlErrors.unsupported("SQLXML result " + _resultClass.getName());
        }

        @Override
        public String toString() {
            return null == writer ? data : writer.toString();
        }
    }
}
