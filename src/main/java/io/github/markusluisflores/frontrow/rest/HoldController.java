package io.github.markusluisflores.frontrow.rest;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.markusluisflores.frontrow.service.ConfirmService;
import io.github.markusluisflores.frontrow.service.HoldCommand;
import io.github.markusluisflores.frontrow.service.HoldGroupCommand;
import io.github.markusluisflores.frontrow.service.HoldResult;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.service.OrderResult;
import io.github.markusluisflores.frontrow.service.ReleaseResult;
import io.github.markusluisflores.frontrow.service.ReleaseService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A buyer's holds (spec §6, REST surface): hold, release, and confirm, the only purchase path. Each method maps the
 * request to one service call; the owner is always the authenticated user's name, never a parameter (spec §3). No
 * method here is transactional: each service starts its own transaction, and refuses to join one (plan 4A decision 4).
 */
@RestController
public class HoldController {

    private final HoldService holds;
    private final ReleaseService releases;
    private final ConfirmService confirms;

    public HoldController(HoldService holds, ReleaseService releases, ConfirmService confirms) {
        this.holds = holds;
        this.releases = releases;
        this.confirms = confirms;
    }

    /**
     * The JSON body of a hold: the specific seats wanted (spec §6; the service never chooses seats). Jackson refuses a
     * null element itself, so the error names event_seat_ids; List.copyOf would throw on it, and only "body" would be
     * named.
     */
    public record HoldRequest(
            @JsonSetter(contentNulls = Nulls.FAIL) List<Long> eventSeatIds) {

        public HoldRequest {
            eventSeatIds = eventSeatIds == null ? null : List.copyOf(eventSeatIds);
        }
    }

    /** 201 on a new hold and on a replay alike: a replay returns the original result (spec §5). */
    @PostMapping("/api/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult hold(
            @PathVariable long eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HoldRequest request,
            Principal principal) {
        // A missing body, or a literal null, never gets here: Spring refuses it, and it is invalid_request "body".
        return holds.hold(new HoldCommand(principal.getName(), eventId, request.eventSeatIds(), idempotencyKey));
    }

    @DeleteMapping("/api/holds/{holdGroupId}")
    public ReleaseResult release(@PathVariable UUID holdGroupId, Principal principal) {
        return releases.release(new HoldGroupCommand(principal.getName(), holdGroupId));
    }

    /** Idempotent: a repeated confirm returns the order the first one made (spec §5). */
    @PostMapping("/api/holds/{holdGroupId}/confirm")
    public OrderResult confirm(@PathVariable UUID holdGroupId, Principal principal) {
        return confirms.confirm(new HoldGroupCommand(principal.getName(), holdGroupId));
    }
}
