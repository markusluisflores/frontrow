package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import io.github.markusluisflores.frontrow.persistence.HoldRow;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.persistence.TicketOrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event PATCH, cancellation included (spec §5, Event cancellation). It locks the event row exclusively, which
 * waits for every hold creation and confirmation in flight on the event and blocks new ones until it commits. So no
 * confirm can commit against a window it has just closed, or an event it has just cancelled. A lock timeout bounds
 * every wait, and a timeout asks the organiser to retry (spec §5, Liveness). The REST adapter lets any ORGANISER call
 * it, for any event: Phase 1 has no per-event ownership (plan 4A decision 2).
 */
@Service
public class EventPatchService {

    /**
     * The widest sales window a PATCH may set: what both timestamptz and a four-digit ISO 8601 year can hold. An
     * instant outside it would otherwise reach Postgres and fail there as a 500 (PR #22 thread 4305094277). This is the
     * one write path that takes caller-supplied instants, so the bound lives here, whichever adapter calls.
     */
    static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

    static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private final LockingGateway gateway;
    private final TicketOrderRepository orders;

    public EventPatchService(LockingGateway gateway, TicketOrderRepository orders) {
        this.gateway = gateway;
        this.orders = orders;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventPatchResult patch(EventPatch patch) {
        TransactionBoundary.requireOwnTransaction();
        try {
            // First, so that it bounds every lock below, including a cancellation's seat rows.
            gateway.setLockTimeout();
            EventRow event = gateway.lockEventForUpdate(patch.eventId())
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
            EventStatus status = patch.status() == null ? event.status() : patch.status();
            // timestamptz keeps microseconds; the window is checked as it will be stored.
            Instant openAt = patch.salesOpenAt() == null
                    ? event.salesOpenAt()
                    : patch.salesOpenAt().truncatedTo(ChronoUnit.MICROS);
            Instant closeAt = patch.salesCloseAt() == null
                    ? event.salesCloseAt()
                    : patch.salesCloseAt().truncatedTo(ChronoUnit.MICROS);
            requireAllowed(event, status, openAt, closeAt);

            int released = status == EventStatus.CANCELLED && event.status() != EventStatus.CANCELLED
                    ? releaseForCancellation(event.id())
                    : 0;
            gateway.updateEvent(event.id(), status, openAt, closeAt);
            return new EventPatchResult(event.id(), status, openAt, closeAt, released);
        } catch (DataAccessException failure) {
            throw SqlFailures.translateOnPatchPath(failure);
        }
    }

    private static void requireAllowed(EventRow event, EventStatus status, Instant openAt, Instant closeAt) {
        boolean statusAllowed =
                switch (event.status()) {
                    case DRAFT -> true;
                    // No PATCH moves an event back to DRAFT (spec §5, confirmation step 3).
                    case ON_SALE -> status != EventStatus.DRAFT;
                    case CANCELLED -> status == EventStatus.CANCELLED;
                };
        if (!statusAllowed) {
            throw invalid("status");
        }
        if (openAt.isBefore(EARLIEST) || closeAt.isAfter(LATEST)) {
            throw invalid("sales_window");
        }
        boolean windowChanged = !openAt.equals(event.salesOpenAt()) || !closeAt.equals(event.salesCloseAt());
        if ((event.status() == EventStatus.CANCELLED && windowChanged) || !openAt.isBefore(closeAt)) {
            throw invalid("sales_window");
        }
    }

    /**
     * Refused once anything is sold: cancelling sold events is part of the cancellation non-goal (spec §2).
     * Otherwise every ACTIVE hold on the event moves to RELEASED, lapsed or not, locked ascending.
     */
    private int releaseForCancellation(long eventId) {
        // After the event-row wait, so it sees every confirmation that committed first (READ COMMITTED, spec §5).
        if (orders.existsByEventId(eventId)) {
            throw invalid("status");
        }
        List<Long> holdIds = gateway.lockActiveHoldsForEvent(eventId).stream()
                .map(HoldRow::id)
                .toList();
        LockedHolds.end(gateway, holdIds, HoldStatus.RELEASED);
        return holdIds.size();
    }

    private static DomainException invalid(String parameter) {
        return new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }
}
