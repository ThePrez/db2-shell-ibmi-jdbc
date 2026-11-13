package io.github.db2shell.jdbc.internal;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;
import java.sql.SQLWarning;

/** Factory for {@link SQLException}s of the appropriate JDBC 4 subclass, keyed by SQLSTATE class. */
public final class SqlErrors {

    private SqlErrors() {
    }

    public static SQLException create(final String _message, final String _sqlState, final int _vendorCode) {
        final String state = null == _sqlState ? "HY000" : _sqlState;
        final String cls = state.length() >= 2 ? state.substring(0, 2) : state;
        if ("0A".equals(cls)) {
            return new SQLFeatureNotSupportedException(_message, state, _vendorCode);
        }
        if ("08".equals(cls)) {
            return new SQLNonTransientConnectionException(_message, state, _vendorCode);
        }
        if ("22".equals(cls)) {
            return new SQLDataException(_message, state, _vendorCode);
        }
        if ("23".equals(cls)) {
            return new SQLIntegrityConstraintViolationException(_message, state, _vendorCode);
        }
        if ("28".equals(cls)) {
            return new SQLInvalidAuthorizationSpecException(_message, state, _vendorCode);
        }
        if ("40".equals(cls)) {
            return new SQLTransactionRollbackException(_message, state, _vendorCode);
        }
        if ("42".equals(cls)) {
            return new SQLSyntaxErrorException(_message, state, _vendorCode);
        }
        if ("57".equals(cls)) {
            // resource not available, statement cancelled (57014), lock timeout (57033)
            return new SQLTransientException(_message, state, _vendorCode);
        }
        if ("58".equals(cls)) {
            return new SQLNonTransientException(_message, state, _vendorCode);
        }
        if ("2D".equals(cls) || "25".equals(cls) || "24".equals(cls) || "26".equals(cls) || "44".equals(cls)) {
            return new SQLNonTransientException(_message, state, _vendorCode);
        }
        return new SQLException(_message, state, _vendorCode);
    }

    public static SQLWarning warning(final String _message, final String _sqlState, final int _vendorCode) {
        return new SQLWarning(_message, _sqlState, _vendorCode);
    }

    public static SQLFeatureNotSupportedException unsupported(final String _what) {
        return new SQLFeatureNotSupportedException(_what + " is not supported by the db2shell driver", "0A000");
    }

    public static SQLException closed(final String _what) {
        return new SQLNonTransientException(_what + " is closed", "HY010");
    }

    public static SQLException protocol(final String _message) {
        return new SQLNonTransientConnectionException("Unexpected output from the db2 utility: " + _message, "08S01");
    }

    public static boolean isWarningState(final String _sqlState) {
        return null != _sqlState && (_sqlState.startsWith("01") || _sqlState.startsWith("02"));
    }
}
