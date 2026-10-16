package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A successful hold: what the caller relays to its human. Stored as JSON in hold_request and returned unchanged on
 * replay, so this record is the replay contract (plan 3A decision 6).
 */
public record HoldResult(
        UUID holdGroupId, long eventId, List<HeldSeat> seats, long totalCents, String currency, Instant expiresAt) {

    public HoldResult {
        seats = List.copyOf(seats);
    }

    public record HeldSeat(long eventSeatId, long priceCents) {}
}
