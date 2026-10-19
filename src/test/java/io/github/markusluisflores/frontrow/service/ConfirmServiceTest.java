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
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ConfirmServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    ReleaseService releases;

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
    void confirmsALiveGroupIntoOneOrderAtEachSeatsPrice() {
        long cheap = fixtures.eventSeats(eventId, venueId, 1, 1000).getFirst();
        long dear = fixtures.eventSeats(eventId, venueId, 1, 2500).getFirst();
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, List.of(dear, cheap), "k1"));
        clock.set(NOW.plusSeconds(60));

        OrderResult order = confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId()));

        assertThat(order.holdGroupId()).isEqualTo(held.holdGroupId());
        assertThat(order.eventId()).isEqualTo(eventId);
        assertThat(order.lines()).containsExactly(new OrderResult.Line(cheap, 1000), new OrderResult.Line(dear, 2500));
        assertThat(order.totalCents()).isEqualTo(3500);
        assertThat(order.currency()).isEqualTo("CAD");
        assertThat(order.createdAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("CONVERTED", "CONVERTED");
        assertThat(fixtures.orderedSeatsOfGroup(held.holdGroupId())).containsExactly(cheap, dear);
    }

    @Test
    void aRepeatedConfirmReturnsTheSameOrderEvenAfterExpiryAndSalesClose() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        HoldGroupCommand confirm = new HoldGroupCommand("alice", held.holdGroupId());
        OrderResult first = confirms.confirm(confirm);

        // The existing-order check comes first, so neither the lapsed hold nor the closed window changes the answer.
        clock.set(NOW.plus(60, ChronoUnit.DAYS));
        assertThat(confirms.confirm(confirm)).isEqualTo(first);
        assertThat(fixtures.ordersForGroup(held.holdGroupId())).isEqualTo(1);
    }

    @Test
    void anotherOwnersGroupIsNotFoundAndIsLeftAlone() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("bob", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
        assertThat(fixtures.ordersForGroup(held.holdGroupId())).isZero();
    }

    @Test
    void anUnknownGroupIsNotFound() {
        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", UUID.randomUUID())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aGroupLapsingExactlyNowIsExpiredAndNothingIsSold() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        clock.set(held.expiresAt());

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE", "ACTIVE");
        assertThat(fixtures.ordersForGroup(held.holdGroupId())).isZero();
    }

    @Test
    void aGroupAnOverlappingHoldPartlyExpiredIsExpired() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, seats, "k1"));
        clock.set(held.expiresAt().plusSeconds(1));
        // Bob's hold lazily expires only the seat it overlaps; alice's other row stays ACTIVE.
        holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(0)), "k1"));
        // Back to before the expiry: alice's other row is live, so only the EXPIRED row makes the group expired.
        clock.set(held.expiresAt().minusMillis(1));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    @Test
    void aReleasedGroupIsNotActive() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));
        releases.release(new HoldGroupCommand("alice", held.holdGroupId()));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    void aCancelledEventIsClosedEvenThoughItsHoldsWereReleased() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));
        // What a cancellation leaves behind (spec §5): the event CANCELLED, its holds RELEASED.
        releases.release(new HoldGroupCommand("alice", held.holdGroupId()));
        fixtures.setEventStatus(eventId, "CANCELLED");

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void aGroupStillLiveAfterSalesCloseIsClosed() {
        long closing = fixtures.event(venueId, "ON_SALE", NOW.minusSeconds(60), NOW.plusSeconds(60));
        HoldResult held = holds.hold(new HoldCommand("alice", closing, fixtures.eventSeats(closing, venueId, 1), "k1"));
        clock.set(NOW.plusSeconds(60));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
    }

    @Test
    void aSoldSeatStaysClaimedSoItCannotBeHeldAgain() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 1);
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, seats, "k1"));
        confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId()));

        clock.set(held.expiresAt().plusSeconds(1));
        assertThat(rejection(() -> holds.hold(new HoldCommand("bob", eventId, seats, "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SEAT_TAKEN);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
