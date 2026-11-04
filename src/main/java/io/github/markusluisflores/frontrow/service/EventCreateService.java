package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.SalesWindow;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.Event;
import io.github.markusluisflores.frontrow.persistence.EventRepository;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.Seat;
import io.github.markusluisflores.frontrow.persistence.SeatRepository;
import io.github.markusluisflores.frontrow.persistence.Venue;
import io.github.markusluisflores.frontrow.persistence.VenueRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event creation (spec §6, POST /api/events): a DRAFT on an existing venue, offering every seat of the venue at its
 * section's price. The instants are bounded and the window must close by the start, as on a PATCH (spec §5). It reads no
 * clock and takes no lock: it makes no time-dependent decision, and nothing else ever locks the venue and seat rows its
 * foreign keys check.
 */
@Service
public class EventCreateService {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final VenueRepository venues;
    private final SeatRepository seats;
    private final EventRepository events;
    private final EventSeatRepository eventSeats;

    public EventCreateService(
            VenueRepository venues, SeatRepository seats, EventRepository events, EventSeatRepository eventSeats) {
        this.venues = venues;
        this.seats = seats;
        this.events = events;
        this.eventSeats = eventSeats;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventDetail create(EventDraft draft) {
        TransactionBoundary.requireOwnTransaction();
        // Postgres text cannot hold U+0000: it would fail at bind time as a 500, not invalid_request.
        if (draft.name() == null || draft.name().isBlank() || draft.name().indexOf('\u0000') >= 0) {
            throw invalid("name");
        }
        if (draft.currency() == null || !CURRENCY.matcher(draft.currency()).matches()) {
            throw invalid("currency");
        }
        Instant startsAt = storable(draft.startsAt(), "starts_at");
        Instant openAt = storable(draft.salesOpenAt(), "sales_window");
        Instant closeAt = storable(draft.salesCloseAt(), "sales_window");
        if (!openAt.isBefore(closeAt) || !SalesWindow.of(openAt, closeAt).closesBy(startsAt)) {
            throw invalid("sales_window");
        }
        if (draft.venueId() == null) {
            throw invalid("venue_id");
        }
        try {
            Venue venue = venues.findById(draft.venueId()).orElseThrow(() -> invalid("venue_id"));
            List<Seat> venueSeats = seats.findByVenueIdOrderByIdAsc(venue.getId());
            Map<String, Long> prices = requirePrices(draft.prices(), venueSeats);
            Event event = events.saveAndFlush(
                    new Event(venue, draft.name(), startsAt, openAt, closeAt, EventStatus.DRAFT, draft.currency()));
            eventSeats.saveAllAndFlush(venueSeats.stream()
                    .map(seat -> new EventSeat(event, seat, venue.getId(), prices.get(seat.getSection())))
                    .toList());
            return EventDetail.of(event);
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    /** Exactly the venue's sections, each at zero cents or more. */
    private static Map<String, Long> requirePrices(Map<String, Long> prices, List<Seat> venueSeats) {
        Set<String> sections = venueSeats.stream().map(Seat::getSection).collect(Collectors.toSet());
        if (prices == null
                || !prices.keySet().equals(sections)
                || prices.values().stream().anyMatch(price -> price < 0)) {
            throw invalid("prices");
        }
        return prices;
    }

    /** timestamptz keeps microseconds: the instant is checked as it will be stored. */
    private static Instant storable(Instant instant, String parameter) {
        if (instant == null || !EventInstants.storable(instant)) {
            throw invalid(parameter);
        }
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static DomainException invalid(String parameter) {
        return new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }
}
