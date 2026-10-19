package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.error.PostgresErrors;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;

/**
 * Turns a driver failure into a spec §6 code, or hands it back unchanged — an unexpected failure is a bug and must
 * surface as one. The caller always throws the result; nothing catches a database failure and carries on.
 */
final class SqlFailures {

    private SqlFailures() {}

    /** Inside the claim loop: only uq_claimed_seat means seat_taken, naming the seat whose insert failed. */
    static RuntimeException translate(DataAccessException failure, long seatBeingClaimed) {
        if (PostgresErrors.UNIQUE_VIOLATION.equals(PostgresErrors.sqlState(failure))
                && PostgresErrors.UQ_CLAIMED_SEAT.equals(PostgresErrors.constraintName(failure))) {
            return new DomainException(ErrorCode.SEAT_TAKEN, Map.of("seat_ids", List.of(seatBeingClaimed)));
        }
        return translate(failure);
    }

    /** The event PATCH, the one path that sets lock_timeout: a timeout there also asks for a retry (spec §5). */
    static RuntimeException translateOnPatchPath(DataAccessException failure) {
        if (PostgresErrors.LOCK_NOT_AVAILABLE.equals(PostgresErrors.sqlState(failure))) {
            return DomainException.of(ErrorCode.CONTENTION_RETRY);
        }
        return translate(failure);
    }

    /** Anywhere: deadlock and serialization failures are mapped defensively; the lock order should prevent both. */
    static RuntimeException translate(DataAccessException failure) {
        String state = PostgresErrors.sqlState(failure);
        if (PostgresErrors.DEADLOCK_DETECTED.equals(state) || PostgresErrors.SERIALIZATION_FAILURE.equals(state)) {
            return DomainException.of(ErrorCode.CONTENTION_RETRY);
        }
        return failure;
    }
}
