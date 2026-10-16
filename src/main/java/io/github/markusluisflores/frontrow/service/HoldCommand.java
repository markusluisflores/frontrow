package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** A request to hold specific seats. The owner always comes from the authenticated principal, never a parameter. */
public record HoldCommand(String owner, long eventId, List<Long> eventSeatIds, String idempotencyKey) {

    public HoldCommand {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner comes from the authenticated principal and must be present");
        }
        if (eventSeatIds == null) {
            eventSeatIds = List.of();
        }
        // A null id must be invalid_request, not the NullPointerException List.copyOf would throw. A loop, because
        // List.of(...).contains(null) itself throws.
        for (Long id : eventSeatIds) {
            if (id == null) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "event_seat_ids"));
            }
        }
        eventSeatIds = List.copyOf(eventSeatIds);
    }
}
