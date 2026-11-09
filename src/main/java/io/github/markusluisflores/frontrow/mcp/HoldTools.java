package io.github.markusluisflores.frontrow.mcp;

import io.github.markusluisflores.frontrow.service.HoldCommand;
import io.github.markusluisflores.frontrow.service.HoldGroupCommand;
import io.github.markusluisflores.frontrow.service.HoldResult;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.service.ReleaseResult;
import io.github.markusluisflores.frontrow.service.ReleaseService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The two write tools (spec §6, plan 5 decisions 3 and 5). Each calls exactly one service method. The caller is the
 * principal of the request's transport context and nothing else: neither tool declares a parameter that could name
 * one, and the {@link McpTransportContext} parameter is the request's own, supplied by ToolCall and left out of the
 * schema. There is no tool that completes a purchase (ADR-002): a hold becomes an order only through the REST confirm
 * endpoint, under the human's own credentials. The methods carry no {@code @McpTool}: they are registered by hand.
 */
@Component
public class HoldTools {

    private final HoldService holds;
    private final ReleaseService releases;
    private final ToolCall calls;

    public HoldTools(HoldService holds, ReleaseService releases, ToolCall calls) {
        this.holds = holds;
        this.releases = releases;
        this.calls = calls;
    }

    List<SyncToolSpecification> specifications() {
        return List.of(
                calls.specification(
                        "hold_seats",
                        "Hold specific seats of an on-sale event for a short time, all or none. Returns a "
                                + "hold_group_id, the seats, the total and when the hold expires; give the "
                                + "hold_group_id to the person you are helping, who finishes the reservation "
                                + "themselves. Calling again with the same idempotency_key and seats returns the "
                                + "same result, and a retry after a timeout is safe.",
                        this,
                        "holdSeats"),
                calls.specification(
                        "release_hold",
                        "Release a hold you made, freeing its seats. Succeeds with zero counts when nothing in the "
                                + "group is live any more.",
                        this,
                        "releaseHold"));
    }

    public HoldResult holdSeats(
            McpTransportContext context,
            @McpToolParam(description = "The event's id, from search_events") Long event_id,
            @McpToolParam(description = "The event_seat_id values to hold, from check_availability, 1 to 8 of them")
                    List<Long> event_seat_ids,
            @McpToolParam(
                            description =
                                    "A fresh key you choose for this request, such as a UUID; reuse it only to retry "
                                            + "the same request")
                    String idempotency_key) {
        return holds.hold(new HoldCommand(ToolCall.owner(context), event_id, event_seat_ids, idempotency_key));
    }

    public ReleaseResult releaseHold(
            McpTransportContext context,
            @McpToolParam(description = "The hold_group_id that hold_seats returned") UUID hold_group_id) {
        return releases.release(new HoldGroupCommand(ToolCall.owner(context), hold_group_id));
    }
}
