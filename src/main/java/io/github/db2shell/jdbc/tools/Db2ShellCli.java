package io.github.db2shell.jdbc.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import io.github.db2shell.jdbc.Db2ShellDriver;

/**
 * A small command line SQL tool built on the driver, and the jar's main class.
 *
 * <pre>
 * java -jar db2-shell-jdbc.jar [options] [SQL ...]
 *   -u, --url URL        JDBC URL (default jdbc:db2shell:)
 *   -p, --prop key=value driver property (repeatable)
 *   -f, --format F       table (default), csv, tsv or json
 *   -h, --help
 * With no SQL arguments, statements are read from stdin, one per line (or terminated by ';' at end of line).
 * </pre>
 */
public final class Db2ShellCli {

    private Db2ShellCli() {
    }

    public static void main(final String[] _args) throws Exception {
        String url = "jdbc:db2shell:";
        String format = "table";
        final Properties props = new Properties();
        final List<String> sqls = new ArrayList<String>();
        for (int i = 0; i < _args.length; i++) {
            final String a = _args[i];
            if (("-u".equals(a) || "--url".equals(a)) && i + 1 < _args.length) {
                url = _args[++i];
            } else if (("-p".equals(a) || "--prop".equals(a)) && i + 1 < _args.length) {
                final String kv = _args[++i];
                final int eq = kv.indexOf('=');
                props.setProperty(eq < 0 ? kv : kv.substring(0, eq), eq < 0 ? "true" : kv.substring(eq + 1));
            } else if (("-f".equals(a) || "--format".equals(a)) && i + 1 < _args.length) {
                format = _args[++i].toLowerCase();
            } else if ("-h".equals(a) || "--help".equals(a)) {
                usage(System.out);
                return;
            } else if ("-v".equals(a) || "--version".equals(a)) {
                System.out.println(Db2ShellDriver.NAME + " " + Db2ShellDriver.version());
                return;
            } else {
                sqls.add(a);
            }
        }
        Class.forName(Db2ShellDriver.class.getName());
        final PrintStream out = new PrintStream(System.out, true, "UTF-8");
        int rc = 0;
        try (Connection c = DriverManager.getConnection(url, props)) {
            printWarnings(c.getWarnings());
            if (sqls.isEmpty()) {
                final BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                final StringBuilder sb = new StringBuilder();
                String line;
                while (null != (line = br.readLine())) {
                    if (line.trim().isEmpty() && sb.length() == 0) {
                        continue;
                    }
                    sb.append(line).append('\n');
                    if (line.trim().endsWith(";")) {
                        rc |= run(c, sb.toString(), format, out);
                        sb.setLength(0);
                    }
                }
                if (sb.toString().trim().length() > 0) {
                    rc |= run(c, sb.toString(), format, out);
                }
            } else {
                for (final String sql : sqls) {
                    rc |= run(c, sql, format, out);
                }
            }
        }
        System.exit(rc);
    }

    private static void usage(final PrintStream _out) {
        _out.println("Usage: java -jar db2-shell-jdbc.jar [options] [SQL ...]");
        _out.println("  -u, --url URL         JDBC URL (default jdbc:db2shell:)");
        _out.println("  -p, --prop key=value  driver property, e.g. -p schema=MYLIB (repeatable)");
        _out.println("  -f, --format FORMAT   table (default), csv, tsv or json");
        _out.println("  -v, --version         print the driver version");
        _out.println("With no SQL arguments, statements are read from stdin (end each with ';').");
    }

    private static int run(final Connection _c, final String _sql, final String _format, final PrintStream _out) {
        try (Statement s = _c.createStatement()) {
            final long start = System.nanoTime();
            final boolean isRs = s.execute(_sql);
            if (isRs) {
                try (ResultSet rs = s.getResultSet()) {
                    final int rows = print(rs, _format, _out);
                    printWarnings(rs.getWarnings());
                    if ("table".equals(_format)) {
                        _out.printf("%d row(s) (%.3f s)%n%n", rows, (System.nanoTime() - start) / 1e9);
                    }
                }
            } else if ("table".equals(_format)) {
                _out.printf("OK, %d row(s) affected (%.3f s)%n%n", s.getLargeUpdateCount(),
                        (System.nanoTime() - start) / 1e9);
            }
            printWarnings(s.getWarnings());
            return 0;
        } catch (final SQLException e) {
            System.err.println("ERROR [" + e.getSQLState() + "/" + e.getErrorCode() + "]: " + e.getMessage());
            return 1;
        }
    }

    private static int print(final ResultSet _rs, final String _format, final PrintStream _out) throws SQLException {
        final ResultSetMetaData md = _rs.getMetaData();
        final int n = md.getColumnCount();
        final List<String[]> rows = new ArrayList<String[]>();
        final String[] head = new String[n];
        for (int i = 0; i < n; i++) {
            head[i] = md.getColumnLabel(i + 1);
        }
        int count = 0;
        if ("csv".equals(_format) || "tsv".equals(_format)) {
            final String sep = "csv".equals(_format) ? "," : "\t";
            _out.println(join(head, sep, "csv".equals(_format)));
            while (_rs.next()) {
                final String[] r = new String[n];
                for (int i = 0; i < n; i++) {
                    r[i] = _rs.getString(i + 1);
                }
                _out.println(join(r, sep, "csv".equals(_format)));
                count++;
            }
            return count;
        }
        if ("json".equals(_format)) {
            _out.print("[");
            while (_rs.next()) {
                _out.print(count == 0 ? "\n  {" : ",\n  {");
                for (int i = 0; i < n; i++) {
                    final Object v = _rs.getObject(i + 1);
                    _out.print((i > 0 ? ", " : "") + json(head[i]) + ": ");
                    if (null == v) {
                        _out.print("null");
                    } else if (v instanceof Number || v instanceof Boolean) {
                        _out.print(v.toString());
                    } else {
                        _out.print(json(_rs.getString(i + 1)));
                    }
                }
                _out.print("}");
                count++;
            }
            _out.println(count == 0 ? "]" : "\n]");
            return count;
        }
        final int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = head[i].length();
        }
        while (_rs.next()) {
            final String[] r = new String[n];
            for (int i = 0; i < n; i++) {
                String v = _rs.getString(i + 1);
                v = null == v ? "NULL" : v.replace("\n", "\\n").replace("\r", "\\r");
                if (v.length() > 200) {
                    v = v.substring(0, 197) + "...";
                }
                r[i] = v;
                w[i] = Math.max(w[i], v.length());
            }
            rows.add(r);
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? " " : "").append(pad(head[i], w[i]));
        }
        _out.println(sb);
        sb.setLength(0);
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? " " : "").append(repeat('-', w[i]));
        }
        _out.println(sb);
        for (final String[] r : rows) {
            sb.setLength(0);
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? " " : "").append(pad(r[i], w[i]));
            }
            _out.println(sb);
        }
        return rows.size();
    }

    private static String join(final String[] _v, final String _sep, final boolean _quote) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < _v.length; i++) {
            if (i > 0) {
                sb.append(_sep);
            }
            String v = _v[i];
            if (null == v) {
                continue;
            }
            if (_quote && (v.contains(",") || v.contains("\"") || v.contains("\n"))) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            } else if (!_quote) {
                v = v.replace("\t", " ").replace("\n", " ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private static String json(final String _s) {
        final StringBuilder sb = new StringBuilder("\"");
        for (final char c : _s.toCharArray()) {
            switch (c) {
            case '"': sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': sb.append("\\n"); break;
            case '\r': sb.append("\\r"); break;
            case '\t': sb.append("\\t"); break;
            default:
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String pad(final String _s, final int _w) {
        final StringBuilder sb = new StringBuilder(_s);
        while (sb.length() < _w) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String repeat(final char _c, final int _n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < _n; i++) {
            sb.append(_c);
        }
        return sb.toString();
    }

    private static void printWarnings(final SQLWarning _w) {
        SQLWarning w = _w;
        while (null != w) {
            System.err.println("WARNING [" + w.getSQLState() + "]: " + w.getMessage());
            w = w.getNextWarning();
        }
    }
}
