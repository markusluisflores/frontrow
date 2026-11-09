package io.github.markusluisflores.frontrow.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.evidence.ExampleRun.Step;
import io.github.markusluisflores.frontrow.service.HoldCommand;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import io.modelcontextprotocol.client.McpSyncClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Plan 5 task 7: the §7 failure-and-recovery scenario and the five-tool tour, in process against the Testcontainers app,
 * so the run is reproducible in CI. The scripted second buyer is a callback calling HoldService as bob; the live log
 * uses scripts/take-seat.sh for the same step, which a test of its own checks against a real response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class ExampleRunTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    HoldService holds;

    HoldFixtures fixtures;
    long eventId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 6);
    }

    private McpSyncClient agent() {
        return ExampleRun.connect("http://localhost:" + port, TestCredentials.token("alice"));
    }

    private static String transcript(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theScenarioLosesASeatHearsSeatTakenAndRecovers() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Long> takenByBob = new ArrayList<>();
        ExampleRun.SeatTaker bob = (event, seat) -> {
            holds.hold(new HoldCommand("bob", event, List.of(seat), "bob-" + UUID.randomUUID()));
            takenByBob.add(seat);
            return "took seat " + seat + " as bob";
        };

        List<Step> steps;
        try (McpSyncClient client = agent();
                PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            steps = ExampleRun.scenario(client, bob, out);
        }

        assertThat(steps)
                .extracting(Step::tool)
                .containsExactly(
                        "search_events", "check_availability", "hold_seats", "check_availability", "hold_seats");
        assertThat(takenByBob).hasSize(1);
        long lost = takenByBob.getFirst();

        // The first availability check lists the seat as free; the first hold is refused and names exactly that seat.
        assertThat(availability(steps.get(1)).get(lost)).isTrue();
        Step refused = steps.get(2);
        assertThat(refused.isError()).isTrue();
        assertThat(refused.body().get("code").asString()).isEqualTo("seat_taken");
        assertThat(refused.body().get("seat_ids")).hasSize(1);
        assertThat(refused.body().get("seat_ids").get(0).asLong()).isEqualTo(lost);

        // The second check no longer lists it as free, and the retry holds other seats and succeeds.
        assertThat(availability(steps.get(3)).get(lost)).isFalse();
        Step recovered = steps.get(4);
        assertThat(recovered.isError()).isFalse();
        List<Long> heldSeats = new ArrayList<>();
        recovered
                .body()
                .get("seats")
                .forEach(seat -> heldSeats.add(seat.get("event_seat_id").asLong()));
        assertThat(heldSeats).hasSize(2).doesNotContain(lost);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE", "ACTIVE");

        // The header says the conflict was scripted, and the transcript is clean.
        String text = transcript(bytes);
        assertThat(text)
                .contains("The conflicting hold")
                .contains("was scripted")
                .contains("seat_taken");
        assertClean(text);
    }

    @Test
    void theTourCallsEachOfTheFiveToolsOnceAndEachSucceeds() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        List<Step> steps;
        try (McpSyncClient client = agent();
                PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            steps = ExampleRun.tour(client, out);
        }

        assertThat(steps)
                .extracting(Step::tool)
                .containsExactly("search_events", "get_event", "check_availability", "hold_seats", "release_hold");
        assertThat(steps).noneMatch(Step::isError);
        assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("RELEASED");
        assertClean(transcript(bytes));
    }

    /** The transcript carries no credential shape and neither of this run's secret values. */
    private static void assertClean(String text) {
        assertThat(EvidenceHygieneTest.violations("transcript", text)).isEmpty();
        assertThat(EvidenceHygieneTest.exactValueViolations(
                        "transcript", text, List.of(TestCredentials.token("alice"), TestCredentials.password("bob"))))
                .isEmpty();
        assertThat(text).doesNotContain("Authorization");
    }

    private static Map<Long, Boolean> availability(Step step) {
        Map<Long, Boolean> free = new java.util.LinkedHashMap<>();
        for (JsonNode seat : step.body().get("seats")) {
            free.put(seat.get("event_seat_id").asLong(), seat.get("available").asBoolean());
        }
        return free;
    }

    // ---- scripts/take-seat.sh against a real response
    // ------------------------------------------------------------------

    @Test
    void takeSeatHoldsAsBobPrintsStatusAndGroupIdOnlyAndReportsAConflict() throws Exception {
        String bash = ExampleRun.bashCommand();
        Assumptions.assumeTrue(runs(bash, "--version"), "bash is not available");
        Assumptions.assumeTrue(runs("curl", "--version"), "curl is not available");
        String base = "http://127.0.0.1:" + port;
        String password = TestCredentials.password("bob");

        ScriptRun first = takeSeat(bash, base, seats.get(0), password);
        ScriptRun second = takeSeat(bash, base, seats.get(0), password);

        assertThat(first.exit()).isZero();
        assertThat(first.output()).matches("HTTP 201 hold_group_id=[0-9a-f-]{36}");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("ACTIVE");
        assertThat(second.exit()).isEqualTo(1);
        assertThat(second.output()).isEqualTo("HTTP 409 hold_group_id=-");
        assertThat(first.output() + second.output()).doesNotContain(password);
    }

    @Test
    void takeSeatRefusesToRunWithoutThePasswordInTheEnvironment() throws Exception {
        String bash = ExampleRun.bashCommand();
        Assumptions.assumeTrue(runs(bash, "--version"), "bash is not available");

        ScriptRun run = takeSeat(bash, "http://127.0.0.1:" + port, seats.get(0), null);

        assertThat(run.exit()).isNotZero();
        assertThat(fixtures.statusesOwnedBy("bob")).isEmpty();
    }

    private record ScriptRun(int exit, String output) {}

    private ScriptRun takeSeat(String bash, String base, long seat, String password) throws Exception {
        return run(
                new ProcessBuilder(bash, "scripts/take-seat.sh", base, Long.toString(eventId), Long.toString(seat)),
                password);
    }

    private static ScriptRun run(ProcessBuilder builder, String password) throws Exception {
        builder.environment().remove("FRONTROW_BOB_PASSWORD");
        if (password != null) {
            builder.environment().put("FRONTROW_BOB_PASSWORD", password);
        }
        Process process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new ScriptRun(process.waitFor(), output);
    }

    private static boolean runs(String... command) {
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
