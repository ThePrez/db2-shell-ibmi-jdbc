package io.github.db2shell.jdbc.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.db2shell.jdbc.DriverConfig;

/** Launches {@code qsh -c "db2 -i -T%"} and exposes its pipes. */
public final class ProcessTransport implements Transport {

    /** Statement terminator passed to the utility with -T. Must be an EBCDIC-invariant character. */
    public static final char TERMINATOR = '%';

    private final Process m_process;
    private final PumpedInputStream m_stdout;
    private final ByteArrayOutputStream m_stderr = new ByteArrayOutputStream();
    private final Charset m_charset;
    private final Thread m_stderrPump;
    private final Tracer m_tracer;

    /**
     * Wraps a started process and starts the daemon threads that drain its standard output and standard error.
     *
     * @param _p the running qsh process
     * @param _cs the encoding of the process output
     * @param _tracer receives diagnostics
     */
    private ProcessTransport(final Process _p, final Charset _cs, final Tracer _tracer) {
        m_process = _p;
        m_charset = _cs;
        m_tracer = _tracer;
        m_stdout = new PumpedInputStream(_p.getInputStream(), "db2shell-stdout");
        m_stderrPump = new Thread(new Runnable() {
            /** Copies standard error (up to 64K) into the buffer reported by {@code getErrorText()}. */
            @Override
            public void run() {
                final byte[] b = new byte[4096];
                final InputStream es = m_process.getErrorStream();
                try {
                    int n;
                    while ((n = es.read(b)) > 0) {
                        synchronized (m_stderr) {
                            if (m_stderr.size() < 64 * 1024) {
                                m_stderr.write(b, 0, n);
                            }
                        }
                    }
                } catch (final IOException e) {
                    // expected when the process ends
                    m_tracer.trace("stderr", e);
                }
            }
        }, "db2shell-stderr");
        m_stderrPump.setDaemon(true);
        m_stderrPump.start();
    }

    /**
     * Builds the qsh command line for the given configuration. Visible for testing. The result contains the password
     * and must never be traced or logged as is.
     *
     * @param _cfg the connection configuration
     * @return the shell command that starts the db2 utility
     */
    public static String buildCommand(final DriverConfig _cfg) {
        final StringBuilder cmd = new StringBuilder();
        cmd.append(shellQuote(_cfg.getDb2Path())).append(" -i -T").append(TERMINATOR);
        if (null != _cfg.getRdb()) {
            cmd.append(" -r ").append(shellQuote(_cfg.getRdb()));
            if (null != _cfg.getUser()) {
                cmd.append(" -u ").append(shellQuote(_cfg.getUser()));
                cmd.append(" -p ").append(shellQuote(null == _cfg.getPassword() ? "" : _cfg.getPassword()));
            }
        }
        return cmd.toString();
    }

    /**
     * Launches the db2 utility through qsh. The traced command line has the password masked.
     *
     * @param _cfg the connection configuration
     * @param _tracer receives diagnostics for this transport
     * @return the transport for the started process
     * @throws IOException if the process cannot be started
     */
    public static ProcessTransport start(final DriverConfig _cfg, final Tracer _tracer) throws IOException {
        final List<String> args = new ArrayList<String>();
        args.add(_cfg.getQshPath());
        args.add("-c");
        final String cmd = buildCommand(_cfg);
        args.add(cmd);
        if (_tracer.isEnabled()) {
            _tracer.trace("launch", _cfg.getQshPath() + " -c " + cmd.replaceAll("-p '([^']|'\\\\'')*'", "-p '****'"));
        }
        final ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(false);
        return new ProcessTransport(pb.start(), _cfg.getCharset(), _tracer);
    }

    static String shellQuote(final String _s) {
        return "'" + _s.replace("'", "'\\''") + "'";
    }

    @Override
    public InputStream getInput() {
        return m_stdout;
    }

    @Override
    public OutputStream getOutput() {
        return m_process.getOutputStream();
    }

    @Override
    public String getErrorText() {
        synchronized (m_stderr) {
            return new String(m_stderr.toByteArray(), m_charset).trim();
        }
    }

    /**
     * Tells whether the qsh process is still running.
     *
     * @return true while the process has not exited
     */
    @Override
    public boolean isAlive() {
        try {
            m_process.exitValue();
            return false;
        } catch (final IllegalThreadStateException e) {
            // thrown by exitValue() precisely when the process has not exited yet
            return true;
        }
    }

    /**
     * Asks Db2 to end the SQL statement running in the given job, using {@code QSYS2.CANCEL_SQL} from a separate,
     * short-lived db2 utility process. The cancelled statement fails with SQLSTATE 57014 and the session survives.
     *
     * @param _cfg the connection configuration (paths and encoding of the utility)
     * @param _jobName the qualified name (number/user/name) of the job running the statement
     * @param _tracer receives diagnostics
     * @return true if the cancel request was accepted
     */
    public static boolean cancelSql(final DriverConfig _cfg, final String _jobName, final Tracer _tracer) {
        if (null == _jobName || !_jobName.matches("\\d{6}/[\\w$#@]{1,10}/[\\w$#@]{1,10}")) {
            return false;
        }
        final String cmd = shellQuote(_cfg.getDb2Path()) + " " + shellQuote("CALL QSYS2.CANCEL_SQL('" + _jobName + "')");
        _tracer.trace("cancel", cmd);
        try {
            final Process p = new ProcessBuilder(_cfg.getQshPath(), "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            final java.io.InputStream in = p.getInputStream();
            final byte[] b = new byte[4096];
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            final String text = new String(out.toByteArray(), _cfg.getCharset());
            _tracer.trace("cancel", text.trim());
            return !text.contains("CLI ERROR");
        } catch (final IOException e) {
            _tracer.trace("cancel", e);
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Releases any blocked reader and terminates the process, forcibly if it does not end within two seconds. Safe to
     * call from any thread, any number of times.
     */
    @Override
    public void kill() {
        // release any reader first: a job started by qsh may keep the pipe open after qsh itself ends
        m_stdout.abort();
        m_process.destroy();
        try {
            if (!m_process.waitFor(2, TimeUnit.SECONDS)) {
                m_process.destroyForcibly();
            }
        } catch (final InterruptedException e) {
            m_process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the utility's standard input and waits up to five seconds for it to end, killing it otherwise, then
     * releases any blocked reader.
     */
    @Override
    public void close() {
        try {
            m_process.getOutputStream().close();
        } catch (final IOException e) {
            m_tracer.trace("close", e);
        }
        try {
            if (!m_process.waitFor(5, TimeUnit.SECONDS)) {
                kill();
            }
        } catch (final InterruptedException e) {
            kill();
            Thread.currentThread().interrupt();
        }
        m_stdout.abort();
        try {
            m_process.getInputStream().close();
        } catch (final IOException e) {
            m_tracer.trace("close", e);
        }
    }
}
