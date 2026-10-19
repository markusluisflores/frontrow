package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduled, hygiene half of hold expiry (spec §5). Correctness never depends on it: hold creation expires lapsed
 * rows itself. Each method is its own transaction, so the key purge runs apart from the sweep, and each reads the
 * clock at its start, because neither waits on a lock.
 */
@Service
public class ExpirySweepService {

    /** Keys live in hold_request for 24 hours (spec §6). */
    static final Duration KEY_RETENTION = Duration.ofHours(24);

    private final LockingGateway gateway;
    private final Clock clock;

    public ExpirySweepService(LockingGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireLapsedHolds() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            return gateway.expireLapsedHolds(now);
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int purgeHoldRequests() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            return gateway.purgeHoldRequestsCreatedBefore(now.minus(KEY_RETENTION));
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }
}
