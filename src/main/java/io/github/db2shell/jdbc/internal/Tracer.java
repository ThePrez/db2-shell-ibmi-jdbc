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

    private static final Tracer DISABLED = new Tracer(false, null);

    private final boolean enabled;
    private final PrintWriter out;
    private final String id;
    private static int s_counter = 0;

    private Tracer(final boolean _enabled, final PrintWriter _out) {
        enabled = _enabled;
        out = _out;
        synchronized (Tracer.class) {
            id = "db2shell#" + (++s_counter);
        }
    }

    public static Tracer create(final boolean _enabled, final String _file) {
        if (!_enabled) {
            return DISABLED;
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
        return enabled;
    }

    public void trace(final String _what, final Object _msg) {
        if (!enabled) {
            return;
        }
        final String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        synchronized (out) {
            out.println(ts + " [" + id + "] " + _what + ": " + _msg);
            out.flush();
        }
    }
}
