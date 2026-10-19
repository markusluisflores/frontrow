package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/**
 * An organiser's change to one event (spec §6, {@code PATCH /api/events/{id}}): a status, a sales window, or both. A
 * null field leaves that value as it is.
 */
public record EventPatch(long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {}
