package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ExpirySweepServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    ExpirySweepService sweeper;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @Test
    void expiresOnlyLapsedActiveHolds() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 3);
        fixtures.hold(seats.get(0), "alice", "ACTIVE", NOW);
        fixtures.hold(seats.get(1), "bob", "ACTIVE", NOW.plusMillis(1));
        fixtures.hold(seats.get(2), "carol", "CONVERTED", NOW.minusSeconds(1));

        assertThat(sweeper.expireLapsedHolds()).isEqualTo(1);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("EXPIRED");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("ACTIVE");
        assertThat(fixtures.statusesOwnedBy("carol")).containsExactly("CONVERTED");
    }

    @Test
    void whetherTheSweepRanNeverChangesWhatConfirmAnswers() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        clock.set(held.expiresAt());
        HoldGroupCommand confirm = new HoldGroupCommand("alice", held.holdGroupId());

        assertThat(confirmCode(confirm)).isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(sweeper.expireLapsedHolds()).isEqualTo(2);
        assertThat(confirmCode(confirm)).isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    @Test
    void purgesKeysOnceTheyAreMoreThanTwentyFourHoursOld() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 1);
        holds.hold(new HoldCommand("alice", eventId, seats, "k1"));

        clock.set(NOW.plus(24, ChronoUnit.HOURS));
        assertThat(sweeper.purgeHoldRequests()).isZero();
        clock.set(NOW.plus(24, ChronoUnit.HOURS).plus(1, ChronoUnit.MICROS));
        assertThat(sweeper.purgeHoldRequests()).isEqualTo(1);
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();
    }

    /**
     * PR #18 thread 4296540462: a key purged between hold creation's claim and its read must not become a 500. A
     * statement-level trigger stands in for the purge committing in between. It fires even when ON CONFLICT DO
     * NOTHING inserts nothing, so the claim conflicts with the old row, then the trigger deletes that row before the
     * read. The request must then run as new, under a fresh claim.
     */
    @Test
    void aKeyPurgedBetweenTheClaimAndTheReadRunsAsANewRequest() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.holdRequest("alice", "k1", NOW.minus(25, ChronoUnit.HOURS));
        jdbc.sql("""
                        CREATE FUNCTION purge_old_keys() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            DELETE FROM hold_request WHERE created_at < '2026-10-01T00:00:00Z';
                            RETURN NULL;
                        END $$""").update();
        jdbc.sql("CREATE TRIGGER purge_old_keys AFTER INSERT ON hold_request"
                        + " FOR EACH STATEMENT EXECUTE FUNCTION purge_old_keys()")
                .update();
        try {
            assertThat(holds.hold(new HoldCommand("alice", eventId, List.of(seat), "k1"))
                            .seats())
                    .hasSize(1);
            assertThat(fixtures.holdRequestCreatedAt("alice", "k1")).isEqualTo(NOW);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS purge_old_keys ON hold_request").update();
            jdbc.sql("DROP FUNCTION IF EXISTS purge_old_keys()").update();
        }
    }

    private ErrorCode confirmCode(HoldGroupCommand command) {
        Throwable thrown = catchThrowable(() -> confirms.confirm(command));
        assertThat(thrown).isInstanceOf(DomainException.class);
        return ((DomainException) thrown).errorCode();
    }
}
