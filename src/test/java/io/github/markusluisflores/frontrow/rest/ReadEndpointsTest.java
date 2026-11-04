package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The three anonymous event reads end to end (spec §6). Bodies are compared as parsed JSON, never as strings. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTestConfiguration.class)
class ReadEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;
    long eventId;
    long draftId;
    long cancelledId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.namedEvent(venueId, "Night Owls Live", "ON_SALE", Instant.parse("2026-11-10T19:00:00Z"));
        draftId = fixtures.namedEvent(venueId, "Secret Draft", "DRAFT", Instant.parse("2026-11-11T19:00:00Z"));
        cancelledId =
                fixtures.namedEvent(venueId, "Cancelled Draft", "CANCELLED", Instant.parse("2026-11-12T19:00:00Z"));
        seats = fixtures.eventSeats(eventId, venueId, 2);
    }

    @Test
    void anonymousCallersSearchAndReadOnSaleEvents() throws Exception {
        MockHttpServletResponse listed = perform(get("/api/events").param("query", "owls"));
        assertThat(listed.getStatus()).isEqualTo(200);
        JsonNode events = body(listed);
        assertThat(events).hasSize(1);
        assertThat(names(events.get(0)))
                .containsExactlyInAnyOrder(
                        "event_id",
                        "venue_id",
                        "venue_name",
                        "name",
                        "starts_at",
                        "sales_open_at",
                        "sales_close_at",
                        "status",
                        "currency");

        MockHttpServletResponse one = perform(get("/api/events/" + eventId));
        assertThat(one.getStatus()).isEqualTo(200);
        assertThat(body(one).get("starts_at").asString()).isEqualTo("2026-11-10T19:00:00Z");
    }

    @Test
    void anEventThatIsNotOnSaleIsNotFoundOverHttp() throws Exception {
        for (long id : List.of(draftId, cancelledId)) {
            for (String path : List.of("/api/events/" + id, "/api/events/" + id + "/availability")) {
                MockHttpServletResponse response = perform(get(path));
                assertThat(response.getStatus()).as(path).isEqualTo(404);
                assertThat(body(response).get("code").asString()).isEqualTo("not_found");
            }
        }
        assertThat(body(perform(get("/api/events")))).hasSize(1);
    }

    @Test
    void availabilityShowsWhetherASeatIsFreeAndNeverWhoseHoldItIs() throws Exception {
        UUID bobsGroup = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), bobsGroup, "bob", "ACTIVE", HoldFixtures.NOW.plusSeconds(60));

        MockHttpServletResponse response = perform(get("/api/events/" + eventId + "/availability"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode availability = body(response);
        // Spec §6 and §12: no hold_group_id, owner or expires_at, at any level.
        assertThat(names(availability)).containsExactlyInAnyOrder("event_id", "currency", "seats");
        for (JsonNode seat : availability.get("seats")) {
            assertThat(names(seat))
                    .containsExactlyInAnyOrder(
                            "event_seat_id", "section", "row_label", "seat_number", "price_cents", "available");
        }
        assertThat(availability.get("seats").get(0).get("available").asBoolean())
                .isFalse();
        assertThat(availability.get("seats").get(1).get("available").asBoolean())
                .isTrue();
        String text = response.getContentAsString();
        assertThat(text).doesNotContain("bob", bobsGroup.toString());
    }

    @Test
    void malformedSearchParametersAreNamed() throws Exception {
        for (Map.Entry<String, String> bad : List.of(
                Map.entry("limit", "abc"),
                Map.entry("limit", "51"),
                Map.entry("query", "a\u0000b"),
                Map.entry("from_date", "2026-13-01"),
                Map.entry("to_date", "yesterday"))) {
            MockHttpServletResponse response = perform(get("/api/events").param(bad.getKey(), bad.getValue()));
            assertThat(response.getStatus()).as(bad.toString()).isEqualTo(400);
            JsonNode problem = body(response);
            assertThat(problem.get("code").asString()).isEqualTo("invalid_request");
            assertThat(problem.get("parameter").asString()).isEqualTo(bad.getKey());
        }
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
