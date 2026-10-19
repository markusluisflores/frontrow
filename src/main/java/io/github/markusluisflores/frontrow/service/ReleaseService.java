package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.HoldRow;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release, spec §5: lock the group's rows (step 4 only; release never reads the event), then decide. A group with a
 * sold row is left alone. Otherwise live rows become RELEASED and lapsed ACTIVE rows become EXPIRED, as lazy expiry
 * would have made them, so a later confirm's answer never depends on the sweeper. A group with nothing live left
 * succeeds and changes nothing.
 */
@Service
public class ReleaseService {

    private final LockingGateway gateway;
    private final Clock clock;

    public ReleaseService(LockingGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReleaseResult release(HoldGroupCommand command) {
        try {
            List<HoldRow> rows = gateway.lockGroupRows(command.holdGroupId(), command.owner());
            if (rows.isEmpty()) {
                // Another owner's group answers the same as no group, so it is not disclosed (spec §6).
                throw DomainException.of(ErrorCode.NOT_FOUND);
            }
            // Read once the group's rows are locked (spec §5).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (rows.stream().anyMatch(row -> row.status() == HoldStatus.CONVERTED)) {
                // Releasing a sold row would take it out of uq_claimed_seat and make the seat holdable again.
                throw DomainException.of(ErrorCode.HOLD_NOT_ACTIVE);
            }
            List<Long> live = new ArrayList<>();
            List<Long> lapsed = new ArrayList<>();
            for (HoldRow row : rows) {
                if (HoldRules.isLive(row.status(), row.expiresAt(), now)) {
                    live.add(row.id());
                } else if (row.status() == HoldStatus.ACTIVE) {
                    lapsed.add(row.id());
                }
            }
            LockedHolds.end(gateway, live, HoldStatus.RELEASED);
            LockedHolds.end(gateway, lapsed, HoldStatus.EXPIRED);
            return new ReleaseResult(command.holdGroupId(), live.size(), lapsed.size());
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }
}
