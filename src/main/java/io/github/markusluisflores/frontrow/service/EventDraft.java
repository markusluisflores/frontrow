package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.Map;

/**
 * An organiser's new event (spec §6, POST /api/events): a DRAFT on an existing venue, with a price in cents per section
 * of that venue. Any field may be null; the service names the first one it refuses.
 */
public record EventDraft(
        Long venueId,
        String name,
        Instant startsAt,
        Instant salesOpenAt,
        Instant salesCloseAt,
        String currency,
        Map<String, Long> prices) {

    public EventDraft {
        prices = prices == null ? null : Map.copyOf(prices);
    }
}
