package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.SalesWindow;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sales_open_at", nullable = false)
    private Instant salesOpenAt;

    @Column(name = "sales_close_at", nullable = false)
    private Instant salesCloseAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EventStatus status;

    /** char(3) in the schema; without the CHAR type code, ddl-auto validate rejects it as varchar. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false)
    private String currency;

    protected Event() {}

    public Event(
            Venue venue,
            String name,
            Instant startsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            EventStatus status,
            String currency) {
        this.venue = venue;
        this.name = name;
        this.startsAt = startsAt;
        this.salesOpenAt = salesOpenAt;
        this.salesCloseAt = salesCloseAt;
        this.status = status;
        this.currency = currency;
    }

    public Long getId() {
        return id;
    }

    public Venue getVenue() {
        return venue;
    }

    public String getName() {
        return name;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getSalesOpenAt() {
        return salesOpenAt;
    }

    public Instant getSalesCloseAt() {
        return salesCloseAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public SalesWindow salesWindow() {
        return SalesWindow.of(salesOpenAt, salesCloseAt);
    }
}
