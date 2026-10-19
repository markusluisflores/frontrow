package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A confirmed order: the only purchase there is (spec §6). A repeated confirm returns the same one. */
public record OrderResult(
        long orderId,
        UUID holdGroupId,
        long eventId,
        List<Line> lines,
        long totalCents,
        String currency,
        Instant createdAt) {

    public OrderResult {
        lines = List.copyOf(lines);
    }

    public record Line(long eventSeatId, long priceCents) {}
}
