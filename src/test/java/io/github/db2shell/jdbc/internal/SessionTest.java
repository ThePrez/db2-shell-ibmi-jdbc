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

    private static final Charset s_ebcdic = Charset.forName("IBM037");

    /** Replays canned output and records what was sent. */
    static final class FakeTransport implements Transport {
        final ByteArrayOutputStream m_sent = new ByteArrayOutputStream();
        final InputStream m_in;

        FakeTransport(final String _output, final Charset _cs) {
            m_in = new ByteArrayInputStream(bytes(_output, _cs));
        }

        @Override
        public InputStream getInput() {
            return m_in;
        }

        @Override
        public OutputStream getOutput() {
            return m_sent;
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
        final Session s = new Session(new FakeTransport(_output, s_ebcdic), s_ebcdic, Tracer.create(false, null));
        s.start(5, true);
        return s;
    }

    /**
     * Encodes like the utility does: lines end with the byte that decodes to LF (0x25 in EBCDIC).
     *
     * @param _s
     *            the text to encode, with lines separated by LF
     * @param _cs
     *            the charset to encode in
     * @return the encoded bytes
     */
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

    /**
     * Right-pad a string with spaces.
     *
     * @param _s
     *            the string to pad
     * @param _w
     *            the minimum width of the result
     * @return the padded string
     */
    private static String pad(final String _s, final int _w) {
        final StringBuilder sb = new StringBuilder(_s);
        while (sb.length() < _w) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static final String OK = "DB20000I  THE SQL COMMAND COMPLETED SUCCESSFULLY.\nDB2>\n";

    /**
     * Verifies that a successful command and a CLI error are both parsed, and that the error is mapped to an
     * SQLException with the reported SQLSTATE, native error code and message.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void successAndError() throws SQLException {
        final Session s = session("DB2>\n" + OK + "\n **** CLI ERROR *****\n         SQLSTATE: 42704\n"
                + "NATIVE ERROR CODE: -204\nNOSUCH in QTEMP type *FILE not found. \nDB2>\n");
        final Session.Response ok = s.send("create table qtemp.t (a int)", 0);
        assertFalse(ok.isError());
        assertFalse(ok.isResult());
        final Session.Response err = s.send("select * from qtemp.nosuch", 0);
        assertTrue(err.isError());
        assertTrue(err.getError() instanceof SQLSyntaxErrorException);
        assertEquals("42704", err.getError().getSQLState());
        assertEquals(-204, err.getError().getErrorCode());
        assertEquals("NOSUCH in QTEMP type *FILE not found.", err.getError().getMessage());
        assertTrue(s.isIdle());
    }

    /**
     * Verifies that a statement is sent followed by the statement terminator, encoded in the job charset.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void sendsTerminatorInJobEncoding() throws SQLException {
        final FakeTransport t = new FakeTransport("DB2>\n" + OK, s_ebcdic);
        final Session s = new Session(t, s_ebcdic, Tracer.create(false, null));
        s.start(5, true);
        s.send("values 1", 0);
        assertArrayEquals(bytes("values 1%\n", s_ebcdic), t.m_sent.toByteArray());
    }

    /**
     * Verifies that a CLI error block with a warning SQLSTATE is reported as a warning, not an error.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void warningIsNotAnError() throws SQLException {
        final Session s = session("DB2>\n\n **** CLI ERROR *****\n         SQLSTATE: 02000\nNATIVE ERROR CODE: 100\n"
                + "Row not found for UPDATE. \nDB2>\n");
        final Session.Response r = s.send("update t set a = 1 where 1 = 0", 0);
        assertFalse(r.isError());
        assertEquals(1, r.getWarnings().size());
        assertEquals("02000", r.getWarnings().get(0).getSQLState());
    }

    /**
     * Verifies that a raw result value containing LF and CR is read as one value rather than split into rows.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void rawResultWithEmbeddedNewline() throws SQLException {
        // values 'line1' concat x'25' concat 'line2' concat x'0D' concat 'end', 'next'
        final Session s = session("DB2>\n\n00001          \n---------------\nline1\nline2\rend\nnext           \n\n"
                + "  2 RECORD(S) SELECTED.\n\nDB2>\n");
        final Session.Response r = s.send("values ...", 0);
        assertTrue(r.isResult());
        assertEquals(1, r.getColumns().length);
        assertEquals(15, r.getWidth());
        final RawRowSource src = new RawRowSource(this, s, r.getColumns(), r.getWidth(), 0);
        assertEquals("line1\nline2\rend", src.next()[0]);
        assertEquals("next", src.next()[0]);
        assertNull(src.next());
        assertTrue(s.isIdle());
    }

    /**
     * Verifies that raw result columns are located from the header, and that the NULL marker is decoded as null.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void rawResultColumnsAndNulls() throws SQLException {
        final Session s = session("DB2>\n\nA           my col      C           \n----------- ----------- -----------\n"
                + "          1 -           hello world\n         -2 x           -          \n\n"
                + "  2 RECORD(S) SELECTED.\n\nDB2>\n");
        final Session.Response r = s.send("select ...", 0);
        assertArrayEquals(new String[] { "A", "my col", "C" }, RawRowSource.headerNames(s, r.getHeader(), r.getColumns()));
        final RawRowSource src = new RawRowSource(this, s, r.getColumns(), r.getWidth(), 0);
        assertArrayEquals(new Object[] { "1", null, "hello world" }, src.next());
        assertArrayEquals(new Object[] { "-2", "x", null }, src.next());
        assertNull(src.next());
    }

    /**
     * Verifies that an error reported in the middle of a result is thrown from the row source, and that the
     * session is usable afterwards.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void errorWhileFetching() throws SQLException {
        final Session s = session("DB2>\n\nX          \n-----------\n          1\n\n **** CLI ERROR *****\n"
                + "         SQLSTATE: 22012\nNATIVE ERROR CODE: -802\nDivision by zero. \n\n"
                + "  1 RECORD(S) SELECTED.\n\nDB2>\n" + OK);
        final Session.Response r = s.send("select ...", 0);
        final RawRowSource src = new RawRowSource(this, s, r.getColumns(), r.getWidth(), 0);
        assertEquals("1", src.next()[0]);
        final SQLException e = assertThrows(SQLException.class, src::next);
        assertEquals("22012", e.getSQLState());
        assertTrue(s.isIdle());
        assertFalse(s.send("values 1", 0).isError());
    }

    /**
     * Verifies that sending a new command while a result is still open buffers the remaining rows, which can
     * still be read afterwards.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void unconsumedResultIsReleasedBeforeNextCommand() throws SQLException {
        final Session s = session("DB2>\n\nX          \n-----------\n          1\n          2\n\n"
                + "  2 RECORD(S) SELECTED.\n\nDB2>\n" + OK);
        final Session.Response r = s.send("select ...", 0);
        final RawRowSource src = new RawRowSource(this, s, r.getColumns(), r.getWidth(), 0);
        assertFalse(s.send("values 1", 0).isError());
        // rows were buffered and are still readable
        assertEquals("1", src.next()[0]);
        assertEquals("2", src.next()[0]);
        assertNull(src.next());
    }

    /**
     * Verifies decoding of the typed (length-prefixed) row format, including rows spanning several lines, NULLs,
     * values containing newlines and empty strings.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
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
        assertEquals(16, r.getWidth());
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

    /**
     * Verifies that in unicode mode string values are decoded from UTF-16 hex.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void typedUnicodeHex() throws SQLException {
        // 'A' followed by the euro sign, as UTF-16 hex = 004120AC, length 8
        final Session s = session("DB2>\n\nL       \n--------\n8:004120\nAC      \n\n  1 RECORD(S) SELECTED.\n\nDB2>\n");
        final Session.Response r = s.send("select ...", 0);
        final List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        cols.add(ColumnInfo.fromDescriptor("S", ColumnInfo.D_VARCHAR, 10, 0, 0, true, 37, 0, "", "", ""));
        final TypedRowSource src = new TypedRowSource(this, s, cols, r.getWidth(), true);
        assertEquals("A\u20ac", src.next()[0]);
    }

    /**
     * Verifies decoding of numeric, special floating point, timestamp, time, boolean and binary values from
     * their text form.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
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

    /**
     * Verifies that a prompt not yet followed by a newline is recognized, as happens with the real utility,
     * which blocks reading stdin right after printing the prompt.
     *
     * @throws Exception
     *             if the piped streams fail or the driver reports an unexpected error
     */
    @Test
    void promptWithoutNewline() throws Exception {
        // the real utility prints "DB2>" and then blocks reading stdin; the newline only comes with the next output
        final java.io.PipedOutputStream feed = new java.io.PipedOutputStream();
        final java.io.PipedInputStream in = new java.io.PipedInputStream(feed, 65536);
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final Transport t = new Transport() {
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
        };
        feed.write(bytes("DB2>", s_ebcdic));
        feed.flush();
        final Session s = new Session(t, s_ebcdic, Tracer.create(false, null));
        s.start(5, true);
        feed.write(bytes("\nDB20000I  THE SQL COMMAND COMPLETED SUCCESSFULLY.\nDB2>", s_ebcdic));
        feed.flush();
        assertFalse(s.send("create table x (a int)", 0).isError());
        feed.write(bytes("\n\nA          \n-----------\n          1\n\n  1 RECORD(S) SELECTED.\n\nDB2>", s_ebcdic));
        feed.flush();
        final Session.Response r = s.send("values 1", 0);
        final RawRowSource src = new RawRowSource(this, s, r.getColumns(), r.getWidth(), 0);
        assertEquals("1", src.next()[0]);
        assertNull(src.next());
        assertTrue(s.isIdle());
    }

    /**
     * Verifies that when the utility's output turns out to be ASCII rather than EBCDIC, the session switches
     * to UTF-8.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void detectsAsciiOutput() throws SQLException {
        final Session s = new Session(new FakeTransport("DB2>\n" + OK, StandardCharsets.US_ASCII), s_ebcdic,
                Tracer.create(false, null));
        s.start(5, false);
        assertEquals(StandardCharsets.UTF_8, s.getCharset());
        assertFalse(s.send("values 1", 0).isError());
    }

    /**
     * Verifies that an error printed before the first prompt makes session start fail with that SQLSTATE.
     */
    @Test
    void connectFailureBeforePrompt() {
        final Session s = new Session(new FakeTransport("\n **** CLI ERROR *****\n         SQLSTATE: 08004\n"
                + "NATIVE ERROR CODE: -30082\nAuthorization failure on distributed database connection attempt. \n"
                + "DB2>\n", s_ebcdic), s_ebcdic, Tracer.create(false, null));
        final SQLException e = assertThrows(SQLException.class, () -> s.start(5, true));
        assertEquals("08004", e.getSQLState());
    }
}
