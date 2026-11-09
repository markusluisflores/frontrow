package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec section 8, plan 5 decision 7: what {@code tools/list} serves (name, description, inputSchema) equals the files
 * under {@code docs/mcp/tools/}. A changed, added, removed or renamed tool is drift. {@code ./mvnw verify} runs this in
 * CI, so CI fails on drift with no workflow change.
 *
 * <p>Regenerate: {@code ./mvnw test -Dtest=ToolSchemasTest -Dfrontrow.schemas.write=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class ToolSchemasTest {

    private static final String WRITE_PROPERTY = "frontrow.schemas.write";
    private static final String REGENERATE = "./mvnw test -Dtest=ToolSchemasTest -D" + WRITE_PROPERTY + "=true";
    private static final String SUFFIX = ".schema.json";
    private static final Path DIR = Path.of("docs", "mcp", "tools");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Spec section 6 table: wire parameters, true when required (a trailing question mark there is optional). */
    private static final Map<String, Map<String, Boolean>> SPEC_TABLE = Map.of(
            "search_events", params("query", false, "from_date", false, "to_date", false, "limit", false),
            "get_event", params("event_id", true),
            "check_availability", params("event_id", true, "section", false),
            "hold_seats", params("event_id", true, "event_seat_ids", true, "idempotency_key", true),
            "release_hold", params("hold_group_id", true));

    @LocalServerPort
    int port;

    @Test
    void servedToolsMatchTheCheckedInSchemaFiles() throws IOException {
        Map<String, String> served = new TreeMap<>();
        for (Tool tool : served()) {
            served.put(tool.name(), render(entry(tool)));
        }

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(DIR);
            for (Map.Entry<String, String> e : served.entrySet()) {
                Files.writeString(file(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
            try (var existing = Files.list(DIR)) {
                for (Path p : existing.toList()) {
                    String fileName = p.getFileName().toString();
                    if (fileName.endsWith(SUFFIX) && !served.containsKey(stripSuffix(fileName))) {
                        Files.delete(p);
                    }
                }
            }
        }

        TreeSet<String> onDisk = new TreeSet<>();
        if (Files.isDirectory(DIR)) {
            try (var files = Files.list(DIR)) {
                files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(SUFFIX))
                        .forEach(n -> onDisk.add(stripSuffix(n)));
            }
        }
        String hint = " Tool schema drift: if the change is intended, regenerate with `" + REGENERATE
                + "` and commit docs/mcp/tools/.";
        assertThat(onDisk)
                .as("tools with a checked-in schema vs tools served." + hint)
                .isEqualTo(served.keySet());
        for (Map.Entry<String, String> e : served.entrySet()) {
            String checkedIn =
                    Files.readString(file(e.getKey()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(e.getValue())
                    .as("%s%s vs the served entry.%s", e.getKey(), SUFFIX, hint)
                    .isEqualTo(checkedIn);
        }
    }

    @Test
    void noServedEntryDeclaresAnOutputSchema() {
        for (Tool tool : served()) {
            assertThat(JSON.valueToTree(tool).has("outputSchema"))
                    .as("%s must not declare outputSchema (plan 5 decisions 4 and 7)", tool.name())
                    .isFalse();
        }
    }

    @Test
    void propertyNamesAndRequiredMatchSpecSixTable() {
        List<Tool> tools = served();
        assertThat(tools.stream().map(Tool::name)).containsExactlyInAnyOrderElementsOf(SPEC_TABLE.keySet());
        for (Tool tool : tools) {
            JsonNode schema = JSON.valueToTree(tool.inputSchema());
            Map<String, Boolean> expected = SPEC_TABLE.get(tool.name());
            assertThat(schema.path("properties").propertyNames())
                    .as("%s properties", tool.name())
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(n -> required.add(n.asString()));
            assertThat(required)
                    .as("%s required", tool.name())
                    .containsExactlyInAnyOrderElementsOf(expected.entrySet().stream()
                            .filter(Map.Entry::getValue)
                            .map(Map.Entry::getKey)
                            .toList());
        }
    }

    private static Map<String, Boolean> params(Object... nameAndRequired) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < nameAndRequired.length; i += 2) {
            m.put((String) nameAndRequired[i], (Boolean) nameAndRequired[i + 1]);
        }
        return m;
    }

    private static String stripSuffix(String fileName) {
        return fileName.substring(0, fileName.length() - SUFFIX.length());
    }

    private static Path file(String tool) {
        return DIR.resolve(tool + SUFFIX);
    }

    /** The part of a tool a client sees: name, description, inputSchema. */
    private static JsonNode entry(Tool tool) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("inputSchema", JSON.valueToTree(tool.inputSchema()));
        return node;
    }

    /** Sorted keys, two-space indent, LF line endings, trailing newline. */
    private static String render(JsonNode node) {
        return JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(sorted(node))
                        .replace("\r\n", "\n") + "\n";
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> byKey = new TreeMap<>();
            node.properties().forEach(e -> byKey.put(e.getKey(), e.getValue()));
            ObjectNode out = JSON.createObjectNode();
            byKey.forEach((k, v) -> out.set(k, sorted(v)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            node.forEach(n -> out.add(sorted(n)));
            return out;
        }
        return node;
    }

    private List<Tool> served() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        "http://localhost:" + port)
                .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice")))
                .build();
        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            return client.listTools().tools();
        }
    }
}
