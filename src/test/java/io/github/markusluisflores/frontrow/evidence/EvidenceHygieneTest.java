package io.github.markusluisflores.frontrow.evidence;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No credential in a committed evidence artifact (spec §7, §12; plan 5 decision 13). Scans the text files under logs/
 * and docs/demo/ (a missing docs/demo/ is fine until the recording lands) for literal secret values, and, when the
 * run's own values are supplied, for those exact values too. A video or GIF cannot be scanned: that is a human check.
 *
 * <p>The patterns match literal values only; a placeholder that references a shell variable is exempt, such as an
 * Authorization line whose value is {@code Bearer $FRONTROW_AGENT_TOKEN}.
 *
 * <ul>
 *   <li>AUTHORIZATION: an {@code Authorization} header or key with a value, Basic or Bearer or bare.
 *   <li>BEARER: {@code Bearer} followed by a token-shaped string (16 or more token characters).
 *   <li>BASIC: {@code Basic} followed by a base64-shaped string that holds a digit or a base64 symbol, so the English
 *       "Basic authentication" does not match.
 *   <li>PASSWORD: a name containing password, passwd, secret or token, then {@code =} or {@code :}, then a value.
 *   <li>CURL_USER: curl's short or long user option followed by a name, a colon and a value.
 * </ul>
 */
class EvidenceHygieneTest {

    static final String TOKEN_VARIABLE = ExampleRun.TOKEN_VARIABLE;
    static final String BOB_PASSWORD_VARIABLE = "FRONTROW_BOB_PASSWORD";

    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)\\bauthorization\\b[\"']?\\s*[:=]\\s*[\"']?(?:(?:bearer|basic)\\s+)?([^\\s\"']+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+([A-Za-z0-9_._~+/=$-]{16,})");
    private static final Pattern BASIC =
            Pattern.compile("(?i)\\bBasic\\s+((?=[A-Za-z0-9+/]*[0-9=+/])[A-Za-z0-9+/$]{8,}={0,2})");
    private static final Pattern PASSWORD =
            Pattern.compile("(?i)\\b\\w*(?:password|passwd|secret|token)\\w*[\"']?\\s*[:=]\\s*[\"']?([^\\s\"',;]+)");
    private static final Pattern CURL_USER = Pattern.compile("(?:\\s-u|\\s--user)\\s+[\"']?\\w+:([^\\s\"']+)");

    private static final List<String> NAMES = List.of("AUTHORIZATION", "BEARER", "BASIC", "PASSWORD", "CURL_USER");
    private static final List<Pattern> PATTERNS = List.of(AUTHORIZATION, BEARER, BASIC, PASSWORD, CURL_USER);

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("txt", "md", "log", "json", "yml", "yaml", "sh", "text", "csv", "html", "cast");

    /** What is wrong with {@code text}, one entry per hit, never quoting the matched value. */
    static List<String> violations(String where, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            for (int p = 0; p < PATTERNS.size(); p++) {
                Matcher matcher = PATTERNS.get(p).matcher(lines[i]);
                while (matcher.find()) {
                    if (!isPlaceholder(matcher.group(1))) {
                        found.add(where + ":" + (i + 1) + " " + NAMES.get(p));
                    }
                }
            }
        }
        return found;
    }

    /** What is wrong with {@code text} against the run's own values; those are never named in the message. */
    static List<String> exactValueViolations(String where, String text, List<String> secrets) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < secrets.size(); i++) {
            if (!secrets.get(i).isBlank() && text.contains(secrets.get(i))) {
                found.add(where + " contains the run's secret value #" + (i + 1));
            }
        }
        return found;
    }

    private static boolean isPlaceholder(String value) {
        return value.startsWith("$") || value.startsWith("<") || value.startsWith("{") || value.startsWith("%");
    }

    static List<Path> evidenceFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String directory : List.of("logs", "docs/demo")) {
            Path dir = root.resolve(directory);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> TEXT_EXTENSIONS.contains(extension(path)))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    // ---- the patterns fire, and only on literal values
    // ---------------------------------------------------------------

    @Test
    void everyPatternFiresOnAFakeCredential(@TempDir Path dir) throws IOException {
        String fake = "Zk3Vq9Lp0Xw7Rt2Yb5Nc8Hd1Gm4Js6A";
        record Seeded(String pattern, String line) {}
        List<Seeded> seeded = List.of(
                new Seeded("AUTHORIZATION", "Authorization: Bearer " + fake),
                new Seeded("AUTHORIZATION", "authorization: " + fake),
                new Seeded("AUTHORIZATION", "\"Authorization\": \"Basic Ym9iOnN1cGVyc2VjcmV0\""),
                new Seeded("BEARER", "the agent sent Bearer " + fake + " to /mcp"),
                new Seeded("BASIC", "header Basic Ym9iOnN1cGVyc2VjcmV0 here"),
                new Seeded("BEARER", "sent Bearer Zk3Vq9Lp_0Xw7Rt2Yb5Nc8Hd1Gm4Js6A to /mcp"),
                new Seeded("BASIC", "header basic Ym9iOnN1cGVyc2VjcmV0 here"),
                new Seeded("PASSWORD", "password=" + fake),
                new Seeded("PASSWORD", "FRONTROW_BOB_PASSWORD: " + fake),
                new Seeded("PASSWORD", "agent_token = '" + fake + "'"),
                new Seeded("CURL_USER", "curl " + "-u bob:" + fake + " http://127.0.0.1:8080/api/me/holds"));
        for (Seeded entry : seeded) {
            Path file = dir.resolve("fake.txt");
            Files.writeString(file, "a harmless line\n" + entry.line() + "\n", StandardCharsets.UTF_8);
            List<String> found = violations(file.getFileName().toString(), Files.readString(file));
            assertThat(found).as("a seeded %s credential", entry.pattern()).isNotEmpty();
            assertThat(found).anyMatch(hit -> hit.endsWith(entry.pattern()));
            assertThat(found).as("the message never quotes the value").noneMatch(hit -> hit.contains(fake));
            assertThat(found.getFirst()).startsWith("fake.txt:2 ");
        }
    }

    @Test
    void aRealLookingTokenInALogFileFailsTheScan(@TempDir Path dir) throws IOException {
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Files.writeString(
                logs.resolve("example_run.txt"), "step 1\nAuthorization: Bearer q8Jd02kZxV7mP1nR4tY6wB3cF9hL5sAe\n");

        List<String> found = new ArrayList<>();
        for (Path file : evidenceFiles(dir)) {
            found.addAll(violations(dir.relativize(file).toString(), Files.readString(file)));
        }

        assertThat(found).isNotEmpty();
    }

    @Test
    void aPlaceholderThatNamesAShellVariableIsNotACredential() {
        String runbook = """
                claude mcp add --transport http frontrow http://127.0.0.1:8080/mcp \\
                  --header "Authorization: Bearer $FRONTROW_AGENT_TOKEN"
                curl -H "Authorization: Bearer ${FRONTROW_AGENT_TOKEN}" http://127.0.0.1:8080/mcp
                export FRONTROW_BOB_PASSWORD=$BOB_PASSWORD
                curl -u bob:$FRONTROW_BOB_PASSWORD http://127.0.0.1:8080/api/me/holds
                Authorization: Bearer <token>
                Basic authentication is used on /api/** and bearer tokens on /mcp.
                """;

        assertThat(violations("runbook.md", runbook)).isEmpty();
    }

    @Test
    void aPlaceholderNextToARealValueStillFails() {
        assertThat(violations(
                        "mixed.md",
                        "Authorization: Bearer $VAR\nAuthorization: Bearer Zk3Vq9Lp0Xw7Rt2Yb5Nc8Hd1Gm4Js6A\n"))
                .hasSize(2)
                .allMatch(hit -> hit.startsWith("mixed.md:2 "));
    }

    @Test
    void theExactValueCheckFindsASecretAndNeverNamesIt() {
        List<String> found =
                exactValueViolations("log.txt", "xx abc123SECRETvalue yy", List.of("nope", "abc123SECRETvalue"));

        assertThat(found).containsExactly("log.txt contains the run's secret value #2");
    }

    // ---- the committed artifacts
    // --------------------------------------------------------------------------------------

    @Test
    void committedEvidenceCarriesNoCredentialShape() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : evidenceFiles(Path.of(""))) {
            found.addAll(violations(file.toString().replace('\\', '/'), Files.readString(file)));
        }

        assertThat(found).isEmpty();
    }

    /**
     * The run's own token and bob's password, from the environment (never committed). Skipped, visibly, when they are
     * not supplied: the completion step of plan 5 task 7 always supplies them.
     */
    @Test
    void committedEvidenceCarriesNeitherOfTheRunsSecretValues() throws IOException {
        String token = System.getenv(TOKEN_VARIABLE);
        String password = System.getenv(BOB_PASSWORD_VARIABLE);
        boolean supplied = token != null && !token.isBlank() && password != null && !password.isBlank();
        if (!supplied) {
            System.err.println("EvidenceHygieneTest: exact-value check SKIPPED, " + TOKEN_VARIABLE + " and "
                    + BOB_PASSWORD_VARIABLE + " are not both set");
        }
        Assumptions.assumeTrue(supplied, "exact-value check skipped: the run's secret values were not supplied");

        List<Path> files = evidenceFiles(Path.of(""));
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(exactValueViolations(
                    file.toString().replace('\\', '/'), Files.readString(file), List.of(token, password)));
        }

        System.err.println("EvidenceHygieneTest: exact-value check ON over " + files.size() + " file(s)");
        assertThat(found).isEmpty();
    }
}
