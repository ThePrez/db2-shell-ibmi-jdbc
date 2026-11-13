package io.github.db2shell.jdbc.internal;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Minimal tracing facility; a disabled tracer costs a single boolean check. */
public final class Tracer {

    private static final Tracer s_disabled = new Tracer(false, null);

    private final boolean m_enabled;
    private final PrintWriter m_out;
    private final String m_id;
    private static int s_counter = 0;

    /**
     * Creates a tracer with a process-wide unique id that prefixes every trace line.
     *
     * @param _enabled whether trace output is written
     * @param _out where trace output is written (null when disabled)
     */
    private Tracer(final boolean _enabled, final PrintWriter _out) {
        m_enabled = _enabled;
        m_out = _out;
        synchronized (Tracer.class) {
            m_id = "db2shell#" + (++s_counter);
        }
    }

    /**
     * Returns a tracer for one connection. An enabled tracer writes to {@code _file} if it can be opened, otherwise to
     * the {@link DriverManager} log writer, otherwise to standard error.
     *
     * @param _enabled whether tracing is on; when false a shared no-op tracer is returned
     * @param _file the file to append trace output to, or null
     * @return the tracer
     */
    public static Tracer create(final boolean _enabled, final String _file) {
        if (!_enabled) {
            return s_disabled;
        }
        PrintWriter w = null;
        if (null != _file) {
            try {
                w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(_file, true), StandardCharsets.UTF_8), true);
            } catch (final IOException e) {
                System.err.println("db2shell: unable to open trace file " + _file + ": " + e);
            }
        }
        if (null == w) {
            w = DriverManager.getLogWriter();
        }
        if (null == w) {
            w = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        }
        return new Tracer(true, w);
    }

    public boolean isEnabled() {
        return m_enabled;
    }

    /**
     * Writes one timestamped trace line; does nothing when tracing is off. Callers must not pass passwords or other
     * secrets.
     *
     * @param _what a short tag naming the event
     * @param _msg the detail, converted with {@code String.valueOf}
     */
    public void trace(final String _what, final Object _msg) {
        if (!m_enabled) {
            return;
        }
        final String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        synchronized (m_out) {
            m_out.println(ts + " [" + m_id + "] " + _what + ": " + _msg);
            m_out.flush();
        }
    }
}
