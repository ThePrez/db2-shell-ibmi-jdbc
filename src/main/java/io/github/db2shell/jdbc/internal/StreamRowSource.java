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

    protected final Session m_session;
    protected final int m_width;
    private final Object m_lock;
    private final Deque<Object[]> m_buffer = new ArrayDeque<Object[]>();
    private final long m_maxRows;
    private long m_produced = 0;
    private boolean m_exhausted = false;
    private boolean m_closed = false;
    private SQLException m_deferred;
    private final List<SQLWarning> m_warnings = new ArrayList<SQLWarning>();

    /**
     * Creates a source over the result currently open in the session and registers it as the session's result
     * owner.
     *
     * @param _lock
     *            the connection-level lock guarding the session
     * @param _session
     *            the session whose current result this source reads
     * @param _width
     *            the width of each output line
     * @param _maxRows
     *            0 for no limit; otherwise rows beyond the limit are discarded
     */
    protected StreamRowSource(final Object _lock, final Session _session, final int _width, final long _maxRows) {
        m_lock = _lock;
        m_session = _session;
        m_width = _width;
        m_maxRows = _maxRows;
        _session.setOwner(this);
    }

    /** Decodes one row whose first line has already been read. */
    protected abstract Object[] decodeRow(byte[] _firstLine) throws SQLException;

    /**
     * Reads the next physical line of the current row (for rows spanning lines).
     *
     * @return the line
     * @throws SQLException
     *             if the result ends in the middle of a row or the session fails
     */
    protected byte[] continuation() throws SQLException {
        final byte[] l = m_session.nextRowLine(m_width);
        if (null == l) {
            throw SqlErrors.protocol("result ended in the middle of a row");
        }
        return l;
    }

    /**
     * Returns the next row: buffered rows first, then any error deferred while buffering, then rows read from the
     * session.
     *
     * @return the next row, or null when exhausted or closed
     * @throws SQLException
     *             if reading or decoding fails
     */
    @Override
    public Object[] next() throws SQLException {
        synchronized (m_lock) {
            if (!m_buffer.isEmpty()) {
                return m_buffer.poll();
            }
            if (null != m_deferred) {
                final SQLException e = m_deferred;
                m_deferred = null;
                throw e;
            }
            if (m_exhausted || m_closed) {
                return null;
            }
            return readOne();
        }
    }

    /**
     * Reads and decodes one row from the session, marking the source exhausted at the end of the result (or when
     * the row limit is reached) and collecting the result's warnings at that point.
     *
     * @return the row, or null if the result has ended or the session now belongs to another result
     * @throws SQLException
     *             if reading or decoding fails
     */
    private Object[] readOne() throws SQLException {
        if (!m_session.isOwner(this)) {
            m_exhausted = true;
            return null;
        }
        final byte[] line;
        try {
            line = m_session.nextRowLine(m_width);
        } catch (final SQLException e) {
            m_exhausted = true;
            collectWarnings();
            throw e;
        }
        if (null == line) {
            m_exhausted = true;
            collectWarnings();
            return null;
        }
        final Object[] row = decodeRow(line);
        m_produced++;
        if (m_maxRows > 0 && m_produced >= m_maxRows) {
            // the driver could not push the limit into the query (raw mode): discard the rest
            m_exhausted = true;
            m_session.discardRows();
            collectWarnings();
        }
        return row;
    }

    /** Records a warning produced while decoding rows. */
    protected void addWarning(final SQLWarning _w) {
        m_warnings.add(_w);
    }

    private void collectWarnings() {
        m_warnings.addAll(m_session.getResultWarnings());
    }

    /**
     * Gives up the session: the remaining rows are buffered (or discarded if this source is closed). An error
     * while buffering is deferred to the next {@link #next()} call unless the session itself died.
     *
     * @throws SQLException
     *             if the session died while the rows were being read
     */
    @Override
    public void releaseSession() throws SQLException {
        // called with the connection lock held
        if (m_closed) {
            m_session.discardRows();
            m_exhausted = true;
            return;
        }
        try {
            while (!m_exhausted) {
                final Object[] row = readOne();
                if (null == row) {
                    break;
                }
                m_buffer.add(row);
            }
        } catch (final SQLException e) {
            if (m_session.isDead()) {
                throw e;
            }
            m_deferred = e;
        }
    }

    /**
     * Closes the source and drops buffered rows. Unread output is discarded lazily, when the session is next
     * needed.
     */
    @Override
    public void close() throws SQLException {
        synchronized (m_lock) {
            m_closed = true;
            m_buffer.clear();
            // Defer discarding the remaining output until the session is needed again; this keeps close() cheap
            // when the connection is about to be closed anyway.
        }
    }

    @Override
    public List<SQLWarning> getWarnings() {
        return m_warnings;
    }
}
