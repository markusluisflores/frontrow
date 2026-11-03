package io.github.markusluisflores.frontrow.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketOrderRepository extends JpaRepository<TicketOrder, Long> {

    Optional<TicketOrder> findByHoldGroupId(UUID holdGroupId);

    boolean existsByEventId(long eventId);

    /** GET /api/me/orders (spec §6): the owner's own orders, newest first. */
    List<TicketOrder> findByOwnerOrderByCreatedAtDescIdDesc(String owner);
}
