package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query(value = "SELECT status FROM event WHERE id = :id", nativeQuery = true)
    String findStatusTextById(Long id);

    /**
     * search_events and GET /api/events (spec §6): one status, a LIKE pattern escaped with '!', and starts_at in
     * [from, to], soonest first. Postgres's lower() lowers both the name and the pattern, so one engine's case rules
     * apply to both sides; it leaves '!', '%' and '_' alone. Every parameter is non-null, so no typed-null binding
     * reaches Postgres.
     */
    @Query("""
            SELECT e FROM Event e JOIN FETCH e.venue
            WHERE e.status = :status AND lower(e.name) LIKE lower(:pattern) ESCAPE '!'
            AND e.startsAt >= :from AND e.startsAt <= :to
            ORDER BY e.startsAt, e.id""")
    List<Event> search(EventStatus status, String pattern, Instant from, Instant to, Limit limit);

    @Query("SELECT e FROM Event e JOIN FETCH e.venue WHERE e.id = :id")
    Optional<Event> findWithVenue(long id);
}
