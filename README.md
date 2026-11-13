# db2-shell-jdbc

> [!WARNING]
> ⚠️⚠️⚠️ **EXPERIMENTAL PROJECT. LARGELY VIBE-CODED.** ⚠️⚠️⚠️
>
> This driver was written quickly, mostly by an AI assistant, and has only been tested on one system.
> It is not supported, not production-ready, and may eat your data, your job log, or your weekend.
> Read the code, test thoroughly, and use it at your own risk. 🚧

> 🤔 *Did I create something useless?* Possibly! IBM already ships a perfectly good JDBC driver for IBM i.
> This one screen-scrapes a command line utility instead. But hey, it works, it's one jar, and it was fun.

A JDBC driver for **IBM i** that runs SQL through the `db2` command line utility (`/usr/bin/db2`, the
`QZDFMDB2` program) under QSH, instead of over a network database protocol.

It is meant for code that runs *on* IBM i and wants plain JDBC without host servers, sign-on, or extra
dependencies. Each `Connection` is one interactive `db2 -i` process; the driver talks to it over stdin/stdout
in the job's CCSID and turns its text output into proper, typed JDBC result sets.

```java
try (Connection c = DriverManager.getConnection("jdbc:db2shell:;schema=MYLIB");
     PreparedStatement ps = c.prepareStatement("select order_id, total, placed from orders where customer = ?")) {
    ps.setString(1, "ACME");
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            BigDecimal total = rs.getBigDecimal("TOTAL");      // real DECIMAL, not text
            Timestamp placed = rs.getTimestamp("PLACED");
        }
    }
}
```

- Single jar, no dependencies, Java 8+ (the IBM i default JVM)
- Typed, lossless results: real JDBC types and metadata, correct NULLs, values containing newlines, rows of any width, LOBs
- Prepared statements, batches, update counts, generated keys
- Transactions and savepoints, CALL with OUT/INOUT parameters and function return values
- `DatabaseMetaData` backed by the SYSIBM catalog views, so it works in tools like DBeaver and in ORMs
- Query timeouts and `Statement.cancel()` that keep the session usable
- A small command line SQL tool in the same jar

---

## Contents

- [Requirements](#requirements)
- [Building](#building)
- [Connecting](#connecting)
- [Connection properties](#connection-properties)
- [Encoding](#encoding)
- [How it works](#how-it-works)
- [What is supported](#what-is-supported)
- [Data types](#data-types)
- [Transactions](#transactions)
- [Timeouts and cancel](#timeouts-and-cancel)
- [Command line tool](#command-line-tool)
- [Performance](#performance)
- [Limitations](#limitations)
- [Testing](#testing)
- [Acknowledgements](#acknowledgements)

---

## Requirements

- IBM i with `/usr/bin/qsh` and `/usr/bin/db2` (standard). Developed and tested on IBM i 7.5.
- Java 8 or later on IBM i.
- The driver only runs **on IBM i itself**. It starts a local process; it cannot connect from another machine.

## Building

```sh
mvn package
```

This produces `target/db2-shell-jdbc-<version>-jar-with-dependencies.jar`: a single jar holding the
driver and the command line tool. The driver has no runtime dependencies, so the plain
`db2-shell-jdbc-<version>.jar` works too. Copy either jar to the IBM i and add it to your classpath.

The driver registers itself through `META-INF/services/java.sql.Driver`. If your environment does not use
the service loader (some application servers, or test runners with their own class loaders), load it
explicitly:

```java
Class.forName("io.github.db2shell.jdbc.Db2ShellDriver");
```

## Connecting

URL format:

```
jdbc:db2shell:[//][RDBNAME][;property=value]...[?property=value&...]
```

| URL | Meaning |
|---|---|
| `jdbc:db2shell:` | Local database, current user, defaults |
| `jdbc:db2shell:;schema=MYLIB;libraries=LIB1,LIB2` | Default schema plus library list entries |
| `jdbc:db2shell://*LOCAL;user=BOB;password=secret` | Run as another user profile (`db2 -r *LOCAL -u BOB -p ...`) |
| `jdbc:db2shell:?unicode=true&trace=true` | Query-string style properties |

You can also pass properties in a `java.util.Properties` object. Properties in the URL take precedence.

A `DataSource` is also available:

```java
Db2ShellDataSource ds = new Db2ShellDataSource();
ds.setSchema("MYLIB");
ds.setProperty("lobMaxLength", "4194304");
try (Connection c = ds.getConnection()) { ... }
```

Each connection starts a new `db2` process. Opening a connection takes about a quarter of a second. Use a
connection pool if you open connections often.

## Connection properties

Property names are case-insensitive.

| Property | Default | Description |
|---|---|---|
| `user` | *(job user)* | User profile to connect as. Passed to `db2 -u`; implies `databaseName=*LOCAL`. |
| `password` | | Password for `user`. **Note:** it is passed on the `qsh` command line, so it is visible in the process list. |
| `databaseName` | | Relational database name (`db2 -r`). Aliases: `rdb`, `database`. Can also be given in the URL. |
| `encoding` | `os400.job.file.encoding` | Java charset of the utility's stdin and stdout. See [Encoding](#encoding). |
| `ccsid` | | Same as `encoding`, given as a CCSID number (e.g. `37`, `273`, `1208`). |
| `schema` | | Default schema (`SET SCHEMA`). Aliases: `currentSchema`, `defaultSchema`. |
| `libraries` | | Comma-separated libraries added to the end of the job's library list (`ADDLIBLE`). |
| `path` | | SQL path (`SET PATH`) for unqualified routines and types. |
| `initSql` | | SQL to run when the session starts. Separate several statements with `;;`. |
| `resultMode` | `typed` | `typed`: describe each query and return typed, lossless values. `raw`: parse the utility's display output; every column is VARCHAR. |
| `unicode` | `false` | Transfer character data as UTF-16 hex, so characters the job CCSID cannot represent survive. Strings take about 4 times the bandwidth. |
| `lineWidth` | `128` | Width of each line of typed result output (32 to 4000). |
| `lobMaxLength` | `1048576` | Maximum characters or bytes retrieved from a LOB or XML value. |
| `updateCounts` | `true` | Return exact update counts for INSERT/UPDATE/DELETE/MERGE (costs one extra round trip). |
| `autoCommit` | `true` | Initial auto-commit mode. |
| `transactionIsolation` | `read committed` | Isolation used when auto-commit is off: `read uncommitted`, `read committed`, `repeatable read`, `serializable` (also `UR`/`CS`/`RS`/`RR`, `*CHG`/`*CS`/`*ALL`). |
| `queryTimeout` | `0` | Default query timeout in seconds for new statements. |
| `startupTimeout` | `60` | Seconds to wait for the utility to start. |
| `describeCacheSize` | `100` | Number of query descriptions cached per connection (0 disables). |
| `qshPath` | `/usr/bin/qsh` | Path of QSH. |
| `db2Path` | `/usr/bin/db2` | Path of the db2 utility. |
| `trace` | `false` | Log every statement sent and every line received. |
| `traceFile` | | Write the trace to this file (default: `DriverManager` log writer, else stderr). |

## Encoding

When Java starts `qsh` on IBM i, the `db2` utility reads its input and writes its output in the **job
CCSID**, typically an EBCDIC code page such as 37. The driver therefore defaults to the charset in the
`os400.job.file.encoding` system property (for example `Cp037`), the same approach
[Mapepire's `AuthFile`](https://github.com/Mapepire-IBMi/mapepire-server/blob/main/src/main/java/com/github/ibm/mapepire/authfile/AuthFile.java)
uses. Override it with `encoding=` or `ccsid=` if your environment differs.

Details:

- If no encoding was given and the output turns out to be ASCII-based (for example because of PASE
  descriptor settings), the driver detects this from the first prompt, switches to UTF-8, and adds a
  connection warning. If you set the encoding explicitly, a mismatch is an error instead.
- SQL **sent** to the utility must fit the job CCSID. String literals, in your SQL or from parameters, that
  contain characters the CCSID cannot represent are rewritten as UTF-16 `UX'...'` literals, so they reach Db2
  intact.
- Data **returned** is converted to the job CCSID by Db2. Characters it cannot represent come back as
  substitution characters, unless you set `unicode=true`.
- Some JDKs encode `\n` as EBCDIC NEL (`0x15`). The utility ends lines with `0x25`, and the driver handles
  this.

## How it works

The `db2` utility can only run SQL and print results as a text table. Using that output directly has
several problems:

- Every value is padded to its declared width. A `VARCHAR(2000)` column costs 2000 bytes per row even
  when it holds one character.
- Output lines are truncated at **4096 bytes**, so wide rows lose data.
- A NULL is shown as `-`, which you cannot tell apart from the string `"-"`.
- Values containing newlines break rows across lines.
- CLOBs display as blanks. DOUBLE is shown with 6 significant digits. BOOLEAN shows as `TR`. FOR BIT DATA
  shows as raw bytes. Date formats depend on the job.
- There are no column types, update counts, parameter markers, or procedure result sets.

The driver works around these problems as follows.

**One persistent session per connection.** The driver starts `qsh -c "db2 -i -T%"` and waits for the
`DB2>` prompt. It sends one statement at a time and reads the response up to the next prompt. Because the
session persists, `SET SCHEMA`, QTEMP, global temporary tables and transactions all behave as expected. The
statement terminator is `%`, an EBCDIC-invariant character, so `;` can appear in compound statements.
`%` is allowed inside literals and delimited identifiers.

**Statement normalization.** The utility reads one line per statement, mangles input lines over about
32K, and joins continuation lines with a space. So the driver does the following before sending:

- Strips comments and flattens whitespace.
- Rewrites newlines inside literals as `CHR(10)` and `CHR(13)` concatenations.
- Splits long literals into concatenated pieces.
- Breaks long statements into multiple input lines, only at existing spaces.
- Processes JDBC escapes such as `{d ...}`, `{ts ...}`, `{fn ...}`, `{call ...}`, `{oj ...}`, `{escape ...}`
  and `{limit ...}`.

**Typed queries.** For each query (`SELECT`, `WITH`, `VALUES`) the driver:

1. **Describes** it without running it. A dynamic compound statement `PREPARE`s the query, runs `DESCRIBE`
   into an SQL descriptor, and copies each column's `GET DESCRIPTOR` items (type, length, precision,
   scale, nullability, CCSID, base table and column) into a session temporary table, which the driver
   then reads. Descriptions are cached per connection.
2. **Wraps** the query so that every row comes back as a single string of tokens: `-` for NULL, otherwise
   `<length>:<value>`. Each column is converted to a canonical text form: job-CCSID text, hex for binary
   data, `CHAR(date, ISO)`, `CHAR(time, JIS)`, `VARCHAR()` for numbers (full precision), `XMLSERIALIZE` for
   XML, `SUBSTRING` for LOBs, or UTF-16 hex in `unicode` mode.
3. **Chunks** each row's string into fixed-width lines with a `LATERAL` join to a numbers table. Each row
   uses only as many lines as its actual data needs. This avoids padding to declared widths and the
   4096-byte line limit. Order is preserved with `ORDER BY ORDER OF`. Common table expressions are hoisted
   out of the nested query, and trailing clauses such as `WITH UR`, `OPTIMIZE FOR`, and `FOR READ ONLY` are
   moved outside it.
4. **Decodes** the token stream at the byte level using exact byte lengths. A line shorter than the known
   width means a value contained a newline, and the row is reassembled.

If a query cannot be wrapped (for example a data-change table reference that the wrapping rejects), the
driver falls back to raw mode for that query.

**Updates.** INSERT, UPDATE, DELETE and MERGE run through `EXECUTE IMMEDIATE` inside a compound statement
that captures `GET DIAGNOSTICS ROW_COUNT`, so `executeUpdate` returns the exact count. Other statements run
directly.

**Parameters.** The utility cannot bind parameters, so prepared statements substitute SQL literals for the
`?` markers when they execute. The literals are type-correct, for example `DATE('...')`,
`TIMESTAMP('...')`, `BX'..'`, and `UX'..'`.

## What is supported

| Area | Support |
|---|---|
| `Statement` | `execute`, `executeQuery`, `executeUpdate`, `executeLargeUpdate`, batches, `maxRows` (pushed into the query as `FETCH FIRST`), `queryTimeout`, `cancel`, `closeOnCompletion`, warnings. |
| `PreparedStatement` | All `setXxx` methods, including streams, LOBs, `java.time` types, `setObject` with a target type and scale, and `Calendar` variants. Also batches, `getMetaData()` before execution, and `getParameterMetaData()` (types inferred from the values set). |
| `CallableStatement` | `CALL` with IN, OUT and INOUT parameters, and `{? = call func(?)}`, by index. Named parameters are not supported. |
| `ResultSet` | Forward-only (streamed) or scroll-insensitive (buffered). All getters with conversions, including `getObject(i, Class)` for `java.time` types, `Clob`, `Blob`, `SQLXML`, `UUID` and others. Several result sets can be open at once on one connection: when the session is needed for another statement, the unread rows of an open result are buffered in memory. Read-only. |
| `ResultSetMetaData` | Types, precision, scale, nullability, display size, base schema, table and column, and class names. Raw mode reports VARCHAR. |
| Generated keys | `RETURN_GENERATED_KEYS` finds the table's identity column. Explicit column names are also accepted. Keys are retrieved with `SELECT ... FROM FINAL TABLE (INSERT ...)`. |
| Transactions | Auto-commit, commit, rollback, isolation levels, and named and unnamed savepoints. |
| `DatabaseMetaData` | Tables, columns (with auto-increment and generated flags), primary, imported and exported keys, cross reference, indexes, best row identifier, procedures, procedure columns, functions, function columns, schemas, catalogs, table types, type info, UDTs, and table and column privileges. Product name is `DB2 UDB for AS/400`, the same as IBM Toolbox for Java, so tools and ORMs pick the IBM i dialect. |
| Other | `DataSource`, `isValid`, `setSchema` and `getSchema`, client info, `nativeSQL`, `unwrap`. `Db2ShellConnection.getJobName()` returns the job running the utility. |

## Data types

| Db2 for i type | JDBC type | Java value |
|---|---|---|
| CHAR, VARCHAR | CHAR, VARCHAR | `String` (CHAR values keep their padding) |
| GRAPHIC, VARGRAPHIC, NCHAR, NVARCHAR | NCHAR, NVARCHAR (CCSID 1200/13488), otherwise CHAR, VARCHAR | `String` |
| CLOB, DBCLOB, NCLOB | CLOB, NCLOB | `Clob` from `getObject` (up to `lobMaxLength`) |
| BINARY, VARBINARY, CHAR/VARCHAR FOR BIT DATA | BINARY, VARBINARY | `byte[]` |
| BLOB | BLOB | `Blob` from `getObject` (up to `lobMaxLength`) |
| SMALLINT, INTEGER | SMALLINT, INTEGER | `Integer` |
| BIGINT | BIGINT | `Long` |
| DECIMAL, NUMERIC | DECIMAL, NUMERIC | `BigDecimal` |
| REAL | REAL | `Float` |
| DOUBLE, FLOAT | DOUBLE | `Double` (full precision) |
| DECFLOAT(16/34) | DECIMAL | `BigDecimal` (`Double` for NaN and Infinity) |
| DATE, TIME, TIMESTAMP(0-12) | DATE, TIME, TIMESTAMP | `java.sql.Date`, `Time`, `Timestamp` (nanoseconds kept) |
| BOOLEAN (7.5+) | BOOLEAN | `Boolean` |
| XML | SQLXML | `SQLXML` from `getObject` |

## Transactions

By default the utility runs without commitment control (`*NONE`): every statement takes effect immediately.

With `setAutoCommit(false)`, the driver starts each unit of work with
`SET TRANSACTION ISOLATION LEVEL <level>`, and `commit()` and `rollback()` end it. Tables changed under
commitment control must be journaled, as with any Db2 for i commitment control; tables created by
`CREATE SCHEMA` libraries are journaled automatically.

The isolation level set with `setTransactionIsolation` applies from the next unit of work. `COMMIT` and
`ROLLBACK` statements you run yourself are tracked too.

## Timeouts and cancel

When a statement exceeds its query timeout, or you call `Statement.cancel()`, the driver runs
`QSYS2.CANCEL_SQL('<job>')` from a short-lived helper process. The statement ends with
`SQLTimeoutException` (SQLSTATE 57014) and **the session stays usable**: QTEMP, temporary tables and
`SET` state all remain.

Some work cannot be cancelled this way, such as a long CL command run through `QCMDEXC`. If the utility
has not responded 10 seconds after the cancel, the driver abandons the session and transparently starts a
new one on the next call. The connection's properties (`schema`, `libraries`, `path`, `initSql`) are
re-applied, and a connection warning (SQLSTATE 01002) reports that session state was lost.

## Command line tool

The jar's main class is a small SQL tool, handy for testing:

```sh
java -jar db2-shell-jdbc.jar "select * from qsys2.systables fetch first 5 rows only"
java -jar db2-shell-jdbc.jar -p schema=MYLIB -f csv "select * from orders" > orders.csv
java -jar db2-shell-jdbc.jar -f json -u "jdbc:db2shell:;unicode=true" "values current server"
echo "select count(*) from qsys2.syscolumns;" | java -jar db2-shell-jdbc.jar
```

| Option | Meaning |
|---|---|
| `-u`, `--url` | JDBC URL |
| `-p`, `--prop key=value` | Driver property (can be repeated) |
| `-f`, `--format` | `table` (default), `csv`, `tsv`, or `json` |
| `-v`, `--version` | Print the driver version |

With no SQL arguments, the tool reads statements from stdin, each ending with `;` at the end of a line.

## Performance

Measured on the development system (IBM i 7.5):

| Operation | Time |
|---|---|
| Open a connection (start the utility and set up the session) | ~0.25 s |
| First run of a distinct query (describe and run) | ~0.3 s, mostly compiling the describe compound statement |
| Repeat of an already-described query (describe cache) | ~10 ms |
| Streaming 20,000 three-column rows | ~0.75 s |
| INSERT/UPDATE/DELETE with update counts | two round trips (set `updateCounts=false` to skip the count) |

Typed mode usually moves *less* data than the utility's own display output, because values are not padded
to their declared widths. In one test, a 20,000-row table with `VARCHAR(2000)` columns took 4.7 s and
43 MB as native display output, but 1.5 s and 4 MB as the driver's token stream.

## Limitations

These come from the `db2` utility:

- **Fetch-time data mapping errors are not reported** by the utility (for example division by zero, or an
  invalid date in data). In typed mode such a row is returned with all columns NULL, and the result set
  gets one SQLWarning (SQLSTATE 01519). For rows wider than `2 × lineWidth`, the row may be omitted. In
  raw mode the utility prints `0` or blanks.
- **Result sets returned by procedures are not available.** The utility does not display them. OUT
  parameters work.
- **No system naming.** Use SQL naming (`schema.table`). The library list is still used to resolve
  unqualified routines through the SQL path. The `libraries` property adds to it.
- **Read-only result sets.** Use UPDATE/INSERT/DELETE statements to change data.
- Constraints such as primary keys cannot be created on QTEMP tables in the utility's session, because Db2
  rejects them under its commitment control. Create such tables in a real schema.
- LOB and XML values are retrieved up to `lobMaxLength` and held in memory.
- Trailing blanks of VARCHAR values are kept in typed mode but trimmed in raw mode.
- `password` is visible in the process list while the connection is open, because it is passed on the
  command line.
- In jobs whose CCSID is a mixed single-byte/double-byte EBCDIC CCSID, use `unicode=true` for reliable
  character data.
- Each connection is a separate process and job. Keep connection counts reasonable and use a pool.

## Testing

Unit tests (lexer, literal generation, protocol parser driven by recorded utility output) run anywhere:

```sh
mvn test
```

Integration tests (`IbmiIntegrationTest`) run only on IBM i. They exercise every data type, wide rows and
LOBs, Unicode, long statements, update counts, batches, generated keys, transactions and savepoints,
callable statements, metadata, timeouts and recovery, raw mode, and encodings. They create a scratch schema
named `DB2SH<random>` and drop it when they finish. To build, deploy, and run them over SSH:

```sh
scripts/ibmi-integration-test.sh myuser@myibmi
```

## Acknowledgements

- The parsing of the `db2` utility's display output in raw mode builds on `db2Parse` in
  [vscode-ibmi's `Tools.ts`](https://github.com/ajshedivy/vscode-ibmi/blob/cf8b9444b145e04f558a20658c9388def2f506e0/src/api/Tools.ts).
- Running `/usr/bin/db2` through QSH and decoding its output with `os400.job.file.encoding` follows
  [Mapepire's `AuthFile.java`](https://github.com/Mapepire-IBMi/mapepire-server/blob/main/src/main/java/com/github/ibm/mapepire/authfile/AuthFile.java).
