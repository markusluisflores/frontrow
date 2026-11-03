package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.persistence.Event;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.OrderLineRepository;
import io.github.markusluisflores.frontrow.persistence.SeatHold;
import io.github.markusluisflores.frontrow.persistence.SeatHoldRepository;
import io.github.markusluisflores.frontrow.persistence.TicketOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own holds and orders (spec §6, GET /api/me/*). Owner scoping is this service's job, not the role
 * table's: every query filters by the owner the adapter passes, which is always the authenticated principal (spec §3).
 * Read-only transactions with no lock (ADR-005).
 */
@Service
public class OwnerReadService {

    private final SeatHoldRepository seatHolds;
    private final TicketOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final Clock clock;

    public OwnerReadService(
            SeatHoldRepository seatHolds, TicketOrderRepository orders, OrderLineRepository orderLines, Clock clock) {
        this.seatHolds = seatHolds;
        this.orders = orders;
        this.orderLines = orderLines;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<HeldGroup> holds(String owner) {
        TransactionBoundary.requireOwnTransaction();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<UUID, List<SeatHold>> groups = new LinkedHashMap<>();
        for (SeatHold row : seatHolds.findGroupsWithALiveRow(owner, now, HoldStatus.ACTIVE)) {
            groups.computeIfAbsent(row.getHoldGroupId(), group -> new ArrayList<>())
                    .add(row);
        }
        return groups.values().stream().map(OwnerReadService::group).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<OrderResult> orders(String owner) {
        TransactionBoundary.requireOwnTransaction();
        return orders.findByOwnerOrderByCreatedAtDescIdDesc(owner).stream()
                .map(order -> OrderResult.of(order, orderLines.findByOrderIdInSeatOrder(order.getId())))
                .toList();
    }

    /** The whole group as held. */
    private static HeldGroup group(List<SeatHold> rows) {
        Event event = rows.getFirst().getEventSeat().getEvent();
        List<HeldGroup.GroupSeat> seats = rows.stream()
                .map(row -> {
                    EventSeat seat = row.getEventSeat();
                    return new HeldGroup.GroupSeat(
                            seat.getId(),
                            seat.getSeat().getSection(),
                            seat.getSeat().getRowLabel(),
                            seat.getSeat().getSeatNumber(),
                            seat.getPriceCents());
                })
                .toList();
        long total = seats.stream().mapToLong(HeldGroup.GroupSeat::priceCents).sum();
        return new HeldGroup(
                rows.getFirst().getHoldGroupId(),
                event.getId(),
                event.getName(),
                seats,
                total,
                event.getCurrency(),
                rows.getFirst().getExpiresAt());
    }
}
