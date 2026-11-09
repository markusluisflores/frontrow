package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agents hold; humans buy (ADR-002). The served tool list is exactly the five tools of spec §6, none of them completes
 * a purchase, and none takes an identity as a parameter. A sixth tool fails this test until the test is touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class NoPurchaseToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PURCHASE_WORDS =
            Pattern.compile("(?i)confirm|buy|purchas|\bpay|checkout|order|sell|\bsold\b|charge");
    private static final List<String> IDENTITY_PROPERTIES = List.of("owner", "user", "buyer", "username");

    @LocalServerPort
    int port;

    @Test
    void theServedToolListIsExactlyTheFiveToolsAndNoneCompletesAPurchase() {
        List<Tool> tools = served();

        assertThat(tools)
                .extracting(Tool::name)
                .containsExactlyInAnyOrder(
                        "search_events", "get_event", "check_availability", "hold_seats", "release_hold");
        for (Tool tool : tools) {
            assertThat(tool.name()).as("name").doesNotContainPattern(PURCHASE_WORDS);
            assertThat(tool.description()).as("%s description", tool.name()).doesNotContainPattern(PURCHASE_WORDS);
            assertThat(tool.title())
                    .as("%s title", tool.name())
                    .satisfiesAnyOf(
                            title -> assertThat(title).isNull(),
                            title -> assertThat(title).doesNotContainPattern(PURCHASE_WORDS));
        }
    }

    @Test
    void noToolSchemaHasAPropertyThatCarriesIdentity() throws Exception {
        for (Tool tool : served()) {
            JsonNode properties = JSON.valueToTree(tool.inputSchema()).path("properties");
            properties
                    .propertyNames()
                    .forEach(name -> assertThat(IDENTITY_PROPERTIES)
                            .as("%s property %s", tool.name(), name)
                            .doesNotContain(name.toLowerCase()));
        }
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
