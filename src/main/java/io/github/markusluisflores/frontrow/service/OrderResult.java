package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.persistence.OrderLine;
import io.github.markusluisflores.frontrow.persistence.TicketOrder;
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

    /** One order as confirm and GET /api/me/orders both return it. */
    static OrderResult of(TicketOrder order, List<OrderLine> lines) {
        return new OrderResult(
                order.getId(),
                order.getHoldGroupId(),
                order.getEvent().getId(),
                lines.stream()
                        .map(line -> new Line(line.getEventSeat().getId(), line.getPriceCents()))
                        .toList(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getCreatedAt());
    }

    public record Line(long eventSeatId, long priceCents) {}
}
