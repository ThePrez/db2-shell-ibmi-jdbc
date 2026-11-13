package io.github.db2shell.jdbc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Splits a byte stream into lines on a single (encoding-specific) newline byte, without decoding. Working
 * at the byte level lets the protocol parser use exact byte widths, which is what the db2 utility pads to.
 */
public final class LineReader {

    private final InputStream in;
    private final byte newline;
    private final byte[] prompt;
    private final byte[] buf = new byte[64 * 1024];
    private int pos = 0;
    private int lim = 0;
    private byte[] pushback = null;
    private boolean eof = false;

    /**
     * @param _prompt the interactive prompt; the utility prints it without a newline, so a line consisting of
     *            exactly these bytes is returned as soon as no further output is pending (may be null)
     */
    public LineReader(final InputStream _in, final byte _newline, final byte[] _prompt) {
        in = _in;
        newline = _newline;
        prompt = _prompt;
    }

    public LineReader(final InputStream _in, final byte _newline) {
        this(_in, _newline, null);
    }

    private boolean isPrompt(final byte[] _acc, final int _len) {
        if (null == prompt || _len != prompt.length) {
            return false;
        }
        for (int i = 0; i < _len; i++) {
            if (_acc[i] != prompt[i]) {
                return false;
            }
        }
        return true;
    }

    /** Reads a line, recognizing an unterminated prompt (see the constructor). */
    public byte[] readLineOrPrompt() throws IOException {
        return read(true);
    }

    /** Pushes a line back so that the next {@link #readLine()} returns it. Only one line can be pushed back. */
    public void unread(final byte[] _line) {
        if (null != pushback) {
            throw new IllegalStateException("pushback already in use");
        }
        pushback = _line;
    }

    /**
     * Reads the next line (without the newline byte).
     *
     * @return the line, or {@code null} at end of stream
     */
    public byte[] readLine() throws IOException {
        return read(false);
    }

    private byte[] read(final boolean _promptAware) throws IOException {
        if (null != pushback) {
            final byte[] ret = pushback;
            pushback = null;
            return ret;
        }
        byte[] acc = null;
        int accLen = 0;
        while (true) {
            if (pos >= lim) {
                if (_promptAware && null != acc && isPrompt(acc, accLen) && in.available() <= 0) {
                    // the prompt is waiting for input; its newline will only arrive with the next response
                    return Arrays.copyOf(acc, accLen);
                }
                if (eof || !fill()) {
                    eof = true;
                    if (null == acc) {
                        return null;
                    }
                    return Arrays.copyOf(acc, accLen);
                }
            }
            int i = pos;
            while (i < lim && buf[i] != newline) {
                i++;
            }
            final int n = i - pos;
            if (i < lim) {
                // found newline
                final byte[] line;
                if (null == acc) {
                    line = Arrays.copyOfRange(buf, pos, i);
                } else {
                    line = Arrays.copyOf(acc, accLen + n);
                    System.arraycopy(buf, pos, line, accLen, n);
                }
                pos = i + 1;
                return line;
            }
            // no newline in buffer: accumulate and refill
            if (null == acc) {
                acc = new byte[Math.max(256, n * 2)];
            } else if (accLen + n > acc.length) {
                acc = Arrays.copyOf(acc, Math.max(acc.length * 2, accLen + n));
            }
            System.arraycopy(buf, pos, acc, accLen, n);
            accLen += n;
            pos = lim;
        }
    }

    private boolean fill() throws IOException {
        final int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            return false;
        }
        pos = 0;
        lim = n;
        return true;
    }
}
