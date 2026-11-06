package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.markusluisflores.frontrow.service.EventReadService;
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
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 5 task 2: the three read tools over a real socket with the MCP client, the way an agent calls them (spec §6).
 * Plan 5 decisions 3, 4 and 5: the structured content is a JSON object in the REST shape, an argument failure is one
 * structured invalid_request naming the parameter, and nothing else is ever a plain string.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class EventToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOOL_CALL_LOGGER = ToolCall.class.getName();
    private static final String INTERNAL_HINT = "unexpected failure; retry, or tell the operator";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @MockitoSpyBean
    EventReadService reads;

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
        draftId = fixtures.namedEvent(venueId, "Secret Owls Draft", "DRAFT", Instant.parse("2026-11-11T19:00:00Z"));
        cancelledId = fixtures.namedEvent(
                venueId, "Cancelled Owls Draft", "CANCELLED", Instant.parse("2026-11-12T19:00:00Z"));
        seats = fixtures.eventSeats(eventId, venueId, 2);
        List<Long> balcony = fixtures.seats(venueId, "Balcony", 2);
        for (long seatId : balcony) {
            jdbc.sql("INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents) VALUES (:e, :s, :v, 7000)")
                    .param("e", eventId)
                    .param("s", seatId)
                    .param("v", venueId)
                    .update();
        }
    }

    // ---- the reads return what REST returns -------------------------------------------------------------------------

    @Test
    void searchEventsReturnsTheRestArrayUnderAnEventsMember() throws Exception {
        fixtures.namedEvent(venueId, "Later Owls", "ON_SALE", Instant.parse("2026-12-01T19:00:00Z"));

        try (Agent agent = new Agent()) {
            CallToolResult result = agent.call("search_events", Map.of("query", "owls"));

            JsonNode structured = structured(result);
            assertThat(structured.isObject()).isTrue();
            assertThat(names(structured)).containsExactly("events");
            assertThat(structured.get("events")).isEqualTo(rest("/api/events?query=owls"));
            assertThat(structured.get("events")).hasSize(2);
            assertThat(JSON.readTree(text(result))).isEqualTo(structured);
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void searchEventsKeepsRestsDatesLimitsAndDefaults() throws Exception {
        fixtures.namedEvent(venueId, "Later Owls", "ON_SALE", Instant.parse("2026-12-01T19:00:00Z"));
        try (Agent agent = new Agent()) {
            CallToolResult dated =
                    agent.call("search_events", Map.of("from_date", "2026-11-20", "to_date", "2026-12-01", "limit", 5));
            assertThat(structured(dated).get("events"))
                    .isEqualTo(rest("/api/events?from_date=2026-11-20&to_date=2026-12-01&limit=5"));
            assertThat(structured(dated).get("events")).hasSize(1);

            CallToolResult everything = agent.call("search_events", Map.of());
            assertThat(structured(everything).get("events")).isEqualTo(rest("/api/events"));
        }
    }

    @Test
    void getEventReturnsTheRestBody() throws Exception {
        try (Agent agent = new Agent()) {
            CallToolResult result = agent.call("get_event", Map.of("event_id", eventId));

            assertThat(structured(result)).isEqualTo(rest("/api/events/" + eventId));
            assertThat(JSON.readTree(text(result))).isEqualTo(structured(result));
            assertThat(names(structured(result))).contains("event_id", "venue_name", "starts_at", "sales_open_at");
        }
    }

    @Test
    void checkAvailabilityReturnsTheRestBodyWithAndWithoutASection() throws Exception {
        try (Agent agent = new Agent()) {
            CallToolResult all = agent.call("check_availability", Map.of("event_id", eventId));
            assertThat(structured(all)).isEqualTo(rest("/api/events/" + eventId + "/availability"));
            assertThat(structured(all).get("seats")).hasSize(4);

            CallToolResult balcony =
                    agent.call("check_availability", Map.of("event_id", eventId, "section", "Balcony"));
            assertThat(structured(balcony)).isEqualTo(rest("/api/events/" + eventId + "/availability?section=Balcony"));
            assertThat(structured(balcony).get("seats")).hasSize(2);
        }
    }

    @Test
    void anEventThatIsNotOnSaleIsNotFoundAndNotListed() throws Exception {
        try (Agent agent = new Agent()) {
            for (long id : List.of(draftId, cancelledId)) {
                for (String tool : List.of("get_event", "check_availability")) {
                    CallToolResult result = agent.call(tool, Map.of("event_id", id));
                    assertError(result, "not_found");
                    assertThat(structured(result).get("hint").asString())
                            .isEqualTo("call search_events to find a valid id");
                }
            }
            String listed = text(agent.call("search_events", Map.of("query", "owls")));
            assertThat(listed).contains("Night Owls Live").doesNotContain("Secret", "Cancelled");
        }
    }

    @Test
    void readsNeverShowAHoldGroupAnOwnerOrAnExpiry() throws Exception {
        UUID bobsGroup = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), bobsGroup, "bob", "ACTIVE", HoldFixtures.NOW.plusSeconds(60));

        try (Agent agent = new Agent()) {
            CallToolResult availability = agent.call("check_availability", Map.of("event_id", eventId));
            JsonNode heldSeat = null;
            for (JsonNode seat : structured(availability).get("seats")) {
                if (seat.get("event_seat_id").asLong() == seats.get(0)) {
                    heldSeat = seat;
                }
            }
            assertThat(heldSeat).isNotNull();
            assertThat(heldSeat.get("available").asBoolean()).isFalse();
            for (CallToolResult result : List.of(
                    availability,
                    agent.call("get_event", Map.of("event_id", eventId)),
                    agent.call("search_events", Map.of()))) {
                String everything = JSON.writeValueAsString(result);
                assertThat(everything)
                        .doesNotContain("hold_group_id", "holdGroupId", "expires_at", "owner", "bob")
                        .doesNotContain(bobsGroup.toString());
            }
        }
    }

    @Test
    void searchMatchesCaseInsensitivelyAndLiterally() throws Exception {
        fixtures.namedEvent(venueId, "100% Funk", "ON_SALE", Instant.parse("2026-12-02T19:00:00Z"));
        fixtures.namedEvent(venueId, "Snow_Ball", "ON_SALE", Instant.parse("2026-12-03T19:00:00Z"));
        fixtures.namedEvent(venueId, "SnowXBall", "ON_SALE", Instant.parse("2026-12-04T19:00:00Z"));

        try (Agent agent = new Agent()) {
            assertThat(eventNames(agent, "NIGHT owls")).containsExactly("Night Owls Live");
            assertThat(eventNames(agent, "100%")).containsExactly("100% Funk");
            assertThat(eventNames(agent, "%")).containsExactly("100% Funk");
            assertThat(eventNames(agent, "snow_")).containsExactly("Snow_Ball");
            assertThat(eventNames(agent, "_")).containsExactly("Snow_Ball");
        }
    }

    // ---- failures are structured and name the parameter ------------------------------------------------------------

    @Test
    void aBadLimitIsInvalidRequestNamingLimit() throws Exception {
        try (Agent agent = new Agent()) {
            for (int limit : List.of(0, 51, -1)) {
                assertInvalid(agent.call("search_events", Map.of("limit", limit)), "limit");
            }
            assertThat(structured(agent.call("search_events", Map.of("limit", 50)))
                            .get("events"))
                    .hasSize(1);
        }
    }

    @Test
    void anArgumentThatCannotBeTheDeclaredTypeIsOneStructuredInvalidRequest() throws Exception {
        try (Agent agent = new Agent()) {
            assertInvalid(agent.call("get_event", Map.of("event_id", "abc")), "event_id");
            assertInvalid(agent.call("check_availability", Map.of("event_id", "abc")), "event_id");
            assertInvalid(agent.call("search_events", Map.of("from_date", "2026-13-45")), "from_date");
            assertInvalid(agent.call("search_events", Map.of("to_date", "yesterday")), "to_date");
            assertInvalid(agent.call("search_events", Map.of("limit", "ten")), "limit");
        }
    }

    @Test
    void aMissingRequiredArgumentIsInvalidRequestNamingIt() throws Exception {
        try (Agent agent = new Agent()) {
            assertInvalid(agent.call("get_event", Map.of()), "event_id");
            assertInvalid(agent.call("check_availability", Map.of("section", "Floor")), "event_id");
            // JSON null is no value, as an absent member is.
            Map<String, Object> nulled = new LinkedHashMap<>();
            nulled.put("event_id", null);
            assertInvalid(agent.call("get_event", nulled), "event_id");
        }
    }

    @Test
    void aToolsCallWithNoArgumentsMemberIsAMissingArgument() throws Exception {
        try (RawAgent raw = new RawAgent()) {
            for (String params : List.of("{\"name\":\"get_event\"}", "{\"name\":\"get_event\",\"arguments\":null}")) {
                JsonNode response = raw.call(params);
                assertInvalid(result(response), "event_id");
            }
            // Optional arguments only: no members at all is an ordinary call.
            assertThat(structured(result(raw.call("{\"name\":\"search_events\"}")))
                            .get("events"))
                    .hasSize(1);
        }
    }

    @Test
    void aJsonNumberIsNotAStringAndAStringIsNotANumber() throws Exception {
        try (Agent agent = new Agent()) {
            assertInvalid(agent.call("search_events", Map.of("query", 42)), "query");
            assertInvalid(agent.call("search_events", Map.of("from_date", 42)), "from_date");
            assertInvalid(agent.call("search_events", Map.of("to_date", 20261201)), "to_date");
            assertInvalid(agent.call("check_availability", Map.of("event_id", eventId, "section", 7)), "section");
            assertInvalid(agent.call("get_event", Map.of("event_id", String.valueOf(eventId))), "event_id");
        }
    }

    @Test
    void onlyAnIntegerIsAnIntegerAndOnlyAStringIsAString() throws Exception {
        try (Agent agent = new Agent()) {
            for (Object notALong : List.of(true, 4.5, List.of(1), Map.of("id", 1), 1.0e30, "")) {
                assertInvalid(agent.call("get_event", Map.of("event_id", notALong)), "event_id");
            }
            for (Object notAString : List.of(true, List.of("a"), Map.of("a", "b"))) {
                assertInvalid(agent.call("search_events", Map.of("query", notAString)), "query");
            }
            assertInvalid(agent.call("search_events", Map.of("limit", 99999999999L)), "limit");
        }
    }

    @Test
    void theOrdinaryCallStillSucceedsAndUnknownArgumentsAreIgnored() throws Exception {
        try (Agent agent = new Agent()) {
            CallToolResult plain = agent.call("get_event", Map.of("event_id", eventId));
            assertThat(plain.isError()).isNotEqualTo(Boolean.TRUE);

            // An argument the tool does not declare, however named, changes nothing and is never read.
            CallToolResult withExtras = agent.call(
                    "get_event", Map.of("event_id", eventId, "owner", "bob", "buyer", "bob", "user", 7, "x", Map.of()));
            assertThat(structured(withExtras)).isEqualTo(structured(plain));
        }
    }

    @Test
    void theOwnerIsNeverANamedArgumentOfAnyTool() throws Exception {
        try (Agent agent = new Agent()) {
            for (Tool tool : agent.client.listTools().tools()) {
                assertThat(JSON.writeValueAsString(tool.inputSchema()))
                        .as(tool.name())
                        .doesNotContain("owner", "buyer", "user");
                assertThat(tool.outputSchema())
                        .as("%s outputSchema", tool.name())
                        .isNull();
            }
        }
    }

    // ---- unexpected failures, SDK failures, the served server ------------------------------------------------------

    @Test
    void anUnexpectedExceptionIsTheGenericInternalErrorAndIsLoggedServerSide() throws Exception {
        doThrow(new IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2"))
                .when(reads)
                .event(eventId);
        ListAppender<ILoggingEvent> appender = capture(TOOL_CALL_LOGGER);
        try (Agent agent = new Agent()) {
            CallToolResult result = agent.call("get_event", Map.of("event_id", eventId));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result))
                    .isEqualTo(JSON.readTree("{\"code\":\"internal_error\",\"hint\":\"" + INTERNAL_HINT + "\"}"));
            assertThat(JSON.writeValueAsString(result)).doesNotContain("secret-host", "hunter2", "IllegalState");
            assertThat(appender.list).anySatisfy(logged -> {
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getThrowableProxy()).isNotNull();
            });
        } finally {
            appender.stop();
        }
    }

    @Test
    void theServedServerRunsToolsOnTheRequestThreadWithoutSdkInputValidation() throws Exception {
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<Object> authentication = new AtomicReference<>();
        doAnswer(invocation -> {
                    thread.set(Thread.currentThread().getName());
                    authentication.set(SecurityContextHolder.getContext().getAuthentication());
                    return invocation.callRealMethod();
                })
                .when(reads)
                .event(eventId);

        try (Agent agent = new Agent()) {
            // immediateExecution(true): the handler runs on the servlet thread, not a scheduler's.
            agent.call("get_event", Map.of("event_id", eventId));
            assertThat(thread.get()).contains("exec").doesNotContain("boundedElastic");
            assertThat(authentication.get()).isNotNull();

            // validateToolInputs(false): the SDK's plain-text schema failure never answers; the adapter's body does.
            CallToolResult invalid = agent.call("get_event", Map.of("event_id", "abc"));
            assertInvalid(invalid, "event_id");
            assertThat(invalid.structuredContent()).isNotNull();
        }
    }

    @Test
    void everyReadToolAnswersAJsonObjectOnSuccessAndOnFailureWithSnakeCaseKeys() throws Exception {
        try (Agent agent = new Agent()) {
            List<CallToolResult> results = new ArrayList<>();
            results.add(agent.call("search_events", Map.of()));
            results.add(agent.call("search_events", Map.of("limit", 0)));
            results.add(agent.call("get_event", Map.of("event_id", eventId)));
            results.add(agent.call("get_event", Map.of("event_id", draftId)));
            results.add(agent.call("check_availability", Map.of("event_id", eventId)));
            results.add(agent.call("check_availability", Map.of()));

            for (CallToolResult result : results) {
                JsonNode structured = structured(result);
                assertThat(structured.isObject()).as(structured.toString()).isTrue();
                assertThat(JSON.readTree(text(result))).isEqualTo(structured);
                allKeys(structured).forEach(key -> assertThat(key).matches("[a-z][a-z0-9_]*"));
            }
        }
    }

    @Test
    void sdkLevelFailuresAreStructuredJsonRpcErrorsWithNoStackTrace() throws Exception {
        try (RawAgent raw = new RawAgent()) {
            for (String params : List.of("{\"name\":\"no_such_tool\",\"arguments\":{}}", "{\"name\":42}")) {
                String body = raw.callRaw(params);
                assertThat(body).as(params).doesNotContain("stack_trace", "stackTrace", "StackTrace", "at io.");
                assertThat(data(body).isObject()).as(body).isTrue();
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private List<String> eventNames(Agent agent, String query) throws Exception {
        List<String> names = new ArrayList<>();
        structured(agent.call("search_events", Map.of("query", query)))
                .get("events")
                .forEach(event -> names.add(event.get("name").asString()));
        return names;
    }

    private static void assertInvalid(CallToolResult result, String parameter) throws Exception {
        assertError(result, "invalid_request");
        JsonNode body = structured(result);
        assertThat(body.get("parameter").asString()).isEqualTo(parameter);
        assertThat(body.get("hint").asString()).isEqualTo("check the named parameter and try again");
        // Exactly one structured result: the text is the same JSON, not an SDK or Spring AI message.
        assertThat(JSON.readTree(text(result))).isEqualTo(body);
        assertThat(result.content()).hasSize(1);
    }

    private static void assertError(CallToolResult result, String code) throws Exception {
        assertThat(result.isError()).as("%s", result).isTrue();
        assertThat(result.structuredContent())
                .as("structured content of %s", result)
                .isNotNull();
        assertThat(structured(result).get("code").asString()).isEqualTo(code);
        assertThat(structured(result).has("hint")).isTrue();
        assertThat(JSON.writeValueAsString(result)).doesNotContain("stack_trace");
    }

    private static JsonNode structured(CallToolResult result) {
        return JSON.valueToTree(result.structuredContent());
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    private static Stream<String> allKeys(JsonNode node) {
        if (node.isObject()) {
            return node.properties().stream()
                    .flatMap(entry -> Stream.concat(Stream.of(entry.getKey()), allKeys(entry.getValue())));
        }
        if (node.isArray()) {
            List<JsonNode> children = new ArrayList<>();
            node.forEach(children::add);
            return children.stream().flatMap(EventToolsTest::allKeys);
        }
        return Stream.empty();
    }

    private static ListAppender<ILoggingEvent> capture(String type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(TOOL_CALL_LOGGER)).detachAndStopAllAppenders();
    }

    private JsonNode rest(String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            return JSON.readTree(response.body());
        }
    }

    /** The first data: line of a text/event-stream body, or the whole body when the answer is plain JSON. */
    private static JsonNode data(String body) throws Exception {
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                return JSON.readTree(line.substring("data:".length()).trim());
            }
        }
        return JSON.readTree(body);
    }

    private static CallToolResult result(JsonNode response) {
        assertThat(response.has("result")).as(response.toString()).isTrue();
        return JSON.treeToValue(response.get("result"), CallToolResult.class);
    }

    /** A real MCP client, authenticated as alice's agent token. */
    private final class Agent implements AutoCloseable {

        final McpSyncClient client;

        Agent() {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                            "http://localhost:" + port)
                    .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice")))
                    .build();
            client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .build();
            client.initialize();
        }

        CallToolResult call(String tool, Map<String, Object> arguments) {
            return client.callTool(
                    CallToolRequest.builder(tool).arguments(arguments).build());
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** Raw JSON-RPC over HTTP, for requests the typed client cannot build (no arguments member, an unknown tool). */
    private final class RawAgent implements AutoCloseable {

        private final HttpClient http = HttpClient.newHttpClient();
        private final String session;
        private int ids = 100;

        RawAgent() throws Exception {
            HttpResponse<String> init = send(
                    null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}");
            session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            send(session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        JsonNode call(String params) throws Exception {
            return data(callRaw(params));
        }

        String callRaw(String params) throws Exception {
            return post(
                    "{\"jsonrpc\":\"2.0\",\"id\":" + (ids++) + ",\"method\":\"tools/call\",\"params\":" + params + "}");
        }

        String post(String json) throws Exception {
            return send(session, json).body();
        }

        private HttpResponse<String> send(String sessionId, String json) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice"))
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId).header("MCP-Protocol-Version", "2025-06-18");
            }
            return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            // close() would wait for an SSE answer stream the server holds open; the test is done with it.
            http.shutdownNow();
        }
    }
}
