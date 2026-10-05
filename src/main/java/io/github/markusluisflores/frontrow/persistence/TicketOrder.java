package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ticket_order")
public class TicketOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "hold_group_id", nullable = false, unique = true)
    private UUID holdGroupId;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    /** char(3) in the schema; without the CHAR type code, ddl-auto validate rejects it as varchar. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketOrder() {}

    public TicketOrder(
            Event event,
            UUID holdGroupId,
            String owner,
            OrderStatus status,
            long totalCents,
            String currency,
            Instant createdAt) {
        this.event = event;
        this.holdGroupId = holdGroupId;
        this.owner = owner;
        this.status = status;
        this.totalCents = totalCents;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public UUID getHoldGroupId() {
        return holdGroupId;
    }

    public String getOwner() {
        return owner;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
