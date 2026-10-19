package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.LockHolder;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spec §5, rev 2.6.3, "When now is read": each transaction reads the clock once the locks before its first
 * time-dependent decision are held. Each test makes a service wait on one of those locks, moves the clock while it
 * waits, then lets it go. A clock read on arrival would give a different answer.
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ClockReadPointTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    ReleaseService releases;

    @Autowired
    ConfirmService confirms;

    @Autowired
    LockingGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    @Test
    void aHoldThatWaitsOnTheEventLockAcrossSalesCloseIsRefused() throws Exception {
        Instant close = NOW.plusSeconds(30);
        long event = fixtures.event(venueId, "ON_SALE", NOW.minusSeconds(60), close);
        long seat = fixtures.eventSeats(event, venueId, 1).getFirst();

        Future<HoldResult> waiting;
        try (LockHolder patch = LockHolder.start(pool, tx, jdbc, () -> gateway.lockEventForUpdate(event))) {
            waiting = pool.submit(() -> holds.hold(new HoldCommand("alice", event, List.of(seat), "k1")));
            patch.awaitSomeoneBlocked();
            clock.set(close);
        }

        assertThat(rejection(waiting).errorCode()).isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void aHoldThatWaitsOnTheEventLockGetsAFullTtlButRecordsItsArrival() throws Exception {
        long event = fixtures.onSaleEvent(venueId);
        long seat = fixtures.eventSeats(event, venueId, 1).getFirst();
        Instant afterTheWait = NOW.plusSeconds(30);

        Future<HoldResult> waiting;
        try (LockHolder patch = LockHolder.start(pool, tx, jdbc, () -> gateway.lockEventForUpdate(event))) {
            waiting = pool.submit(() -> holds.hold(new HoldCommand("alice", event, List.of(seat), "k1")));
            patch.awaitSomeoneBlocked();
            clock.set(afterTheWait);
        }

        assertThat(waiting.get(10, TimeUnit.SECONDS).expiresAt()).isEqualTo(afterTheWait.plus(properties.holdTtl()));
        assertThat(fixtures.holdRequestCreatedAt("alice", "k1")).isEqualTo(NOW);
    }

    @Test
    void aReleaseThatWaitsOnItsRowsPastExpiryExpiresThemInsteadOfReleasing() throws Exception {
        long event = fixtures.onSaleEvent(venueId);
        HoldResult held = holds.hold(new HoldCommand("alice", event, fixtures.eventSeats(event, venueId, 2), "k1"));

        Future<ReleaseResult> waiting;
        try (LockHolder rows =
                LockHolder.start(pool, tx, jdbc, () -> gateway.lockGroupRows(held.holdGroupId(), "alice"))) {
            waiting = pool.submit(() -> releases.release(new HoldGroupCommand("alice", held.holdGroupId())));
            rows.awaitSomeoneBlocked();
            clock.set(held.expiresAt());
        }

        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 2));
    }

    @Test
    void aConfirmThatWaitsOnItsRowsPastExpiryIsExpired() throws Exception {
        long event = fixtures.onSaleEvent(venueId);
        HoldResult held = holds.hold(new HoldCommand("alice", event, fixtures.eventSeats(event, venueId, 2), "k1"));

        Future<OrderResult> waiting;
        try (LockHolder rows =
                LockHolder.start(pool, tx, jdbc, () -> gateway.lockGroupRows(held.holdGroupId(), "alice"))) {
            waiting = pool.submit(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())));
            rows.awaitSomeoneBlocked();
            clock.set(held.expiresAt());
        }

        assertThat(rejection(waiting).errorCode()).isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    private static DomainException rejection(Future<?> call) {
        Throwable thrown = catchThrowable(() -> call.get(10, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).isInstanceOf(DomainException.class);
        return (DomainException) thrown.getCause();
    }
}
