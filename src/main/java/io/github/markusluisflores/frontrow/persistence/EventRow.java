package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/** An event row as committed, read under a lock — deliberately not an entity (spec §5). */
public record EventRow(
        long id, long venueId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt, String currency) {}
