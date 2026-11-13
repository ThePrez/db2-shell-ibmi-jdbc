package io.github.db2shell.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;

import org.junit.jupiter.api.Test;

class SqlTextTest {

    private static final Charset s_ebcdic = Charset.forName("IBM037");

    private static SqlText p(final String _sql) throws SQLException {
        return SqlText.parse(_sql, s_ebcdic, true);
    }

    @Test
    void flattensWhitespaceAndStripsComments() throws SQLException {
        final SqlText t = p("select a, -- the a column\n  b /* block\ncomment */ from\r\n\tt;  ");
        assertEquals("select a, b from t", t.getSql().replaceAll(" +", " "));
        assertEquals("SELECT", t.getKeyword());
        assertTrue(t.isQuery());
    }

    @Test
    void keepsCommentMarkersInsideLiterals() throws SQLException {
        assertEquals("values '--x', '/*y*/'", p("values '--x', '/*y*/'").getSql());
    }

    @Test
    void rewritesNewlinesInLiterals() throws SQLException {
        assertEquals("values ('a' CONCAT CHR(10) CONCAT 'b')", p("values 'a\nb'").getSql());
        assertEquals("values (CHR(13) CONCAT CHR(10))", p("values '\r\n'").getSql());
    }

    @Test
    void rewritesUnencodableLiteralsAsUnicode() throws SQLException {
        // the euro sign is not in CCSID 37
        assertEquals("values UX'20AC0041'", p("values '\u20acA'").getSql());
    }

    @Test
    void leavesPrefixedLiteralsAlone() throws SQLException {
        assertEquals("values X'C1C2', BX'00', UX'0041', G'x'", p("values X'C1C2', BX'00', UX'0041', G'x'").getSql());
    }

    @Test
    void doubledQuotes() throws SQLException {
        assertEquals("values 'it''s'", p("values 'it''s'").getSql());
    }

    @Test
    void findsParameterMarkers() throws SQLException {
        final SqlText t = p("select * from t where a = ? and b = '?' and \"c?\" = ? -- ?\n");
        assertEquals(2, t.getParameterCount());
        assertEquals("select * from t where a = 1 and b = '?' and \"c?\" = 'x'", t.bind(new String[] { "1", "'x'" }));
    }

    @Test
    void rejectsTerminatorOutsideLiterals() {
        assertThrows(SQLSyntaxErrorException.class, () -> p("select 5 % 2 from t"));
    }

    @Test
    void allowsTerminatorInsideLiteralsAndIdentifiers() throws SQLException {
        assertEquals("select a as \"x%\" from t where b like 'a%'", p("select a as \"x%\" from t where b like 'a%'").getSql());
    }

    /**
     * Verifies that JDBC escape sequences for dates, timestamps, functions and procedure calls are translated
     * to native SQL.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void jdbcEscapes() throws SQLException {
        assertEquals("select DATE( '2024-01-02'), TIMESTAMP( '2024-01-02 10:00:00'), ucase(x) from t",
                p("select {d '2024-01-02'}, {ts '2024-01-02 10:00:00'}, {fn ucase(x)} from t").getSql()
                        .replaceAll("  +", " "));
        assertEquals("CALL  myproc(?)", p("{call myproc(?)}").getSql());
        assertEquals("CALL", p("{call myproc(?)}").getKeyword());
    }

    @Test
    void unterminatedLiteral() {
        assertThrows(SQLSyntaxErrorException.class, () -> p("values 'abc"));
    }

    @Test
    void splitsCtePrefix() {
        final String[] parts = SqlText.splitQuery("WITH a (x) AS (VALUES 1), \"b\" AS (SELECT * FROM a) SELECT * FROM b");
        assertEquals("WITH a (x) AS (VALUES 1), \"b\" AS (SELECT * FROM a)", parts[0]);
        assertEquals("SELECT * FROM b", parts[1]);
        assertEquals("", parts[2]);
    }

    /**
     * Verifies that trailing isolation, read-only and optimize clauses are split off a query, but that the
     * same words inside literals or subqueries, and a FETCH FIRST clause, are left in the query body.
     */
    @Test
    void splitsTailClauses() {
        String[] parts = SqlText.splitQuery("select * from t where x = 'with ur' order by 1 with ur");
        assertEquals("select * from t where x = 'with ur' order by 1", parts[1]);
        assertEquals("with ur", parts[2]);
        parts = SqlText.splitQuery("select * from t for read only optimize for 10 rows");
        assertEquals("select * from t", parts[1]);
        assertEquals("for read only optimize for 10 rows", parts[2]);
        parts = SqlText.splitQuery("select * from (select a from t) x");
        assertEquals("select * from (select a from t) x", parts[1]);
        assertEquals("", parts[2]);
        parts = SqlText.splitQuery("select * from t fetch first 5 rows only");
        assertEquals("select * from t fetch first 5 rows only", parts[1]);
    }

    /**
     * Verifies statement classification: an INSERT in parentheses is not a query, while parenthesized
     * unions and VALUES are.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void notAQuery() throws SQLException {
        final SqlText t = p("  (insert into t values 1)");
        assertEquals("INSERT", t.getKeyword());
        assertFalse(t.isQuery());
        assertTrue(p("(select 1 from t) union (select 2 from t)").isQuery());
        assertTrue(p("VALUES 1").isQuery());
    }

    /**
     * Verifies that a long statement is split into input lines no longer than the limit, never inside a
     * string literal, so that rejoining the lines with spaces gives back the statement.
     */
    @Test
    void splitsLongInputLines() {
        final StringBuilder sb = new StringBuilder("values ");
        for (int i = 0; i < 2000; i++) {
            sb.append("'abc def' CONCAT ");
        }
        sb.append("'end'");
        final List<String> lines = Session.splitLines(sb.toString(), 1000);
        assertTrue(lines.size() > 30);
        final StringBuilder joined = new StringBuilder();
        for (final String l : lines) {
            assertTrue(l.length() <= 1001, "line too long: " + l.length());
            joined.append(joined.length() > 0 ? " " : "").append(l);
        }
        assertEquals(sb.toString(), joined.toString());
        for (final String l : lines) {
            // never split inside a literal
            assertEquals(0, l.replaceAll("[^']", "").length() % 2);
        }
    }

    /**
     * Verifies that long string literals are split into concatenated pieces, and that very long ones are
     * additionally wrapped in a CAST.
     *
     * @throws SQLException
     *             if the driver reports an unexpected error
     */
    @Test
    void longLiteralsAreSplit() throws SQLException {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append('x');
        }
        final String lit = SqlLiterals.string(sb.toString(), s_ebcdic);
        assertTrue(lit.startsWith("('xxx"));
        assertEquals(3, lit.split(" CONCAT ").length);
        final String big = SqlLiterals.string(sb.toString() + sb + sb + sb, s_ebcdic);
        assertTrue(big.startsWith("(CAST('xxx"), big.substring(0, 20));
    }

    @Test
    void hexRoundTrip() {
        final byte[] b = { 0, 1, (byte) 0xff, 0x7f, (byte) 0x80 };
        assertEquals("0001FF7F80", SqlLiterals.hex(b, 0, b.length));
        assertArrayEquals(b, SqlLiterals.unhex("0001FF7F80"));
    }
}
