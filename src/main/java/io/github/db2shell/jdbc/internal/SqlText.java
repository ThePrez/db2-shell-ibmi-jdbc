package io.github.db2shell.jdbc.internal;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SQL statement normalized for the db2 utility: one line, no comments, newlines inside literals rewritten,
 * JDBC escapes translated, parameter markers located, and (for queries) the CTE prefix and trailing
 * isolation/locking clauses identified so the statement can be wrapped.
 */
public final class SqlText {

    /** Trailing clauses of a select-statement that cannot appear inside a nested table expression. */
    private static final Pattern TAIL = Pattern.compile("(?is)^(.*?)\\s+((?:(?:FOR\\s+(?:READ|FETCH)\\s+ONLY)"
            + "|(?:FOR\\s+UPDATE(?:\\s+OF\\s+[\\w\\s,\"$#@.]+?)?)"
            + "|(?:WITH\\s+(?:NC|UR|CS|RS|RR)(?:\\s+USE\\s+AND\\s+KEEP\\s+(?:SHARE|UPDATE|EXCLUSIVE)\\s+LOCKS|\\s+KEEP\\s+LOCKS)?)"
            + "|(?:OPTIMIZE\\s+FOR\\s+\\d+\\s+ROWS?)|(?:SKIP\\s+LOCKED\\s+DATA)|(?:WAIT\\s+FOR\\s+OUTCOME)"
            + "|(?:USE\\s+CURRENTLY\\s+COMMITTED))(?:\\s+(?:(?:FOR\\s+(?:READ|FETCH)\\s+ONLY)"
            + "|(?:FOR\\s+UPDATE(?:\\s+OF\\s+[\\w\\s,\"$#@.]+?)?)"
            + "|(?:WITH\\s+(?:NC|UR|CS|RS|RR)(?:\\s+USE\\s+AND\\s+KEEP\\s+(?:SHARE|UPDATE|EXCLUSIVE)\\s+LOCKS|\\s+KEEP\\s+LOCKS)?)"
            + "|(?:OPTIMIZE\\s+FOR\\s+\\d+\\s+ROWS?)|(?:SKIP\\s+LOCKED\\s+DATA)|(?:WAIT\\s+FOR\\s+OUTCOME)"
            + "|(?:USE\\s+CURRENTLY\\s+COMMITTED)))*)\\s*$");

    private final String sql;
    private final int[] markers;
    private final String keyword;

    private SqlText(final String _sql, final int[] _markers, final String _keyword) {
        sql = _sql;
        markers = _markers;
        keyword = _keyword;
    }

    /** The normalized single-line statement text (parameter markers still present as '?'). */
    public String getSql() {
        return sql;
    }

    /** Number of parameter markers. */
    public int getParameterCount() {
        return markers.length;
    }

    /** Offsets of the parameter markers within {@link #getSql()}. */
    public int[] getMarkerOffsets() {
        return markers.clone();
    }

    /** The first keyword of the statement, upper case (e.g. SELECT, INSERT, CALL). */
    public String getKeyword() {
        return keyword;
    }

    public boolean isQuery() {
        return isQueryKeyword(keyword);
    }

    public static boolean isQueryKeyword(final String _kw) {
        return "SELECT".equals(_kw) || "WITH".equals(_kw) || "VALUES".equals(_kw);
    }

    /** Replaces the parameter markers with the given SQL fragments. */
    public String bind(final String[] _values) throws SQLException {
        if (_values.length != markers.length) {
            throw new SQLException("Expected " + markers.length + " parameter values but got " + _values.length, "07001");
        }
        if (markers.length == 0) {
            return sql;
        }
        final StringBuilder sb = new StringBuilder(sql.length() + 32 * markers.length);
        int last = 0;
        for (int i = 0; i < markers.length; i++) {
            sb.append(sql, last, markers[i]);
            if (null == _values[i]) {
                throw new SQLException("Parameter " + (i + 1) + " has not been set", "07001");
            }
            sb.append(_values[i]);
            last = markers[i] + 1;
        }
        sb.append(sql, last, sql.length());
        return sb.toString();
    }

    // =================================================================================== parsing

    /**
     * Normalizes a statement.
     *
     * @param _sql the statement as given by the application
     * @param _cs the job charset (literals it cannot encode are rewritten as UTF-16 literals)
     * @param _escapes whether to process JDBC escape syntax
     */
    public static SqlText parse(final String _sql, final Charset _cs, final boolean _escapes) throws SQLException {
        if (null == _sql) {
            throw new SQLSyntaxErrorException("SQL statement is null", "42601");
        }
        final StringBuilder out = new StringBuilder(_sql.length() + 16);
        final List<Integer> marks = new ArrayList<Integer>();
        final Deque<String> escapeClosers = new ArrayDeque<String>();
        final int n = _sql.length();
        int i = 0;
        while (i < n) {
            final char c = _sql.charAt(i);
            // ---- comments
            if (c == '-' && i + 1 < n && _sql.charAt(i + 1) == '-') {
                while (i < n && _sql.charAt(i) != '\n' && _sql.charAt(i) != '\r') {
                    i++;
                }
                space(out);
                continue;
            }
            if (c == '/' && i + 1 < n && _sql.charAt(i + 1) == '*') {
                final int end = _sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                space(out);
                continue;
            }
            // ---- string literals
            if (c == '\'') {
                int j = i + 1;
                final StringBuilder content = new StringBuilder();
                while (true) {
                    if (j >= n) {
                        throw new SQLSyntaxErrorException("Unterminated string literal", "42603");
                    }
                    final char d = _sql.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < n && _sql.charAt(j + 1) == '\'') {
                            content.append('\'');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    content.append(d);
                    j++;
                }
                if (hasLiteralPrefix(out)) {
                    // X'..', UX'..', BX'..', GX'..', G'..', N'..', U&'..': copy verbatim (flattening newlines)
                    out.append(_sql, i, j + 1);
                    flattenTail(out, j + 1 - i);
                } else {
                    out.append(SqlLiterals.string(content.toString(), _cs));
                }
                i = j + 1;
                continue;
            }
            // ---- delimited identifiers
            if (c == '"') {
                final int end = findClosing(_sql, i + 1, '"');
                if (end < 0) {
                    throw new SQLSyntaxErrorException("Unterminated delimited identifier", "42603");
                }
                out.append(_sql, i, end + 1);
                flattenTail(out, end + 1 - i);
                i = end + 1;
                continue;
            }
            // ---- JDBC escapes
            if (_escapes && c == '{') {
                final int[] consumed = new int[1];
                final String[] translated = translateEscape(_sql, i, consumed);
                if (null != translated) {
                    out.append(translated[0]);
                    escapeClosers.push(translated[1]);
                    i += consumed[0];
                    continue;
                }
                escapeClosers.push("}");
                out.append(c);
                i++;
                continue;
            }
            if (_escapes && c == '}' && !escapeClosers.isEmpty()) {
                out.append(escapeClosers.pop());
                i++;
                continue;
            }
            // ---- everything else
            if (c == '?') {
                marks.add(out.length());
                out.append('?');
            } else if (c == ProcessTransport.TERMINATOR) {
                throw new SQLSyntaxErrorException("The character '" + ProcessTransport.TERMINATOR
                        + "' is only supported inside string literals and delimited identifiers", "42601");
            } else if (c == '\n' || c == '\r' || c == '\t' || c == '\f') {
                space(out);
            } else {
                out.append(c);
            }
            i++;
        }
        // trim, and drop trailing statement terminators
        int end = out.length();
        while (end > 0 && (Character.isWhitespace(out.charAt(end - 1)) || out.charAt(end - 1) == ';')) {
            end--;
        }
        int start = 0;
        while (start < end && Character.isWhitespace(out.charAt(start))) {
            start++;
        }
        final String text = out.substring(start, end);
        final int[] m = new int[marks.size()];
        int k = 0;
        for (final Integer pos : marks) {
            if (pos - start < text.length()) {
                m[k++] = pos - start;
            }
        }
        final int[] markers = k == m.length ? m : java.util.Arrays.copyOf(m, k);
        return new SqlText(text, markers, firstKeyword(text));
    }

    private static void space(final StringBuilder _out) {
        if (_out.length() > 0 && _out.charAt(_out.length() - 1) != ' ') {
            _out.append(' ');
        }
    }

    /** Replaces newline characters in the last {@code _len} characters of the builder with spaces. */
    private static void flattenTail(final StringBuilder _out, final int _len) {
        for (int p = _out.length() - _len; p < _out.length(); p++) {
            final char ch = _out.charAt(p);
            if (ch == '\n' || ch == '\r') {
                _out.setCharAt(p, ' ');
            }
        }
    }

    private static int findClosing(final String _s, final int _from, final char _q) {
        int j = _from;
        while (j < _s.length()) {
            if (_s.charAt(j) == _q) {
                if (j + 1 < _s.length() && _s.charAt(j + 1) == _q) {
                    j += 2;
                    continue;
                }
                return j;
            }
            j++;
        }
        return -1;
    }

    /** True if the output ends with a literal prefix such as X, UX, BX, GX, G, N or U&. */
    private static boolean hasLiteralPrefix(final StringBuilder _out) {
        int p = _out.length();
        if (p > 0 && _out.charAt(p - 1) == '&') {
            p--;
        }
        int s = p;
        while (s > 0 && Character.isLetter(_out.charAt(s - 1))) {
            s--;
        }
        if (s == p) {
            return false;
        }
        if (s > 0) {
            final char before = _out.charAt(s - 1);
            if (Character.isLetterOrDigit(before) || before == '_' || before == '$' || before == '#' || before == '@') {
                return false;
            }
        }
        final String prefix = _out.substring(s, _out.length()).toUpperCase(Locale.ROOT);
        return prefix.equals("X") || prefix.equals("UX") || prefix.equals("BX") || prefix.equals("GX")
                || prefix.equals("G") || prefix.equals("N") || prefix.equals("U&");
    }

    /**
     * Translates the start of a JDBC escape at {@code _at} (which holds '{').
     *
     * @return {replacement, closer} or null if this is not a recognized escape
     */
    private static String[] translateEscape(final String _s, final int _at, final int[] _consumed) {
        int j = _at + 1;
        while (j < _s.length() && Character.isWhitespace(_s.charAt(j))) {
            j++;
        }
        final int kwStart = j;
        while (j < _s.length() && Character.isLetter(_s.charAt(j))) {
            j++;
        }
        final String kw = _s.substring(kwStart, j).toLowerCase(Locale.ROOT);
        final String[] ret;
        if (kw.equals("d")) {
            ret = new String[] { "DATE(", ")" };
        } else if (kw.equals("t")) {
            ret = new String[] { "TIME(", ")" };
        } else if (kw.equals("ts")) {
            ret = new String[] { "TIMESTAMP(", ")" };
        } else if (kw.equals("fn") || kw.equals("oj")) {
            ret = new String[] { "", "" };
        } else if (kw.equals("call")) {
            ret = new String[] { "CALL ", "" };
        } else if (kw.equals("escape")) {
            ret = new String[] { "ESCAPE ", "" };
        } else if (kw.equals("limit")) {
            ret = new String[] { "LIMIT ", "" };
        } else {
            return null;
        }
        if (j < _s.length() && !Character.isWhitespace(_s.charAt(j)) && _s.charAt(j) != '\'' && _s.charAt(j) != '('
                && _s.charAt(j) != '}') {
            return null;
        }
        _consumed[0] = j - _at;
        return ret;
    }

    static String firstKeyword(final String _text) {
        int i = 0;
        while (i < _text.length() && (_text.charAt(i) == '(' || Character.isWhitespace(_text.charAt(i)))) {
            i++;
        }
        final int s = i;
        while (i < _text.length() && (Character.isLetter(_text.charAt(i)) || _text.charAt(i) == '_')) {
            i++;
        }
        return _text.substring(s, i).toUpperCase(Locale.ROOT);
    }

    // =================================================================================== query structure

    /** A query split into the parts needed to wrap it: [ctePrefix (may be empty), body, tailClauses (may be empty)]. */
    public static String[] splitQuery(final String _sql) {
        String prefix = "";
        String body = _sql;
        if ("WITH".equals(firstKeyword(_sql)) && !_sql.trim().startsWith("(")) {
            final int bodyStart = findCteBodyStart(_sql);
            if (bodyStart > 0) {
                prefix = _sql.substring(0, bodyStart).trim();
                body = _sql.substring(bodyStart).trim();
            }
        }
        String tail = "";
        final int lastQuote = Math.max(body.lastIndexOf('\''), body.lastIndexOf('"'));
        final int lastParen = body.lastIndexOf(')');
        final int guard = Math.max(lastQuote, lastParen);
        final String scan = guard >= 0 ? body.substring(guard + 1) : body;
        final Matcher m = TAIL.matcher(" " + scan);
        if (m.matches() && !m.group(2).trim().isEmpty()) {
            tail = m.group(2).trim();
            body = body.substring(0, body.length() - scan.length()) + m.group(1).substring(Math.min(1, m.group(1).length()));
            body = body.trim();
        }
        return new String[] { prefix, body, tail };
    }

    /** Returns the offset where the fullselect after a WITH clause starts, or -1. */
    static int findCteBodyStart(final String _s) {
        int i = skipWs(_s, firstKeywordEnd(_s));
        while (true) {
            // common table name
            if (i >= _s.length()) {
                return -1;
            }
            if (_s.charAt(i) == '"') {
                final int e = findClosing(_s, i + 1, '"');
                if (e < 0) {
                    return -1;
                }
                i = e + 1;
            } else {
                final int s = i;
                while (i < _s.length() && isIdentChar(_s.charAt(i))) {
                    i++;
                }
                if (s == i) {
                    return -1;
                }
            }
            i = skipWs(_s, i);
            if (i < _s.length() && _s.charAt(i) == '(') {
                i = skipWs(_s, skipParens(_s, i));
                if (i < 0) {
                    return -1;
                }
            }
            if (!_s.regionMatches(true, i, "AS", 0, 2)) {
                return -1;
            }
            i = skipWs(_s, i + 2);
            if (i >= _s.length() || _s.charAt(i) != '(') {
                return -1;
            }
            i = skipParens(_s, i);
            if (i < 0) {
                return -1;
            }
            i = skipWs(_s, i);
            if (i < _s.length() && _s.charAt(i) == ',') {
                i = skipWs(_s, i + 1);
                continue;
            }
            return i;
        }
    }

    private static int firstKeywordEnd(final String _s) {
        int i = skipWs(_s, 0);
        while (i < _s.length() && Character.isLetter(_s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isIdentChar(final char _c) {
        return Character.isLetterOrDigit(_c) || _c == '_' || _c == '$' || _c == '#' || _c == '@';
    }

    private static int skipWs(final String _s, final int _i) {
        if (_i < 0) {
            return _i;
        }
        int i = _i;
        while (i < _s.length() && Character.isWhitespace(_s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Given the index of '(' returns the index just after the matching ')', or -1. */
    static int skipParens(final String _s, final int _open) {
        int depth = 0;
        int i = _open;
        while (i < _s.length()) {
            final char c = _s.charAt(i);
            if (c == '\'' || c == '"') {
                final int e = findClosing(_s, i + 1, c);
                if (e < 0) {
                    return -1;
                }
                i = e + 1;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }
}
