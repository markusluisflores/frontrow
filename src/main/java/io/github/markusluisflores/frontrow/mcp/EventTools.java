package io.github.markusluisflores.frontrow.mcp;

import io.github.markusluisflores.frontrow.service.Availability;
import io.github.markusluisflores.frontrow.service.EventDetail;
import io.github.markusluisflores.frontrow.service.EventQuery;
import io.github.markusluisflores.frontrow.service.EventReadService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The three read tools (spec §6, plan 5 decision 3). Each calls exactly one EventReadService method and returns the
 * record REST returns. The Java parameter names are the wire names: ToolCall binds each argument by name and the input
 * schema is generated from these declarations, so a rename to camelCase changes the wire. Every parameter is boxed so
 * that a missing one reaches the check that turns it into invalid_request. The methods carry no {@code @McpTool}: they
 * are registered by hand (decision 5).
 */
@Component
public class EventTools {

    /** search_events wraps REST's top-level array in an object: structured content is always an object (decision 4). */
    public record EventList(List<EventDetail> events) {

        public EventList {
            events = List.copyOf(events);
        }
    }

    private final EventReadService reads;
    private final ToolCall calls;

    public EventTools(EventReadService reads, ToolCall calls) {
        this.reads = reads;
        this.calls = calls;
    }

    List<SyncToolSpecification> specifications() {
        return List.of(
                calls.specification(
                        "search_events",
                        "Search events that are on sale, soonest first. Optional filters: a case-insensitive "
                                + "literal text match on the event name, and an inclusive UTC date range on the start.",
                        this,
                        "searchEvents"),
                calls.specification(
                        "get_event",
                        "Get one event that is on sale: its venue, start and sales window.",
                        this,
                        "getEvent"),
                calls.specification(
                        "check_availability",
                        "List an on-sale event's seats and whether each is free, optionally for one section. "
                                + "Use the event_seat_id values to hold seats.",
                        this,
                        "checkAvailability"));
    }

    public EventList searchEvents(
            @McpToolParam(required = false, description = "Text to find in the event name, ignoring case") String query,
            @McpToolParam(required = false, description = "Earliest start date, ISO yyyy-MM-dd, inclusive (UTC)")
                    LocalDate from_date,
            @McpToolParam(required = false, description = "Latest start date, ISO yyyy-MM-dd, inclusive (UTC)")
                    LocalDate to_date,
            @McpToolParam(required = false, description = "Most events to return, 1 to 50; default 10") Integer limit) {
        return new EventList(reads.search(new EventQuery(query, from_date, to_date, limit)));
    }

    public EventDetail getEvent(@McpToolParam(description = "The event's id, from search_events") Long event_id) {
        return reads.event(event_id);
    }

    public Availability checkAvailability(
            @McpToolParam(description = "The event's id, from search_events") Long event_id,
            @McpToolParam(required = false, description = "Only seats in this section, such as Floor") String section) {
        return reads.availability(event_id, section);
    }
}
