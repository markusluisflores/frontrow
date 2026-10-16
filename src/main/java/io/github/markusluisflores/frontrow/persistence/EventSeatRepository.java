package io.github.markusluisflores.frontrow.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    /** The requested seats this event actually offers. event_seat rows are never written, so a JPA read is safe. */
    @Query("SELECT s FROM EventSeat s WHERE s.event.id = :eventId AND s.id IN :ids")
    List<EventSeat> findOfferedSeats(long eventId, Collection<Long> ids);
}
