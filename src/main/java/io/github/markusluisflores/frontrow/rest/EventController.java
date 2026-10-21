package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.service.EventPatch;
import io.github.markusluisflores.frontrow.service.EventPatchResult;
import io.github.markusluisflores.frontrow.service.EventPatchService;
import java.time.Instant;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * An organiser's changes to an event (spec §6): its status, cancellation included, and its sales window. Any
 * ORGANISER may change any event, because Phase 1 records no event owner (plan 4A decision 2). Plan 4B adds the reads
 * and event creation here.
 */
@RestController
public class EventController {

    private final EventPatchService patches;

    public EventController(EventPatchService patches) {
        this.patches = patches;
    }

    /** The JSON body of a PATCH. An absent field keeps its current value. */
    public record EventPatchRequest(EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {}

    @PatchMapping("/api/events/{eventId}")
    public EventPatchResult patch(@PathVariable long eventId, @RequestBody EventPatchRequest request) {
        return patches.patch(new EventPatch(eventId, request.status(), request.salesOpenAt(), request.salesCloseAt()));
    }
}
