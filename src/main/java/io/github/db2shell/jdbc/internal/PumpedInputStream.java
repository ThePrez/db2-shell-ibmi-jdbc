package io.github.db2shell.jdbc.internal;

import java.io.IOException;
import java.io.InputStream;

/**
 * Copies a process's output on a daemon thread into a bounded buffer, so that readers can be released by
 * {@link #abort()} even when the process (or a job it started) keeps the pipe open. A blocked native read
 * cannot be interrupted reliably, but a wait on this buffer can.
 */
final class PumpedInputStream extends InputStream {

    private final byte[] buf = new byte[1024 * 1024];
    private int head = 0; // next byte to read
    private int count = 0; // bytes available
    private boolean eof = false;
    private boolean aborted = false;
    private IOException failure;

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
                        while (count == buf.length && !aborted) {
                            wait();
                        }
                        if (aborted) {
                            return;
                        }
                        final int tail = (head + count) % buf.length;
                        final int space = Math.min(buf.length - count, buf.length - tail);
                        final int len = Math.min(space, n - off);
                        System.arraycopy(chunk, off, buf, tail, len);
                        count += len;
                        off += len;
                        notifyAll();
                    }
                }
            }
        } catch (final IOException e) {
            synchronized (this) {
                failure = e;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                eof = true;
                notifyAll();
            }
        }
    }

    /** Releases all readers; subsequent reads fail. */
    synchronized void abort() {
        aborted = true;
        notifyAll();
    }

    private void await() throws IOException {
        while (count == 0 && !eof && !aborted) {
            try {
                wait();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        if (aborted) {
            throw new IOException("db2 utility session was ended");
        }
    }

    @Override
    public synchronized int read() throws IOException {
        await();
        if (count == 0) {
            if (null != failure) {
                throw failure;
            }
            return -1;
        }
        final int b = buf[head] & 0xff;
        head = (head + 1) % buf.length;
        count--;
        notifyAll();
        return b;
    }

    @Override
    public synchronized int read(final byte[] _b, final int _off, final int _len) throws IOException {
        if (_len == 0) {
            return 0;
        }
        await();
        if (count == 0) {
            if (null != failure) {
                throw failure;
            }
            return -1;
        }
        final int n = Math.min(_len, Math.min(count, buf.length - head));
        System.arraycopy(buf, head, _b, _off, n);
        head = (head + n) % buf.length;
        count -= n;
        notifyAll();
        return n;
    }

    @Override
    public synchronized int available() {
        return count;
    }

    @Override
    public void close() {
        abort();
    }
}
