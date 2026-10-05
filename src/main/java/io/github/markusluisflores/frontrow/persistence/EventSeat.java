package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_seat")
public class EventSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    /** Written explicitly: the composite foreign keys in V1 (event, venue) and (seat, venue) need it. */
    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    protected EventSeat() {}

    public EventSeat(Event event, Seat seat, Long venueId, long priceCents) {
        this.event = event;
        this.seat = seat;
        this.venueId = venueId;
        this.priceCents = priceCents;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Seat getSeat() {
        return seat;
    }

    public Long getVenueId() {
        return venueId;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public Money price(String currency) {
        return Money.ofCents(priceCents, currency);
    }
}
