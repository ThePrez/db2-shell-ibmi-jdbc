package io.github.db2shell.jdbc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Splits a byte stream into lines on a single (encoding-specific) newline byte, without decoding. Working
 * at the byte level lets the protocol parser use exact byte widths, which is what the db2 utility pads to.
 */
public final class LineReader {

    private final InputStream m_in;
    private final byte m_newline;
    private final byte[] m_prompt;
    private final byte[] m_buf = new byte[64 * 1024];
    private int m_pos = 0;
    private int m_lim = 0;
    private byte[] m_pushback = null;
    private boolean m_eof = false;

    /**
     * @param _in the stream to read lines from
     * @param _newline the byte that ends a line
     * @param _prompt the interactive prompt; the utility prints it without a newline, so a line consisting of
     *            exactly these bytes is returned as soon as no further output is pending (may be null)
     */
    public LineReader(final InputStream _in, final byte _newline, final byte[] _prompt) {
        m_in = _in;
        m_newline = _newline;
        m_prompt = _prompt;
    }

    public LineReader(final InputStream _in, final byte _newline) {
        this(_in, _newline, null);
    }

    /**
     * Tells whether the first {@code _len} bytes of {@code _acc} are exactly the prompt.
     *
     * @param _acc the accumulated bytes of the current, unterminated line
     * @param _len the number of valid bytes in {@code _acc}
     * @return true if a prompt is configured and the bytes match it
     */
    private boolean isPrompt(final byte[] _acc, final int _len) {
        if (null == m_prompt || _len != m_prompt.length) {
            return false;
        }
        for (int i = 0; i < _len; i++) {
            if (_acc[i] != m_prompt[i]) {
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
        if (null != m_pushback) {
            throw new IllegalStateException("pushback already in use");
        }
        m_pushback = _line;
    }

    /**
     * Reads the next line (without the newline byte).
     *
     * @return the line, or {@code null} at end of stream
     */
    public byte[] readLine() throws IOException {
        return read(false);
    }

    /**
     * Returns the pushed-back line if there is one, otherwise reads the next line from the stream. An unterminated
     * final line is returned as is at end of stream.
     *
     * @param _promptAware true to return an unterminated prompt as a line as soon as no further input is available
     * @return the line without its newline byte, or null at end of stream
     * @throws IOException if reading the underlying stream fails
     */
    private byte[] read(final boolean _promptAware) throws IOException {
        if (null != m_pushback) {
            final byte[] ret = m_pushback;
            m_pushback = null;
            return ret;
        }
        byte[] acc = null;
        int accLen = 0;
        while (true) {
            if (m_pos >= m_lim) {
                if (_promptAware && null != acc && isPrompt(acc, accLen) && m_in.available() <= 0) {
                    // the prompt is waiting for input; its newline will only arrive with the next response
                    return Arrays.copyOf(acc, accLen);
                }
                if (m_eof || !fill()) {
                    m_eof = true;
                    if (null == acc) {
                        return null;
                    }
                    return Arrays.copyOf(acc, accLen);
                }
            }
            int i = m_pos;
            while (i < m_lim && m_buf[i] != m_newline) {
                i++;
            }
            final int n = i - m_pos;
            if (i < m_lim) {
                // found newline
                final byte[] line;
                if (null == acc) {
                    line = Arrays.copyOfRange(m_buf, m_pos, i);
                } else {
                    line = Arrays.copyOf(acc, accLen + n);
                    System.arraycopy(m_buf, m_pos, line, accLen, n);
                }
                m_pos = i + 1;
                return line;
            }
            // no newline in buffer: accumulate and refill
            if (null == acc) {
                acc = new byte[Math.max(256, n * 2)];
            } else if (accLen + n > acc.length) {
                acc = Arrays.copyOf(acc, Math.max(acc.length * 2, accLen + n));
            }
            System.arraycopy(m_buf, m_pos, acc, accLen, n);
            accLen += n;
            m_pos = m_lim;
        }
    }

    /**
     * Refills the buffer from the underlying stream, blocking until some input is available.
     *
     * @return false at end of stream
     * @throws IOException if reading the underlying stream fails
     */
    private boolean fill() throws IOException {
        final int n = m_in.read(m_buf, 0, m_buf.length);
        if (n <= 0) {
            return false;
        }
        m_pos = 0;
        m_lim = n;
        return true;
    }
}
