package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.PostgresErrors;
import io.github.markusluisflores.frontrow.support.LockWaits;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

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

    @Autowired
    JsonMapper json;

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
    void refusesToRunOutsideATransaction() {
        assertThat(catchThrowable(() -> gateway.lockOwner("alice")))
                .isInstanceOf(IllegalTransactionStateException.class);
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
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            exclusiveHeld.countDown();
            awaitQuietly(release);
        }));
        assertThat(exclusiveHeld.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockEventForShare(eventId).isPresent();
        }));

        // Postgres's own view: this waiter's session is blocked on a lock, not merely unscheduled.
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
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
        CompletableFuture<Integer> secondAlicePid = new CompletableFuture<>();

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
            secondAlicePid.complete(LockWaits.backendPid(jdbc));
            gateway.lockOwner("alice");
        }));
        LockWaits.awaitBlockedOnLock(jdbc, secondAlicePid.get(10, TimeUnit.SECONDS));
        assertThat(secondAlice.isDone()).isFalse();

        release.countDown();
        secondAlice.get(10, TimeUnit.SECONDS);
        alice.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aLockedActiveHoldMakesASecondLockerWait() throws Exception {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            assertThat(gateway.lockActiveHoldForSeat(eventSeatId)).isPresent();
            held.countDown();
            awaitQuietly(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockActiveHoldForSeat(eventSeatId).isPresent();
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aLockedGroupMakesASecondGroupLockerWait() throws Exception {
        UUID group = UUID.randomUUID();
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(secondEventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            assertThat(gateway.lockGroupRows(group, "alice")).hasSize(2);
            held.countDown();
            awaitQuietly(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Integer> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockGroupRows(group, "alice").size();
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo(2);
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aHoldExpiringExactlyNowIsExpired() {
        long holdId = insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW);

        tx.executeWithoutResult(status -> {
            assertThat(gateway.expireHold(holdId, NOW)).isTrue();
            assertThat(statusOf(holdId)).isEqualTo("EXPIRED");
        });
    }

    @Test
    void aGroupExpiringExactlyNowDoesNotCountTowardTheCap() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW);
        assertThat(tx.<Integer>execute(status -> gateway.countActiveHoldGroups("alice", NOW)))
                .isZero();
    }

    @Test
    void expiringAHoldIsAnImmediateUpdateAndIsIdempotent() {
        UUID group = UUID.randomUUID();
        long holdId = insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.minusSeconds(1));

        tx.executeWithoutResult(status -> {
            assertThat(gateway.expireHold(holdId, NOW)).isTrue();
            // Read back through SQL inside the same transaction: the UPDATE already reached the database.
            assertThat(statusOf(holdId)).isEqualTo("EXPIRED");
            assertThat(gateway.expireHold(holdId, NOW)).isFalse();
        });
    }

    @Test
    void aStillLiveHoldIsNotExpired() {
        long holdId = insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(1));

        tx.executeWithoutResult(status -> {
            assertThat(gateway.expireHold(holdId, NOW)).isFalse();
            assertThat(statusOf(holdId)).isEqualTo("ACTIVE");
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

    @Test
    void theCountExcludesEndedRowsAndOtherOwnersAndCountsAMixedGroupOnce() {
        long[] seats = new long[5];
        for (int i = 0; i < seats.length; i++) {
            seats[i] = insertEventSeat(insertSeat("B", i + 1));
        }
        UUID mixed = UUID.randomUUID();
        // One group with a live row and a lapsed row: DISTINCT must count it once.
        insertHold(seats[0], mixed, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(seats[1], mixed, "alice", HoldStatus.ACTIVE, NOW.minusSeconds(1));
        // A RELEASED row with a future expiry is not live.
        insertHold(seats[2], UUID.randomUUID(), "alice", HoldStatus.RELEASED, NOW.plusSeconds(600));
        // A live group of another owner is not alice's.
        insertHold(seats[3], UUID.randomUUID(), "bob", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        // A lapsed-only group is not live.
        insertHold(seats[4], UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.minusSeconds(5));

        assertThat(tx.<Integer>execute(status -> gateway.countActiveHoldGroups("alice", NOW)))
                .isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> gateway.countActiveHoldGroups("bob", NOW)))
                .isEqualTo(1);
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

    @Test
    void claimsAnIdempotencyKeyOnlyOnce() {
        tx.executeWithoutResult(status -> {
            assertThat(gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW))
                    .isTrue();
            // Its own uncommitted row conflicts at once: a transaction never waits on itself.
            assertThat(gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW))
                    .isFalse();
            // Keys are per owner.
            assertThat(gateway.claimIdempotencyKey("bob", "k1", "hash-a", NOW)).isTrue();
        });
    }

    @Test
    void aConcurrentClaimOfTheSameKeyWaitsThenSeesTheCommittedRow() throws Exception {
        assertThat(racingClaimAfterFirstClaim(false)).isFalse();
    }

    @Test
    void aConcurrentClaimOfTheSameKeyProceedsWhenTheFirstRollsBack() throws Exception {
        assertThat(racingClaimAfterFirstClaim(true)).isTrue();
    }

    @Test
    void readsBackTheStoredResponseAsEqualJson() {
        UUID group = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW);
            gateway.recordHoldResponse("alice", "k1", group, "{\"ok\":true,\"seats\":[3,1]}");
        });

        StoredHoldRequest stored =
                tx.execute(status -> gateway.findHoldRequest("alice", "k1")).orElseThrow();
        assertThat(stored.requestHash()).isEqualTo("hash-a");
        // jsonb normalises whitespace and key order, so compare parsed trees, never raw text (spec §5, rev 2.6.2).
        assertThat(json.readTree(stored.responseJson())).isEqualTo(json.readTree("{\"seats\":[3,1],\"ok\":true}"));
        assertThat(tx.<Optional<StoredHoldRequest>>execute(status -> gateway.findHoldRequest("alice", "other")))
                .isEmpty();
    }

    @Test
    void recordingAResponseForAnUnclaimedKeyFails() {
        assertThat(catchThrowable(() -> tx.executeWithoutResult(
                        status -> gateway.recordHoldResponse("alice", "never", UUID.randomUUID(), "{}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void insertsAnActiveHoldImmediately() {
        tx.executeWithoutResult(status -> {
            long holdId = gateway.insertHold(eventSeatId, UUID.randomUUID(), "alice", NOW.plusSeconds(600), NOW);
            // Read back through SQL inside the same transaction: the INSERT already reached the database.
            assertThat(statusOf(holdId)).isEqualTo("ACTIVE");
        });
    }

    @Test
    void aSecondClaimOnAClaimedSeatViolatesTheClaimIndex() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));

        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(
                status -> gateway.insertHold(eventSeatId, UUID.randomUUID(), "bob", NOW.plusSeconds(600), NOW)));

        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(PostgresErrors.UNIQUE_VIOLATION);
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo(PostgresErrors.UQ_CLAIMED_SEAT);
    }

    @Test
    void anUncommittedClaimMakesAConcurrentInsertOnTheSameSeatWait() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.insertHold(eventSeatId, UUID.randomUUID(), "alice", NOW.plusSeconds(600), NOW);
            inserted.countDown();
            awaitQuietly(release);
            status.setRollbackOnly();
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Long> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.insertHold(eventSeatId, UUID.randomUUID(), "bob", NOW.plusSeconds(600), NOW);
        }));
        // Spec §5 step 4 counts unique-index insert waits in the lock order; this is that wait, observed.
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isPositive();
    }

    /** A first transaction claims "k1" and holds it open; a second claims the same key and must wait for it. */
    private boolean racingClaimAfterFirstClaim(boolean firstRollsBack) throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW);
            claimed.countDown();
            awaitQuietly(release);
            if (firstRollsBack) {
                status.setRollbackOnly();
            }
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> second = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW);
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(second.isDone()).isFalse();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        return second.get(10, TimeUnit.SECONDS);
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
