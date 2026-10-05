package io.github.markusluisflores.frontrow.domain;

import java.time.Instant;
import java.util.Collection;

/** The hold predicates that decide outcomes. Pure functions over values, so they are testable without a database. */
public final class HoldRules {

    private HoldRules() {}

    /** One hold row as read under the lock. */
    public record HoldRow(long eventSeatId, HoldStatus status, Instant expiresAt) {}

    /** What a confirm should do, in the precedence order spec §5 defines. */
    public enum ConfirmOutcome {
        PROCEED,
        HOLD_NOT_ACTIVE,
        HOLD_EXPIRED
    }

    /** A hold is live iff it is ACTIVE and has not reached its expiry instant (spec §4). */
    public static boolean isLive(HoldStatus status, Instant expiresAt, Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }

    /**
     * Confirm precedence: every row live proceeds; any RELEASED or CONVERTED row reports hold_not_active; anything
     * else — EXPIRED, or ACTIVE past its expiry, including a group only partly expired by an overlapping hold —
     * reports hold_expired. Spec §5 step 4 names only RELEASED for hold_not_active; grouping CONVERTED with it is a
     * plan-level decision (see the plan's "Plan-level decisions" section), consistent with §5's Release rule 1.
     */
    public static ConfirmOutcome confirmOutcome(Collection<HoldRow> rows, Instant now) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("confirmOutcome needs at least one row");
        }
        boolean allLive = true;
        for (HoldRow row : rows) {
            if (row.status() == HoldStatus.RELEASED || row.status() == HoldStatus.CONVERTED) {
                return ConfirmOutcome.HOLD_NOT_ACTIVE;
            }
            if (!isLive(row.status(), row.expiresAt(), now)) {
                allLive = false;
            }
        }
        return allLive ? ConfirmOutcome.PROCEED : ConfirmOutcome.HOLD_EXPIRED;
    }
}
