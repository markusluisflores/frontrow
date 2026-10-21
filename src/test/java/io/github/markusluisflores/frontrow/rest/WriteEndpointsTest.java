package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four write endpoints end to end: the real filter chain, the controllers, the services and Postgres. Bodies are
 * compared as parsed JSON, never as strings (PR #18 thread 4296749419).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTestConfiguration.class)
class WriteEndpointsTest {

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
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 6);
    }

    @Test
    void holdReplayConfirmAndConfirmAgain() throws Exception {
        MockHttpServletResponse held = perform(hold("alice", "k1", seats.get(1), seats.get(0)));
        assertThat(held.getStatus()).isEqualTo(201);
        JsonNode hold = body(held);
        assertThat(hold.get("event_id").asLong()).isEqualTo(eventId);
        assertThat(hold.get("seats").findValuesAsString("event_seat_id"))
                .containsExactly(seats.get(0).toString(), seats.get(1).toString());
        assertThat(hold.get("total_cents").asLong()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
        assertThat(hold.get("expires_at").asString()).isEqualTo("2026-10-01T12:10:00Z");

        MockHttpServletResponse replayed = perform(hold("alice", "k1", seats.get(0), seats.get(1)));
        assertThat(replayed.getStatus()).isEqualTo(201);
        assertThat(body(replayed)).isEqualTo(hold);

        String group = hold.get("hold_group_id").asString();
        MockHttpServletResponse confirmed =
                perform(post("/api/holds/" + group + "/confirm").with(as("alice")));
        assertThat(confirmed.getStatus()).isEqualTo(200);
        JsonNode order = body(confirmed);
        assertThat(order.get("hold_group_id").asString()).isEqualTo(group);
        assertThat(order.get("lines")).hasSize(2);

        MockHttpServletResponse again =
                perform(post("/api/holds/" + group + "/confirm").with(as("alice")));
        assertThat(body(again)).isEqualTo(order);
        assertThat(fixtures.ordersForGroup(UUID.fromString(group))).isEqualTo(1);
    }

    @Test
    void releaseFreesTheSeatsForSomeoneElse() throws Exception {
        String group = body(perform(hold("alice", "k1", seats.get(0))))
                .get("hold_group_id")
                .asString();

        MockHttpServletResponse released = perform(delete("/api/holds/" + group).with(as("alice")));

        assertThat(released.getStatus()).isEqualTo(200);
        assertThat(body(released)).isEqualTo(JSON.readTree("""
                        {"hold_group_id": "%s", "released": 1, "expired": 0}""".formatted(group)));
        assertThat(perform(hold("bob", "k1", seats.get(0))).getStatus()).isEqualTo(201);
    }

    @Test
    void anotherOwnersHoldIsNotFoundToConfirmAndToRelease() throws Exception {
        String group = body(perform(hold("alice", "k1", seats.get(0))))
                .get("hold_group_id")
                .asString();

        for (MockHttpServletRequestBuilder request :
                List.of(post("/api/holds/" + group + "/confirm"), delete("/api/holds/" + group))) {
            MockHttpServletResponse response = perform(request.with(as("bob")));
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(body(response).get("code").asString()).isEqualTo("not_found");
        }
        assertThat(fixtures.ordersForGroup(UUID.fromString(group))).isZero();
    }

    @Test
    void aTakenSeatIsAConflictNamingTheSeat() throws Exception {
        perform(hold("alice", "k1", seats.get(0)));

        MockHttpServletResponse taken = perform(hold("bob", "k1", seats.get(0)));

        assertThat(taken.getStatus()).isEqualTo(409);
        JsonNode problem = body(taken);
        assertThat(problem.get("code").asString()).isEqualTo("seat_taken");
        assertThat(problem.get("seat_ids").get(0).asLong()).isEqualTo(seats.get(0));
    }

    @Test
    void theIdempotencyKeyIsNamedAsTheHeaderWhetherMissingOrMalformed() throws Exception {
        MockHttpServletResponse missing = perform(post("/api/events/" + eventId + "/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_seat_ids\": [%d]}".formatted(seats.get(0)))
                .with(as("alice")));
        MockHttpServletResponse blank = perform(hold("alice", " ", seats.get(0)));

        for (MockHttpServletResponse response : List.of(missing, blank)) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(body(response).get("parameter").asString()).isEqualTo("Idempotency-Key");
        }
    }

    @Test
    void aKeyReusedForADifferentRequestIsUnprocessable() throws Exception {
        perform(hold("alice", "k1", seats.get(0)));

        MockHttpServletResponse reused = perform(hold("alice", "k1", seats.get(1)));

        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(body(reused).get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void thePerOwnerGroupCapHoldsThroughRest() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(perform(hold("alice", "k" + i, seats.get(i))).getStatus())
                    .isEqualTo(201);
        }

        MockHttpServletResponse capped = perform(hold("alice", "k3", seats.get(3)));

        assertThat(capped.getStatus()).isEqualTo(409);
        assertThat(body(capped).get("code").asString()).isEqualTo("hold_limit_exceeded");
        assertThat(perform(hold("bob", "k3", seats.get(3))).getStatus()).isEqualTo(201);
    }

    @Test
    void malformedRequestsNameTheParameter() throws Exception {
        assertInvalid(post("/api/holds/not-a-uuid/confirm").with(as("alice")), "hold_group_id");
        assertInvalid(
                post("/api/events/x/holds")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_seat_ids\": [1]}")
                        .with(as("alice")),
                "event_id");
        assertInvalid(
                post("/api/events/" + eventId + "/holds")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null")
                        .with(as("alice")),
                "body");
        assertInvalid(hold("alice", "k1"), "event_seat_ids");
        assertInvalid(
                post("/api/events/" + eventId + "/holds")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_seat_ids\": [1, null]}")
                        .with(as("alice")),
                "event_seat_ids");
    }

    @Test
    void anOrganiserCancelsAndTheHoldsAreReleased() throws Exception {
        perform(hold("alice", "k1", seats.get(0), seats.get(1)));

        MockHttpServletResponse cancelled = perform(patchEvent("olivia", "{\"status\": \"CANCELLED\"}"));

        assertThat(cancelled.getStatus()).isEqualTo(200);
        JsonNode result = body(cancelled);
        assertThat(result.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(result.get("released_holds").asInt()).isEqualTo(2);
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("CANCELLED");
    }

    @Test
    void aPatchOutsideWhatPostgresCanStoreIsInvalidNotA500() throws Exception {
        MockHttpServletResponse response =
                perform(patchEvent("olivia", "{\"sales_close_at\": \"+300000-01-01T00:00:00Z\"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response).get("parameter").asString()).isEqualTo("sales_window");
    }

    @Test
    void anUnknownStatusNamesTheField() throws Exception {
        assertInvalid(
                patch("/api/events/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SOLD_OUT\"}")
                        .with(as("olivia")),
                "status");
    }

    private MockHttpServletRequestBuilder hold(String user, String key, Long... seatIds) {
        return post("/api/events/" + eventId + "/holds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("event_seat_ids", List.of(seatIds))))
                .with(as(user));
    }

    private MockHttpServletRequestBuilder patchEvent(String user, String json) {
        return patch("/api/events/" + eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(as(user));
    }

    private void assertInvalid(MockHttpServletRequestBuilder request, String parameter) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode problem = body(response);
        assertThat(problem.get("code").asString()).isEqualTo("invalid_request");
        assertThat(problem.get("parameter").asString()).isEqualTo(parameter);
    }

    private static RequestPostProcessor as(String user) {
        return httpBasic(user, TestCredentials.password(user));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
