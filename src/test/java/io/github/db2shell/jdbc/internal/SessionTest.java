package io.github.db2shell.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Exercises the protocol parser against output shaped exactly like the db2 utility's (captured on IBM i). */
class SessionTest {

    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** Replays canned output and records what was sent. */
    static final class FakeTransport implements Transport {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final InputStream in;

        FakeTransport(final String _output, final Charset _cs) {
            in = new ByteArrayInputStream(bytes(_output, _cs));
        }

        @Override
        public InputStream getInput() {
            return in;
        }

        @Override
        public OutputStream getOutput() {
            return sent;
        }

        @Override
        public String getErrorText() {
            return "";
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void kill() {
        }

        @Override
        public void close() {
        }
    }

    private static Session session(final String _output) throws SQLException {
        final Session s = new Session(new FakeTransport(_output, EBCDIC), EBCDIC, Tracer.create(false, null));
        s.start(5, true);
        return s;
    }

    /** Encodes like the utility does: lines end with the byte that decodes to LF (0x25 in EBCDIC). */
    static byte[] bytes(final String _s, final Charset _cs) {
        final byte nl = Session.newlineByte(_cs);
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (final String part : _s.split("\n", -1)) {
            final byte[] p = part.getBytes(_cs);
            bos.write(p, 0, p.length);
            bos.write(nl);
        }
        final byte[] b = bos.toByteArray();
        return java.util.Arrays.copyOf(b, b.length - 1); // drop the separator added after the last part
    }

    private static String pad(final String _s, final int _w) {
        final StringBuilder sb = new StringBuilder(_s);
        while (sb.length() < _w) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static final String OK ="DB20000I  THE SQL COMMAND COMPLETED SUCCESSFULLY.\nDB2>\n";

    @Test
    void successAndError() throws SQLException {
        final Session s = session("DB2>\n" + OK + "\n **** CLI ERROR *****\n         SQLSTATE: 42704\n"
                + "NATIVE ERROR CODE: -204\nNOSUCH in QTEMP type *FILE not found. \nDB2>\n");
        final Session.Response ok = s.send("create table qtemp.t (a int)", 0);
        assertFalse(ok.isError());
        assertFalse(ok.isResult());
        final Session.Response err = s.send("select * from qtemp.nosuch", 0);
        assertTrue(err.isError());
        assertTrue(err.error instanceof SQLSyntaxErrorException);
        assertEquals("42704", err.error.getSQLState());
        assertEquals(-204, err.error.getErrorCode());
        assertEquals("NOSUCH in QTEMP type *FILE not found.", err.error.getMessage());
        assertTrue(s.isIdle());
    }

    @Test
    void sendsTerminatorInJobEncoding() throws SQLException {
        final FakeTransport t = new FakeTransport("DB2>\n" + OK, EBCDIC);
        final Session s = new Session(t, EBCDIC, Tracer.create(false, null));
        s.start(5, true);
        s.send("values 1", 0);
        assertArrayEquals(bytes("values 1%\n", EBCDIC), t.sent.toByteArray());
    }

    @Test
    void warningIsNotAnError() throws SQLException {
        final Session s = session("DB2>\n\n **** CLI ERROR *****\n         SQLSTATE: 02000\nNATIVE ERROR CODE: 100\n"
                + "Row not found for UPDATE. \nDB2>\n");
        final Session.Response r = s.send("update t set a = 1 where 1 = 0", 0);
        assertFalse(r.isError());
        assertEquals(1, r.warnings.size());
        assertEquals("02000", r.warnings.get(0).getSQLState());
    }

    @Test
    void rawResultWithEmbeddedNewline() throws SQLException {
        // values 'line1' concat x'25' concat 'line2' concat x'0D' concat 'end', 'next'
        final Session s = session("DB2>\n\n00001          \n---------------\nline1\nline2\rend\nnext           \n\n"
                + "  2 RECORD(S) SELECTED.\n\nDB2>\n");
        final Session.Response r = s.send("values ...", 0);
        assertTrue(r.isResult());
        assertEquals(1, r.columns.length);
        assertEquals(15, r.width);
        final RawRowSource src = new RawRowSource(this, s, r.columns, r.width, 0);
        assertEquals("line1\nline2\rend", src.next()[0]);
        assertEquals("next", src.next()[0]);
        assertNull(src.next());
        assertTrue(s.isIdle());
    }

    @Test
    void rawResultColumnsAndNulls() throws SQLException {
        final Session s = session("DB2>\n\nA           my col      C           \n----------- ----------- -----------\n"
                + "          1 -           hello world\n         -2 x           -          \n\n"
                + "  2 RECORD(S) SELECTED.\n\nDB2>\n");
        final Session.Response r = s.send("select ...", 0);
        assertArrayEquals(new String[] { "A", "my col", "C" }, RawRowSource.headerNames(s, r.header, r.columns));
        final RawRowSource src = new RawRowSource(this, s, r.columns, r.width, 0);
        assertArrayEquals(new Object[] { "1", null, "hello world" }, src.next());
        assertArrayEquals(new Object[] { "-2", "x", null }, src.next());
        assertNull(src.next());
    }

    @Test
    void errorWhileFetching() throws SQLException {
        final Session s = session("DB2>\n\nX          \n-----------\n          1\n\n **** CLI ERROR *****\n"
                + "         SQLSTATE: 22012\nNATIVE ERROR CODE: -802\nDivision by zero. \n\n"
                + "  1 RECORD(S) SELECTED.\n\nDB2>\n" + OK);
        final Session.Response r = s.send("select ...", 0);
        final RawRowSource src = new RawRowSource(this, s, r.columns, r.width, 0);
        assertEquals("1", src.next()[0]);
        final SQLException e = assertThrows(SQLException.class, src::next);
        assertEquals("22012", e.getSQLState());
        assertTrue(s.isIdle());
        assertFalse(s.send("values 1", 0).isError());
    }

    @Test
    void unconsumedResultIsReleasedBeforeNextCommand() throws SQLException {
        final Session s = session("DB2>\n\nX          \n-----------\n          1\n          2\n\n"
                + "  2 RECORD(S) SELECTED.\n\nDB2>\n" + OK);
        final Session.Response r = s.send("select ...", 0);
        final RawRowSource src = new RawRowSource(this, s, r.columns, r.width, 0);
        assertFalse(s.send("values 1", 0).isError());
        // rows were buffered and are still readable
        assertEquals("1", src.next()[0]);
        assertEquals("2", src.next()[0]);
        assertNull(src.next());
    }

    @Test
    void typedTokenStream() throws SQLException {
        // W = 16; row 1 spans two lines, row 2 has a NULL and a value containing a newline, row 3 has an empty string
        final String row1 = pad("1:72:ab10:2024-01-02", 32); // two physical lines of 16
        final String row2 = pad("2:-53:x\ny-", 16); // a value with a newline: the utility breaks the line there
        final String row3 = pad("1:00:-", 16);
        final String out = "DB2>\n\nL               \n----------------\n" + row1.substring(0, 16) + "\n"
                + row1.substring(16) + "\n" + row2 + "\n" + row3 + "\n\n  3 RECORD(S) SELECTED.\n\nDB2>\n";
        final Session s = session(out);
        final Session.Response r = s.send("select ...", 0);
        assertEquals(16, r.width);
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        cols.add(ColumnInfo.fromDescriptor("I", ColumnInfo.D_INTEGER, 4, 9, 0, true, 0, 0, "", "", ""));
        cols.add(ColumnInfo.fromDescriptor("S", ColumnInfo.D_VARCHAR, 10, 0, 0, true, 37, 0, "", "", ""));
        cols.add(ColumnInfo.fromDescriptor("D", ColumnInfo.D_DATETIME, 10, 0, 0, true, 37, 1, "", "", ""));
        final TypedRowSource src = new TypedRowSource(this, s, cols, 16, false);
        final Object[] a = src.next();
        assertEquals(7, a[0]);
        assertEquals("ab", a[1]);
        assertEquals(java.sql.Date.valueOf("2024-01-02"), a[2]);
        final Object[] b = src.next();
        assertEquals(-5, b[0]);
        assertEquals("x\ny", b[1]);
        assertNull(b[2]);
        final Object[] c = src.next();
        assertEquals(0, c[0]);
        assertEquals("", c[1]);
        assertNull(c[2]);
        assertNull(src.next());
        assertTrue(s.isIdle());
    }

    @Test
    void typedUnicodeHex() throws SQLException {
        // 'A€' as UTF-16 hex = 004120AC, length 8
        final Session s = session("DB2>\n\nL       \n--------\n8:004120\nAC      \n\n  1 RECORD(S) SELECTED.\n\nDB2>\n");
        final Session.Response r = s.send("select ...", 0);
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        cols.add(ColumnInfo.fromDescriptor("S", ColumnInfo.D_VARCHAR, 10, 0, 0, true, 37, 0, "", "", ""));
        final TypedRowSource src = new TypedRowSource(this, s, cols, r.width, true);
        assertEquals("A€", src.next()[0]);
    }

    @Test
    void decodesNumbersAndTimestamps() throws SQLException {
        assertEquals(new BigDecimal("-12.50"), Values.decode(ColumnInfo.Kind.DECIMAL, "-12.50"));
        assertEquals(-1.2345678000000001E24, (Double) Values.decode(ColumnInfo.Kind.DOUBLE, "-1.2345678000000001E24"), 0);
        assertTrue(Double.isNaN((Double) Values.decode(ColumnInfo.Kind.DECFLOAT, "NAN")));
        assertEquals(java.sql.Timestamp.valueOf("2026-11-12 21:20:49.033496"),
                Values.decode(ColumnInfo.Kind.TIMESTAMP, "2026-11-12-21.20.49.033496"));
        assertEquals(java.sql.Timestamp.valueOf("2026-11-12 21:20:49.123456789"),
                Values.decode(ColumnInfo.Kind.TIMESTAMP, "2026-11-12-21.20.49.123456789012"));
        assertEquals(java.sql.Time.valueOf("12:34:56"), Values.decode(ColumnInfo.Kind.TIME, "12:34:56"));
        assertEquals(Boolean.TRUE, Values.decode(ColumnInfo.Kind.BOOLEAN, "1"));
        assertArrayEquals(new byte[] { 0, (byte) 0xC1 }, (byte[]) Values.decode(ColumnInfo.Kind.BINARY, "00C1"));
    }

    @Test
    void promptWithoutNewline() throws Exception {
        // the real utility prints "DB2>" and then blocks reading stdin; the newline only comes with the next output
        final java.io.PipedOutputStream feed = new java.io.PipedOutputStream();
        final java.io.PipedInputStream in = new java.io.PipedInputStream(feed, 65536);
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final Transport t = new Transport() {
            @Override public InputStream getInput() { return in; }
            @Override public OutputStream getOutput() { return sent; }
            @Override public String getErrorText() { return ""; }
            @Override public boolean isAlive() { return true; }
            @Override public void kill() { }
            @Override public void close() { }
        };
        feed.write(bytes("DB2>", EBCDIC));
        feed.flush();
        final Session s = new Session(t, EBCDIC, Tracer.create(false, null));
        s.start(5, true);
        feed.write(bytes("\nDB20000I  THE SQL COMMAND COMPLETED SUCCESSFULLY.\nDB2>", EBCDIC));
        feed.flush();
        assertFalse(s.send("create table x (a int)", 0).isError());
        feed.write(bytes("\n\nA          \n-----------\n          1\n\n  1 RECORD(S) SELECTED.\n\nDB2>", EBCDIC));
        feed.flush();
        final Session.Response r = s.send("values 1", 0);
        final RawRowSource src = new RawRowSource(this, s, r.columns, r.width, 0);
        assertEquals("1", src.next()[0]);
        assertNull(src.next());
        assertTrue(s.isIdle());
    }

    @Test
    void detectsAsciiOutput() throws SQLException {
        final Session s = new Session(new FakeTransport("DB2>\n" + OK, StandardCharsets.US_ASCII), EBCDIC,
                Tracer.create(false, null));
        s.start(5, false);
        assertEquals(StandardCharsets.UTF_8, s.getCharset());
        assertFalse(s.send("values 1", 0).isError());
    }

    @Test
    void connectFailureBeforePrompt() {
        final Session s = new Session(new FakeTransport("\n **** CLI ERROR *****\n         SQLSTATE: 08004\n"
                + "NATIVE ERROR CODE: -30082\nAuthorization failure on distributed database connection attempt. \n"
                + "DB2>\n", EBCDIC), EBCDIC, Tracer.create(false, null));
        final SQLException e = assertThrows(SQLException.class, () -> s.start(5, true));
        assertEquals("08004", e.getSQLState());
    }
}
