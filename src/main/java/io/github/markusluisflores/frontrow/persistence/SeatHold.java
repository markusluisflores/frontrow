package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
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

@Entity
@Table(name = "seat_hold")
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_seat_id", nullable = false)
    private EventSeat eventSeat;

    @Column(name = "hold_group_id", nullable = false)
    private UUID holdGroupId;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SeatHold() {}

    public SeatHold(
            EventSeat eventSeat,
            UUID holdGroupId,
            String owner,
            HoldStatus status,
            Instant expiresAt,
            Instant createdAt) {
        this.eventSeat = eventSeat;
        this.holdGroupId = holdGroupId;
        this.owner = owner;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public EventSeat getEventSeat() {
        return eventSeat;
    }

    public UUID getHoldGroupId() {
        return holdGroupId;
    }

    public String getOwner() {
        return owner;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Only ACTIVE may move, and only to an ended status (domain rule). */
    public void transitionTo(HoldStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException("illegal hold transition " + this.status + " -> " + target);
        }
        this.status = target;
    }
}
