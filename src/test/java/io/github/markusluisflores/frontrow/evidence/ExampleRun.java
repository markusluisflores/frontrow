package io.github.markusluisflores.frontrow.evidence;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scripted agent behind logs/example_run.txt (spec §7, plan 5 decision 2): a real MCP client driving the real
 * server over HTTP. Scenario mode runs the failure-and-recovery story; tour mode calls each of the five tools once.
 *
 * <p>Usage: {@code ExampleRun <base-url> [scenario|tour]}. The agent's bearer token comes only from the environment
 * variable {@value #TOKEN_VARIABLE}, and the transcript never carries it: it prints tool names, arguments and results.
 * The conflicting hold in scenario mode is scripted: a {@link SeatTaker} takes one of the agent's seats as a second
 * user between check_availability and hold_seats. The conflict, the error and the recovery are the server's own.
 */
public final class ExampleRun {

    public static final String TOKEN_VARIABLE = "FRONTROW_AGENT_TOKEN";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The scripted second actor: takes one seat and says, in a line, what happened (never a credential). */
    @FunctionalInterface
    public interface SeatTaker {
        String take(long eventId, long seatId) throws Exception;
    }

    /** One tool call and what the server answered. */
    public record Step(String tool, Map<String, Object> arguments, CallToolResult result) {

        public boolean isError() {
            return Boolean.TRUE.equals(result.isError());
        }

        public JsonNode body() {
            return JSON.valueToTree(result.structuredContent());
        }
    }

    private final McpSyncClient client;
    private final PrintStream out;
    private final List<Step> steps = new ArrayList<>();
    private int stepNumber = 0;

    private ExampleRun(McpSyncClient client, PrintStream out) {
        this.client = client;
        this.out = out;
    }

    public static McpSyncClient connect(String baseUrl, String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }

    /** The §7 story: look, lose a seat to a scripted second buyer, hear seat_taken, look again, hold other seats. */
    public static List<Step> scenario(McpSyncClient client, SeatTaker taker, PrintStream out) throws Exception {
        ExampleRun run = new ExampleRun(client, out);
        run.header(
                "scenario: an agent loses a seat to another buyer and recovers",
                "The conflicting hold (the scripted step below) was scripted: scripts/take-seat.sh took one of the\n"
                        + "# agent's chosen seats as a second user (bob) between check_availability and hold_seats.\n"
                        + "# The conflict, the seat_taken error and the recovery are the server's real behaviour, unedited.");
        long eventId = run.firstEventId();
        List<Long> chosen = run.freeSeats(run.call("check_availability", args("event_id", eventId)), 2, List.of());
        long taken = chosen.getFirst();
        run.scripted(taker.take(eventId, taken));
        Step refused = run.call("hold_seats", holdArguments(eventId, chosen));
        if (!refused.isError()
                || !"seat_taken".equals(refused.body().path("code").asString())) {
            throw new IllegalStateException("the scenario expected seat_taken and did not get it");
        }
        List<Long> retry = run.freeSeats(run.call("check_availability", args("event_id", eventId)), 2, List.of(taken));
        Step recovered = run.call("hold_seats", holdArguments(eventId, retry));
        if (recovered.isError()) {
            throw new IllegalStateException("the retry did not succeed");
        }
        run.out.println();
        run.out.println("# recovered: the agent holds seats " + retry + " instead of " + chosen);
        return List.copyOf(run.steps);
    }

    /** Each of the five tools once, in the order an agent would use them. */
    public static List<Step> tour(McpSyncClient client, PrintStream out) {
        ExampleRun run = new ExampleRun(client, out);
        run.header("tour: each of the five tools called once", null);
        long eventId = run.firstEventId();
        run.call("get_event", args("event_id", eventId));
        List<Long> seat = run.freeSeats(run.call("check_availability", args("event_id", eventId)), 1, List.of());
        Step held = run.call("hold_seats", holdArguments(eventId, seat));
        run.call(
                "release_hold",
                args("hold_group_id", held.body().path("hold_group_id").asString()));
        return List.copyOf(run.steps);
    }

    private long firstEventId() {
        Step found = call("search_events", args("limit", 1));
        JsonNode events = found.body().path("events");
        if (found.isError() || !events.isArray() || events.isEmpty()) {
            throw new IllegalStateException("search_events found no event on sale");
        }
        return events.get(0).path("event_id").asLong();
    }

    /** The first {@code count} seats the availability result says are free, skipping {@code avoid}. */
    private List<Long> freeSeats(Step availability, int count, List<Long> avoid) {
        List<Long> free = new ArrayList<>();
        for (JsonNode seat : availability.body().path("seats")) {
            long id = seat.path("event_seat_id").asLong();
            if (seat.path("available").asBoolean() && !avoid.contains(id) && free.size() < count) {
                free.add(id);
            }
        }
        if (free.size() < count) {
            throw new IllegalStateException("fewer than " + count + " seats are free");
        }
        return free;
    }

    private Step call(String tool, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(
                CallToolRequest.builder(tool).arguments(arguments).build());
        Step step = new Step(tool, arguments, result);
        steps.add(step);
        out.println();
        out.println("step " + ++stepNumber + ": tool " + tool);
        out.println("  arguments: " + JSON.writeValueAsString(arguments));
        out.println("  outcome:   "
                + (step.isError() ? "ERROR " + step.body().path("code").asString() : "ok"));
        if (tool.equals("check_availability") && !step.isError()) {
            long free = 0;
            JsonNode seats = step.body().path("seats");
            for (JsonNode seat : seats) {
                free += seat.path("available").asBoolean() ? 1 : 0;
            }
            out.println("  summary:   " + free + " of " + seats.size() + " seats free (derived from the result below)");
        }
        out.println("  result:    " + JSON.writeValueAsString(step.body()));
        return step;
    }

    /** The scripted second buyer's step: numbered like the others, but not an agent call. */
    private void scripted(String what) {
        out.println();
        out.println("step " + ++stepNumber + ": SCRIPTED conflicting hold (not an agent call)");
        out.println("  " + what);
    }

    private void header(String title, String scriptedNote) {
        out.println("# FrontRow example run (" + title + ")");
        out.println("# Run at " + Instant.now() + " against the local demo app, over MCP Streamable HTTP.");
        out.println("# The agent is a scripted MCP client (src/test/.../evidence/ExampleRun.java); it is not an LLM.");
        if (scriptedNote != null) {
            out.println("# " + scriptedNote);
        }
        out.println("# No credential appears here: the token is read from the environment and never printed.");
    }

    private static Map<String, Object> args(String name, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(name, value);
        return map;
    }

    private static Map<String, Object> holdArguments(long eventId, List<Long> seats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event_id", eventId);
        map.put("event_seat_ids", seats);
        map.put("idempotency_key", "example-run-" + UUID.randomUUID());
        return map;
    }

    /** The bash that runs scripts/take-seat.sh: Git Bash on Windows, where a bare "bash" can be WSL's. */
    public static String bashCommand() {
        String configured = System.getenv("FRONTROW_BASH");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && Files.exists(gitBash)) {
            return gitBash.toString();
        }
        return "bash";
    }

    /** Takes a seat through scripts/take-seat.sh; the script reads bob's password from its own environment. */
    public static SeatTaker scriptTaker(String baseUrl) {
        return (eventId, seatId) -> {
            Process process = new ProcessBuilder(
                            bashCommand(),
                            "scripts/take-seat.sh",
                            baseUrl,
                            Long.toString(eventId),
                            Long.toString(seatId))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("scripts/take-seat.sh failed: " + output);
            }
            return "scripts/take-seat.sh " + baseUrl + " " + eventId + " " + seatId + " (as bob) -> " + output;
        };
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2) {
            System.err.println("usage: ExampleRun <base-url> [scenario|tour]");
            System.exit(2);
        }
        String token = System.getenv(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            System.err.println(TOKEN_VARIABLE + " is not set");
            System.exit(2);
        }
        String mode = arguments.length == 2 ? arguments[1] : "scenario";
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        // The MCP client's own INFO logging goes to System.out; send it to stderr so stdout is the transcript only.
        System.setOut(System.err);
        try (McpSyncClient client = connect(arguments[0], token)) {
            switch (mode) {
                case "scenario" -> scenario(client, scriptTaker(arguments[0]), out);
                case "tour" -> tour(client, out);
                default -> {
                    System.err.println("unknown mode: " + mode);
                    System.exit(2);
                }
            }
        }
    }
}
