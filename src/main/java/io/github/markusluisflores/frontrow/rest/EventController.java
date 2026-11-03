package io.github.markusluisflores.frontrow.rest;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.service.EventCreateService;
import io.github.markusluisflores.frontrow.service.EventDetail;
import io.github.markusluisflores.frontrow.service.EventDraft;
import io.github.markusluisflores.frontrow.service.EventPatch;
import io.github.markusluisflores.frontrow.service.EventPatchResult;
import io.github.markusluisflores.frontrow.service.EventPatchService;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Events (spec §6): an organiser creates a DRAFT and changes its status, cancellation included, and its sales window.
 * Any ORGANISER may change any event, because Phase 1 records no event owner (plan 4A decision 2).
 */
@RestController
public class EventController {

    private final EventPatchService patches;
    private final EventCreateService creations;

    public EventController(EventPatchService patches, EventCreateService creations) {
        this.patches = patches;
        this.creations = creations;
    }

    /** The JSON body of a PATCH. An absent field keeps its current value. */
    public record EventPatchRequest(EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {}

    /**
     * The JSON body of a creation. prices maps each section name to cents; map keys are data, so the snake_case wire
     * leaves them alone. Jackson refuses a null price itself, so the error names prices.
     */
    public record EventCreateRequest(
            Long venueId,
            String name,
            Instant startsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            String currency,
            @JsonSetter(contentNulls = Nulls.FAIL) Map<String, Long> prices) {

        public EventCreateRequest {
            prices = prices == null ? null : Map.copyOf(prices);
        }
    }

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetail create(@RequestBody EventCreateRequest request) {
        return creations.create(new EventDraft(
                request.venueId(),
                request.name(),
                request.startsAt(),
                request.salesOpenAt(),
                request.salesCloseAt(),
                request.currency(),
                request.prices()));
    }

    @PatchMapping("/api/events/{eventId}")
    public EventPatchResult patch(@PathVariable long eventId, @RequestBody EventPatchRequest request) {
        return patches.patch(new EventPatch(eventId, request.status(), request.salesOpenAt(), request.salesCloseAt()));
    }
}
