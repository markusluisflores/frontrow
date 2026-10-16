package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.Race;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec §5's centrepiece tests that need only hold creation: 1, 3, 4, 5 and 7. Tests 2 and 6 need confirm and cancel
 * and arrive with Plan 3B. Every racer runs in its own transaction on its own virtual thread, against real Postgres.
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class HoldConcurrencyTest {

    private static final int RACERS = 8;
    private static final int REPETITIONS = 20;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    /** Test 1. Distinct owners and keys, so neither the advisory lock nor idempotency serialises the race. */
    @Test
    void holdRaceHasExactlyOneWinnerPerSeat() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();

            List<Race.Outcome<HoldResult>> outcomes = raceForOneSeat(seat, "r" + rep);

            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.won()).allSatisfy(outcome -> {
                assertThat(outcome.errorCode()).as("%s", outcome.failure()).isEqualTo(ErrorCode.SEAT_TAKEN);
            });
            assertThat(fixtures.claimsOn(seat)).isEqualTo(1);
        }
    }

    /**
     * Test 1's own proof: drop uq_claimed_seat and the same race lets several racers through (decision 4). The index
     * is shared by every later test in this context, so the restore is always attempted, and it succeeds when the
     * scoped DELETE does. If that DELETE fails, the CREATE fails too when the race left duplicate rows, so this test
     * fails and the build goes red; the index then stays dropped for the rest of this context. The timeout fails this
     * test but cannot interrupt a JDBC call blocked on a lock; that statement stays queued in Postgres, and later
     * writers in this context queue behind it.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void withoutTheClaimIndexTheSameRaceHasSeveralWinners() throws Exception {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        jdbc.sql("DROP INDEX uq_claimed_seat").update();
        try {
            List<Race.Outcome<HoldResult>> outcomes = raceForOneSeat(seat, "no-index");
            assertThat(outcomes)
                    .as("only uq_claimed_seat makes the race safe")
                    .filteredOn(Race.Outcome::won)
                    .hasSizeGreaterThan(1);
        } finally {
            try {
                // Only this test's rows, which would block the index. A TRUNCATE would need an exclusive table lock.
                jdbc.sql("DELETE FROM seat_hold WHERE event_seat_id = :seat")
                        .param("seat", seat)
                        .update();
            } finally {
                // Attempted even if the DELETE failed; it then fails too when the race left duplicates.
                jdbc.sql("CREATE UNIQUE INDEX IF NOT EXISTS uq_claimed_seat ON seat_hold (event_seat_id)"
                                + " WHERE status IN ('ACTIVE', 'CONVERTED')")
                        .update();
            }
        }
    }

    /** Test 3. Overlapping seat sets, requested in different orders: no deadlock, no partial hold. */
    @Test
    void overlappingMultiSeatHoldsNeverDeadlockOrHoldPartially() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            List<Long> s = fixtures.eventSeats(eventId, venueId, 4);
            List<List<Long>> requests = List.of(
                    List.of(s.get(0), s.get(1), s.get(2)),
                    List.of(s.get(2), s.get(1), s.get(3)),
                    List.of(s.get(3), s.get(0)),
                    List.of(s.get(1), s.get(3), s.get(0), s.get(2)));
            List<Callable<HoldResult>> calls = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                String owner = "overlap-r" + rep + "-" + i;
                List<Long> seats = requests.get(i);
                calls.add(() -> holds.hold(new HoldCommand(owner, eventId, seats, "key-" + owner)));
            }

            List<Race.Outcome<HoldResult>> outcomes = Race.run(pool, calls);

            assertThat(outcomes).as("repetition %d", rep).anyMatch(Race.Outcome::won);
            for (int i = 0; i < outcomes.size(); i++) {
                Race.Outcome<HoldResult> outcome = outcomes.get(i);
                String owner = "overlap-r" + rep + "-" + i;
                if (outcome.won()) {
                    assertThat(fixtures.seatsInGroup(outcome.result().holdGroupId()))
                            .containsExactlyInAnyOrderElementsOf(requests.get(i));
                } else {
                    // seat_taken only: a deadlock would surface as contention_retry, anything else as unexpected.
                    assertThat(outcome.errorCode()).as("%s", outcome.failure()).isEqualTo(ErrorCode.SEAT_TAKEN);
                    assertThat(fixtures.statusesOwnedBy(owner))
                            .as("no partial hold")
                            .isEmpty();
                }
            }
        }
    }

    /** Test 4. One owner, more parallel calls than the cap, disjoint seats: only the cap can reject them. */
    @Test
    void theActiveGroupCapHoldsUnderConcurrency() throws Exception {
        int cap = properties.maxActiveHoldGroups();
        int callers = cap + 5;
        for (int rep = 0; rep < REPETITIONS / 2; rep++) {
            String owner = "capped-r" + rep;
            List<Long> seats = fixtures.eventSeats(eventId, venueId, callers);
            List<Callable<HoldResult>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                List<Long> one = List.of(seats.get(i));
                String key = "key-" + i;
                calls.add(() -> holds.hold(new HoldCommand(owner, eventId, one, key)));
            }

            List<Race.Outcome<HoldResult>> outcomes = Race.run(pool, calls);

            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(cap);
            assertThat(outcomes).filteredOn(outcome -> !outcome.won()).allSatisfy(outcome -> {
                assertThat(outcome.errorCode()).as("%s", outcome.failure()).isEqualTo(ErrorCode.HOLD_LIMIT_EXCEEDED);
            });
            assertThat(fixtures.groupsOwnedBy(owner)).isEqualTo(cap);
        }
    }

    /** Test 5. The same key and request, twice in parallel: one group, and both callers get it. */
    @Test
    void aConcurrentIdempotentRetryYieldsOneGroup() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            String owner = "retry-r" + rep;
            HoldCommand command = new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "same-key");
            List<Callable<HoldResult>> calls = List.of(() -> holds.hold(command), () -> holds.hold(command));

            List<Race.Outcome<HoldResult>> outcomes = Race.run(pool, calls);

            assertThat(outcomes).as("repetition %d", rep).allMatch(Race.Outcome::won);
            // Record equality: the replayed result is the stored one, deserialized — JSON-equal by construction.
            assertThat(outcomes.get(0).result()).isEqualTo(outcomes.get(1).result());
            assertThat(fixtures.groupsOwnedBy(owner)).isEqualTo(1);
            assertThat(fixtures.holdRequestsOwnedBy(owner)).isEqualTo(1);
        }
    }

    /**
     * Test 7. Sequential, but it guards the correctness claim: with no sweeper, a lapsed hold must not block the next
     * buyer. Plan 3A has no sweeper at all; Plan 3B must keep this test running with its sweeper disabled.
     */
    @Test
    void lazyExpiryFreesSeatsWithoutTheSweeper() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult first = holds.hold(new HoldCommand("alice", eventId, seats, "a-1"));

        clock.set(first.expiresAt().plusMillis(1));
        HoldResult second = holds.hold(new HoldCommand("bob", eventId, seats, "b-1"));

        assertThat(fixtures.seatsInGroup(second.holdGroupId())).containsExactlyElementsOf(seats);
        assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("EXPIRED");
    }

    private List<Race.Outcome<HoldResult>> raceForOneSeat(long seat, String tag) throws Exception {
        List<Callable<HoldResult>> calls = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            String owner = "racer-" + tag + "-" + i;
            calls.add(() -> holds.hold(new HoldCommand(owner, eventId, List.of(seat), "key-" + owner)));
        }
        return Race.run(pool, calls);
    }
}
