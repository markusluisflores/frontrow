package io.github.markusluisflores.frontrow.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * Confirm's first, unlocked read (spec Â§5): the group's event, if the group exists and is this owner's. Safe
     * without a lock, because a group's event never changes. A scalar, so no SeatHold entity is loaded.
     */
    @Query(value = """
                    SELECT DISTINCT s.event_id FROM seat_hold h JOIN event_seat s ON s.id = h.event_seat_id
                    WHERE h.hold_group_id = :holdGroupId AND h.owner = :owner""", nativeQuery = true)
    Optional<Long> findEventIdOfGroup(UUID holdGroupId, String owner);
}
