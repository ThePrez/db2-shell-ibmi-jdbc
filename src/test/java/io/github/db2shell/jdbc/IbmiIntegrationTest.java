package io.github.db2shell.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end tests against the real db2 utility. Only runs on IBM i (os.name OS/400). Creates a schema named
 * DB2SH + a random suffix for tests that need journaling, procedures or catalog entries, and drops it afterwards.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class IbmiIntegrationTest {

    private static Connection conn;
    private static String schema;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue("OS/400".equals(System.getProperty("os.name")), "IBM i only");
        // the JUnit console's class loader is not scanned by DriverManager's ServiceLoader on Java 8
        Class.forName(Db2ShellDriver.class.getName());
        conn = DriverManager.getConnection("jdbc:db2shell:");
        schema = "DB2SH" + Long.toString(System.nanoTime() % 100000L, 36).toUpperCase();
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (null == conn) {
            return;
        }
        try (Statement s = conn.createStatement()) {
            conn.setAutoCommit(true);
            s.execute("DROP SCHEMA " + schema + " CASCADE");
        } catch (final SQLException e) {
            System.err.println("could not drop " + schema + ": " + e);
        }
        conn.close();
    }

    private static Statement st() throws SQLException {
        return conn.createStatement();
    }

    private static Connection open(final String _props) throws SQLException {
        return DriverManager.getConnection("jdbc:db2shell:" + _props);
    }

    // =================================================================================== basics

    @Test
    void a01_simpleQueryAndMetadata() throws SQLException {
        try (ResultSet rs = st().executeQuery("select 1 as a, cast(null as int) as \"my col\", 'hello' c "
                + "from sysibm.sysdummy1")) {
            final ResultSetMetaData md = rs.getMetaData();
            assertEquals(3, md.getColumnCount());
            assertEquals("A", md.getColumnName(1));
            assertEquals("my col", md.getColumnLabel(2));
            assertEquals(Types.INTEGER, md.getColumnType(1));
            assertEquals(ResultSetMetaData.columnNullable, md.isNullable(2));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("A"));
            assertNull(rs.getObject(2));
            assertTrue(rs.wasNull());
            assertEquals("hello", rs.getString("c"));
            assertFalse(rs.next());
        }
    }

    @Test
    void a02_dataTypes() throws SQLException {
        final String sql = "select cast('ab' as char(4)) ch, cast('ab ' as varchar(10)) vc, "
                + "cast('g' as graphic(2) ccsid 1200) gr, cast('ng' as nvarchar(5)) nv, "
                + "smallint(-7) si, int(2147483647) i, bigint(-9223372036854775808) bi, "
                + "cast(-123.45 as decimal(9,2)) de, cast(99.5 as numeric(5,1)) nu, cast(1.25 as real) re, "
                + "cast(-1.2345678901234567e200 as double) db, cast('1.5E-20' as decfloat(34)) df, "
                + "date('2024-02-29') dt, time('23:59:58') tm, timestamp('2024-02-29-23.59.58.123456') ts, "
                + "cast(timestamp('2024-02-29-23.59.58.123456') as timestamp(12)) ts12, "
                + "cast(x'00C1FF' as varbinary(10)) vb, cast(x'C1C2' as char(2) for bit data) fb, "
                + "cast('clob text' as clob(1k)) cl, cast(x'0102' as blob(1k)) bl, "
                + "xmlparse(document '<a>x</a>') xm, true bo, false bf, cast(null as boolean) bn "
                + "from sysibm.sysdummy1";
        try (ResultSet rs = st().executeQuery(sql)) {
            final ResultSetMetaData md = rs.getMetaData();
            assertTrue(rs.next());
            assertEquals("ab  ", rs.getString("CH"));
            assertEquals(Types.CHAR, md.getColumnType(1));
            assertEquals("ab", rs.getString("VC").trim());
            assertEquals("g ", rs.getString("GR"));
            assertEquals("ng", rs.getString("NV"));
            assertEquals(Types.NVARCHAR, md.getColumnType(4));
            assertEquals(-7, rs.getShort("SI"));
            assertEquals(Integer.valueOf(-7), rs.getObject("SI"));
            assertEquals(2147483647, rs.getInt("I"));
            assertEquals(Long.MIN_VALUE, rs.getLong("BI"));
            assertEquals(new BigDecimal("-123.45"), rs.getBigDecimal("DE"));
            assertEquals(9, md.getPrecision(8));
            assertEquals(2, md.getScale(8));
            assertEquals(new BigDecimal("99.5"), rs.getBigDecimal("NU"));
            assertEquals(1.25f, rs.getFloat("RE"), 0);
            assertEquals(-1.2345678901234567e200, rs.getDouble("DB"), 1e185);
            assertEquals(0, new BigDecimal("1.5E-20").compareTo(rs.getBigDecimal("DF")));
            assertEquals(Date.valueOf("2024-02-29"), rs.getDate("DT"));
            assertEquals(Time.valueOf("23:59:58"), rs.getTime("TM"));
            assertEquals(Timestamp.valueOf("2024-02-29 23:59:58.123456"), rs.getTimestamp("TS"));
            assertEquals(Timestamp.valueOf("2024-02-29 23:59:58.123456"), rs.getTimestamp("TS12"));
            assertEquals(java.time.LocalDateTime.of(2024, 2, 29, 23, 59, 58, 123456000),
                    rs.getObject("TS", java.time.LocalDateTime.class));
            assertArrayEquals(new byte[] { 0, (byte) 0xC1, (byte) 0xFF }, rs.getBytes("VB"));
            assertEquals(Types.VARBINARY, md.getColumnType(17));
            assertArrayEquals(new byte[] { (byte) 0xC1, (byte) 0xC2 }, rs.getBytes("FB"));
            assertEquals("clob text", rs.getString("CL"));
            assertTrue(rs.getObject("CL") instanceof Clob);
            assertArrayEquals(new byte[] { 1, 2 }, rs.getBlob("BL").getBytes(1, 2));
            assertEquals("<a>x</a>", rs.getSQLXML("XM").getString());
            assertTrue(rs.getBoolean("BO"));
            assertEquals(Boolean.FALSE, rs.getObject("BF"));
            assertNull(rs.getObject("BN"));
        }
    }

    @Test
    void a03_tricksyStrings() throws SQLException {
        try (ResultSet rs = st().executeQuery("values ('-', cast(null as varchar(5)), 'a' concat x'25' concat 'b', "
                + "'', '   ', 'x%y', 'it''s', 'DB2>')")) {
            assertTrue(rs.next());
            assertEquals("-", rs.getString(1));
            assertNull(rs.getString(2));
            assertEquals("a\nb", rs.getString(3));
            assertEquals("", rs.getString(4));
            assertEquals("   ", rs.getString(5));
            assertEquals("x%y", rs.getString(6));
            assertEquals("it's", rs.getString(7));
            assertEquals("DB2>", rs.getString(8));
        }
    }

    @Test
    void a04_newlineLiteralsAndComments() throws SQLException {
        try (ResultSet rs = st().executeQuery("select -- a comment\n 'line1\nline2' as v /* block */\n"
                + "from sysibm.sysdummy1;")) {
            assertTrue(rs.next());
            assertEquals("line1\nline2", rs.getString(1));
        }
    }

    @Test
    void a05_wideRowsAndLobs() throws SQLException {
        try (ResultSet rs = st().executeQuery("select repeat('x', 10000) a, cast(repeat('y', 30000) as varchar(30000)) b, "
                + "repeat(cast('z' as clob(200k)), 100000) c, repeat(cast(x'AB' as blob(100k)), 50000) d, "
                + "cast(repeat('w', 20000) as varchar(20000)) e from sysibm.sysdummy1")) {
            assertTrue(rs.next());
            assertEquals(10000, rs.getString(1).length());
            assertEquals(30000, rs.getString(2).length());
            assertTrue(rs.getString(2).matches("y+"));
            assertEquals(100000, rs.getString(3).length());
            final byte[] b = rs.getBytes(4);
            assertEquals(50000, b.length);
            assertEquals((byte) 0xAB, b[49999]);
            assertEquals(20000, rs.getString(5).length());
        }
    }

    @Test
    void a06_unicode() throws SQLException {
        final String euro = "€ é 中文";
        // literals the job CCSID cannot represent are sent as UTF-16; values round trip in unicode mode
        try (Connection c = open(";unicode=true"); PreparedStatement ps = c.prepareStatement(
                "select cast(? as vargraphic(20) ccsid 1200) a, cast('" + euro + "' as nvarchar(20)) b "
                        + "from sysibm.sysdummy1")) {
            ps.setString(1, euro);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(euro, rs.getString(1));
                assertEquals(euro, rs.getString(2));
            }
        }
        // without unicode mode, characters outside the job CCSID are substituted, but others survive
        try (ResultSet rs = st().executeQuery("values cast('été' as nvarchar(10))")) {
            assertTrue(rs.next());
            assertEquals("été", rs.getString(1));
        }
    }

    @Test
    void a07_queryShapes() throws SQLException {
        try (ResultSet rs = st().executeQuery("with c(n) as (values 3, 1, 2) select n from c order by n desc")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
        try (ResultSet rs = st().executeQuery("select ibmreqd from sysibm.sysdummy1 with ur")) {
            assertTrue(rs.next());
            assertEquals("Y", rs.getString(1));
        }
        try (ResultSet rs = st().executeQuery("(select 1 from sysibm.sysdummy1) union all (select 2 from sysibm.sysdummy1)"
                + " order by 1 fetch first 1 rows only")) {
            assertTrue(rs.next());
            assertFalse(rs.next());
        }
        try (ResultSet rs = st().executeQuery("select * from sysibm.sysdummy1 where 1 = 0")) {
            assertFalse(rs.next());
            assertEquals(1, rs.getMetaData().getColumnCount());
        }
        try (ResultSet rs = st().executeQuery("select {fn ucase('abc')}, {d '2024-01-02'} from sysibm.sysdummy1")) {
            assertTrue(rs.next());
            assertEquals("ABC", rs.getString(1));
            assertEquals(Date.valueOf("2024-01-02"), rs.getDate(2));
        }
    }

    @Test
    void a08_longStatement() throws SQLException {
        // well over the utility's ~32K input line limit: the driver splits the statement across input lines
        final StringBuilder lit = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            lit.append((char) ('a' + i % 26));
        }
        final StringBuilder sb = new StringBuilder("select length('" + lit + "'), count(*) from sysibm.sysdummy1 "
                + "where 1 in (");
        for (int i = 0; i < 2000; i++) {
            sb.append(i > 0 ? ", " : "").append(i + 1);
        }
        sb.append(")");
        assertTrue(sb.length() > 70000);
        try (ResultSet rs = st().executeQuery(sb.toString())) {
            assertTrue(rs.next());
            assertEquals(60000, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
        }
        try (PreparedStatement ps = conn.prepareStatement("values cast(? as clob(1m))")) {
            ps.setString(1, lit.toString() + lit);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(lit.toString() + lit, rs.getString(1));
            }
        }
    }

    @Test
    void a09_manyRows() throws SQLException {
        final long t0 = System.currentTimeMillis();
        int n = 0;
        long sum = 0;
        try (ResultSet rs = st().executeQuery("with r(k) as (values 1 union all select k + 1 from r where k < 20000) "
                + "select k, 'name ' concat k, current date from r")) {
            while (rs.next()) {
                n++;
                sum += rs.getInt(1);
            }
        }
        assertEquals(20000, n);
        assertEquals(20000L * 20001 / 2, sum);
        System.out.println("20000 rows in " + (System.currentTimeMillis() - t0) + " ms");
    }

    // =================================================================================== errors

    @Test
    void b01_errors() throws SQLException {
        final SQLException e1 = assertThrows(SQLSyntaxErrorException.class, () -> st().executeQuery("selec 1"));
        assertEquals("42601", e1.getSQLState());
        final SQLException e2 = assertThrows(SQLException.class,
                () -> st().executeQuery("select * from qtemp.does_not_exist"));
        assertEquals("42704", e2.getSQLState());
        assertEquals(-204, e2.getErrorCode());
        // errors raised while fetching
        // the utility does not report fetch-time mapping errors; the row comes back as NULLs with a warning
        try (ResultSet rs = st().executeQuery("select x, 10 / (x - 2) from (values 4, 3, 2, 1) t(x)")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals(10, rs.getInt(2));
            assertTrue(rs.next());
            assertNull(rs.getObject(1));
            assertNull(rs.getObject(2));
            assertTrue(rs.next());
            assertEquals(-10, rs.getInt(2));
            assertFalse(rs.next());
            assertNotNull(rs.getWarnings());
            assertEquals("01519", rs.getWarnings().getSQLState());
        }
        // errors raised while fetching are reported
        try (ResultSet rs = st().executeQuery("select cast(raise_error('70001', 'boom') as int) from (values 1, 2) t(x)")) {
            final SQLException e3 = assertThrows(SQLException.class, rs::next);
            assertEquals("70001", e3.getSQLState());
        } catch (final SQLException e) {
            assertEquals("70001", e.getSQLState());
        }
        // still usable afterwards
        try (ResultSet rs = st().executeQuery("values 42")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }
    }

    // =================================================================================== updates

    @Test
    void c01_updateCountsAndPrepared() throws SQLException {
        final Statement s = st();
        s.execute("create table " + schema + ".t1 (id int not null primary key, name varchar(50), "
                + "amt decimal(11,2), ts timestamp, bin varbinary(20), flag boolean, d date)");
        assertEquals(3, s.executeUpdate("insert into " + schema + ".t1 (id, name) values (1, 'a'), (2, 'b'), (3, 'c')"));
        assertEquals(2, s.executeUpdate("update " + schema + ".t1 set name = 'z' where id >= 2"));
        assertEquals(0, s.executeUpdate("update " + schema + ".t1 set name = 'q' where id > 100"));
        assertEquals(1, s.executeUpdate("delete from " + schema + ".t1 where id = 1"));
        try (PreparedStatement ps = conn.prepareStatement("insert into " + schema + ".t1 values (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, 10);
            ps.setString(2, "O'Brien\nline2 50%");
            ps.setBigDecimal(3, new BigDecimal("-12345.67"));
            ps.setTimestamp(4, Timestamp.valueOf("2024-05-06 07:08:09.123456"));
            ps.setBytes(5, new byte[] { 0, 1, 2, (byte) 0xff });
            ps.setBoolean(6, true);
            ps.setDate(7, Date.valueOf("1999-12-31"));
            assertEquals(1, ps.executeUpdate());
            ps.setInt(1, 11);
            ps.setNull(2, Types.VARCHAR);
            ps.setNull(3, Types.DECIMAL);
            ps.setNull(4, Types.TIMESTAMP);
            ps.setNull(5, Types.VARBINARY);
            ps.setNull(6, Types.BOOLEAN);
            ps.setObject(7, java.time.LocalDate.of(2000, 1, 1));
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("select * from " + schema + ".t1 where id = ?")) {
            ps.setInt(1, 10);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("O'Brien\nline2 50%", rs.getString("NAME"));
                assertEquals(new BigDecimal("-12345.67"), rs.getBigDecimal("AMT"));
                assertEquals(Timestamp.valueOf("2024-05-06 07:08:09.123456"), rs.getTimestamp("TS"));
                assertArrayEquals(new byte[] { 0, 1, 2, (byte) 0xff }, rs.getBytes("BIN"));
                assertTrue(rs.getBoolean("FLAG"));
                assertEquals(Date.valueOf("1999-12-31"), rs.getDate("D"));
            }
            ps.setInt(1, 11);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("NAME"));
                assertNull(rs.getObject("FLAG"));
                assertEquals(Date.valueOf("2000-01-01"), rs.getDate("D"));
            }
        }
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> s.executeUpdate("insert into " + schema + ".t1 (id) values 10"));
    }

    @Test
    void c02_batch() throws SQLException {
        final Statement s = st();
        s.execute("create table " + schema + ".t2 (id int not null primary key)");
        try (PreparedStatement ps = conn.prepareStatement("insert into " + schema + ".t2 values ?")) {
            for (int i = 0; i < 5; i++) {
                ps.setInt(1, i);
                ps.addBatch();
            }
            assertArrayEquals(new int[] { 1, 1, 1, 1, 1 }, ps.executeBatch());
            ps.setInt(1, 100);
            ps.addBatch();
            ps.setInt(1, 1); // duplicate
            ps.addBatch();
            final BatchUpdateException bue = assertThrows(BatchUpdateException.class, ps::executeBatch);
            assertEquals(1, bue.getUpdateCounts().length);
        }
        s.addBatch("insert into " + schema + ".t2 values 200");
        s.addBatch("delete from " + schema + ".t2 where id < 3");
        assertArrayEquals(new int[] { 1, 3 }, s.executeBatch());
    }

    @Test
    void c03_generatedKeys() throws SQLException {
        final Statement s = st();
        s.execute("create table " + schema + ".gk (id int generated always as identity, name varchar(10))");
        assertEquals(2, s.executeUpdate("insert into " + schema + ".gk (name) values ('a'), ('b')",
                Statement.RETURN_GENERATED_KEYS));
        try (ResultSet keys = s.getGeneratedKeys()) {
            assertTrue(keys.next());
            assertEquals(1, keys.getInt(1));
            assertTrue(keys.next());
            assertEquals(2, keys.getInt(1));
        }
        try (PreparedStatement ps = conn.prepareStatement("insert into " + schema + ".gk (name) values (?)",
                new String[] { "ID" })) {
            ps.setString(1, "c");
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(3, keys.getInt(1));
            }
        }
    }

    // =================================================================================== transactions

    @Test
    void d01_transactions() throws SQLException {
        final Statement s = st();
        s.execute("create table " + schema + ".tx (id int)");
        conn.setAutoCommit(false);
        try {
            s.executeUpdate("insert into " + schema + ".tx values 1");
            conn.rollback();
            assertEquals(0, count("tx"));
            s.executeUpdate("insert into " + schema + ".tx values 2");
            conn.commit();
            s.executeUpdate("insert into " + schema + ".tx values 3");
            final Savepoint sp = conn.setSavepoint("SP1");
            s.executeUpdate("insert into " + schema + ".tx values 4");
            conn.rollback(sp);
            conn.commit();
            assertEquals(2, count("tx"));
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            s.executeUpdate("delete from " + schema + ".tx");
            conn.rollback();
            assertEquals(2, count("tx"));
        } finally {
            conn.setAutoCommit(true);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        // auto-commit: immediately visible to another connection
        st().executeUpdate("insert into " + schema + ".tx values 5");
        try (Connection c2 = open(""); ResultSet rs = c2.createStatement().executeQuery(
                "select count(*) from " + schema + ".tx")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
        }
    }

    private static int count(final String _table) throws SQLException {
        try (ResultSet rs = st().executeQuery("select count(*) from " + schema + "." + _table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // =================================================================================== procedures

    @Test
    void e01_callable() throws SQLException {
        final Statement s = st();
        s.execute("create procedure " + schema + ".p1 (in a int, out b varchar(20), inout c decimal(9,2)) "
                + "language sql begin set b = 'got ' concat a; set c = c * 2; end");
        try (CallableStatement cs = conn.prepareCall("{call " + schema + ".p1(?, ?, ?)}")) {
            cs.setInt(1, 21);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.setBigDecimal(3, new BigDecimal("1.25"));
            cs.registerOutParameter(3, Types.DECIMAL, 2);
            assertFalse(cs.execute());
            assertEquals("got 21", cs.getString(2));
            assertEquals(new BigDecimal("2.50"), cs.getBigDecimal(3));
        }
        s.execute("create function " + schema + ".f1 (x int) returns int language sql return x * 3");
        try (CallableStatement cs = conn.prepareCall("{? = call " + schema + ".f1(?)}")) {
            cs.registerOutParameter(1, Types.INTEGER);
            cs.setInt(2, 14);
            cs.execute();
            assertEquals(42, cs.getInt(1));
        }
        // a CALL without output parameters
        s.execute("create table " + schema + ".pt (v int)");
        s.execute("create procedure " + schema + ".p2 (in v int) language sql insert into " + schema + ".pt values v");
        try (CallableStatement cs = conn.prepareCall("call " + schema + ".p2(?)")) {
            cs.setInt(1, 7);
            cs.execute();
        }
        assertEquals(1, count("pt"));
    }

    // =================================================================================== metadata

    @Test
    void f01_databaseMetaData() throws SQLException {
        st().execute("create table " + schema + ".parent (id int not null primary key, name varchar(30) not null)");
        st().execute("create table " + schema + ".child (cid int not null primary key, pid int references "
                + schema + ".parent (id))");
        final DatabaseMetaData md = conn.getMetaData();
        assertEquals("DB2 UDB for AS/400", md.getDatabaseProductName());
        assertTrue(md.getDatabaseMajorVersion() >= 7);
        assertNotNull(md.getUserName());
        try (ResultSet rs = md.getTables(null, schema, "%", new String[] { "TABLE" })) {
            int n = 0;
            while (rs.next()) {
                assertEquals(schema, rs.getString("TABLE_SCHEM"));
                n++;
            }
            assertTrue(n >= 2);
        }
        try (ResultSet rs = md.getColumns(null, schema, "PARENT", "%")) {
            assertTrue(rs.next());
            assertEquals("ID", rs.getString("COLUMN_NAME"));
            assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));
            assertEquals("NO", rs.getString("IS_NULLABLE"));
            assertTrue(rs.next());
            assertEquals("NAME", rs.getString("COLUMN_NAME"));
            assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));
            assertEquals(30, rs.getInt("COLUMN_SIZE"));
            assertEquals(2, rs.getInt("ORDINAL_POSITION"));
        }
        try (ResultSet rs = md.getPrimaryKeys(null, schema, "PARENT")) {
            assertTrue(rs.next());
            assertEquals("ID", rs.getString("COLUMN_NAME"));
        }
        try (ResultSet rs = md.getImportedKeys(null, schema, "CHILD")) {
            assertTrue(rs.next());
            assertEquals("PARENT", rs.getString("PKTABLE_NAME"));
            assertEquals("PID", rs.getString("FKCOLUMN_NAME"));
        }
        try (ResultSet rs = md.getSchemas(null, schema)) {
            assertTrue(rs.next());
        }
        try (ResultSet rs = md.getTypeInfo()) {
            assertTrue(rs.next());
        }
        try (ResultSet rs = md.getTableTypes()) {
            assertTrue(rs.next());
        }
        try (ResultSet rs = md.getIndexInfo(null, schema, "PARENT", false, true)) {
            assertNotNull(rs.getMetaData());
        }
        try (ResultSet rs = md.getProcedures(null, "QSYS2", "QCMDEXC")) {
            assertTrue(rs.next());
        }
    }

    // =================================================================================== connection features

    @Test
    void g01_schemaAndSession() throws SQLException {
        try (Connection c = open(";schema=" + schema)) {
            assertEquals(schema, c.getSchema());
            c.createStatement().execute("create table t3 (a int)");
            c.setSchema("QSYS2");
            assertEquals("QSYS2", c.getSchema());
            try (ResultSet rs = c.createStatement().executeQuery("select count(*) from systables fetch first 1 row only")) {
                assertTrue(rs.next());
            }
            // QTEMP persists for the life of the connection
            c.createStatement().execute("create table qtemp.keep (a int)");
            c.createStatement().execute("insert into qtemp.keep values 1");
            try (ResultSet rs = c.createStatement().executeQuery("select a from qtemp.keep")) {
                assertTrue(rs.next());
            }
            assertTrue(c.isValid(5));
        }
    }

    @Test
    void g02_interleavedResultSetsScrollingAndLimits() throws SQLException {
        final ResultSet a = st().executeQuery("with r(k) as (values 1 union all select k + 1 from r where k < 50) "
                + "select k from r");
        final ResultSet b = st().executeQuery("values 'x', 'y'");
        assertTrue(a.next());
        assertEquals(1, a.getInt(1));
        assertTrue(b.next());
        assertEquals("x", b.getString(1));
        assertTrue(a.next());
        assertEquals(2, a.getInt(1));
        a.close();
        b.close();

        final Statement sc = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        try (ResultSet rs = sc.executeQuery("values 1, 2, 3")) {
            assertTrue(rs.last());
            assertEquals(3, rs.getRow());
            assertTrue(rs.previous());
            assertEquals(2, rs.getInt(1));
            assertTrue(rs.absolute(1));
            assertEquals(1, rs.getInt(1));
        }
        final Statement lim = st();
        lim.setMaxRows(3);
        try (ResultSet rs = lim.executeQuery("with r(k) as (values 1 union all select k + 1 from r where k < 100) "
                + "select k from r")) {
            int n = 0;
            while (rs.next()) {
                n++;
            }
            assertEquals(3, n);
        }
    }

    @Test
    void g03_timeoutAndRecovery() throws SQLException {
        try (Connection c = open("")) {
            c.createStatement().execute("create table qtemp.marker (a int)");
            final String job = ((Db2ShellConnection) c).getJobName();
            final Statement s = c.createStatement();
            s.setQueryTimeout(2);
            // a long-running query is cancelled with QSYS2.CANCEL_SQL; the session (and QTEMP) survive
            long t0 = System.currentTimeMillis();
            assertThrows(SQLTimeoutException.class,
                    () -> s.executeQuery("select count(*) from qsys2.syscolumns a, qsys2.syscolumns b, "
                            + "qsys2.syscolumns c").next());
            assertTrue(System.currentTimeMillis() - t0 < 10000, "took " + (System.currentTimeMillis() - t0));
            assertEquals(job, ((Db2ShellConnection) c).getJobName());
            try (ResultSet rs = c.createStatement().executeQuery("select count(*) from qtemp.marker")) {
                assertTrue(rs.next());
            }
            // a CL command cannot be cancelled: the session is abandoned and transparently restarted
            t0 = System.currentTimeMillis();
            assertThrows(SQLTimeoutException.class, () -> s.execute("call qsys2.qcmdexc('DLYJOB DLY(40)')"));
            assertTrue(System.currentTimeMillis() - t0 < 25000, "took " + (System.currentTimeMillis() - t0));
            try (ResultSet rs = c.createStatement().executeQuery("values 1")) {
                assertTrue(rs.next());
            }
            assertNotNull(c.getWarnings());
        }
    }

    @Test
    void g04_rawModeAndExplicitEncoding() throws SQLException {
        try (Connection c = open(";resultMode=raw;encoding=" + System.getProperty("os400.job.file.encoding", "Cp037"));
                ResultSet rs = c.createStatement().executeQuery("select 1 as a, 'b' as b, cast(null as int) n "
                        + "from sysibm.sysdummy1")) {
            assertEquals(Types.VARCHAR, rs.getMetaData().getColumnType(1));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("A"));
            assertEquals("b", rs.getString("B"));
            assertNull(rs.getString("N"));
        }
        try (Connection c = open(";ccsid=37"); ResultSet rs = c.createStatement().executeQuery("values 'ok'")) {
            assertTrue(rs.next());
            assertEquals("ok", rs.getString(1));
        }
    }

    @Test
    void g05_dataSourceAndProperties() throws SQLException {
        final Db2ShellDataSource ds = new Db2ShellDataSource();
        ds.setSchema("QSYS2");
        try (Connection c = ds.getConnection()) {
            assertEquals("QSYS2", c.getSchema());
        }
        final Properties p = new Properties();
        p.setProperty("initSql", "set path = system path, " + schema);
        try (Connection c = DriverManager.getConnection("jdbc:db2shell:", p);
                ResultSet rs = c.createStatement().executeQuery("values current path")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).contains(schema), rs.getString(1));
        }
        assertEquals(Arrays.asList("typed", "raw"),
                Arrays.asList(new Db2ShellDriver().getPropertyInfo("jdbc:db2shell:", null)[11].choices));
    }
}
