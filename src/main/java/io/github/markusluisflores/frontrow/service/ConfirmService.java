package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.domain.Money;
import io.github.markusluisflores.frontrow.domain.OrderStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRepository;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.HoldRow;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.persistence.OrderLine;
import io.github.markusluisflores.frontrow.persistence.OrderLineRepository;
import io.github.markusluisflores.frontrow.persistence.SeatHoldRepository;
import io.github.markusluisflores.frontrow.persistence.TicketOrder;
import io.github.markusluisflores.frontrow.persistence.TicketOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hold confirmation, spec §5: the only purchase path, REST only. One transaction: an unlocked read of the group's
 * event, a shared lock on the event row, the group's rows ascending, then the decision: the existing order first,
 * the event second and the rows' state last.
 */
@Service
public class ConfirmService {

    private final LockingGateway gateway;
    private final SeatHoldRepository seatHolds;
    private final TicketOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final Clock clock;

    public ConfirmService(
            LockingGateway gateway,
            SeatHoldRepository seatHolds,
            TicketOrderRepository orders,
            OrderLineRepository orderLines,
            EventRepository events,
            EventSeatRepository eventSeats,
            Clock clock) {
        this.gateway = gateway;
        this.seatHolds = seatHolds;
        this.orders = orders;
        this.orderLines = orderLines;
        this.events = events;
        this.eventSeats = eventSeats;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResult confirm(HoldGroupCommand command) {
        TransactionBoundary.requireOwnTransaction();
        UUID group = command.holdGroupId();
        String owner = command.owner();
        try {
            // Step 1. Another owner's group answers the same as no group (spec §5, §6).
            long eventId = seatHolds
                    .findEventIdOfGroup(group, owner)
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
            EventRow event = gateway.lockEventForShare(eventId)
                    .orElseThrow(() -> new IllegalStateException("hold group " + group + " has no event row"));
            List<HoldRow> rows = gateway.lockGroupRows(group, owner);
            // Read once step 1's locks are held (spec §5).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

            // Step 2. A repeated or double-clicked confirm waited on step 1's locks; it returns the order (spec §5).
            Optional<TicketOrder> existing = orders.findByHoldGroupId(group);
            if (existing.isPresent()) {
                return result(
                        existing.get(),
                        orderLines.findByOrderIdInSeatOrder(existing.get().getId()));
            }
            // Step 3, before the rows: a cancellation also releases the holds, and "the event is closed" is the
            // true answer then, not "your hold was released".
            SalesGate.requireOpen(event, now);
            // Step 4.
            DomainException refusal =
                    switch (HoldRules.confirmOutcome(
                            rows.stream().map(HoldRow::toRule).toList(), now)) {
                        case PROCEED -> null;
                        case HOLD_NOT_ACTIVE -> DomainException.of(ErrorCode.HOLD_NOT_ACTIVE);
                        case HOLD_EXPIRED -> DomainException.of(ErrorCode.HOLD_EXPIRED);
                    };
            if (refusal != null) {
                throw refusal;
            }
            // Step 5.
            LockedHolds.end(gateway, rows.stream().map(HoldRow::id).toList(), HoldStatus.CONVERTED);
            return placeOrder(event, group, owner, rows, now);
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    /**
     * New rows, so JPA writes them, flushed here so that a constraint violation surfaces inside confirm's
     * translation scope rather than at commit (ADR-005, plan 3B amendment).
     */
    private OrderResult placeOrder(EventRow event, UUID group, String owner, List<HoldRow> rows, Instant now) {
        List<Long> seatIds = rows.stream().map(HoldRow::eventSeatId).toList();
        Map<Long, EventSeat> seats = new HashMap<>();
        for (EventSeat seat : eventSeats.findOfferedSeats(event.id(), seatIds)) {
            seats.put(seat.getId(), seat);
        }
        Money total = Money.ofCents(0, event.currency());
        for (long seatId : seatIds) {
            total = total.plus(seats.get(seatId).price(event.currency()));
        }
        TicketOrder order = orders.saveAndFlush(new TicketOrder(
                events.getReferenceById(event.id()),
                group,
                owner,
                OrderStatus.CONFIRMED,
                total.cents(),
                total.currency(),
                now));
        List<OrderLine> lines = new ArrayList<>();
        for (long seatId : seatIds) {
            EventSeat seat = seats.get(seatId);
            lines.add(new OrderLine(order, seat, seat.getPriceCents()));
        }
        return result(order, orderLines.saveAllAndFlush(lines));
    }

    private static OrderResult result(TicketOrder order, List<OrderLine> lines) {
        return new OrderResult(
                order.getId(),
                order.getHoldGroupId(),
                order.getEvent().getId(),
                lines.stream()
                        .map(line -> new OrderResult.Line(line.getEventSeat().getId(), line.getPriceCents()))
                        .toList(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getCreatedAt());
    }
}
