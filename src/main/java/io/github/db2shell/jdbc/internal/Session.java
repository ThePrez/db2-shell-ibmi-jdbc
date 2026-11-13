package io.github.db2shell.jdbc.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conversation with one interactive {@code db2 -i -T%} process.
 *
 * <p>The utility prints {@code DB2>} when it is ready for a statement. After a statement it prints one of:</p>
 * <ul>
 * <li>{@code DB20000I  THE SQL COMMAND COMPLETED SUCCESSFULLY.}</li>
 * <li>a {@code **** CLI ERROR *****} block (SQLSTATE, native error code, message)</li>
 * <li>a result table: header line, dash line, fixed-width rows, a blank line, {@code n RECORD(S) SELECTED.}</li>
 * </ul>
 * <p>followed by the next {@code DB2>} prompt. Output lines longer than 4096 bytes are truncated by the utility, and
 * values containing newlines break rows across lines; callers handle the former by keeping lines narrow and this
 * class handles the latter by reassembling rows to their known fixed width.</p>
 *
 * <p>Not thread safe; callers synchronize.</p>
 */
public final class Session {

    /** The utility truncates every output line to this many bytes. */
    public static final int MAX_OUTPUT_LINE = 4096;
    /** Longest input line we send. The utility mangles input lines over ~32K. */
    static final int MAX_INPUT_LINE = 28000;

    /** The interactive prompt. */
    static final String PROMPT = "DB2>";
    private static final Pattern RECORDS =Pattern.compile("^\\s*\\d+\\s+RECORD\\(S\\) SELECTED\\.\\s*$");
    private static final Pattern DB2_MSG = Pattern.compile("^DB2\\d{4,5}([IWE])\\s+(.*)$");
    private static final Timer TIMER = new Timer("db2shell-timeout", true);

    /** Receives a request to finish reading an open result before another command can be sent. */
    public interface ResultOwner {
        /** Read (buffer or discard) all remaining rows so that the session becomes idle. */
        void releaseSession() throws SQLException;
    }

    /** What a statement produced. */
    public static final class Response {
        public final SQLException error;
        public final List<SQLWarning> warnings;
        /** Header line of a result table, or null when the statement produced no table. */
        public final byte[] header;
        /** Column ranges derived from the dash line: pairs of [start, length] in bytes. */
        public final int[][] columns;
        /** Total row width in bytes (capped at {@link Session#MAX_OUTPUT_LINE}). */
        public final int width;

        Response(final SQLException _error, final List<SQLWarning> _warnings, final byte[] _header, final int[][] _columns,
                final int _width) {
            error = _error;
            warnings = _warnings;
            header = _header;
            columns = _columns;
            width = _width;
        }

        public boolean isResult() {
            return null != header;
        }

        public boolean isError() {
            return null != error;
        }

        /** The first native error code of the response (0 if none). */
        public String sqlState() {
            return null == error ? null : error.getSQLState();
        }
    }

    private enum State {
        STARTING, IDLE, RESULT, DEAD
    }

    private final Transport transport;
    private final Tracer tracer;
    private Charset charset;
    private byte newline;
    private LineReader reader;
    private OutputStream out;
    private State state = State.STARTING;
    private ResultOwner owner;
    private final List<SQLWarning> resultWarnings = new ArrayList<SQLWarning>();
    private volatile boolean killedByTimeout = false;
    private volatile boolean killed = false;
    private TimerTask pendingTimeout;
    private volatile TimerTask pendingKill;
    /** Timeout still armed while waiting for the first row of a result (-1 when not armed). */
    private int rowTimeoutSeconds = -1;
    /** True while waiting for the response to a statement. */
    private volatile boolean inFlight = false;
    private volatile Runnable canceller;
    /** Seconds to wait for a cancelled statement to end before the session is abandoned. */
    static final int CANCEL_GRACE_SECONDS = 10;
    private String encodingNote;

    public Session(final Transport _transport, final Charset _charset, final Tracer _tracer) {
        transport = _transport;
        charset = _charset;
        tracer = _tracer;
        newline = newlineByte(_charset);
    }

    static byte newlineByte(final Charset _cs) {
        // Some JDKs encode '\n' as EBCDIC NEL (0x15), but the utility ends lines with 0x25, which decodes to LF.
        // Prefer the byte that *decodes* to LF.
        for (final int candidate : new int[] { 0x25, 0x0A, 0x15 }) {
            if ("\n".equals(new String(new byte[] { (byte) candidate }, _cs))) {
                return (byte) candidate;
            }
        }
        final byte[] nl = "\n".getBytes(_cs);
        if (nl.length != 1) {
            throw new IllegalArgumentException("Encoding " + _cs + " is not supported (newline is not a single byte)");
        }
        return nl[0];
    }

    public Charset getCharset() {
        return charset;
    }

    /** A note about how the output encoding was determined, if it was adjusted automatically. */
    public String getEncodingNote() {
        return encodingNote;
    }

    public boolean isDead() {
        return state == State.DEAD;
    }

    /** False if the utility process has ended (e.g. it was killed after a timeout). */
    public boolean isProcessAlive() {
        return transport.isAlive();
    }

    public boolean isIdle() {
        return state == State.IDLE;
    }

    // =================================================================================== startup

    /**
     * Waits for the first prompt. Detects whether the output is EBCDIC or ASCII; if the encoding was not given
     * explicitly and the output turns out to be ASCII, switches to UTF-8 (the PASE default).
     */
    public void start(final int _timeoutSeconds, final boolean _charsetExplicit) throws SQLException {
        final InputStream in = transport.getInput();
        out = transport.getOutput();
        armTimeout(_timeoutSeconds);
        // the prompt is printed without a newline while the utility waits for input
        final byte[] want = PROMPT.getBytes(charset);
        final byte[] ascii = PROMPT.getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream seen = new ByteArrayOutputStream();
        boolean asciiDetected = false;
        try {
            while (true) {
                final int b = in.read();
                if (b < 0) {
                    break;
                }
                seen.write(b);
                final byte[] s = seen.toByteArray();
                if (endsWith(s, want)) {
                    break;
                }
                if (!Arrays.equals(want, ascii) && endsWith(s, ascii)) {
                    asciiDetected = true;
                    break;
                }
            }
        } catch (final IOException e) {
            state = State.DEAD;
            throw connectionError("Unable to read from the db2 utility", e);
        } finally {
            disarmTimeout();
        }
        final byte[] s = seen.toByteArray();
        if (asciiDetected) {
            if (_charsetExplicit) {
                state = State.DEAD;
                throw new SQLNonTransientConnectionException("The db2 utility output appears to be ASCII, but encoding "
                        + charset.name() + " was configured. Set the 'encoding' property to an ASCII-based charset "
                        + "(e.g. UTF-8 or ISO-8859-1).", "08001");
            }
            encodingNote = "db2 utility output is ASCII-based; using UTF-8 instead of " + charset.name();
            charset = StandardCharsets.UTF_8;
            newline = newlineByte(charset);
            tracer.trace("encoding", encodingNote);
        } else if (!endsWith(s, want)) {
            state = State.DEAD;
            final String text = new String(s, charset).trim();
            if (text.contains("**** CLI ERROR")) {
                transport.kill();
                throw parseErrorText(text);
            }
            if (killedByTimeout) {
                throw new SQLNonTransientConnectionException("Timed out waiting for the db2 utility to start. Output: "
                        + text, "08001");
            }
            final String err = transport.getErrorText();
            throw new SQLNonTransientConnectionException("The db2 utility did not start"
                    + (text.isEmpty() ? "" : ". Output: " + text) + (err.isEmpty() ? "" : ". Errors: " + err)
                    + (text.isEmpty() && err.isEmpty() ? " (no output; check the encoding property and the paths "
                            + "of qsh and db2)" : ""), "08001");
        }
        reader = new LineReader(in, newline, PROMPT.getBytes(charset));
        state = State.IDLE;
        // A failed connect (bad password, unknown RDB) reports a CLI error before the first prompt.
        final String pre = new String(s, 0, s.length - want.length, charset);
        if (pre.contains("**** CLI ERROR")) {
            final SQLException e = parseErrorText(pre);
            close();
            throw e;
        }
        tracer.trace("ready", "encoding " + charset.name());
    }

    private static boolean endsWith(final byte[] _s, final byte[] _suffix) {
        if (_s.length < _suffix.length) {
            return false;
        }
        for (int i = 0; i < _suffix.length; i++) {
            if (_s[_s.length - _suffix.length + i] != _suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private SQLException parseErrorText(final String _text) {
        String state = "08001";
        int code = 0;
        final StringBuilder msg = new StringBuilder();
        boolean inBlock = false;
        for (final String line : _text.split("\n")) {
            final String t = line.trim();
            if (t.startsWith("**** CLI ERROR")) {
                inBlock = true;
            } else if (inBlock && t.startsWith("SQLSTATE:")) {
                state = t.substring(9).trim();
            } else if (inBlock && t.startsWith("NATIVE ERROR CODE:")) {
                code = parseIntSafe(t.substring(18).trim());
            } else if (inBlock && !t.isEmpty()) {
                msg.append(msg.length() > 0 ? " " : "").append(t);
            }
        }
        if (state.startsWith("08") || state.startsWith("28") || state.startsWith("42")) {
            return SqlErrors.create("Connection failed: " + msg, state, code);
        }
        return new SQLNonTransientConnectionException("Connection failed: " + msg + " (SQLSTATE " + state + ")", "08001",
                code);
    }

    // =================================================================================== commands

    /**
     * Sends a statement and reads its response up to the start of the rows (for queries) or the next prompt.
     *
     * @param _sql a statement already normalized to a single line (see {@link SqlText})
     * @param _timeoutSeconds 0 for no timeout; otherwise the process is killed if the response does not start in time
     */
    public Response send(final String _sql, final int _timeoutSeconds) throws SQLException {
        prepareForCommand();
        tracer.trace("send", _sql);
        try {
            write(_sql);
        } catch (final IOException e) {
            state = State.DEAD;
            throw connectionError("Unable to send to the db2 utility", e);
        }
        killedByTimeout = false;
        killed = false;
        armTimeout(_timeoutSeconds);
        final Response r;
        try {
            inFlight = true;
            r = readResponse();
        } catch (final SQLException e) {
            disarmTimeout();
            throw e;
        } finally {
            inFlight = false;
        }
        if (r.isResult()) {
            // The header is printed before the query has produced anything; keep the timeout until the first row.
            rowTimeoutSeconds = _timeoutSeconds;
        } else {
            disarmTimeout();
        }
        if (killedByTimeout && r.isError() && "57014".equals(r.error.getSQLState())) {
            if (r.isResult()) {
                discardRows();
            }
            throw new SQLTimeoutException("The statement exceeded the query timeout of " + _timeoutSeconds
                    + " seconds and was cancelled", "57014", r.error.getErrorCode());
        }
        return r;
    }

    /** Ensures the session is idle, asking the owner of any open result to release it. */
    void prepareForCommand() throws SQLException {
        if (state == State.DEAD) {
            throw new SQLNonTransientConnectionException("The db2 utility session has ended", "08003");
        }
        if (state == State.RESULT) {
            final ResultOwner o = owner;
            if (null != o) {
                o.releaseSession();
            }
            if (state == State.RESULT) {
                discardRows();
            }
        }
        owner = null;
        if (state != State.IDLE) {
            throw new SQLNonTransientConnectionException("The db2 utility session is not ready", "08S01");
        }
    }

    private void write(final String _sql) throws IOException {
        final List<String> lines = splitLines(_sql, MAX_INPUT_LINE);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(_sql.length() + 16);
        for (int i = 0; i < lines.size(); i++) {
            bos.write(lines.get(i).getBytes(charset));
            if (i == lines.size() - 1) {
                bos.write(String.valueOf(ProcessTransport.TERMINATOR).getBytes(charset));
            }
            bos.write(newline);
        }
        out.write(bos.toByteArray());
        out.flush();
    }

    /**
     * Splits a single-line statement into input lines no longer than {@code _max} characters, breaking only at
     * spaces outside of string literals and delimited identifiers. The utility joins input lines with a space, so
     * breaking at an existing space is lossless.
     */
    static List<String> splitLines(final String _sql, final int _max) {
        final List<String> ret = new ArrayList<String>();
        if (_sql.length() <= _max) {
            ret.add(_sql);
            return ret;
        }
        int start = 0;
        int lastBreak = -1;
        char quote = 0;
        for (int i = 0; i < _sql.length(); i++) {
            final char c = _sql.charAt(i);
            if (0 != quote) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ' ') {
                lastBreak = i;
            }
            if (i - start >= _max && lastBreak > start) {
                ret.add(_sql.substring(start, lastBreak));
                start = lastBreak + 1;
                lastBreak = -1;
            }
        }
        ret.add(_sql.substring(start));
        return ret;
    }

    private Response readResponse() throws SQLException {
        final List<SQLWarning> warnings = new ArrayList<SQLWarning>();
        SQLException error = null;
        while (true) {
            final byte[] line = readLineOrDie(true);
            final String t = decode(line).trim();
            if (t.isEmpty() || t.equals("?>")) {
                continue;
            }
            if (t.equals("DB2>")) {
                state = State.IDLE;
                return new Response(error, warnings, null, null, 0);
            }
            if (t.startsWith("**** CLI ERROR")) {
                final SQLException e = readErrorBlock();
                if (SqlErrors.isWarningState(e.getSQLState())) {
                    warnings.add(SqlErrors.warning(e.getMessage(), e.getSQLState(), e.getErrorCode()));
                } else if (null == error) {
                    error = e;
                } else {
                    error.setNextException(e);
                }
                continue;
            }
            final Matcher m = DB2_MSG.matcher(t);
            if (m.matches()) {
                if ("E".equals(m.group(1)) && null == error) {
                    error = SqlErrors.create(t, "HY000", 0);
                } else if ("W".equals(m.group(1))) {
                    warnings.add(SqlErrors.warning(t, "01000", 0));
                }
                continue;
            }
            // A result table. The next line holds the dashes that give the column widths.
            final byte[] dashes = readLineOrDie(false);
            final int[][] cols = parseDashes(dashes);
            if (null == cols) {
                state = State.DEAD;
                transport.kill();
                throw SqlErrors.protocol("expected column separator line after '" + t + "' but got '"
                        + decode(dashes) + "'");
            }
            int width = dashes.length;
            if (width > MAX_OUTPUT_LINE) {
                width = MAX_OUTPUT_LINE;
            }
            state = State.RESULT;
            resultWarnings.clear();
            return new Response(error, warnings, line, cols, width);
        }
    }

    /** Parses a line like {@code ----- --- ------} into [start,length] pairs; null if it is not a dash line. */
    int[][] parseDashes(final byte[] _line) {
        final byte dash = "-".getBytes(charset)[0];
        final byte space = " ".getBytes(charset)[0];
        final List<int[]> cols = new ArrayList<int[]>();
        int i = 0;
        while (i < _line.length) {
            if (_line[i] == space) {
                i++;
                continue;
            }
            if (_line[i] != dash) {
                return null;
            }
            final int s = i;
            while (i < _line.length && _line[i] == dash) {
                i++;
            }
            cols.add(new int[] { s, i - s });
        }
        return cols.isEmpty() ? null : cols.toArray(new int[0][]);
    }

    /** Reads an error block after the {@code **** CLI ERROR *****} line. */
    private SQLException readErrorBlock() throws SQLException {
        String sqlState = "HY000";
        int code = 0;
        final StringBuilder msg = new StringBuilder();
        while (true) {
            final byte[] line = readLineOrDie(true);
            final String t = decode(line).trim();
            if (t.equals("DB2>")) {
                reader.unread(line);
                break;
            }
            if (t.isEmpty()) {
                if (msg.length() > 0) {
                    break;
                }
                continue;
            }
            if (t.startsWith("SQLSTATE:")) {
                sqlState = t.substring("SQLSTATE:".length()).trim();
            } else if (t.startsWith("NATIVE ERROR CODE:")) {
                code = parseIntSafe(t.substring("NATIVE ERROR CODE:".length()).trim());
            } else {
                msg.append(msg.length() > 0 ? " " : "").append(t);
            }
        }
        tracer.trace("error", sqlState + " " + code + " " + msg);
        return SqlErrors.create(msg.toString(), sqlState, code);
    }

    private static int parseIntSafe(final String _s) {
        try {
            return Integer.parseInt(_s);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    // =================================================================================== rows

    /** Registers who is consuming the current result, so it can be asked to release the session. */
    public void setOwner(final ResultOwner _owner) {
        owner = _owner;
    }

    public boolean isOwner(final ResultOwner _owner) {
        return state == State.RESULT && owner == _owner;
    }

    /** Warnings reported while reading the rows of the current result. */
    public List<SQLWarning> getResultWarnings() {
        return resultWarnings;
    }

    /**
     * Returns the next row line of the current result, reassembled to {@code _width} bytes when a value contained a
     * newline. Returns null when the result is exhausted (the session is then idle). Throws if the utility reported
     * an error while fetching.
     */
    public byte[] nextRowLine(final int _width) throws SQLException {
        if (rowTimeoutSeconds < 0) {
            return nextRowLine0(_width);
        }
        try {
            return nextRowLine0(_width);
        } catch (final SQLException e) {
            if (killedByTimeout && "57014".equals(e.getSQLState())) {
                throw new SQLTimeoutException("The statement exceeded the query timeout of " + rowTimeoutSeconds
                        + " seconds and was cancelled", "57014", e.getErrorCode());
            }
            throw e;
        } finally {
            rowTimeoutSeconds = -1;
            disarmTimeout();
        }
    }

    private byte[] nextRowLine0(final int _width) throws SQLException {
        if (state != State.RESULT) {
            return null;
        }
        byte[] line = readLineOrDie(false);
        if (line.length == 0) {
            final byte[] next = readLineOrDie(true);
            final String t = decode(next).trim();
            if (RECORDS.matcher(t).matches()) {
                finishToPrompt();
                return null;
            }
            if (t.startsWith("**** CLI ERROR")) {
                final SQLException e = readErrorBlock();
                if (!SqlErrors.isWarningState(e.getSQLState())) {
                    finishToPrompt();
                    throw e;
                }
                resultWarnings.add(SqlErrors.warning(e.getMessage(), e.getSQLState(), e.getErrorCode()));
                // after a warning, either more rows or the end of the result follow
                while (true) {
                    final byte[] l = readLineOrDie(true);
                    final String lt = decode(l).trim();
                    if (lt.isEmpty()) {
                        continue;
                    }
                    if (RECORDS.matcher(lt).matches()) {
                        finishToPrompt();
                        return null;
                    }
                    if (lt.equals("DB2>")) {
                        state = State.IDLE;
                        return null;
                    }
                    reader.unread(l);
                    return nextRowLine0(_width);
                }
            }
            // a row that begins with a newline character
            line = join(line, next);
        }
        while (line.length < _width) {
            line = join(line, readLineOrDie(false));
        }
        return line;
    }

    private byte[] join(final byte[] _a, final byte[] _b) {
        final byte[] r = new byte[_a.length + 1 + _b.length];
        System.arraycopy(_a, 0, r, 0, _a.length);
        r[_a.length] = newline;
        System.arraycopy(_b, 0, r, _a.length + 1, _b.length);
        return r;
    }

    /** Reads and discards the remainder of the current result. */
    public void discardRows() throws SQLException {
        while (state == State.RESULT) {
            try {
                if (null == nextRowLine(1)) {
                    break;
                }
            } catch (final SQLException e) {
                if (state == State.DEAD) {
                    throw e;
                }
                // errors while discarding are irrelevant
            }
        }
    }

    private void finishToPrompt() throws SQLException {
        while (true) {
            final byte[] line = readLineOrDie(true);
            final String t = decode(line).trim();
            if (t.equals("DB2>")) {
                state = State.IDLE;
                return;
            }
            if (t.startsWith("**** CLI ERROR")) {
                final SQLException e = readErrorBlock();
                if (SqlErrors.isWarningState(e.getSQLState())) {
                    resultWarnings.add(SqlErrors.warning(e.getMessage(), e.getSQLState(), e.getErrorCode()));
                }
            }
        }
    }

    // =================================================================================== I/O helpers

    private byte[] readLineOrDie(final boolean _promptAware) throws SQLException {
        final byte[] line;
        try {
            line = _promptAware ? reader.readLineOrPrompt() : reader.readLine();
        } catch (final IOException e) {
            state = State.DEAD;
            if (killedByTimeout) {
                throw new SQLTimeoutException("The statement timed out; the db2 utility session was ended", "57014");
            }
            throw connectionError("Error reading from the db2 utility", e);
        }
        if (null == line) {
            state = State.DEAD;
            if (killedByTimeout) {
                throw new SQLTimeoutException("The statement timed out; the db2 utility session was ended", "57014");
            }
            if (killed) {
                throw new SQLNonTransientConnectionException("The statement was cancelled; the db2 utility session was "
                        + "ended", "57014");
            }
            final String err = transport.getErrorText();
            throw new SQLNonTransientConnectionException("The db2 utility ended unexpectedly"
                    + (err.isEmpty() ? "" : ": " + err), "08006");
        }
        if (tracer.isEnabled()) {
            tracer.trace("recv", decode(line));
        }
        return line;
    }

    public String decode(final byte[] _b) {
        return new String(_b, charset);
    }

    public String decode(final byte[] _b, final int _off, final int _len) {
        return new String(_b, _off, _len, charset);
    }

    private SQLException connectionError(final String _msg, final Exception _cause) {
        final String err = transport.getErrorText();
        return new SQLNonTransientConnectionException(_msg + (err.isEmpty() ? "" : ": " + err), "08006", _cause);
    }

    /**
     * Installs the action that cancels the running statement without ending the session (QSYS2.CANCEL_SQL against
     * the utility's job). Without one, timeouts and cancels end the session.
     */
    public void setCanceller(final Runnable _canceller) {
        canceller = _canceller;
    }

    private void armTimeout(final int _seconds) {
        if (_seconds <= 0) {
            return;
        }
        pendingTimeout = new TimerTask() {
            @Override
            public void run() {
                killedByTimeout = true;
                tracer.trace("timeout", "statement exceeded " + _seconds + "s; cancelling");
                cancelThenKill();
            }
        };
        TIMER.schedule(pendingTimeout, _seconds * 1000L);
    }

    private void disarmTimeout() {
        if (null != pendingTimeout) {
            pendingTimeout.cancel();
            pendingTimeout = null;
        }
        if (null != pendingKill) {
            pendingKill.cancel();
            pendingKill = null;
        }
    }

    /**
     * Cancels the running statement: first politely (the statement ends with SQLSTATE 57014 and the session
     * survives), then, if the utility still has not responded after a grace period (e.g. it is running a CL
     * command), by abandoning the session.
     */
    private void cancelThenKill() {
        final Runnable c = canceller;
        if (null == c || state == State.STARTING) {
            transport.kill();
            return;
        }
        final Thread t = new Thread(c, "db2shell-cancel");
        t.setDaemon(true);
        t.start();
        final TimerTask k = new TimerTask() {
            @Override
            public void run() {
                if (inFlight || state == State.RESULT) {
                    tracer.trace("cancel", "no response after cancel; ending the db2 utility session");
                    transport.kill();
                }
            }
        };
        pendingKill = k;
        TIMER.schedule(k, CANCEL_GRACE_SECONDS * 1000L);
    }

    /** Cancels the running statement (Statement.cancel()); safe from any thread. */
    public void kill() {
        if (!inFlight && state != State.RESULT) {
            return;
        }
        killed = true;
        cancelThenKill();
    }

    /** Ends the session politely. */
    public void close() {
        if (state == State.DEAD) {
            transport.kill();
            return;
        }
        state = State.DEAD;
        try {
            if (null != out) {
                out.write(("quit" + ProcessTransport.TERMINATOR).getBytes(charset));
                out.write(newline);
                out.flush();
            }
        } catch (final IOException e) {
            // ignore
        }
        transport.close();
    }
}
