package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.Event;
import io.github.markusluisflores.frontrow.persistence.EventRepository;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.SeatHoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event reads (spec §6): search, one event, and its availability. The REST reads and plan 5's three read tools call
 * the same methods. Read-only transactions with no lock (ADR-005): nothing here writes, so no entity is ever flushed.
 * Only ON_SALE events are readable, by id or in search (spec §4).
 */
@Service
public class EventReadService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final SeatHoldRepository seatHolds;
    private final Clock clock;

    public EventReadService(
            EventRepository events, EventSeatRepository eventSeats, SeatHoldRepository seatHolds, Clock clock) {
        this.events = events;
        this.eventSeats = eventSeats;
        this.seatHolds = seatHolds;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<EventDetail> search(EventQuery query) {
        TransactionBoundary.requireOwnTransaction();
        int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "limit"));
        }
        // Postgres text cannot hold U+0000: bound into the LIKE pattern it would fail as a 500, not invalid_request.
        if (query.query() != null && query.query().indexOf('\u0000') >= 0) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "query"));
        }
        return events
                .search(
                        EventStatus.ON_SALE,
                        pattern(query.query()),
                        startOf(query.fromDate()),
                        endOf(query.toDate()),
                        Limit.of(limit))
                .stream()
                .map(EventDetail::of)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public EventDetail event(long eventId) {
        TransactionBoundary.requireOwnTransaction();
        return EventDetail.of(visible(eventId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public Availability availability(long eventId, String section) {
        TransactionBoundary.requireOwnTransaction();
        Event event = visible(eventId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Set<Long> claimed =
                Set.copyOf(seatHolds.findClaimedSeatIds(eventId, now, HoldStatus.CONVERTED, HoldStatus.ACTIVE));
        List<Availability.SeatState> seats = eventSeats.findByEventWithSeat(eventId).stream()
                .filter(seat -> section == null || seat.getSeat().getSection().equals(section))
                .map(seat -> state(seat, claimed))
                .toList();
        return new Availability(event.getId(), event.getCurrency(), seats);
    }

    /**
     * An event that is not ON_SALE answers the same as a missing id. A DRAFT is hidden by spec §4. A CANCELLED event
     * may have been cancelled straight from DRAFT (EventPatchService allows it), so it is hidden too, or a
     * never-published draft's details would become readable by cancelling it.
     */
    private Event visible(long eventId) {
        return events.findWithVenue(eventId)
                .filter(event -> event.getStatus() == EventStatus.ON_SALE)
                .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
    }

    private static Availability.SeatState state(EventSeat seat, Set<Long> claimed) {
        return new Availability.SeatState(
                seat.getId(),
                seat.getSeat().getSection(),
                seat.getSeat().getRowLabel(),
                seat.getSeat().getSeatNumber(),
                seat.getPriceCents(),
                !claimed.contains(seat.getId()));
    }

    /**
     * A literal substring match: '!', '%' and '_' in the query are escaped with '!'. The case is left alone:
     * EventRepository#search lowers both sides in Postgres, because Java's lower-casing differs from Postgres's on a
     * word-final Σ and on İ.
     */
    private static String pattern(String query) {
        if (query == null) {
            return "%";
        }
        String escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static Instant startOf(LocalDate fromDate) {
        return fromDate == null
                ? EventInstants.EARLIEST
                : clamp(fromDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * The last storable instant of to_date (the next day's start less a microsecond), an inclusive bound, so an event
     * starting exactly at LATEST is found. No to_date, or a year past 9999, is LATEST itself.
     */
    private static Instant endOf(LocalDate toDate) {
        if (toDate == null || toDate.getYear() > 9999) {
            return EventInstants.LATEST;
        }
        return clamp(toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minus(1, ChronoUnit.MICROS));
    }

    /** Postgres never sees an instant outside the range timestamptz holds (spec §5). */
    private static Instant clamp(Instant instant) {
        if (instant.isBefore(EventInstants.EARLIEST)) {
            return EventInstants.EARLIEST;
        }
        return instant.isAfter(EventInstants.LATEST) ? EventInstants.LATEST : instant;
    }
}
