package io.github.db2shell.jdbc.internal;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

/** A forward-only supply of rows. */
public interface RowSource {

    /** @return the next row, or null when exhausted */
    Object[] next() throws SQLException;

    /** Releases the source. Remaining rows are discarded. */
    void close() throws SQLException;

    /** Warnings reported while producing rows (may be empty). */
    List<SQLWarning> getWarnings();

    /** Rows held in memory. */
    final class ListRowSource implements RowSource {
        private final List<Object[]> m_rows;
        private int m_pos = 0;

        public ListRowSource(final List<Object[]> _rows) {
            m_rows = null == _rows ? new ArrayList<Object[]>() : _rows;
        }

        @Override
        public Object[] next() {
            return m_pos < m_rows.size() ? m_rows.get(m_pos++) : null;
        }

        @Override
        public void close() {
            m_pos = m_rows.size();
        }

        @Override
        public List<SQLWarning> getWarnings() {
            return new ArrayList<SQLWarning>();
        }
    }
}
