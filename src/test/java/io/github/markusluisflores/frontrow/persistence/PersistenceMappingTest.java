package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.domain.OrderStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Round-trips every entity against real Postgres, so a column or enum mismatch fails here rather than in a service. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PersistenceMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    VenueRepository venues;

    @Autowired
    SeatRepository seats;

    @Autowired
    EventRepository events;

    @Autowired
    EventSeatRepository eventSeats;

    @Autowired
    SeatHoldRepository seatHolds;

    @Autowired
    HoldRequestRepository holdRequests;

    @Autowired
    TicketOrderRepository orders;

    @Autowired
    OrderLineRepository orderLines;

    @Test
    void roundTripsTheWholeGraph() {
        Venue venue = venues.save(new Venue("Main Hall"));
        Seat seat = seats.save(new Seat(venue, "Floor", "A", 1));
        Event event = events.save(new Event(
                venue,
                "Test event",
                NOW.plus(30, ChronoUnit.DAYS),
                NOW.minus(1, ChronoUnit.DAYS),
                NOW.plus(29, ChronoUnit.DAYS),
                EventStatus.ON_SALE,
                "CAD"));
        EventSeat eventSeat = eventSeats.save(new EventSeat(event, seat, venue.getId(), 5000L));

        UUID group = UUID.randomUUID();
        SeatHold hold = seatHolds.save(
                new SeatHold(eventSeat, group, "alice", HoldStatus.ACTIVE, NOW.plus(10, ChronoUnit.MINUTES), NOW));
        TicketOrder order =
                orders.save(new TicketOrder(event, group, "alice", OrderStatus.CONFIRMED, 5000L, "CAD", NOW));
        orderLines.save(new OrderLine(order, eventSeat, 5000L));
        holdRequests.save(new HoldRequest("alice", "key-1", "hash-1", group, "{\"ok\":true}", NOW));

        seatHolds.flush();

        assertThat(hold.getId()).isNotNull();
        assertThat(seatHolds.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.ON_SALE);
        assertThat(orders.findByHoldGroupId(group).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(holdRequests
                        .findById(new HoldRequestId("alice", "key-1"))
                        .orElseThrow()
                        .getHoldGroupId())
                .isEqualTo(group);
    }

    @Test
    void storesTimestampsToTheMillisecondWithoutDrift() {
        Venue venue = venues.save(new Venue("Precision Hall"));
        Instant odd = Instant.parse("2026-10-01T12:34:56.789Z");
        Event event = events.save(
                new Event(venue, "Precise", odd, odd.minusSeconds(60), odd.plusSeconds(60), EventStatus.DRAFT, "CAD"));
        events.flush();

        assertThat(events.findById(event.getId()).orElseThrow().getStartsAt()).isEqualTo(odd);
    }

    @Test
    void statusesAreStoredAsTextNotOrdinals() {
        Venue venue = venues.save(new Venue("Text Hall"));
        Event event = events.save(new Event(
                venue, "Textual", NOW, NOW.minusSeconds(1), NOW.plusSeconds(1), EventStatus.CANCELLED, "CAD"));
        events.flush();

        String stored = events.findStatusTextById(event.getId());
        assertThat(stored).isEqualTo("CANCELLED");
    }
}
