package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class OwnerReadServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    OwnerReadService reads;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long eventId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 6);
    }

    @Test
    void holdsListsOnlyTheCallersGroupsThatStillHaveALiveSeat() {
        UUID alices = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(0), seats.get(1)), "k1"))
                .holdGroupId();
        UUID bobs = holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(2)), "k1"))
                .holdGroupId();
        // Lapsed and never swept: no live row, so not listed.
        fixtures.holdInGroup(seats.get(3), UUID.randomUUID(), "alice", "ACTIVE", NOW.minusSeconds(1));
        // A live group that expires sooner than the new hold's 10-minute TTL.
        UUID sooner = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(4), sooner, "alice", "ACTIVE", NOW.plusSeconds(60));

        List<HeldGroup> alicesGroups = reads.holds("alice");

        // Soonest expiry first: the first group expires in 60 s, the new hold in the 10-minute TTL.
        assertThat(alicesGroups).extracting(HeldGroup::holdGroupId).containsExactly(sooner, alices);
        HeldGroup held = alicesGroups.get(1);
        assertThat(held.eventId()).isEqualTo(eventId);
        assertThat(held.eventName()).isEqualTo("Fixture event");
        assertThat(held.seats())
                .extracting(HeldGroup.GroupSeat::eventSeatId)
                .containsExactly(seats.get(0), seats.get(1));
        assertThat(held.totalCents()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
        assertThat(held.expiresAt()).isEqualTo(NOW.plusSeconds(600));

        assertThat(reads.holds("bob")).extracting(HeldGroup::holdGroupId).containsExactly(bobs);
        assertThat(reads.holds("carol")).isEmpty();
    }

    @Test
    void ordersListsOnlyTheCallersOrdersNewestFirst() {
        UUID first = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(0)), "k1"))
                .holdGroupId();
        long firstOrder = confirms.confirm(new HoldGroupCommand("alice", first)).orderId();
        UUID bobs = holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(1)), "k1"))
                .holdGroupId();
        long bobsOrder = confirms.confirm(new HoldGroupCommand("bob", bobs)).orderId();
        clock.set(NOW.plusSeconds(60));
        UUID second = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(2), seats.get(3)), "k2"))
                .holdGroupId();
        long secondOrder =
                confirms.confirm(new HoldGroupCommand("alice", second)).orderId();

        List<OrderResult> alicesOrders = reads.orders("alice");

        assertThat(alicesOrders).extracting(OrderResult::orderId).containsExactly(secondOrder, firstOrder);
        assertThat(alicesOrders.get(0).lines())
                .extracting(OrderResult.Line::eventSeatId)
                .containsExactly(seats.get(2), seats.get(3));
        assertThat(reads.orders("bob")).extracting(OrderResult::orderId).containsExactly(bobsOrder);
        // A converted group is bought, not held: it leaves holds and appears in orders.
        assertThat(reads.holds("alice")).isEmpty();
    }
}
