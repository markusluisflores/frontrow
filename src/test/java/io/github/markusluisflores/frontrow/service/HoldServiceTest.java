package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class HoldServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

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
    void holdsSeatsInAscendingOrderAndRecordsTheResult() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);

        HoldResult result = holds.hold(command("alice", List.of(seats.get(1), seats.get(0)), "k1"));

        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.seats())
                .extracting(HoldResult.HeldSeat::eventSeatId)
                .containsExactly(seats.get(0), seats.get(1));
        assertThat(result.totalCents()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
        assertThat(result.currency()).isEqualTo("CAD");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(properties.holdTtl()));
        assertThat(fixtures.seatsInGroup(result.holdGroupId())).containsExactlyElementsOf(seats);
        assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("ACTIVE");
        // The stored response is the result. Read it back the way replay does, never as raw text (spec §5, rev 2.6.2).
        // Not readTree-vs-valueToTree: parsing yields IntNode for small numbers, valueToTree keeps the long fields as
        // LongNode, and IntNode(9) is not equal to LongNode(9) — observed with Jackson 3.1.5.
        assertThat(HoldService.STORED_FORM.readValue(fixtures.storedResponse("alice", "k1"), HoldResult.class))
                .isEqualTo(result);
    }

    @Test
    void keepsEachSeatsOwnPriceOnItsHeldSeat() {
        long cheap = fixtures.eventSeats(eventId, venueId, 1, 1000).getFirst();
        long dear = fixtures.eventSeats(eventId, venueId, 1, 2500).getFirst();

        HoldResult result = holds.hold(command("alice", List.of(dear, cheap), "k1"));

        assertThat(result.seats())
                .containsExactly(new HoldResult.HeldSeat(cheap, 1000), new HoldResult.HeldSeat(dear, 2500));
        assertThat(result.totalCents()).isEqualTo(3500);
    }

    @Test
    void storesExpiryAtTheDatabasesPrecision() {
        clock.set(NOW.plusNanos(1_234_567));
        HoldResult result = holds.hold(command("alice", fixtures.eventSeats(eventId, venueId, 1), "k1"));
        assertThat(result.expiresAt().getNano() % 1_000).isZero();
    }

    @Test
    void replaysTheStoredResultForTheSameKeyAndRequest() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult first = holds.hold(command("alice", seats, "k1"));

        // Past expiry: a replay reports what happened, including the original expires_at; it does not re-check.
        clock.set(first.expiresAt().plusSeconds(60));
        HoldResult replayed = holds.hold(command("alice", List.of(seats.get(1), seats.get(0)), "k1"));

        assertThat(replayed).isEqualTo(first);
        assertThat(fixtures.groupsOwnedBy("alice")).isEqualTo(1);
    }

    @Test
    void theSameKeyForADifferentRequestIsRejected() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        holds.hold(command("alice", List.of(seats.get(0)), "k1"));

        assertThat(rejection(() -> holds.hold(command("alice", List.of(seats.get(1)), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aFailedRequestIsNotRecordedSoItsRetryRunsAgain() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.hold(seat, "bob", "ACTIVE", NOW.plusSeconds(600));

        assertThat(rejection(() -> holds.hold(command("alice", List.of(seat), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();

        // Bob's hold lapses; the same key now runs the request for real instead of replaying a failure.
        clock.set(NOW.plusSeconds(600));
        assertThat(holds.hold(command("alice", List.of(seat), "k1")).seats()).hasSize(1);
    }

    @Test
    void aTakenSeatIsNamedAndNothingIsHeld() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        fixtures.hold(seats.get(1), "bob", "ACTIVE", NOW.plusSeconds(600));

        DomainException rejected = rejection(() -> holds.hold(command("alice", seats, "k1")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(rejected.details()).containsEntry("seat_ids", List.of(seats.get(1)));
        // All-or-nothing: the free seat claimed before the conflict was rolled back with it.
        assertThat(fixtures.statusesOwnedBy("alice")).isEmpty();
    }

    @Test
    void aSoldSeatCannotBeHeld() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.hold(seat, "bob", "CONVERTED", NOW.minusSeconds(60));

        assertThat(rejection(() -> holds.hold(command("alice", List.of(seat), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SEAT_TAKEN);
    }

    @Test
    void aHoldLapsingExactlyNowIsExpiredAndItsSeatReclaimed() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.hold(seat, "bob", "ACTIVE", NOW);

        holds.hold(command("alice", List.of(seat), "k1"));

        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("EXPIRED");
        assertThat(fixtures.claimsOn(seat)).isEqualTo(1);
    }

    @Test
    void aMissingEventIsNotFound() {
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", -1L, List.of(1L), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aDraftEventIsNotFoundAndDisclosesNothing() {
        // Its window opens later, so before spec rev 2.6.3 this answered sales_not_open with that opening time.
        long draft = fixtures.event(venueId, "DRAFT", NOW.plusSeconds(60), NOW.plusSeconds(120));
        long seat = fixtures.eventSeats(draft, venueId, 1).getFirst();
        DomainException rejected = rejection(() -> holds.hold(new HoldCommand("alice", draft, List.of(seat), "k1")));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejected.details()).isEmpty();
    }

    @Test
    void theWindowIsHalfOpen() {
        long later = fixtures.event(venueId, "ON_SALE", NOW.plusMillis(1), NOW.plusSeconds(60));
        long seatLater = fixtures.eventSeats(later, venueId, 1).getFirst();
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", later, List.of(seatLater), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_NOT_OPEN);

        long closing = fixtures.event(venueId, "ON_SALE", NOW.minusSeconds(60), NOW);
        long seatClosing = fixtures.eventSeats(closing, venueId, 1).getFirst();
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", closing, List.of(seatClosing), "k2")))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);

        long opening = fixtures.event(venueId, "ON_SALE", NOW, NOW.plusSeconds(60));
        long seatOpening = fixtures.eventSeats(opening, venueId, 1).getFirst();
        assertThat(holds.hold(new HoldCommand("alice", opening, List.of(seatOpening), "k3"))
                        .seats())
                .hasSize(1);
    }

    @Test
    void aCancelledEventIsClosed() {
        long cancelled = fixtures.event(venueId, "CANCELLED", NOW.minusSeconds(60), NOW.plusSeconds(60));
        long seat = fixtures.eventSeats(cancelled, venueId, 1).getFirst();
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", cancelled, List.of(seat), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void duplicateSeatIdsAreNamed() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(seat, seat), "k1")));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details())
                .containsEntry("parameter", "event_seat_ids")
                .containsEntry("seat_ids", List.of(seat));
    }

    @Test
    void aSeatThisEventDoesNotOfferIsNamed() {
        long otherEvent = fixtures.onSaleEvent(venueId);
        long foreign = fixtures.eventSeats(otherEvent, venueId, 1).getFirst();
        long own = fixtures.eventSeats(eventId, venueId, 1).getFirst();

        DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(own, foreign), "k1")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details())
                .containsEntry("parameter", "event_seat_ids")
                .containsEntry("seat_ids", List.of(foreign));
    }

    @Test
    void aKeyWithAValidSurrogatePairIsAccepted() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        assertThat(holds.hold(command("alice", List.of(seat), "k🎫")).seats()).hasSize(1);
    }

    @Test
    void tooManySeatsIsRejectedBeforeTheKeyIsClaimed() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, properties.maxSeatsPerHold() + 1);
        holds.hold(command("alice", List.of(seats.getFirst()), "k1"));

        // Reusing a committed key for a different request: had the claim run first, this would be
        // idempotency_key_reused (decision 3).
        DomainException rejected = rejection(() -> holds.hold(command("alice", seats, "k1")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.TOO_MANY_SEATS);
        assertThat(rejected.details()).containsEntry("max_seats", properties.maxSeatsPerHold());
    }

    @Test
    void theActiveGroupCapCountsOnlyLiveGroups() {
        int cap = properties.maxActiveHoldGroups();
        List<Long> seats = fixtures.eventSeats(eventId, venueId, cap + 2);
        // A lapsed group does not count (spec §4).
        fixtures.hold(seats.get(cap + 1), "alice", "ACTIVE", NOW.minusSeconds(1));
        for (int i = 0; i < cap; i++) {
            holds.hold(command("alice", List.of(seats.get(i)), "k" + i));
        }

        DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(seats.get(cap)), "over")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.HOLD_LIMIT_EXCEEDED);
        assertThat(rejected.details()).containsEntry("max_active_hold_groups", cap);
    }

    @Test
    void malformedParametersAreNamedBeforeTheKeyIsClaimed() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        holds.hold(command("alice", List.of(seat), "k1"));

        // Reuses the committed key for a different request: had the claim run first, this would be
        // idempotency_key_reused. So this case fails if validation ever moves behind the claim (decision 3).
        DomainException noSeats = rejection(() -> holds.hold(command("alice", List.of(), "k1")));
        assertThat(noSeats.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(noSeats.details()).containsEntry("parameter", "event_seat_ids");

        // Keys holding a NUL (Postgres text cannot store one: binding it fails with 22021, decision 8) or a lone
        // surrogate (the JDK's UTF-8 encoder inside pgjdbc would send it as '?', aliasing a different key).
        List<String> badKeys = new ArrayList<>(List.of(
                "", "   ", "x".repeat(HoldService.MAX_IDEMPOTENCY_KEY_LENGTH + 1), "k\u0000ey", "k\uD800", "\uDC00k"));
        badKeys.add(null);
        for (String key : badKeys) {
            DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(seat), key)));
            assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(rejected.details()).containsEntry("parameter", "idempotency_key");
        }
        DomainException nullSeat = rejection(() -> holds.hold(command("alice", Arrays.asList(seat, null), "k1")));
        assertThat(nullSeat.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(nullSeat.details()).containsEntry("parameter", "event_seat_ids");
        // Only the first, well-formed request was recorded.
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isEqualTo(1);
    }

    private HoldCommand command(String owner, List<Long> seats, String key) {
        return new HoldCommand(owner, eventId, seats, key);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
