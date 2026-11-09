package io.github.markusluisflores.frontrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The README claims that mirror a list or a file (plan 5, decision 14; spec §7, §12). It asserts presence and
 * resolvable links, not truth: the headline numbers come from scripts/readme-metrics.sh, and a stale number passes here.
 */
class ReadmeTest {

    private static final Path SPEC = Path.of("docs/superpowers/specs/2026-09-17-frontrow-design.md");
    private static final Set<String> RECORDING_EXTENSIONS = Set.of(".gif", ".mp4", ".webm", ".mov", ".mkv");

    @Test
    void theArchitectureSectionHasAMermaidDiagram() throws IOException {
        assertThat(readme()).contains("```mermaid");
    }

    @Test
    void theNoConfirmOrderDecisionIsStatedWithItsReason() throws IOException {
        String readme = flat(readme());

        assertThat(readme).contains("no `confirm_order` tool");
        assertThat(readme).contains("`POST /api/holds/{holdGroupId}/confirm`");
        assertThat(flat(readme)).contains("which accepts HTTP Basic only");
        assertThat(flat(readme)).contains("it cannot buy");
    }

    @Test
    void theDemoGradeSecurityCaveatAndTheTwoDesignDecisionsAreStated() throws IOException {
        String readme = flat(readme());

        assertThat(readme).contains("demo-grade");
        assertThat(readme).contains("release that human's holds");
        assertThat(readme).contains("replay their idempotency keys");
        assertThat(readme).contains("steer which hold the human confirms");
    }

    @Test
    void everyApprovedLimitationAppearsByItsLeadSentenceFromTheSpec() throws IOException {
        String readme = flat(readme());
        List<String> leads = approvedLimitationLeads();

        // Four approved on 2026-10-28; a fifth in the spec must be reproduced too, so the count is a floor.
        assertThat(leads).hasSizeGreaterThanOrEqualTo(4);
        for (String lead : leads) {
            assertThat(readme).as("lead sentence: %s", lead).contains(lead);
        }
    }

    @Test
    void theLocalOnlyAssumptionsAreStated() throws IOException {
        String readme = flat(readme());

        assertThat(readme).contains("Docker Engine 28.3.3");
        assertThat(readme).contains("DNS rebinding");
    }

    @Test
    void theFourHeadlineMetricsAreLabelled() throws IOException {
        String readme = readme();

        assertThat(readme).contains("Hold-race test");
        assertThat(readme).contains("Tests per layer");
        assertThat(readme).contains("Integration suite runtime");
        assertThat(readme).contains("Tools with checked-in schemas");
    }

    @Test
    void theKnownLimitationsAndPossibleNextStepsHaveSections() throws IOException {
        assertThat(readme()).contains("\n## Known limitations\n").contains("\n## Possible next steps\n");
    }

    @Test
    void theEvidenceFilesAreLinkedAndExist() throws IOException {
        String readme = readme();
        List<Path> schemas;
        try (Stream<Path> files = Files.list(Path.of("docs/mcp/tools"))) {
            schemas = files.filter(file -> file.toString().endsWith(".schema.json"))
                    .toList();
        }

        assertThat(schemas).hasSize(5);
        for (Path schema : schemas) {
            assertThat(readme)
                    .as("link to %s", schema.getFileName())
                    .contains("docs/mcp/tools/" + schema.getFileName());
        }
        assertThat(readme).contains("](logs/example_run.txt)");
        assertThat(readme).contains("](docs/demo/README.md)");
    }

    @Test
    void everyRelativeLinkResolvesToAFile() throws IOException {
        Matcher link = Pattern.compile("\\]\\(([^)\\s]+)\\)").matcher(readme());
        List<String> broken = new ArrayList<>();
        int checked = 0;
        while (link.find()) {
            String target = link.group(1);
            if (target.startsWith("http") || target.startsWith("#")) {
                continue;
            }
            checked++;
            String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
            if (!Files.exists(Path.of(path))) {
                broken.add(target);
            }
        }

        assertThat(checked).isGreaterThan(5);
        assertThat(broken).isEmpty();
    }

    @Test
    void aRecordingInTheDemoDirectoryIsLinked() throws IOException {
        List<Path> recordings;
        try (Stream<Path> files = Files.list(Path.of("docs/demo"))) {
            recordings = files.filter(file -> {
                        String name = file.getFileName().toString().toLowerCase();
                        return RECORDING_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .toList();
        }
        String readme = readme();

        // No recording yet (Task 8 is a human task): the README must not claim one. Once a file lands it must be
        // linked.
        if (recordings.isEmpty()) {
            assertThat(readme)
                    .doesNotContainIgnoringCase("recording is at")
                    .doesNotContainIgnoringCase("watch the demo");
        }
        for (Path recording : recordings) {
            assertThat(readme)
                    .as("link to %s", recording.getFileName())
                    .contains("docs/demo/" + recording.getFileName());
        }
    }

    /** The bold lead sentence of each entry under "Approved known limitations" in spec §9, whitespace collapsed. */
    private static List<String> approvedLimitationLeads() throws IOException {
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);
        int start = spec.indexOf("**Approved known limitations**");
        int end = spec.indexOf("**(c) Hardening is gated", start);
        assertThat(start).as("limitations section in the spec").isNotNegative();
        assertThat(end).as("end of the limitations section").isGreaterThan(start);
        Matcher entry = Pattern.compile("(?m)^\\d+\\. \\*\\*(.+?)\\*\\*", Pattern.DOTALL)
                .matcher(spec.substring(start, end));
        List<String> leads = new ArrayList<>();
        while (entry.find()) {
            leads.add(flat(entry.group(1)));
        }
        return leads;
    }

    private static String readme() throws IOException {
        // A Windows checkout with core.autocrlf turns the README's LF into CRLF; the heading asserts look for "\n".
        return Files.readString(Path.of("README.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
