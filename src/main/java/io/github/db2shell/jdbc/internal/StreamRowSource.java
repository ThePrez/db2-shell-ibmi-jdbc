package io.github.db2shell.jdbc.internal;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rows read lazily from an open result in the db2 utility session. If the connection needs the session for
 * another command before this result has been consumed, the remaining rows are buffered in memory (or discarded,
 * if this source has been closed), so multiple open result sets per connection behave as expected.
 */
public abstract class StreamRowSource implements RowSource, Session.ResultOwner {

    protected final Session session;
    protected final int width;
    private final Object lock;
    private final Deque<Object[]> buffer = new ArrayDeque<Object[]>();
    private final long maxRows;
    private long produced = 0;
    private boolean exhausted = false;
    private boolean closed = false;
    private SQLException deferred;
    private final List<SQLWarning> warnings = new ArrayList<SQLWarning>();

    protected StreamRowSource(final Object _lock, final Session _session, final int _width, final long _maxRows) {
        lock = _lock;
        session = _session;
        width = _width;
        maxRows = _maxRows;
        _session.setOwner(this);
    }

    /** Decodes one row whose first line has already been read. */
    protected abstract Object[] decodeRow(byte[] _firstLine) throws SQLException;

    /** Reads the next physical line of the current row (for rows spanning lines). */
    protected byte[] continuation() throws SQLException {
        final byte[] l = session.nextRowLine(width);
        if (null == l) {
            throw SqlErrors.protocol("result ended in the middle of a row");
        }
        return l;
    }

    @Override
    public Object[] next() throws SQLException {
        synchronized (lock) {
            if (!buffer.isEmpty()) {
                return buffer.poll();
            }
            if (null != deferred) {
                final SQLException e = deferred;
                deferred = null;
                throw e;
            }
            if (exhausted || closed) {
                return null;
            }
            return readOne();
        }
    }

    private Object[] readOne() throws SQLException {
        if (!session.isOwner(this)) {
            exhausted = true;
            return null;
        }
        final byte[] line;
        try {
            line = session.nextRowLine(width);
        } catch (final SQLException e) {
            exhausted = true;
            collectWarnings();
            throw e;
        }
        if (null == line) {
            exhausted = true;
            collectWarnings();
            return null;
        }
        final Object[] row = decodeRow(line);
        produced++;
        if (maxRows > 0 && produced >= maxRows) {
            // the driver could not push the limit into the query (raw mode): discard the rest
            exhausted = true;
            session.discardRows();
            collectWarnings();
        }
        return row;
    }

    /** Records a warning produced while decoding rows. */
    protected void addWarning(final SQLWarning _w) {
        warnings.add(_w);
    }

    private void collectWarnings() {
        warnings.addAll(session.getResultWarnings());
    }

    @Override
    public void releaseSession() throws SQLException {
        // called with the connection lock held
        if (closed) {
            session.discardRows();
            exhausted = true;
            return;
        }
        try {
            while (!exhausted) {
                final Object[] row = readOne();
                if (null == row) {
                    break;
                }
                buffer.add(row);
            }
        } catch (final SQLException e) {
            if (session.isDead()) {
                throw e;
            }
            deferred = e;
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock) {
            closed = true;
            buffer.clear();
            // Defer discarding the remaining output until the session is needed again; this keeps close() cheap
            // when the connection is about to be closed anyway.
        }
    }

    @Override
    public List<SQLWarning> getWarnings() {
        return warnings;
    }
}
