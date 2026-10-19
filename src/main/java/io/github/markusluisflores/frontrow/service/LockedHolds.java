package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import java.util.List;

/** Ends rows the caller has locked. Every one must move: a locked ACTIVE row cannot have ended under another writer. */
final class LockedHolds {

    private LockedHolds() {}

    static void end(LockingGateway gateway, List<Long> holdIds, HoldStatus target) {
        int moved = gateway.endHolds(holdIds, target);
        if (moved != holdIds.size()) {
            throw new IllegalStateException(moved + " of " + holdIds.size() + " locked holds moved to " + target);
        }
    }
}
