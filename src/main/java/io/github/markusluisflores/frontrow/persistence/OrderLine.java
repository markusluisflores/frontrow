package io.github.markusluisflores.frontrow.persistence;

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
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private TicketOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_seat_id", nullable = false)
    private EventSeat eventSeat;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    protected OrderLine() {}

    public OrderLine(TicketOrder order, EventSeat eventSeat, long priceCents) {
        this.order = order;
        this.eventSeat = eventSeat;
        this.priceCents = priceCents;
    }

    public Long getId() {
        return id;
    }

    public TicketOrder getOrder() {
        return order;
    }

    public EventSeat getEventSeat() {
        return eventSeat;
    }

    public long getPriceCents() {
        return priceCents;
    }
}
