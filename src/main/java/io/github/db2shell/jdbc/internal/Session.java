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
    private static final Pattern s_records = Pattern.compile("^\\s*\\d+\\s+RECORD\\(S\\) SELECTED\\.\\s*$");
    private static final Pattern s_db2Msg = Pattern.compile("^DB2\\d{4,5}([IWE])\\s+(.*)$");
    private static final Timer s_timer = new Timer("db2shell-timeout", true);

    /** Receives a request to finish reading an open result before another command can be sent. */
    public interface ResultOwner {
        /** Read (buffer or discard) all remaining rows so that the session becomes idle. */
        void releaseSession() throws SQLException;
    }

    /** What a statement produced. */
    public static final class Response {
        private final SQLException m_error;
        private final List<SQLWarning> m_warnings;
        private final byte[] m_header;
        private final int[][] m_columns;
        private final int m_width;

        /**
         * @param _error the error the statement produced, or null
         * @param _warnings warnings the statement produced (never null)
         * @param _header header line of a result table, or null when the statement produced no table
         * @param _columns column ranges of a result table as [start, length] pairs, or null
         * @param _width total row width in bytes of a result table (0 when there is none)
         */
        Response(final SQLException _error, final List<SQLWarning> _warnings, final byte[] _header, final int[][] _columns,
                final int _width) {
            m_error = _error;
            m_warnings = _warnings;
            m_header = _header;
            m_columns = _columns;
            m_width = _width;
        }

        /** The error the statement produced, or null. */
        public SQLException getError() {
            return m_error;
        }

        /** Warnings the statement produced (never null; may be appended to). */
        public List<SQLWarning> getWarnings() {
            return m_warnings;
        }

        /** Header line of a result table, or null when the statement produced no table. */
        public byte[] getHeader() {
            return m_header;
        }

        /** Column ranges derived from the dash line: pairs of [start, length] in bytes. */
        public int[][] getColumns() {
            return m_columns;
        }

        /** Total row width in bytes (capped at {@link Session#MAX_OUTPUT_LINE}). */
        public int getWidth() {
            return m_width;
        }

        public boolean isResult() {
            return null != m_header;
        }

        public boolean isError() {
            return null != m_error;
        }

        /** The SQLSTATE of the error, or null if there is none. */
        public String getSqlState() {
            return null == m_error ? null : m_error.getSQLState();
        }
    }

    private enum State {
        STARTING, IDLE, RESULT, DEAD
    }

    private final Transport m_transport;
    private final Tracer m_tracer;
    private Charset m_charset;
    private byte m_newline;
    private LineReader m_reader;
    private OutputStream m_out;
    private State m_state = State.STARTING;
    private ResultOwner m_owner;
    private final List<SQLWarning> m_resultWarnings = new ArrayList<SQLWarning>();
    private volatile boolean m_killedByTimeout = false;
    private volatile boolean m_killed = false;
    private TimerTask m_pendingTimeout;
    private volatile TimerTask m_pendingKill;
    /** Timeout still armed while waiting for the first row of a result (-1 when not armed). */
    private int m_rowTimeoutSeconds = -1;
    /** True while waiting for the response to a statement. */
    private volatile boolean m_inFlight = false;
    private volatile Runnable m_canceller;
    /** Seconds to wait for a cancelled statement to end before the session is abandoned. */
    static final int CANCEL_GRACE_SECONDS = 10;
    private String m_encodingNote;

    public Session(final Transport _transport, final Charset _charset, final Tracer _tracer) {
        m_transport = _transport;
        m_charset = _charset;
        m_tracer = _tracer;
        m_newline = newlineByte(_charset);
    }

    /**
     * Returns the byte that ends an output line of the utility in the given encoding.
     *
     * @param _cs the encoding of the utility's output
     * @return the newline byte
     * @throws IllegalArgumentException if the encoding has no single-byte newline
     */
    static byte newlineByte(final Charset _cs) {
        // Some JDKs encode '\n' as EBCDIC NEL (0x15), but the utility ends lines with 0x25, which decodes to LF.
        // Prefer the byte that *decodes* to LF.
        for (final int candidate : new int[] { 0x25, 0x0A, 0x15 }) {
            if ("\n".equals(new String(new byte[] { (byte) candidate }, _cs))) {
                return (byte) candidate;
            }
        }
        final byte[] nl = "\n".getBytes(_cs);
        if (1 != nl.length) {
            throw new IllegalArgumentException("Encoding " + _cs + " is not supported (newline is not a single byte)");
        }
        return nl[0];
    }

    public Charset getCharset() {
        return m_charset;
    }

    /** A note about how the output encoding was determined, if it was adjusted automatically. */
    public String getEncodingNote() {
        return m_encodingNote;
    }

    public boolean isDead() {
        return State.DEAD == m_state;
    }

    /** False if the utility process has ended (e.g. it was killed after a timeout). */
    public boolean isProcessAlive() {
        return m_transport.isAlive();
    }

    public boolean isIdle() {
        return State.IDLE == m_state;
    }

    // =================================================================================== startup

    /**
     * Waits for the first prompt. Detects whether the output is EBCDIC or ASCII; if the encoding was not given
     * explicitly and the output turns out to be ASCII, switches to UTF-8 (the PASE default).
     *
     * @param _timeoutSeconds 0 for no timeout; otherwise the process is killed if the prompt does not appear in time
     * @param _charsetExplicit true if the encoding was configured explicitly and must not be adjusted
     * @throws SQLException if the utility does not start, reports a connection error, or its output does not match
     *             an explicitly configured encoding
     */
    public void start(final int _timeoutSeconds, final boolean _charsetExplicit) throws SQLException {
        final InputStream in = m_transport.getInput();
        m_out = m_transport.getOutput();
        armTimeout(_timeoutSeconds);
        // the prompt is printed without a newline while the utility waits for input
        final byte[] want = PROMPT.getBytes(m_charset);
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
            m_state = State.DEAD;
            throw connectionError("Unable to read from the db2 utility", e);
        } finally {
            disarmTimeout();
        }
        final byte[] s = seen.toByteArray();
        if (asciiDetected) {
            if (_charsetExplicit) {
                m_state = State.DEAD;
                throw new SQLNonTransientConnectionException("The db2 utility output appears to be ASCII, but encoding "
                        + m_charset.name() + " was configured. Set the 'encoding' property to an ASCII-based charset "
                        + "(e.g. UTF-8 or ISO-8859-1).", "08001");
            }
            m_encodingNote = "db2 utility output is ASCII-based; using UTF-8 instead of " + m_charset.name();
            m_charset = StandardCharsets.UTF_8;
            m_newline = newlineByte(m_charset);
            m_tracer.trace("encoding", m_encodingNote);
        } else if (!endsWith(s, want)) {
            m_state = State.DEAD;
            final String text = new String(s, m_charset).trim();
            if (text.contains("**** CLI ERROR")) {
                m_transport.kill();
                throw parseErrorText(text);
            }
            if (m_killedByTimeout) {
                throw new SQLNonTransientConnectionException("Timed out waiting for the db2 utility to start. Output: "
                        + text, "08001");
            }
            final String err = m_transport.getErrorText();
            throw new SQLNonTransientConnectionException("The db2 utility did not start"
                    + (text.isEmpty() ? "" : ". Output: " + text) + (err.isEmpty() ? "" : ". Errors: " + err)
                    + (text.isEmpty() && err.isEmpty() ? " (no output; check the encoding property and the paths "
                            + "of qsh and db2)" : ""), "08001");
        }
        m_reader = new LineReader(in, m_newline, PROMPT.getBytes(m_charset));
        m_state = State.IDLE;
        // A failed connect (bad password, unknown RDB) reports a CLI error before the first prompt.
        final String pre = new String(s, 0, s.length - want.length, m_charset);
        if (pre.contains("**** CLI ERROR")) {
            final SQLException e = parseErrorText(pre);
            close();
            throw e;
        }
        m_tracer.trace("ready", "encoding " + m_charset.name());
    }

    /**
     * Tells whether {@code _s} ends with {@code _suffix}.
     *
     * @param _s the bytes to test
     * @param _suffix the expected trailing bytes
     * @return true if the last bytes of {@code _s} equal {@code _suffix}
     */
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

    /**
     * Converts the CLI error block the utility printed before its first prompt into a connection failure.
     *
     * @param _text the utility output preceding the prompt
     * @return a connection exception carrying the reported message, SQLSTATE and native error code
     */
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
     * @return the response; for a query, the session then holds an open result until its rows are read
     * @throws SQLException if the session is unusable, the statement timed out, or the utility cannot be reached
     */
    public Response send(final String _sql, final int _timeoutSeconds) throws SQLException {
        prepareForCommand();
        m_tracer.trace("send", _sql);
        try {
            write(_sql);
        } catch (final IOException e) {
            m_state = State.DEAD;
            throw connectionError("Unable to send to the db2 utility", e);
        }
        m_killedByTimeout = false;
        m_killed = false;
        armTimeout(_timeoutSeconds);
        final Response r;
        try {
            m_inFlight = true;
            r = readResponse();
        } catch (final SQLException e) {
            disarmTimeout();
            throw e;
        } finally {
            m_inFlight = false;
        }
        if (r.isResult()) {
            // The header is printed before the query has produced anything; keep the timeout until the first row.
            m_rowTimeoutSeconds = _timeoutSeconds;
        } else {
            disarmTimeout();
        }
        if (m_killedByTimeout && r.isError() && "57014".equals(r.getError().getSQLState())) {
            if (r.isResult()) {
                discardRows();
            }
            throw new SQLTimeoutException("The statement exceeded the query timeout of " + _timeoutSeconds
                    + " seconds and was cancelled", "57014", r.getError().getErrorCode());
        }
        return r;
    }

    /**
     * Ensures the session is idle, asking the owner of any open result to release it.
     *
     * @throws SQLException if the session has ended or cannot be made idle
     */
    void prepareForCommand() throws SQLException {
        if (State.DEAD == m_state) {
            throw new SQLNonTransientConnectionException("The db2 utility session has ended", "08003");
        }
        if (State.RESULT == m_state) {
            final ResultOwner o = m_owner;
            if (null != o) {
                o.releaseSession();
            }
            if (State.RESULT == m_state) {
                discardRows();
            }
        }
        m_owner = null;
        if (State.IDLE != m_state) {
            throw new SQLNonTransientConnectionException("The db2 utility session is not ready", "08S01");
        }
    }

    /**
     * Writes a statement to the utility, split into input lines it accepts, followed by the terminator.
     *
     * @param _sql the single-line statement
     * @throws IOException if writing to the utility fails
     */
    private void write(final String _sql) throws IOException {
        final List<String> lines = splitLines(_sql, MAX_INPUT_LINE);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(_sql.length() + 16);
        for (int i = 0; i < lines.size(); i++) {
            bos.write(lines.get(i).getBytes(m_charset));
            if (i == lines.size() - 1) {
                bos.write(String.valueOf(ProcessTransport.TERMINATOR).getBytes(m_charset));
            }
            bos.write(m_newline);
        }
        m_out.write(bos.toByteArray());
        m_out.flush();
    }

    /**
     * Splits a single-line statement into input lines no longer than {@code _max} characters, breaking only at
     * spaces outside of string literals and delimited identifiers. The utility joins input lines with a space, so
     * breaking at an existing space is lossless.
     *
     * @param _sql the single-line statement
     * @param _max the preferred maximum line length; a line is longer only when it has no usable break
     * @return the input lines, in order
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
            } else if ('\'' == c || '"' == c) {
                quote = c;
            } else if (' ' == c) {
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

    /**
     * Reads the response to a statement up to the next prompt or, for a query, up to and including the dash line of
     * the result table.
     *
     * @return the response
     * @throws SQLException if the utility cannot be read or its output cannot be parsed
     */
    private Response readResponse() throws SQLException {
        final List<SQLWarning> warnings = new ArrayList<SQLWarning>();
        SQLException error = null;
        while (true) {
            final byte[] line = readLineOrDie(true);
            final String t = decode(line).trim();
            if (t.isEmpty() || "?>".equals(t)) {
                continue;
            }
            if ("DB2>".equals(t)) {
                m_state = State.IDLE;
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
            final Matcher m = s_db2Msg.matcher(t);
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
                m_state = State.DEAD;
                m_transport.kill();
                throw SqlErrors.protocol("expected column separator line after '" + t + "' but got '"
                        + decode(dashes) + "'");
            }
            int width = dashes.length;
            if (width > MAX_OUTPUT_LINE) {
                width = MAX_OUTPUT_LINE;
            }
            m_state = State.RESULT;
            m_resultWarnings.clear();
            return new Response(error, warnings, line, cols, width);
        }
    }

    /**
     * Parses a line like {@code ----- --- ------} into [start,length] pairs.
     *
     * @param _line the raw line
     * @return the column ranges, or null if it is not a dash line
     */
    int[][] parseDashes(final byte[] _line) {
        final byte dash = "-".getBytes(m_charset)[0];
        final byte space = " ".getBytes(m_charset)[0];
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

    /**
     * Reads an error block after the {@code **** CLI ERROR *****} line.
     *
     * @return the reported error (which may carry a warning SQLSTATE)
     * @throws SQLException if the utility cannot be read
     */
    private SQLException readErrorBlock() throws SQLException {
        String sqlState = "HY000";
        int code = 0;
        final StringBuilder msg = new StringBuilder();
        while (true) {
            final byte[] line = readLineOrDie(true);
            final String t = decode(line).trim();
            if ("DB2>".equals(t)) {
                m_reader.unread(line);
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
        m_tracer.trace("error", sqlState + " " + code + " " + msg);
        return SqlErrors.create(msg.toString(), sqlState, code);
    }

    /**
     * Parses a native error code.
     *
     * @param _s the decimal text
     * @return the value, or 0 if the text is not a number
     */
    private static int parseIntSafe(final String _s) {
        try {
            return Integer.parseInt(_s);
        } catch (final NumberFormatException e) {
            // a missing or malformed native error code is reported as 0 by design
            return 0;
        }
    }

    // =================================================================================== rows

    /** Registers who is consuming the current result, so it can be asked to release the session. */
    public void setOwner(final ResultOwner _owner) {
        m_owner = _owner;
    }

    public boolean isOwner(final ResultOwner _owner) {
        return State.RESULT == m_state && m_owner == _owner;
    }

    /** Warnings reported while reading the rows of the current result. */
    public List<SQLWarning> getResultWarnings() {
        return m_resultWarnings;
    }

    /**
     * Returns the next row line of the current result, reassembled to {@code _width} bytes when a value contained a
     * newline. Returns null when the result is exhausted (the session is then idle). Throws if the utility reported
     * an error while fetching.
     *
     * @param _width the fixed width of a row in bytes
     * @return the row bytes, or null when the result is exhausted
     * @throws SQLException if the utility reported an error, the statement timed out, or the utility cannot be read
     */
    public byte[] nextRowLine(final int _width) throws SQLException {
        if (m_rowTimeoutSeconds < 0) {
            return nextRowLine0(_width);
        }
        try {
            return nextRowLine0(_width);
        } catch (final SQLException e) {
            if (m_killedByTimeout && "57014".equals(e.getSQLState())) {
                throw new SQLTimeoutException("The statement exceeded the query timeout of " + m_rowTimeoutSeconds
                        + " seconds and was cancelled", "57014", e.getErrorCode());
            }
            throw e;
        } finally {
            m_rowTimeoutSeconds = -1;
            disarmTimeout();
        }
    }

    /**
     * Implements {@link #nextRowLine(int)} without the first-row timeout handling.
     *
     * @param _width the fixed width of a row in bytes
     * @return the row bytes, or null when the result is exhausted or no result is open
     * @throws SQLException if the utility reported an error or cannot be read
     */
    private byte[] nextRowLine0(final int _width) throws SQLException {
        if (State.RESULT != m_state) {
            return null;
        }
        byte[] line = readLineOrDie(false);
        if (0 == line.length) {
            final byte[] next = readLineOrDie(true);
            final String t = decode(next).trim();
            if (s_records.matcher(t).matches()) {
                finishToPrompt();
                return null;
            }
            if (t.startsWith("**** CLI ERROR")) {
                final SQLException e = readErrorBlock();
                if (!SqlErrors.isWarningState(e.getSQLState())) {
                    finishToPrompt();
                    throw e;
                }
                m_resultWarnings.add(SqlErrors.warning(e.getMessage(), e.getSQLState(), e.getErrorCode()));
                // after a warning, either more rows or the end of the result follow
                while (true) {
                    final byte[] l = readLineOrDie(true);
                    final String lt = decode(l).trim();
                    if (lt.isEmpty()) {
                        continue;
                    }
                    if (s_records.matcher(lt).matches()) {
                        finishToPrompt();
                        return null;
                    }
                    if ("DB2>".equals(lt)) {
                        m_state = State.IDLE;
                        return null;
                    }
                    m_reader.unread(l);
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

    /**
     * Joins two pieces of a row that the utility split at an embedded newline.
     *
     * @param _a the first piece
     * @param _b the second piece
     * @return {@code _a}, the newline byte, then {@code _b}
     */
    private byte[] join(final byte[] _a, final byte[] _b) {
        final byte[] r = new byte[_a.length + 1 + _b.length];
        System.arraycopy(_a, 0, r, 0, _a.length);
        r[_a.length] = m_newline;
        System.arraycopy(_b, 0, r, _a.length + 1, _b.length);
        return r;
    }

    /**
     * Reads and discards the remainder of the current result. Errors reported while discarding are ignored unless
     * the session ended.
     *
     * @throws SQLException if the session ended while discarding
     */
    public void discardRows() throws SQLException {
        while (State.RESULT == m_state) {
            try {
                if (null == nextRowLine(1)) {
                    break;
                }
            } catch (final SQLException e) {
                if (State.DEAD == m_state) {
                    throw e;
                }
                // errors while discarding are irrelevant to the caller
                m_tracer.trace("discard", e);
            }
        }
    }

    /**
     * Reads up to the next prompt, collecting any warnings, and leaves the session idle.
     *
     * @throws SQLException if the utility cannot be read
     */
    private void finishToPrompt() throws SQLException {
        while (true) {
            final byte[] line = readLineOrDie(true);
            final String t = decode(line).trim();
            if ("DB2>".equals(t)) {
                m_state = State.IDLE;
                return;
            }
            if (t.startsWith("**** CLI ERROR")) {
                final SQLException e = readErrorBlock();
                if (SqlErrors.isWarningState(e.getSQLState())) {
                    m_resultWarnings.add(SqlErrors.warning(e.getMessage(), e.getSQLState(), e.getErrorCode()));
                }
            }
        }
    }

    // =================================================================================== I/O helpers

    /**
     * Reads the next output line; marks the session dead if the utility cannot be read or has ended.
     *
     * @param _promptAware true to return an unterminated prompt as a line
     * @return the line (never null)
     * @throws SQLException if reading fails or the utility ended (a timeout or cancel is reported as such)
     */
    private byte[] readLineOrDie(final boolean _promptAware) throws SQLException {
        final byte[] line;
        try {
            line = _promptAware ? m_reader.readLineOrPrompt() : m_reader.readLine();
        } catch (final IOException e) {
            m_state = State.DEAD;
            if (m_killedByTimeout) {
                throw new SQLTimeoutException("The statement timed out; the db2 utility session was ended", "57014");
            }
            throw connectionError("Error reading from the db2 utility", e);
        }
        if (null == line) {
            m_state = State.DEAD;
            if (m_killedByTimeout) {
                throw new SQLTimeoutException("The statement timed out; the db2 utility session was ended", "57014");
            }
            if (m_killed) {
                throw new SQLNonTransientConnectionException("The statement was cancelled; the db2 utility session was "
                        + "ended", "57014");
            }
            final String err = m_transport.getErrorText();
            throw new SQLNonTransientConnectionException("The db2 utility ended unexpectedly"
                    + (err.isEmpty() ? "" : ": " + err), "08006");
        }
        if (m_tracer.isEnabled()) {
            m_tracer.trace("recv", decode(line));
        }
        return line;
    }

    public String decode(final byte[] _b) {
        return new String(_b, m_charset);
    }

    public String decode(final byte[] _b, final int _off, final int _len) {
        return new String(_b, _off, _len, m_charset);
    }

    private SQLException connectionError(final String _msg, final Exception _cause) {
        final String err = m_transport.getErrorText();
        return new SQLNonTransientConnectionException(_msg + (err.isEmpty() ? "" : ": " + err), "08006", _cause);
    }

    /**
     * Installs the action that cancels the running statement without ending the session (QSYS2.CANCEL_SQL against
     * the utility's job). Without one, timeouts and cancels end the session.
     */
    public void setCanceller(final Runnable _canceller) {
        m_canceller = _canceller;
    }

    /**
     * Schedules a cancel of the running statement after the given number of seconds.
     *
     * @param _seconds the timeout; nothing is scheduled when it is 0 or negative
     */
    private void armTimeout(final int _seconds) {
        if (_seconds <= 0) {
            return;
        }
        m_pendingTimeout = new TimerTask() {
            @Override
            public void run() {
                m_killedByTimeout = true;
                m_tracer.trace("timeout", "statement exceeded " + _seconds + "s; cancelling");
                cancelThenKill();
            }
        };
        s_timer.schedule(m_pendingTimeout, _seconds * 1000L);
    }

    /** Cancels any scheduled timeout and any scheduled kill that follows a cancel. */
    private void disarmTimeout() {
        if (null != m_pendingTimeout) {
            m_pendingTimeout.cancel();
            m_pendingTimeout = null;
        }
        if (null != m_pendingKill) {
            m_pendingKill.cancel();
            m_pendingKill = null;
        }
    }

    /**
     * Cancels the running statement: first politely (the statement ends with SQLSTATE 57014 and the session
     * survives), then, if the utility still has not responded after a grace period (e.g. it is running a CL
     * command), by abandoning the session.
     */
    private void cancelThenKill() {
        final Runnable c = m_canceller;
        if (null == c || State.STARTING == m_state) {
            m_transport.kill();
            return;
        }
        final Thread t = new Thread(c, "db2shell-cancel");
        t.setDaemon(true);
        t.start();
        final TimerTask k = new TimerTask() {
            @Override
            public void run() {
                if (m_inFlight || State.RESULT == m_state) {
                    m_tracer.trace("cancel", "no response after cancel; ending the db2 utility session");
                    m_transport.kill();
                }
            }
        };
        m_pendingKill = k;
        s_timer.schedule(k, CANCEL_GRACE_SECONDS * 1000L);
    }

    /** Cancels the running statement (Statement.cancel()); safe from any thread. */
    public void kill() {
        if (!m_inFlight && State.RESULT != m_state) {
            return;
        }
        m_killed = true;
        cancelThenKill();
    }

    /** Ends the session politely. */
    public void close() {
        if (State.DEAD == m_state) {
            m_transport.kill();
            return;
        }
        m_state = State.DEAD;
        try {
            if (null != m_out) {
                m_out.write(("quit" + ProcessTransport.TERMINATOR).getBytes(m_charset));
                m_out.write(m_newline);
                m_out.flush();
            }
        } catch (final IOException e) {
            m_tracer.trace("close", e);
        }
        m_transport.close();
    }
}
