package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of the caller's hold groups (spec §6, GET /api/me/holds), listed while at least one of its rows is live: the
 * rule that makes it count against the hold-group cap (spec §4). A user and their agent are one owner, so this lists
 * the agent's holds too (spec §6, the handover).
 */
public record HeldGroup(
        UUID holdGroupId,
        long eventId,
        String eventName,
        List<GroupSeat> seats,
        long totalCents,
        String currency,
        Instant expiresAt) {

    public HeldGroup {
        seats = List.copyOf(seats);
    }

    public record GroupSeat(long eventSeatId, String section, String rowLabel, int seatNumber, long priceCents) {}
}
