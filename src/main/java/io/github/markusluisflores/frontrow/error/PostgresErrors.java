package io.github.markusluisflores.frontrow.error;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads SQLState and constraint names from the driver's structured error, never from message text (spec §5).
 * Hibernate's ConstraintViolationException#getConstraintName parses the message instead and returns null when
 * Postgres reports in a language other than English, so it is deliberately not used.
 */
public final class PostgresErrors {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    public static final String NOT_NULL_VIOLATION = "23502";
    public static final String CHECK_VIOLATION = "23514";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String LOCK_NOT_AVAILABLE = "55P03";

    public static final String UQ_CLAIMED_SEAT = "uq_claimed_seat";

    private PostgresErrors() {}

    public static String sqlState(Throwable thrown) {
        PSQLException psql = find(thrown);
        return psql == null ? null : psql.getSQLState();
    }

    public static String constraintName(Throwable thrown) {
        PSQLException psql = find(thrown);
        if (psql == null) {
            return null;
        }
        ServerErrorMessage serverError = psql.getServerErrorMessage();
        return serverError == null ? null : serverError.getConstraint();
    }

    /**
     * Walks the cause chain, and any BatchUpdateException's own chain, to the first PSQLException. A single instanceof
     * on the top-level exception would miss it and turn seat_taken into a 500 (spec §5).
     */
    private static PSQLException find(Throwable thrown) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = thrown; current != null && seen.put(current, Boolean.TRUE) == null; ) {
            if (current instanceof PSQLException psql) {
                return psql;
            }
            if (current instanceof BatchUpdateException batch) {
                for (SQLException next = batch.getNextException();
                        next != null && seen.put(next, Boolean.TRUE) == null;
                        next = next.getNextException()) {
                    if (next instanceof PSQLException psql) {
                        return psql;
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }
}
