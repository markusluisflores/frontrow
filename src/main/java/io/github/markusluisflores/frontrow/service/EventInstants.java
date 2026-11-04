package io.github.markusluisflores.frontrow.service;

import java.time.Instant;

/**
 * The instants both event write paths take from the caller: creation's starts_at and sales window, and a PATCH's sales
 * window (spec §5, Event cancellation). An instant outside this range is invalid_request and never reaches SQL, where
 * it would fail as a 500 (PR #22 thread 4305094277). The range is what both timestamptz and a four-digit ISO 8601 year
 * hold.
 */
final class EventInstants {

    static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

    static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private EventInstants() {}

    static boolean storable(Instant instant) {
        return !instant.isBefore(EARLIEST) && !instant.isAfter(LATEST);
    }
}
