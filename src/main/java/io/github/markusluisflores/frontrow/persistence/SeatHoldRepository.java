package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * Confirm's first, unlocked read (spec §5): the group's event, if the group exists and is this owner's. Safe
     * without a lock, because a group's event never changes. A scalar, so no SeatHold entity is loaded.
     */
    @Query(value = """
                    SELECT DISTINCT s.event_id FROM seat_hold h JOIN event_seat s ON s.id = h.event_seat_id
                    WHERE h.hold_group_id = :holdGroupId AND h.owner = :owner""", nativeQuery = true)
    Optional<Long> findEventIdOfGroup(UUID holdGroupId, String owner);

    /**
     * The event's seats claimed now: a CONVERTED row, or an ACTIVE row still live (spec §4). Seat ids only, so the
     * availability read cannot show whose claim it is (spec §6).
     */
    @Query("""
            SELECT h.eventSeat.id FROM SeatHold h
            WHERE h.eventSeat.event.id = :eventId
            AND (h.status = :sold OR (h.status = :active AND h.expiresAt > :now))""")
    List<Long> findClaimedSeatIds(long eventId, Instant now, HoldStatus sold, HoldStatus active);
}
