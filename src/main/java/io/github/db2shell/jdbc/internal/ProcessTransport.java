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

    private final Process process;
    private final PumpedInputStream stdout;
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final Charset charset;
    private final Thread stderrPump;

    private ProcessTransport(final Process _p, final Charset _cs) {
        process = _p;
        charset = _cs;
        stdout = new PumpedInputStream(_p.getInputStream(), "db2shell-stdout");
        stderrPump = new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] b = new byte[4096];
                final InputStream es = process.getErrorStream();
                try {
                    int n;
                    while ((n = es.read(b)) > 0) {
                        synchronized (stderr) {
                            if (stderr.size() < 64 * 1024) {
                                stderr.write(b, 0, n);
                            }
                        }
                    }
                } catch (final IOException e) {
                    // process ended
                }
            }
        }, "db2shell-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    /** Builds the qsh command line for the given configuration. Visible for testing. */
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
        return new ProcessTransport(pb.start(), _cfg.getCharset());
    }

    static String shellQuote(final String _s) {
        return "'" + _s.replace("'", "'\\''") + "'";
    }

    @Override
    public InputStream getInput() {
        return stdout;
    }

    @Override
    public OutputStream getOutput() {
        return process.getOutputStream();
    }

    @Override
    public String getErrorText() {
        synchronized (stderr) {
            return new String(stderr.toByteArray(), charset).trim();
        }
    }

    @Override
    public boolean isAlive() {
        try {
            process.exitValue();
            return false;
        } catch (final IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Asks Db2 to end the SQL statement running in the given job, using {@code QSYS2.CANCEL_SQL} from a separate,
     * short-lived db2 utility process. The cancelled statement fails with SQLSTATE 57014 and the session survives.
     *
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

    @Override
    public void kill() {
        // release any reader first: a job started by qsh may keep the pipe open after qsh itself ends
        stdout.abort();
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        try {
            process.getOutputStream().close();
        } catch (final IOException e) {
            // ignore
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                kill();
            }
        } catch (final InterruptedException e) {
            kill();
            Thread.currentThread().interrupt();
        }
        stdout.abort();
        try {
            process.getInputStream().close();
        } catch (final IOException e) {
            // ignore
        }
    }
}
