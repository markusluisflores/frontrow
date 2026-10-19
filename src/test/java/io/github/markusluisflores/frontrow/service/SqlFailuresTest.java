package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

class SqlFailuresTest {

    @Test
    void aClaimIndexViolationIsSeatTakenNamingTheSeat() {
        RuntimeException translated = SqlFailures.translate(duplicate("uq_claimed_seat"), 42L);
        assertThat(translated).isInstanceOf(DomainException.class);
        DomainException domain = (DomainException) translated;
        assertThat(domain.errorCode()).isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(domain.details()).containsEntry("seat_ids", List.of(42L));
    }

    @Test
    void anyOtherUniqueViolationIsABugAndIsRethrownUnchanged() {
        DataAccessException other = duplicate("uq_sold_once");
        assertThat(SqlFailures.translate(other, 42L)).isSameAs(other);
    }

    @Test
    void aClaimIndexViolationOutsideTheClaimLoopIsAlsoRethrownUnchanged() {
        DataAccessException outside = duplicate("uq_claimed_seat");
        assertThat(SqlFailures.translate(outside)).isSameAs(outside);
    }

    @Test
    void deadlockAndSerializationFailuresAskTheCallerToRetry() {
        for (String state : List.of("40P01", "40001")) {
            RuntimeException translated =
                    SqlFailures.translate(new CannotAcquireLockException("lock", psql(state, null)));
            assertThat(translated).isInstanceOf(DomainException.class);
            assertThat(((DomainException) translated).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        }
    }

    @Test
    void aLockTimeoutOnThePatchPathAsksTheCallerToRetry() {
        RuntimeException translated =
                SqlFailures.translateOnPatchPath(new CannotAcquireLockException("timeout", psql("55P03", null)));
        assertThat(translated).isInstanceOf(DomainException.class);
        assertThat(((DomainException) translated).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
    }

    @Test
    void aLockTimeoutAnywhereElseIsABugAndIsRethrownUnchanged() {
        DataAccessException timeout = new CannotAcquireLockException("timeout", psql("55P03", null));
        assertThat(SqlFailures.translate(timeout)).isSameAs(timeout);
    }

    @Test
    void thePatchPathStillMapsDeadlocksAndRethrowsTheRest() {
        RuntimeException deadlock =
                SqlFailures.translateOnPatchPath(new CannotAcquireLockException("lock", psql("40P01", null)));
        assertThat(((DomainException) deadlock).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        DataAccessException other = duplicate("uq_sold_once");
        assertThat(SqlFailures.translateOnPatchPath(other)).isSameAs(other);
    }

    @Test
    void aFailureWithNoPostgresCauseIsRethrownUnchanged() {
        DataAccessException plain = new DuplicateKeyException("no driver error inside");
        assertThat(SqlFailures.translate(plain, 42L)).isSameAs(plain);
    }

    private static DataAccessException duplicate(String constraint) {
        return new DuplicateKeyException("duplicate", psql("23505", constraint));
    }

    private static PSQLException psql(String state, String constraint) {
        String serverMessage = "SERROR\u0000C" + state + "\u0000Mfailed\u0000"
                + (constraint == null ? "" : "n" + constraint + "\u0000");
        return new PSQLException(new ServerErrorMessage(serverMessage));
    }
}
