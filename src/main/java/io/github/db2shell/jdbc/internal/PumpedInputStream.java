package io.github.db2shell.jdbc.internal;

import java.io.IOException;
import java.io.InputStream;

/**
 * Copies a process's output on a daemon thread into a bounded buffer, so that readers can be released by
 * {@link #abort()} even when the process (or a job it started) keeps the pipe open. A blocked native read
 * cannot be interrupted reliably, but a wait on this buffer can.
 */
final class PumpedInputStream extends InputStream {

    private final byte[] m_buf = new byte[1024 * 1024];
    private int m_head = 0; // next byte to read
    private int m_count = 0; // bytes available
    private boolean m_eof = false;
    private boolean m_aborted = false;
    private IOException m_failure;

    /**
     * Starts a daemon thread that copies {@code _src} into this stream's buffer until end of stream, a read failure,
     * or {@link #abort()}.
     *
     * @param _src the process output to copy
     * @param _threadName the name of the pump thread
     */
    PumpedInputStream(final InputStream _src, final String _threadName) {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(_src);
            }
        }, _threadName);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Copies {@code _src} into the buffer, waiting while it is full. A read failure is recorded so that readers see
     * it once the buffered bytes are consumed; in every case end of stream is signalled to readers.
     *
     * @param _src the process output to copy
     */
    private void pump(final InputStream _src) {
        final byte[] chunk = new byte[16384];
        try {
            while (true) {
                final int n = _src.read(chunk);
                if (n < 0) {
                    break;
                }
                int off = 0;
                while (off < n) {
                    synchronized (this) {
                        while (m_count == m_buf.length && !m_aborted) {
                            wait();
                        }
                        if (m_aborted) {
                            return;
                        }
                        final int tail = (m_head + m_count) % m_buf.length;
                        final int space = Math.min(m_buf.length - m_count, m_buf.length - tail);
                        final int len = Math.min(space, n - off);
                        System.arraycopy(chunk, off, m_buf, tail, len);
                        m_count += len;
                        off += len;
                        notifyAll();
                    }
                }
            }
        } catch (final IOException e) {
            synchronized (this) {
                m_failure = e;
            }
        } catch (final InterruptedException e) {
            // The pump thread has no caller to report to: restore the flag and stop pumping; the finally block
            // signals end of stream to readers.
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                m_eof = true;
                notifyAll();
            }
        }
    }

    /** Releases all readers; subsequent reads fail. */
    synchronized void abort() {
        m_aborted = true;
        notifyAll();
    }

    /**
     * Waits until bytes are available, the pump has reached end of stream, or the stream was aborted. Must be called
     * while holding this object's monitor.
     *
     * @throws IOException if the stream was aborted or the waiting thread was interrupted
     */
    private void await() throws IOException {
        while (0 == m_count && !m_eof && !m_aborted) {
            try {
                wait();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        if (m_aborted) {
            throw new IOException("db2 utility session was ended");
        }
    }

    /**
     * Reads one byte, blocking until one is available.
     *
     * @return the byte, or -1 at end of stream
     * @throws IOException if the stream was aborted, the wait was interrupted, or the pump failed to read the process
     *             output
     */
    @Override
    public synchronized int read() throws IOException {
        await();
        if (0 == m_count) {
            if (null != m_failure) {
                throw m_failure;
            }
            return -1;
        }
        final int b = m_buf[m_head] & 0xff;
        m_head = (m_head + 1) % m_buf.length;
        m_count--;
        notifyAll();
        return b;
    }

    /**
     * Reads up to {@code _len} bytes, blocking until at least one is available.
     *
     * @param _b the destination buffer
     * @param _off the offset in {@code _b} at which to store the bytes
     * @param _len the maximum number of bytes to read
     * @return the number of bytes read, or -1 at end of stream
     * @throws IOException if the stream was aborted, the wait was interrupted, or the pump failed to read the process
     *             output
     */
    @Override
    public synchronized int read(final byte[] _b, final int _off, final int _len) throws IOException {
        if (0 == _len) {
            return 0;
        }
        await();
        if (0 == m_count) {
            if (null != m_failure) {
                throw m_failure;
            }
            return -1;
        }
        final int n = Math.min(_len, Math.min(m_count, m_buf.length - m_head));
        System.arraycopy(m_buf, m_head, _b, _off, n);
        m_head = (m_head + n) % m_buf.length;
        m_count -= n;
        notifyAll();
        return n;
    }

    @Override
    public synchronized int available() {
        return m_count;
    }

    @Override
    public void close() {
        abort();
    }
}
