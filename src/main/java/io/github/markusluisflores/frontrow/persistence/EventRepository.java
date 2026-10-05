package io.github.markusluisflores.frontrow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query(value = "SELECT status FROM event WHERE id = :id", nativeQuery = true)
    String findStatusTextById(Long id);
}
