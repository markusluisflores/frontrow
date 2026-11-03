package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.persistence.Event;
import java.time.Instant;

/**
 * An event as creation and the reads return it (spec §6). A read never returns a DRAFT: a draft is not_found there
 * (spec §4). Creation returns the new DRAFT, which is how an organiser sees it.
 */
public record EventDetail(
        long eventId,
        long venueId,
        String venueName,
        String name,
        Instant startsAt,
        Instant salesOpenAt,
        Instant salesCloseAt,
        EventStatus status,
        String currency) {

    static EventDetail of(Event event) {
        return new EventDetail(
                event.getId(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getName(),
                event.getStartsAt(),
                event.getSalesOpenAt(),
                event.getSalesCloseAt(),
                event.getStatus(),
                event.getCurrency());
    }
}
