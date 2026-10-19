package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/** A seat_hold row as committed, read under a lock — deliberately not an entity (spec §5). */
public record HoldRow(long id, long eventSeatId, UUID holdGroupId, String owner, HoldStatus status, Instant expiresAt) {

    /** The domain's view of this row. The conversion lives here because domain imports nothing but java.*. */
    public HoldRules.HoldRow toRule() {
        return new HoldRules.HoldRow(eventSeatId, status, expiresAt);
    }
}
