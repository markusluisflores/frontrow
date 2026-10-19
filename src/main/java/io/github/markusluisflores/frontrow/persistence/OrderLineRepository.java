package io.github.markusluisflores.frontrow.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    @Query("SELECT l FROM OrderLine l WHERE l.order.id = :orderId ORDER BY l.eventSeat.id")
    List<OrderLine> findByOrderIdInSeatOrder(long orderId);
}
