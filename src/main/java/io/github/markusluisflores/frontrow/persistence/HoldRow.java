package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/** A seat_hold row as committed, read under a lock — deliberately not an entity (spec §5). */
public record HoldRow(
        long id, long eventSeatId, UUID holdGroupId, String owner, HoldStatus status, Instant expiresAt) {}
