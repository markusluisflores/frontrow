package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.markusluisflores.frontrow.security.Credentials;
import io.github.markusluisflores.frontrow.service.EventReadService;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Plan 5 task 4 (decision 8): one INFO line per tool call, from the adapter, on a fixed logger name, and nothing a
 * caller sent is in any log. The tests capture everything the run logs (the root logger), not just the tool logger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class ToolLoggingTest {

    private static final String TOOL_LOGGER = "frontrow.mcp.tools";
    private static final Pattern LINE = Pattern.compile(
            "tool=(?<tool>[a-z_]+) outcome=(?<outcome>[a-z_]+) duration_ms=(?<ms>\\d+) owner=(?<owner>[A-Za-z0-9_.-]+)");
    private static final String KEY = "marker-one-two";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @MockitoSpyBean
    EventReadService reads;

    @MockitoSpyBean
    HoldService holds;

    HoldFixtures fixtures;
    long venueId;
    long eventId;
    long draftId;
    List<Long> seats;
    ListAppender<ILoggingEvent> everything;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        draftId = fixtures.namedEvent(venueId, "Draft", "DRAFT", java.time.Instant.parse("2026-11-11T19:00:00Z"));
        seats = fixtures.eventSeats(eventId, venueId, 6);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        everything = new ListAppender<>();
        everything.start();
        root.addAppender(everything);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(everything);
        everything.stop();
    }

    // ---- one line per call, with the right fields -------------------------------------------------------------------

    @Test
    void eachToolLogsExactlyOneLineForASuccessAndOneForAFailure() throws Exception {
        try (Agent agent = new Agent("alice")) {
            expectOne(agent, "search_events", Map.of("query", "x"), "ok");
            expectOne(agent, "search_events", Map.of("limit", 0), "invalid_request");
            expectOne(agent, "get_event", Map.of("event_id", eventId), "ok");
            expectOne(agent, "get_event", Map.of("event_id", draftId), "not_found");
            expectOne(agent, "check_availability", Map.of("event_id", eventId), "ok");
            expectOne(agent, "check_availability", Map.of("event_id", draftId), "not_found");
            expectOne(agent, "hold_seats", holdArguments(KEY + "-1", seats.get(0)), "ok");
            Long[] tooMany = seats.toArray(Long[]::new);
            // Six seats are within the limit; the same key with a different request is a reuse.
            expectOne(agent, "hold_seats", holdArguments(KEY + "-1", tooMany), "idempotency_key_reused");
            UUID group = UUID.randomUUID();
            fixtures.holdInGroup(seats.get(5), group, "alice", "ACTIVE", HoldFixtures.NOW.plusSeconds(300));
            expectOne(agent, "release_hold", Map.of("hold_group_id", group.toString()), "ok");
            expectOne(
                    agent,
                    "release_hold",
                    Map.of("hold_group_id", UUID.randomUUID().toString()),
                    "not_found");
        }
    }

    @Test
    void theOwnerIsTheTokensPrincipal() throws Exception {
        try (Agent carol = new Agent("carol")) {
            carol.call("get_event", Map.of("event_id", eventId));
        }
        assertThat(parsed(toolLines()).getFirst().group("owner")).isEqualTo("carol");
    }

    @Test
    void anUnexpectedFailureLogsOutcomeInternalErrorAndOnePlainErrorLineWithTheClassOnly() throws Exception {
        doThrow(new IllegalStateException("jdbc://secret-host key=" + KEY + " seat=" + seats.get(0)))
                .when(reads)
                .event(eventId);
        try (Agent agent = new Agent("alice")) {
            CallToolResult result = agent.call("get_event", Map.of("event_id", eventId));
            assertThat(result.isError()).isTrue();
        }
        List<Matcher> lines = parsed(toolLines());
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().group("outcome")).isEqualTo("internal_error");
        assertThat(lines.getFirst().group("tool")).isEqualTo("get_event");

        List<ILoggingEvent> errors = everything.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("unexpected"))
                .toList();
        assertThat(errors).hasSize(1);
        ILoggingEvent error = errors.getFirst();
        assertThat(error.getLoggerName()).isNotEqualTo(TOOL_LOGGER);
        assertThat(error.getFormattedMessage()).contains("IllegalStateException");
        assertThat(error.getThrowableProxy()).isNull();
        assertThat(everythingText()).doesNotContain("secret-host", KEY);
    }

    @Test
    void anArgumentFailureLogsExactlyOneInvalidRequestLine() throws Exception {
        try (Agent agent = new Agent("alice")) {
            expectOne(agent, "get_event", Map.of(), "invalid_request");
            expectOne(agent, "get_event", Map.of("event_id", "abc"), "invalid_request");
            expectOne(agent, "check_availability", Map.of("section", "Floor"), "invalid_request");
            expectOne(agent, "search_events", Map.of("from_date", "2026-13-45"), "invalid_request");
            expectOne(agent, "release_hold", Map.of("hold_group_id", "not-a-uuid"), "invalid_request");
            expectOne(agent, "release_hold", Map.of(), "invalid_request");
            Map<String, Object> nonNumeric = holdArguments(KEY + "-2", seats.get(0));
            nonNumeric.put("event_seat_ids", new ArrayList<Object>(List.of(seats.get(0), "x")));
            expectOne(agent, "hold_seats", nonNumeric, "invalid_request");
            Map<String, Object> missing = holdArguments(KEY + "-3", seats.get(0));
            missing.remove("idempotency_key");
            expectOne(agent, "hold_seats", missing, "invalid_request");
        }
    }

    // ---- what is never logged ---------------------------------------------------------------------------------------

    @Test
    void noLogOutputHoldsATokenItsHashAnAuthorizationValueAKeyOrSeatIds() throws Exception {
        try (Agent agent = new Agent("alice")) {
            agent.call("hold_seats", holdArguments(KEY + "-4", seats.get(0), seats.get(1)));
            agent.call("hold_seats", holdArguments(KEY + "-4", seats.get(2)));
            agent.call("hold_seats", holdArguments(KEY + "-5", seats.get(0)));
            agent.call("get_event", Map.of("event_id", eventId));
            agent.call("release_hold", Map.of("hold_group_id", "not-a-uuid"));
        }
        doThrow(new IllegalStateException(KEY + " " + seats.get(3)))
                .when(holds)
                .hold(org.mockito.ArgumentMatchers.any());
        try (Agent agent = new Agent("alice")) {
            agent.call("hold_seats", holdArguments(KEY + "-6", seats.get(3)));
        }

        String output = everythingText();
        String token = TestCredentials.token("alice");
        assertThat(output)
                .doesNotContain(token)
                .doesNotContain(Credentials.sha256Hex(token))
                .doesNotContain("Bearer")
                .doesNotContain("Authorization")
                .doesNotContain(KEY)
                .doesNotContain("event_seat_ids")
                .doesNotContain("idempotency_key=");
        // Each tool line is exactly the four fields, so no seat id or other argument can ride along.
        List<ILoggingEvent> lines = toolLines();
        assertThat(lines).hasSize(6);
        assertThat(parsed(lines)).hasSize(6);
        assertThat(lines).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    void anUnknownTokenLogsNoToolLineAndNoTokenText() throws Exception {
        String bogus = "bogus-token-0123456789abcdef";
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bogus)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":"
                                    + "\"tools/call\",\"params\":{\"name\":\"get_event\",\"arguments\":{}}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }
        assertThat(toolLines()).isEmpty();
        assertThat(everythingText())
                .doesNotContain(bogus)
                .doesNotContain(Credentials.sha256Hex(bogus))
                .doesNotContain("Bearer");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void expectOne(Agent agent, String tool, Map<String, Object> arguments, String outcome) {
        everything.list.clear();
        agent.call(tool, arguments);
        List<ILoggingEvent> lines = toolLines();
        assertThat(lines).as("%s -> %s", tool, outcome).hasSize(1);
        assertThat(lines.getFirst().getLevel()).isEqualTo(Level.INFO);
        List<Matcher> parsed = parsed(lines);
        assertThat(parsed.getFirst().group("tool")).isEqualTo(tool);
        assertThat(parsed.getFirst().group("outcome")).isEqualTo(outcome);
        assertThat(parsed.getFirst().group("owner")).isEqualTo("alice");
        assertThat(Long.parseLong(parsed.getFirst().group("ms"))).isGreaterThanOrEqualTo(0);
    }

    private List<ILoggingEvent> toolLines() {
        List<ILoggingEvent> copy;
        synchronized (everything.list) {
            copy = new ArrayList<>(everything.list);
        }
        return copy.stream().filter(e -> TOOL_LOGGER.equals(e.getLoggerName())).toList();
    }

    private static List<Matcher> parsed(List<ILoggingEvent> lines) {
        List<Matcher> matchers = new ArrayList<>();
        for (ILoggingEvent line : lines) {
            Matcher matcher = LINE.matcher(line.getFormattedMessage());
            assertThat(matcher.matches()).as(line.getFormattedMessage()).isTrue();
            matchers.add(matcher);
        }
        return matchers;
    }

    private String everythingText() {
        StringBuilder text = new StringBuilder();
        List<ILoggingEvent> copy;
        synchronized (everything.list) {
            copy = new ArrayList<>(everything.list);
        }
        for (ILoggingEvent event : copy) {
            text.append(event.getLoggerName())
                    .append(' ')
                    .append(event.getFormattedMessage())
                    .append('\n');
            event.getMDCPropertyMap()
                    .forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
            IThrowableProxy proxy = event.getThrowableProxy();
            if (proxy != null) {
                text.append(ThrowableProxyUtil.asString(proxy)).append('\n');
            }
        }
        return text.toString();
    }

    private Map<String, Object> holdArguments(String key, Long... seatIds) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("event_id", eventId);
        arguments.put("event_seat_ids", List.of(seatIds));
        arguments.put("idempotency_key", key);
        return arguments;
    }

    /** A real MCP client, authenticated as one user's agent token. */
    private final class Agent implements AutoCloseable {

        final McpSyncClient client;

        Agent(String user) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                            "http://localhost:" + port)
                    .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token(user)))
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

        @Override
        public void close() {
            client.close();
        }
    }
}
