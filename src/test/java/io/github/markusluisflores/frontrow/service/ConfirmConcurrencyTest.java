package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.Race;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec §5's centrepiece tests that need confirm and cancellation: 2 and 6, plus the double-clicked confirm the READ
 * COMMITTED list depends on, and a release racing a confirm of the same group. Every racer runs in its own transaction
 * on its own virtual thread, against real Postgres.
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ConfirmConcurrencyTest {

    private static final int REPETITIONS = 20;
    private static final int RELEASE_LAG_MS = 5;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    ReleaseService releases;

    @Autowired
    EventPatchService patches;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    /**
     * Test 2. A 3-seat group, and two racers straddling its expiry, each on its own clock: the owner confirms at
     * expires_at - 1ms, and a rival holds an overlapping set at expires_at + 1ms. Either may win, never both.
     */
    @Test
    void aConfirmAndAnOverlappingHoldAcrossExpiryHaveExactlyOneWinner() throws Exception {
        long eventId = fixtures.onSaleEvent(venueId);
        for (int rep = 0; rep < REPETITIONS; rep++) {
            List<Long> s = fixtures.eventSeats(eventId, venueId, 4);
            String owner = "owner-r" + rep;
            String rivalOwner = "rival-r" + rep;
            HoldResult group = holds.hold(new HoldCommand(owner, eventId, s.subList(0, 3), "group"));
            Instant expiry = group.expiresAt();
            List<Long> overlap = List.of(s.get(3), s.get(2), s.get(1));

            List<Race.Outcome<Object>> outcomes = Race.run(
                    pool,
                    List.of(
                            at(
                                    expiry.minusMillis(1),
                                    () -> confirms.confirm(new HoldGroupCommand(owner, group.holdGroupId()))),
                            at(
                                    expiry.plusMillis(1),
                                    () -> holds.hold(new HoldCommand(rivalOwner, eventId, overlap, "rival")))));

            Race.Outcome<Object> confirm = outcomes.get(0);
            Race.Outcome<Object> rival = outcomes.get(1);
            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(1);
            if (confirm.won()) {
                assertThat(rival.errorCode()).as("%s", rival.failure()).isEqualTo(ErrorCode.SEAT_TAKEN);
            } else {
                assertThat(confirm.errorCode()).as("%s", confirm.failure()).isEqualTo(ErrorCode.HOLD_EXPIRED);
            }
            // Never both a sale and a new claim on the overlapping seats.
            boolean sold = fixtures.ordersForGroup(group.holdGroupId()) == 1;
            boolean reclaimed = !fixtures.statusesOwnedBy(rivalOwner).isEmpty();
            assertThat(sold).as("repetition %d: sold xor reclaimed", rep).isNotEqualTo(reclaimed);
            for (long seat : s.subList(1, 3)) {
                assertThat(fixtures.claimsOn(seat)).isEqualTo(1);
            }
        }
    }

    /**
     * Test 6. An organiser cancels while the owner confirms. Either the confirm gets sales_closed and the event is
     * CANCELLED, or the order exists and the cancellation gets invalid_request. Never both, and no deadlock.
     */
    @Test
    void aCancellationAndAConfirmNeverBothSucceed() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            long eventId = fixtures.onSaleEvent(venueId);
            String owner = "buyer-r" + rep;
            HoldResult group =
                    holds.hold(new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));

            List<Race.Outcome<Object>> outcomes = Race.run(
                    pool,
                    List.<Callable<Object>>of(
                            () -> confirms.confirm(new HoldGroupCommand(owner, group.holdGroupId())),
                            () -> patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null))));

            Race.Outcome<Object> confirm = outcomes.get(0);
            Race.Outcome<Object> cancel = outcomes.get(1);
            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(1);
            if (cancel.won()) {
                assertThat(confirm.errorCode()).as("%s", confirm.failure()).isEqualTo(ErrorCode.SALES_CLOSED);
                assertThat(fixtures.eventStatus(eventId)).isEqualTo("CANCELLED");
                assertThat(fixtures.statusesOwnedBy(owner)).containsOnly("RELEASED");
                assertThat(fixtures.ordersForGroup(group.holdGroupId())).isZero();
            } else {
                assertThat(cancel.errorCode()).as("%s", cancel.failure()).isEqualTo(ErrorCode.INVALID_REQUEST);
                assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
                assertThat(fixtures.ordersForGroup(group.holdGroupId())).isEqualTo(1);
            }
        }
    }

    /** A double-clicked confirm: the second waits on the first's locks, then returns the same order. */
    @Test
    void twoConfirmsOfOneGroupInParallelYieldOneOrder() throws Exception {
        long eventId = fixtures.onSaleEvent(venueId);
        for (int rep = 0; rep < REPETITIONS; rep++) {
            String owner = "clicker-r" + rep;
            HoldResult group =
                    holds.hold(new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
            HoldGroupCommand command = new HoldGroupCommand(owner, group.holdGroupId());

            List<Race.Outcome<OrderResult>> outcomes =
                    Race.run(pool, List.of(() -> confirms.confirm(command), () -> confirms.confirm(command)));

            assertThat(outcomes).as("repetition %d", rep).allMatch(Race.Outcome::won);
            assertThat(outcomes.get(0).result()).isEqualTo(outcomes.get(1).result());
            assertThat(fixtures.ordersForGroup(group.holdGroupId())).isEqualTo(1);
        }
    }

    /**
     * The owner releases while the same owner confirms one live group. Either the order exists, the release gets
     * hold_not_active and the rows stay CONVERTED, or the rows are RELEASED, the confirm gets hold_not_active and no
     * order exists. Never both, never contention_retry, and no deadlock. Release does less work before its lock than
     * confirm does, so it starts up to RELEASE_LAG_MS late, varied by repetition, for both outcomes to occur.
     */
    @Test
    void aReleaseAndAConfirmOfOneGroupHaveExactlyOneWinner() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            long eventId = fixtures.onSaleEvent(venueId);
            String owner = "releaser-r" + rep;
            HoldResult group =
                    holds.hold(new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
            HoldGroupCommand command = new HoldGroupCommand(owner, group.holdGroupId());
            long lag = rep % (RELEASE_LAG_MS + 1);

            List<Race.Outcome<Object>> outcomes = Race.run(
                    pool,
                    List.<Callable<Object>>of(
                            () -> confirms.confirm(command), after(lag, () -> releases.release(command))));

            Race.Outcome<Object> confirm = outcomes.get(0);
            Race.Outcome<Object> release = outcomes.get(1);
            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .extracting(Race.Outcome::errorCode)
                    .doesNotContain(ErrorCode.CONTENTION_RETRY);
            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(1);
            if (confirm.won()) {
                assertThat(release.errorCode()).as("%s", release.failure()).isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
                assertThat(fixtures.statusesOwnedBy(owner)).containsOnly("CONVERTED");
                assertThat(fixtures.ordersForGroup(group.holdGroupId())).isEqualTo(1);
            } else {
                assertThat(confirm.errorCode()).as("%s", confirm.failure()).isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
                assertThat(release.result()).isEqualTo(new ReleaseResult(group.holdGroupId(), 2, 0));
                assertThat(fixtures.statusesOwnedBy(owner)).containsOnly("RELEASED");
                assertThat(fixtures.ordersForGroup(group.holdGroupId())).isZero();
            }
        }
    }

    /** Starts the call the given number of milliseconds after the race does. */
    private static Callable<Object> after(long millis, Callable<?> call) {
        return () -> {
            Thread.sleep(millis);
            return call.call();
        };
    }

    /** Runs the call on this racer's own clock: test 2 needs two instants at once. */
    private Callable<Object> at(Instant instant, Callable<?> call) {
        return () -> {
            clock.setForThisThread(instant);
            try {
                return call.call();
            } finally {
                clock.clearForThisThread();
            }
        };
    }
}
