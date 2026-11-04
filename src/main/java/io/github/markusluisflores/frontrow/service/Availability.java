package io.github.markusluisflores.frontrow.service;

import java.util.List;

/**
 * check_availability and GET /api/events/{id}/availability (spec §6). available means nothing claims the seat now: no
 * sale and no live hold. It shows whether a seat is free and never whose hold it is: no hold_group_id, owner or
 * expires_at (spec §6, §12). It does not say the event is on sale; the event read shows its status.
 */
public record Availability(long eventId, String currency, List<SeatState> seats) {

    public Availability {
        seats = List.copyOf(seats);
    }

    public record SeatState(
            long eventSeatId, String section, String rowLabel, int seatNumber, long priceCents, boolean available) {}
}
