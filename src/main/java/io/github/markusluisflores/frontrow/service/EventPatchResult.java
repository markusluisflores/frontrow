package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/** The event as the PATCH left it, and how many holds a cancellation released. */
public record EventPatchResult(
        long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt, int releasedHolds) {}
