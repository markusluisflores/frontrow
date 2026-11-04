package io.github.markusluisflores.frontrow.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Every seat in a venue, for event creation (spec §6). Seats are never written after seeding. */
    List<Seat> findByVenueIdOrderByIdAsc(Long venueId);
}
