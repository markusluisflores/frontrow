package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 5 task 3: hold_seats and release_hold over a real socket with the MCP client, the way an agent calls them, and
 * the handover to the human over REST (spec §6, ADR-002). The owner is always the token's principal (ADR-004).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class HoldToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @MockitoSpyBean
    HoldService holds;

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
        seats = fixtures.eventSeats(eventId, venueId, 12);
    }

    // ---- hold_seats: success, replay, key reuse ---------------------------------------------------------------------

    @Test
    void holdSeatsReturnsTheHoldResultAndHoldsTheSeatsForTheTokensOwner() throws Exception {
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.call(
                    "hold_seats",
                    Map.of(
                            "event_id",
                            eventId,
                            "event_seat_ids",
                            List.of(seats.get(1), seats.get(0)),
                            "idempotency_key",
                            "k1"));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            JsonNode body = structured(result);
            assertThat(body.isObject()).isTrue();
            UUID group = UUID.fromString(body.get("hold_group_id").asString());
            assertThat(body.get("event_id").asLong()).isEqualTo(eventId);
            assertThat(body.get("seats").findValuesAsString("event_seat_id"))
                    .containsExactly(seats.get(0).toString(), seats.get(1).toString());
            assertThat(body.get("seats").get(0).get("price_cents").asLong()).isEqualTo(HoldFixtures.PRICE_CENTS);
            assertThat(body.get("total_cents").asLong()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
            assertThat(body.get("currency").asString()).isEqualTo("CAD");
            assertThat(body.get("expires_at").asString()).isEqualTo("2026-10-01T12:10:00Z");
            assertThat(JSON.readTree(text(result))).isEqualTo(body);
            // The owner is the token's principal, and nothing else.
            assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE", "ACTIVE");
            assertThat(fixtures.seatsInGroup(group)).containsExactly(seats.get(0), seats.get(1));
        }
    }

    @Test
    void aReplayWithTheSameKeyAndRequestReturnsTheOriginalResult() throws Exception {
        try (Agent agent = new Agent("alice")) {
            CallToolResult first = agent.hold("k1", seats.get(0), seats.get(1));
            CallToolResult replay = agent.hold("k1", seats.get(1), seats.get(0));

            assertThat(structured(replay)).isEqualTo(structured(first));
            assertThat(fixtures.groupsOwnedBy("alice")).isEqualTo(1);
        }
    }

    @Test
    void theSameKeyWithADifferentRequestIsIdempotencyKeyReused() throws Exception {
        try (Agent agent = new Agent("alice")) {
            agent.hold("k1", seats.get(0));

            CallToolResult reused = agent.hold("k1", seats.get(1));

            assertError(reused, "idempotency_key_reused");
            assertMatchesRest(reused, "alice", "k1", eventId, seats.get(1));
        }
    }

    // ---- hold_seats: every §6 failure comes back as REST's code -----------------------------------------------------

    @Test
    void seatTakenNamesTheSeatsAndMatchesRest() throws Exception {
        fixtures.hold(seats.get(1), "carol", "ACTIVE", HoldFixtures.NOW.plusSeconds(300));
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.hold("k1", seats.get(0), seats.get(1));

            assertError(result, "seat_taken");
            assertThat(structured(result).get("seat_ids")).hasSize(1);
            assertThat(structured(result).get("seat_ids").get(0).asLong()).isEqualTo(seats.get(1));
            assertMatchesRest(result, "alice", "k2", eventId, seats.get(0), seats.get(1));
            // All or nothing: the free seat was not kept.
            assertThat(fixtures.groupsOwnedBy("alice")).isZero();
        }
    }

    @Test
    void salesNotOpenAndSalesClosedMatchRest() throws Exception {
        long early = fixtures.event(
                venueId,
                "ON_SALE",
                HoldFixtures.NOW.plus(1, ChronoUnit.DAYS),
                HoldFixtures.NOW.plus(29, ChronoUnit.DAYS));
        long late = fixtures.event(
                venueId,
                "ON_SALE",
                HoldFixtures.NOW.minus(2, ChronoUnit.DAYS),
                HoldFixtures.NOW.minus(1, ChronoUnit.HOURS));
        long earlySeat = fixtures.eventSeats(early, venueId, 1).getFirst();
        long lateSeat = fixtures.eventSeats(late, venueId, 1).getFirst();
        try (Agent agent = new Agent("alice")) {
            CallToolResult notOpen = agent.call("hold_seats", holdArguments(early, "k1", earlySeat));
            CallToolResult closed = agent.call("hold_seats", holdArguments(late, "k2", lateSeat));

            assertError(notOpen, "sales_not_open");
            assertMatchesRest(notOpen, "alice", "k3", early, earlySeat);
            assertError(closed, "sales_closed");
            assertMatchesRest(closed, "alice", "k4", late, lateSeat);
        }
    }

    @Test
    void holdLimitExceededAndTooManySeatsMatchRest() throws Exception {
        try (Agent agent = new Agent("alice")) {
            for (int i = 0; i < 3; i++) {
                assertThat(agent.hold("g" + i, seats.get(i)).isError()).isNotEqualTo(Boolean.TRUE);
            }
            CallToolResult capped = agent.hold("g3", seats.get(3));
            assertError(capped, "hold_limit_exceeded");
            assertMatchesRest(capped, "alice", "g4", eventId, seats.get(3));

            CallToolResult many = agent.hold("g5", seats.subList(0, 9).toArray(Long[]::new));
            assertError(many, "too_many_seats");
            assertThat(structured(many).get("max_seats").asInt()).isEqualTo(8);
            assertMatchesRest(many, "alice", "g6", eventId, seats.subList(0, 9).toArray(Long[]::new));
        }
    }

    @Test
    void anEventThatIsNotThereOrNotOnSaleIsNotFound() throws Exception {
        long draft = fixtures.namedEvent(venueId, "Draft", "DRAFT", Instant.parse("2026-11-11T19:00:00Z"));
        long draftSeat = fixtures.eventSeats(draft, venueId, 1).getFirst();
        try (Agent agent = new Agent("alice")) {
            CallToolResult missing = agent.call("hold_seats", holdArguments(987654321L, "k1", seats.get(0)));
            CallToolResult unlisted = agent.call("hold_seats", holdArguments(draft, "k2", draftSeat));

            assertError(missing, "not_found");
            assertMatchesRest(missing, "alice", "k3", 987654321L, seats.get(0));
            assertError(unlisted, "not_found");
            assertMatchesRest(unlisted, "alice", "k4", draft, draftSeat);
        }
    }

    @Test
    void seatsThatAreNotTheEventsAreInvalidRequestAsInRest() throws Exception {
        long otherEvent = fixtures.onSaleEvent(venueId);
        long otherSeat = fixtures.eventSeats(otherEvent, venueId, 1).getFirst();
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.hold("k1", otherSeat);

            assertError(result, "invalid_request");
            assertMatchesRest(result, "alice", "k2", eventId, otherSeat);
        }
    }

    @Test
    void contentionRetryComesBackStructured() throws Exception {
        doThrow(DomainException.of(ErrorCode.CONTENTION_RETRY)).when(holds).hold(org.mockito.ArgumentMatchers.any());
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.hold("k1", seats.get(0));

            assertError(result, "contention_retry");
            assertThat(structured(result).get("hint").asString()).isNotBlank();
        }
    }

    // ---- hold_seats: arguments --------------------------------------------------------------------------------------

    @Test
    void anInvalidIdempotencyKeyNamesIdempotencyKeyNotTheHeader() throws Exception {
        try (Agent agent = new Agent("alice")) {
            for (String key : List.of("", "   ", "x".repeat(201))) {
                CallToolResult result = agent.hold(key, seats.get(0));
                assertInvalid(result, "idempotency_key");
                assertThat(JSON.writeValueAsString(result)).doesNotContain("Idempotency-Key");
            }
        }
    }

    @Test
    void anEmptySeatListIsInvalidRequestNamingIt() throws Exception {
        try (Agent agent = new Agent("alice")) {
            assertInvalid(
                    agent.call(
                            "hold_seats",
                            Map.of("event_id", eventId, "event_seat_ids", List.of(), "idempotency_key", "k")),
                    "event_seat_ids");
        }
    }

    @Test
    void anArgumentThatCannotBeTheDeclaredTypeIsOneStructuredInvalidRequest() throws Exception {
        try (Agent agent = new Agent("alice")) {
            // A scalar where a list is wanted, a non-integer element, a null element, a non-numeric event_id.
            for (Object notAList : List.of(seats.get(0), "5", true, Map.of("a", 1))) {
                assertInvalid(agent.call("hold_seats", argumentsWith("event_seat_ids", notAList)), "event_seat_ids");
            }
            for (Object badElement : List.of("x", 1.5, true, List.of(1), Map.of("a", 1), "7")) {
                List<Object> list = new ArrayList<>(List.of(seats.get(0)));
                list.add(badElement);
                assertInvalid(agent.call("hold_seats", argumentsWith("event_seat_ids", list)), "event_seat_ids");
            }
            List<Object> withNull = new ArrayList<>(List.of(seats.get(0)));
            withNull.add(null);
            assertInvalid(agent.call("hold_seats", argumentsWith("event_seat_ids", withNull)), "event_seat_ids");

            for (Object notALong : List.of("abc", "7", 4.5, true, List.of(1))) {
                assertInvalid(agent.call("hold_seats", argumentsWith("event_id", notALong)), "event_id");
            }
            assertInvalid(agent.call("hold_seats", argumentsWith("idempotency_key", 42)), "idempotency_key");
            // None of it held anything.
            assertThat(fixtures.groupsOwnedBy("alice")).isZero();
        }
    }

    @Test
    void aMissingArgumentIsInvalidRequestNamingIt() throws Exception {
        try (Agent agent = new Agent("alice")) {
            for (String name : List.of("event_id", "event_seat_ids", "idempotency_key")) {
                Map<String, Object> arguments = new LinkedHashMap<>(holdArguments(eventId, "k1", seats.get(0)));
                arguments.remove(name);
                assertInvalid(agent.call("hold_seats", arguments), name);
                arguments.put(name, null);
                assertInvalid(agent.call("hold_seats", arguments), name);
            }
        }
    }

    // ---- release_hold -----------------------------------------------------------------------------------------------

    @Test
    void releasingALiveHoldReleasesItAndFreesTheSeats() throws Exception {
        try (Agent agent = new Agent("alice")) {
            UUID group = groupOf(agent.hold("k1", seats.get(0), seats.get(1)));

            CallToolResult released = agent.release(group.toString());

            assertThat(released.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(structured(released))
                    .isEqualTo(JSON.readTree("{\"hold_group_id\":\"" + group + "\",\"released\":2,\"expired\":0}"));
            assertThat(JSON.readTree(text(released))).isEqualTo(structured(released));
            assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("RELEASED");
            assertThat(fixtures.claimsOn(seats.get(0))).isZero();
        }
    }

    @Test
    void aGroupWhoseHoldsLapsedIsMarkedExpiredByTheRelease() throws Exception {
        UUID group = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), group, "alice", "ACTIVE", HoldFixtures.NOW.minusSeconds(5));
        fixtures.holdInGroup(seats.get(1), group, "alice", "ACTIVE", HoldFixtures.NOW.minusSeconds(5));
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.release(group.toString());

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(structured(result).get("released").asInt()).isZero();
            assertThat(structured(result).get("expired").asInt()).isEqualTo(2);
            assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("EXPIRED");
        }
    }

    @Test
    void aGroupAlreadyReleasedAnswersZerosAndChangesNothing() throws Exception {
        UUID group = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), group, "alice", "RELEASED", HoldFixtures.NOW.plusSeconds(300));
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.release(group.toString());

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(structured(result).get("released").asInt()).isZero();
            assertThat(structured(result).get("expired").asInt()).isZero();
            assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED");
        }
    }

    @Test
    void aGroupAlreadySweptAnswersZerosAndChangesNothing() throws Exception {
        UUID group = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), group, "alice", "EXPIRED", HoldFixtures.NOW.minusSeconds(60));
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.release(group.toString());

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(structured(result).get("released").asInt()).isZero();
            assertThat(structured(result).get("expired").asInt()).isZero();
            assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("EXPIRED");
        }
    }

    @Test
    void anotherOwnersGroupAndAMissingGroupAreTheSameNotFound() throws Exception {
        UUID alices = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), alices, "alice", "ACTIVE", HoldFixtures.NOW.plusSeconds(300));
        try (Agent carol = new Agent("carol")) {
            CallToolResult foreign = carol.release(alices.toString());
            CallToolResult missing = carol.release(UUID.randomUUID().toString());

            assertError(foreign, "not_found");
            assertError(missing, "not_found");
            assertThat(structured(foreign)).isEqualTo(structured(missing));
            assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
        }
    }

    @Test
    void aMalformedHoldGroupIdIsOneInvalidRequestNamingIt() throws Exception {
        try (Agent agent = new Agent("alice")) {
            for (Object bad : List.of("not-a-uuid", "", 42, true)) {
                assertInvalid(agent.call("release_hold", Map.of("hold_group_id", bad)), "hold_group_id");
            }
            assertInvalid(agent.call("release_hold", Map.of()), "hold_group_id");
        }
    }

    // ---- the handover -----------------------------------------------------------------------------------------------

    @Test
    void aHoldTheAgentMadeIsTheHumansToConfirmOverRestAndNoOneElses() throws Exception {
        try (Agent agent = new Agent("alice");
                HttpClient http = HttpClient.newHttpClient()) {
            UUID group = groupOf(agent.hold("k1", seats.get(0), seats.get(1)));

            // The human, over Basic, sees the agent's hold.
            assertThat(groupIds(rest(http, "GET", "/api/me/holds", basic("alice"))))
                    .containsExactly(group.toString());
            // bob sees none of it and can neither release nor confirm it.
            assertThat(groupIds(rest(http, "GET", "/api/me/holds", basic("bob"))))
                    .isEmpty();
            assertThat(rest(http, "DELETE", "/api/holds/" + group, basic("bob")).statusCode())
                    .isEqualTo(404);
            assertThat(rest(http, "POST", "/api/holds/" + group + "/confirm", basic("bob"))
                            .statusCode())
                    .isEqualTo(404);
            // The agent's own token cannot reach the purchase endpoint: /api/** takes Basic only (ADR-002).
            assertThat(rest(http, "POST", "/api/holds/" + group + "/confirm", bearer("alice"))
                            .statusCode())
                    .isEqualTo(401);
            assertThat(fixtures.ordersForGroup(group)).isZero();

            // alice confirms it herself, and the order is hers.
            HttpResponse<String> confirmed = rest(http, "POST", "/api/holds/" + group + "/confirm", basic("alice"));
            assertThat(confirmed.statusCode()).isEqualTo(200);
            assertThat(groupIds(rest(http, "GET", "/api/me/orders", basic("alice"))))
                    .containsExactly(group.toString());
            assertThat(groupIds(rest(http, "GET", "/api/me/orders", basic("bob"))))
                    .isEmpty();
            assertThat(groupIds(rest(http, "GET", "/api/me/holds", basic("alice"))))
                    .isEmpty();

            // The agent, now late, learns the hold is no longer active.
            assertError(agent.release(group.toString()), "hold_not_active");
            assertThat(fixtures.ordersForGroup(group)).isEqualTo(1);
        }
    }

    // ---- the owner is the token, never the session ------------------------------------------------------------------

    @Test
    void tokenBUsingTokenAsSessionIdActsAsB() throws Exception {
        try (Raw alice = new Raw("alice")) {
            String aliceSession = alice.session;
            UUID alicesGroup = UUID.randomUUID();
            fixtures.holdInGroup(seats.get(5), alicesGroup, "alice", "ACTIVE", HoldFixtures.NOW.plusSeconds(300));

            // carol, with her own token, on alice's session id: she cannot release alice's group...
            JsonNode release = Raw.result(Raw.callWith(
                    port, "carol", aliceSession, "release_hold", "{\"hold_group_id\":\"" + alicesGroup + "\"}"));
            assertThat(release.get("isError").asBoolean()).isTrue();
            assertThat(release.get("structuredContent").get("code").asString()).isEqualTo("not_found");
            assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");

            // ...and what she holds there is carol's, not alice's.
            JsonNode held = Raw.result(Raw.callWith(
                    port,
                    "carol",
                    aliceSession,
                    "hold_seats",
                    "{\"event_id\":" + eventId + ",\"event_seat_ids\":[" + seats.get(0)
                            + "],\"idempotency_key\":\"k1\"}"));
            assertThat(held.get("structuredContent").has("hold_group_id")).isTrue();
            assertThat(fixtures.statusesOwnedBy("carol")).containsExactly("ACTIVE");
            assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
            assertThat(fixtures.groupsOwnedBy("alice")).isEqualTo(1);
        }
    }

    // ---- two tokens race for one seat -------------------------------------------------------------------------------

    @Test
    void twoTokensRacingForOneSeatOverHttpGiveOneWinnerAndOneSeatTaken() throws Exception {
        try (Agent alice = new Agent("alice");
                Agent carol = new Agent("carol");
                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<CallToolResult>> calls =
                    List.of(() -> alice.hold("race-a", seats.get(0)), () -> carol.hold("race-c", seats.get(0)));
            var latch = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<CallToolResult>> futures = new ArrayList<>();
            for (Callable<CallToolResult> call : calls) {
                futures.add(pool.submit(() -> {
                    latch.await();
                    return call.call();
                }));
            }
            latch.countDown();
            List<CallToolResult> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(r -> r.isError() != Boolean.TRUE).count())
                    .isEqualTo(1);
            List<CallToolResult> losers =
                    results.stream().filter(r -> r.isError() == Boolean.TRUE).toList();
            assertThat(losers).hasSize(1);
            assertError(losers.getFirst(), "seat_taken");
            assertThat(structured(losers.getFirst()).get("seat_ids").get(0).asLong())
                    .isEqualTo(seats.get(0));
            assertThat(fixtures.claimsOn(seats.get(0))).isEqualTo(1);
        }
    }

    // ---- structured content is always an object ---------------------------------------------------------------------

    @Test
    void bothWriteToolsAnswerAJsonObjectOnSuccessAndOnFailureWithSnakeCaseKeys() throws Exception {
        try (Agent agent = new Agent("alice")) {
            List<CallToolResult> results = new ArrayList<>();
            CallToolResult held = agent.hold("k1", seats.get(0));
            results.add(held);
            results.add(agent.hold("k1", seats.get(1)));
            results.add(agent.hold("k2", seats.get(0)));
            results.add(agent.release(groupOf(held).toString()));
            results.add(agent.release(UUID.randomUUID().toString()));
            results.add(agent.release("not-a-uuid"));

            for (CallToolResult result : results) {
                JsonNode structured = structured(result);
                assertThat(structured.isObject()).as(structured.toString()).isTrue();
                assertThat(JSON.readTree(text(result))).isEqualTo(structured);
                allKeys(structured).forEach(key -> assertThat(key).matches("[a-z][a-z0-9_]*"));
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private Map<String, Object> holdArguments(long event, String key, Long... seatIds) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("event_id", event);
        arguments.put("event_seat_ids", List.of(seatIds));
        arguments.put("idempotency_key", key);
        return arguments;
    }

    private Map<String, Object> argumentsWith(String name, Object value) {
        Map<String, Object> arguments = new LinkedHashMap<>(holdArguments(eventId, "k1", seats.get(0)));
        arguments.put(name, value);
        return arguments;
    }

    private static UUID groupOf(CallToolResult held) {
        return UUID.fromString(structured(held).get("hold_group_id").asString());
    }

    private static void assertInvalid(CallToolResult result, String parameter) throws Exception {
        assertError(result, "invalid_request");
        JsonNode body = structured(result);
        assertThat(body.get("parameter").asString()).isEqualTo(parameter);
        assertThat(body.get("hint").asString()).isEqualTo("check the named parameter and try again");
        assertThat(JSON.readTree(text(result))).isEqualTo(body);
        assertThat(result.content()).hasSize(1);
    }

    private static void assertError(CallToolResult result, String code) throws Exception {
        assertThat(result.isError()).as("%s", result).isTrue();
        assertThat(result.structuredContent()).as("%s", result).isNotNull();
        assertThat(structured(result).get("code").asString()).isEqualTo(code);
        assertThat(structured(result).has("hint")).isTrue();
        assertThat(JSON.writeValueAsString(result)).doesNotContain("stack_trace");
    }

    /** The same input to REST gives the same code and the same details (idempotency_key is REST's header name there). */
    private void assertMatchesRest(CallToolResult mcp, String user, String key, long event, Long... seatIds)
            throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + event + "/holds"))
                            .header("Authorization", basic(user))
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    JSON.writeValueAsString(Map.of("event_seat_ids", List.of(seatIds)))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode rest = JSON.readTree(response.body());
            JsonNode tool = structured(mcp);
            assertThat(tool.get("code")).isEqualTo(rest.get("code"));
            assertThat(tool.get("hint")).isEqualTo(rest.get("hint"));
            for (String member : List.of("seat_ids", "max_seats", "max_active_hold_groups", "opens_at", "closed_at")) {
                if (rest.has(member) || tool.has(member)) {
                    assertThat(tool.get(member)).as(member).isEqualTo(rest.get(member));
                }
            }
        }
    }

    private HttpResponse<String> rest(HttpClient http, String method, String path, String authorization)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", authorization)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> groupIds(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        JSON.readTree(response.body())
                .forEach(row -> ids.add(row.get("hold_group_id").asString()));
        return ids;
    }

    private static String basic(String user) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((user + ":" + TestCredentials.password(user)).getBytes(StandardCharsets.UTF_8));
    }

    private static String bearer(String user) {
        return "Bearer " + TestCredentials.token(user);
    }

    private static JsonNode structured(CallToolResult result) {
        return JSON.valueToTree(result.structuredContent());
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static Stream<String> allKeys(JsonNode node) {
        if (node.isObject()) {
            return node.properties().stream()
                    .flatMap(entry -> Stream.concat(Stream.of(entry.getKey()), allKeys(entry.getValue())));
        }
        if (node.isArray()) {
            List<JsonNode> children = new ArrayList<>();
            node.forEach(children::add);
            return children.stream().flatMap(HoldToolsTest::allKeys);
        }
        return Stream.empty();
    }

    /** A real MCP client, authenticated as one user's agent token. */
    private final class Agent implements AutoCloseable {

        final McpSyncClient client;

        Agent(String user) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                            "http://localhost:" + port)
                    .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                            builder.header(HttpHeaders.AUTHORIZATION, bearer(user)))
                    .build();
            client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(20))
                    .build();
            client.initialize();
        }

        CallToolResult call(String tool, Map<String, Object> arguments) {
            return client.callTool(
                    CallToolRequest.builder(tool).arguments(arguments).build());
        }

        CallToolResult hold(String key, Long... seatIds) {
            return call("hold_seats", holdArguments(eventId, key, seatIds));
        }

        CallToolResult release(String group) {
            return call("release_hold", Map.of("hold_group_id", group));
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** Raw JSON-RPC over HTTP, to put one token's request on another token's session. */
    private final class Raw implements AutoCloseable {

        private static int ids = 100;

        final String session;
        private final HttpClient http = HttpClient.newHttpClient();

        Raw(String user) throws Exception {
            HttpResponse<String> init = post(
                    http,
                    port,
                    user,
                    null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}");
            session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            post(http, port, user, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        static JsonNode callWith(int port, String user, String session, String tool, String arguments)
                throws Exception {
            HttpClient http = HttpClient.newHttpClient();
            try {
                String body = post(
                                http,
                                port,
                                user,
                                session,
                                "{\"jsonrpc\":\"2.0\",\"id\":" + (ids++) + ",\"method\":\"tools/call\",\"params\":"
                                        + "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}")
                        .body();
                for (String line : body.split("\\R")) {
                    if (line.startsWith("data:")) {
                        return JSON.readTree(line.substring("data:".length()).trim());
                    }
                }
                return JSON.readTree(body);
            } finally {
                http.shutdownNow();
            }
        }

        static JsonNode result(JsonNode response) {
            assertThat(response.has("result")).as(response.toString()).isTrue();
            return response.get("result");
        }

        private static HttpResponse<String> post(HttpClient http, int port, String user, String session, String json)
                throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (session != null) {
                builder.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18");
            }
            return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .get(15, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            http.shutdownNow();
        }
    }
}
