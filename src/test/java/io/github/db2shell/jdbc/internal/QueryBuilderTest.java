package io.github.db2shell.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.db2shell.jdbc.DriverConfig;

class QueryBuilderTest {

    /**
     * Collect column descriptions into a mutable list.
     *
     * @param _c
     *            the columns
     * @return a new list holding the columns in order
     */
    private static List<ColumnInfo> cols(final ColumnInfo... _c) {
        final List<ColumnInfo> l = new ArrayList<ColumnInfo>();
        for (final ColumnInfo c : _c) {
            l.add(c);
        }
        return l;
    }

    /**
     * Verifies that a result whose rows fit in the line width is streamed as one line per row, with no
     * number table needed.
     */
    @Test
    void narrowRowsUseOneLine() {
        final QueryBuilder.StreamQuery q = QueryBuilder.stream("select id from t",
                cols(ColumnInfo.fromDescriptor("ID", ColumnInfo.D_INTEGER, 4, 9, 0, false, 0, 0, "", "", "")), 0, 128,
                false, 1000, true);
        assertEquals(0, q.getNumsNeeded());
        assertTrue(q.getSql().startsWith("SELECT CAST(COALESCE(VARCHAR(LENGTH(VARCHAR(J1)))"), q.getSql());
        assertTrue(q.getSql().endsWith("FROM (select id from t) AS X(J1) ORDER BY ORDER OF X"), q.getSql());
    }

    /**
     * Verifies that rows wider than the line width are split across several lines using a number table, that
     * the CTE prefix and isolation clause are kept outside the wrapped query, and that the row limit is applied.
     */
    @Test
    void wideRowsAreSplitIntoLines() {
        final QueryBuilder.StreamQuery q = QueryBuilder.stream(
                "with c as (select * from t) select * from c with ur",
                cols(ColumnInfo.fromDescriptor("A", ColumnInfo.D_VARCHAR, 2000, 0, 0, true, 37, 0, "", "", ""),
                        ColumnInfo.fromDescriptor("B", ColumnInfo.D_BLOB, 1000000, 0, 0, true, 65535, 0, "", "", "")),
                10, 128, false, 50000, true);
        assertTrue(q.getNumsNeeded() > 700, "nums " + q.getNumsNeeded());
        assertTrue(q.getSql().startsWith("with c as (select * from t) SELECT Z.L FROM (SELECT CAST('' AS CLOB("), q.getSql());
        assertTrue(q.getSql().contains("FROM (select * from c) AS X(J1, J2) ORDER BY ORDER OF X FETCH FIRST 10 ROWS ONLY"),
                q.getSql());
        assertTrue(q.getSql().contains("HEX(SUBSTRING(J2, 16001, 16000))"), q.getSql());
        assertTrue(q.getSql().endsWith("ORDER BY ORDER OF Y, Z.K with ur"), q.getSql());
    }

    @Test
    void unicodeModeHexEncodesStrings() {
        final QueryBuilder.StreamQuery q = QueryBuilder.stream("select s from t",
                cols(ColumnInfo.fromDescriptor("S", ColumnInfo.D_VARCHAR, 10, 0, 0, true, 1208, 0, "", "", "")), 0, 128,
                true, 1000, true);
        assertTrue(q.getSql().contains("HEX(CAST(J1 AS VARGRAPHIC(10) CCSID 1200))"), q.getSql());
    }

    @Test
    void describeEmbedsQueryAsLiteral() {
        final String d = QueryBuilder.describe("select 'a' from t");
        assertTrue(d.contains("SET JDBC_SQL = 'select ''a'' from t';"), d);
        assertTrue(d.startsWith("BEGIN ") && d.endsWith(" END"));
    }

    /**
     * Verifies the mapping from db2 descriptor types and CCSIDs to JDBC types, and decimal precision and scale.
     */
    @Test
    void columnTypeMapping() {
        assertEquals(Types.VARBINARY,
                ColumnInfo.fromDescriptor("X", ColumnInfo.D_VARCHAR, 4, 0, 0, true, 65535, 0, "", "", "").getJdbcType());
        assertEquals(Types.NVARCHAR,
                ColumnInfo.fromDescriptor("X", ColumnInfo.D_VARGRAPHIC, 4, 0, 0, true, 1200, 0, "", "", "").getJdbcType());
        assertEquals(Types.TIMESTAMP,
                ColumnInfo.fromDescriptor("X", ColumnInfo.D_DATETIME, 26, 6, 0, true, 37, 3, "", "", "").getJdbcType());
        assertEquals(Types.BOOLEAN,
                ColumnInfo.fromDescriptor("X", ColumnInfo.D_BOOLEAN, 1, 0, 0, true, 65535, 0, "", "", "").getJdbcType());
        final ColumnInfo dec = ColumnInfo.fromDescriptor("X", ColumnInfo.D_DECIMAL, 3, 5, 2, true, 0, 0, "", "", "");
        assertEquals(5, dec.getPrecision());
        assertEquals(2, dec.getScale());
    }

    /**
     * Verifies how Java parameter values are rendered as SQL literals, including typed NULLs and target type
     * conversions.
     *
     * @throws SQLException
     *             if a value cannot be rendered as a literal
     */
    @Test
    void literals() throws SQLException {
        final Charset cs = Charset.forName("IBM037");
        assertEquals("'O''Brien'", Params.literal("O'Brien", Params.UNSPECIFIED, -1, cs, true));
        assertEquals("1.5E0", Params.literal(1.5d, Params.UNSPECIFIED, -1, cs, true));
        assertEquals("12.30", Params.literal(new java.math.BigDecimal("12.30"), Params.UNSPECIFIED, -1, cs, true));
        assertEquals("TRUE", Params.literal(true, Params.UNSPECIFIED, -1, cs, true));
        assertEquals("1", Params.literal(true, Params.UNSPECIFIED, -1, cs, false));
        assertEquals("BX'00FF'", Params.literal(new byte[] { 0, -1 }, Params.UNSPECIFIED, -1, cs, true));
        assertEquals("DATE('2024-01-02')", Params.literal(java.sql.Date.valueOf("2024-01-02"), Params.UNSPECIFIED, -1,
                cs, true));
        assertEquals("TIMESTAMP('2024-01-02-03.04.05.123456789')",
                Params.literal(java.sql.Timestamp.valueOf("2024-01-02 03:04:05.123456789"), Params.UNSPECIFIED, -1, cs,
                        true));
        assertEquals("CAST(NULL AS INTEGER)", Params.literal(null, Types.INTEGER, -1, cs, true));
        assertEquals("42", Params.literal("42", Types.INTEGER, -1, cs, true));
        assertEquals("'2024-01-02'", Params.literal(java.sql.Date.valueOf("2024-01-02"), Types.VARCHAR, -1, cs, true));
    }

    /**
     * Verifies parsing of the JDBC URL and properties into a driver configuration, and the db2 command line
     * built from it.
     *
     * @throws SQLException
     *             if the URL cannot be parsed
     */
    @Test
    void urlParsing() throws SQLException {
        final java.util.Properties p = new java.util.Properties();
        p.setProperty("password", "pw");
        DriverConfig c = DriverConfig.parse("jdbc:db2shell://MYRDB;user=bob;Schema=LIB1;libraries=A,B?trace=true", p);
        assertEquals("MYRDB", c.getRdb());
        assertEquals("bob", c.getUser());
        assertEquals("pw", c.getPassword());
        assertEquals("LIB1", c.getSchema());
        assertEquals(2, c.getLibraries().size());
        assertTrue(c.isTrace());
        c = DriverConfig.parse("jdbc:db2shell:;ccsid=273", null);
        assertTrue(c.getCharset().name().contains("273"), c.getCharset().name());
        c = DriverConfig.parse("jdbc:db2shell:;user=x", null);
        assertEquals("*LOCAL", c.getRdb());
        assertEquals("'/usr/bin/db2' -i -T% -r '*LOCAL' -u 'x' -p ''", ProcessTransport.buildCommand(c));
    }
}
