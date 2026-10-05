package io.github.markusluisflores.frontrow.domain;

import java.util.EnumSet;
import java.util.Set;

/** Hold lifecycle (spec §4). ACTIVE is the only non-terminal status. */
public enum HoldStatus {
    ACTIVE,
    EXPIRED,
    RELEASED,
    CONVERTED;

    private static final Set<HoldStatus> FROM_ACTIVE = EnumSet.of(EXPIRED, RELEASED, CONVERTED);

    public boolean canTransitionTo(HoldStatus target) {
        return this == ACTIVE && FROM_ACTIVE.contains(target);
    }
}
