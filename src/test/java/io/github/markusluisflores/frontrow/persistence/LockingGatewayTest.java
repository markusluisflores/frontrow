package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.PostgresErrors;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * Proves the Postgres lock semantics spec §5 depends on, with real concurrent transactions: a single-threaded test
 * cannot tell a lock that waits from one that does not.
 *
 * <p>Two rules make these proofs honest. Every task runs on a dedicated virtual-thread executor, never the common
 * ForkJoinPool — a latch-blocked task there can starve the pool on a small machine, and a test would then "prove" a
 * lock waits when nothing was ever scheduled. And a waiter is only treated as blocked once Postgres itself reports a
 * session waiting on a lock, read from pg_stat_activity, rather than inferring it from a TimeoutException.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LockingGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    LockingGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    ExecutorService pool;
    long eventId;
    long venueId;
    long eventSeatId;
    long secondEventSeatId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        jdbc.sql("TRUNCATE order_line, ticket_order, hold_request, seat_hold, event_seat, event, seat, venue")
                .update();
        venueId = jdbc.sql("INSERT INTO venue (name) VALUES ('Lock Hall') RETURNING id")
                .query(Long.class)
                .single();
        long seatOne = insertSeat("A", 1);
        long seatTwo = insertSeat("A", 2);
        eventId = jdbc.sql("""
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (:venue, 'Lock test', :starts, :open, :close, 'ON_SALE', 'CAD') RETURNING id""")
                .param("venue", venueId)
                .param("starts", utc(NOW.plus(30, ChronoUnit.DAYS)))
                .param("open", utc(NOW.minus(1, ChronoUnit.DAYS)))
                .param("close", utc(NOW.plus(29, ChronoUnit.DAYS)))
                .query(Long.class)
                .single();
        eventSeatId = insertEventSeat(seatOne);
        secondEventSeatId = insertEventSeat(seatTwo);
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    @Test
    void readsTheEventRowUnderAShareLock() {
        EventRow row = tx.execute(status -> gateway.lockEventForShare(eventId)).orElseThrow();
        assertThat(row.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(row.venueId()).isEqualTo(venueId);
        assertThat(row.currency()).isEqualTo("CAD");
        assertThat(row.salesOpenAt()).isEqualTo(NOW.minus(1, ChronoUnit.DAYS));
    }

    @Test
    void reportsAMissingEvent() {
        assertThat(tx.<Optional<EventRow>>execute(status -> gateway.lockEventForShare(-1L)))
                .isEmpty();
    }

    @Test
    void twoShareLocksOnTheSameEventDoNotBlockEachOther() throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForShare(eventId);
            firstHolds.countDown();
            awaitQuietly(release);
        }));
        assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();

        // No latch games here: if a share lock blocked a share lock, this call would simply never return.
        Future<Boolean> second = pool.submit(
                () -> tx.execute(status -> gateway.lockEventForShare(eventId).isPresent()));
        assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
    }

    @Test
    void anExclusiveEventLockMakesAShareLockWait() throws Exception {
        CountDownLatch exclusiveHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            exclusiveHeld.countDown();
            awaitQuietly(release);
        }));
        assertThat(exclusiveHeld.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> waiter = pool.submit(() -> tx.execute(status -> {
            waiterStarted.countDown();
            return gateway.lockEventForShare(eventId).isPresent();
        }));
        assertThat(waiterStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // Postgres's own view of the world: a session is blocked on a lock, not merely unscheduled.
        awaitSessionsWaitingOnLocks(1);
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void lockTimeoutTurnsAnUnavailableLockIntoAPostgresTimeout() throws Exception {
        CountDownLatch exclusiveHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            exclusiveHeld.countDown();
            awaitQuietly(release);
        }));
        assertThat(exclusiveHeld.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> {
                gateway.setLockTimeout();
                gateway.lockEventForUpdate(eventId);
            }));
            assertThat(thrown)
                    .as("the second exclusive lock must time out, not wait forever")
                    .isNotNull();
            assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(PostgresErrors.LOCK_NOT_AVAILABLE);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theAdvisoryLockSerializesOneOwnerAndNotTwo() throws Exception {
        CountDownLatch aliceHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAliceStarted = new CountDownLatch(1);

        Future<?> alice = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockOwner("alice");
            aliceHolds.countDown();
            awaitQuietly(release);
        }));
        assertThat(aliceHolds.await(10, TimeUnit.SECONDS)).isTrue();

        // A different owner is a different key, so this must not wait at all.
        pool.submit(() -> tx.executeWithoutResult(status -> gateway.lockOwner("bob")))
                .get(10, TimeUnit.SECONDS);

        Future<?> secondAlice = pool.submit(() -> tx.executeWithoutResult(status -> {
            secondAliceStarted.countDown();
            gateway.lockOwner("alice");
        }));
        assertThat(secondAliceStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitSessionsWaitingOnLocks(1);
        assertThat(secondAlice.isDone()).isFalse();

        release.countDown();
        secondAlice.get(10, TimeUnit.SECONDS);
        alice.get(10, TimeUnit.SECONDS);
    }

    @Test
    void expiringAHoldIsAnImmediateUpdateAndIsIdempotent() {
        UUID group = UUID.randomUUID();
        long holdId = insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.minusSeconds(1));

        tx.executeWithoutResult(status -> {
            assertThat(gateway.expireHold(holdId)).isTrue();
            // Read back through SQL inside the same transaction: the UPDATE already reached the database.
            assertThat(statusOf(holdId)).isEqualTo("EXPIRED");
            assertThat(gateway.expireHold(holdId)).isFalse();
        });
    }

    @Test
    void readsTheActiveHoldForASeatAndIgnoresEndedOnes() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.RELEASED, NOW.plusSeconds(600));
        assertThat(tx.<Optional<HoldRow>>execute(status -> gateway.lockActiveHoldForSeat(eventSeatId)))
                .isEmpty();

        long holdId = insertHold(eventSeatId, UUID.randomUUID(), "bob", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        HoldRow row =
                tx.execute(status -> gateway.lockActiveHoldForSeat(eventSeatId)).orElseThrow();
        assertThat(row.id()).isEqualTo(holdId);
        assertThat(row.owner()).isEqualTo("bob");
        assertThat(row.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(row.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void locksGroupRowsInAscendingSeatOrder() {
        UUID group = UUID.randomUUID();
        // Insert the higher seat id first, so ascending order cannot come from insertion order.
        insertHold(secondEventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));

        List<HoldRow> rows = tx.execute(status -> gateway.lockGroupRows(group, "alice"));
        assertThat(rows).extracting(HoldRow::eventSeatId).containsExactly(eventSeatId, secondEventSeatId);
    }

    @Test
    void doesNotReturnAnotherOwnersGroup() {
        UUID group = UUID.randomUUID();
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        assertThat(tx.<List<HoldRow>>execute(status -> gateway.lockGroupRows(group, "bob")))
                .isEmpty();
    }

    @Test
    void countsOnlyGroupsWithAtLeastOneLiveRow() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(secondEventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.minusSeconds(1));
        assertThat(tx.<Integer>execute(status -> gateway.countActiveHoldGroups("alice", NOW)))
                .isEqualTo(1);
    }

    /** Waits until Postgres reports at least `expected` sessions blocked on a lock, so "it waits" is observed. */
    private void awaitSessionsWaitingOnLocks(int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int waiting = jdbc.sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock'""").query(Integer.class).single();
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no session reported waiting on a lock within 10s");
    }

    private long insertSeat(String row, int number) {
        return jdbc.sql("""
                        INSERT INTO seat (venue_id, section, row_label, seat_number)
                        VALUES (:venue, 'Floor', :row, :number) RETURNING id""")
                .param("venue", venueId)
                .param("row", row)
                .param("number", number)
                .query(Long.class)
                .single();
    }

    private long insertEventSeat(long seatId) {
        return jdbc.sql("""
                        INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
                        VALUES (:event, :seat, :venue, 5000) RETURNING id""")
                .param("event", eventId)
                .param("seat", seatId)
                .param("venue", venueId)
                .query(Long.class)
                .single();
    }

    private long insertHold(long eventSeat, UUID group, String owner, HoldStatus status, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                        VALUES (:seat, :group, :owner, :status, :expires, :created) RETURNING id""")
                .param("seat", eventSeat)
                .param("group", group)
                .param("owner", owner)
                .param("status", status.name())
                .param("expires", utc(expiresAt))
                .param("created", utc(NOW))
                .query(Long.class)
                .single();
    }

    private String statusOf(long holdId) {
        return jdbc.sql("SELECT status FROM seat_hold WHERE id = :id")
                .param("id", holdId)
                .query(String.class)
                .single();
    }

    /** pgjdbc cannot infer a SQL type for a bare Instant, so every bind goes through OffsetDateTime. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A lock holder waits longer than the 10s an assertion spends polling for a waiter, so a slow poll cannot make
     * the holder give up its lock mid-assertion and turn a real pass into a flake.
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch did not open within 30s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
