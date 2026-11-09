package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 5 task 9b: three JSON-RPC request shapes the SDK transport never answered, so a client hung. Each now gets a
 * prompt JSON-RPC error with no stack trace and no exception text. A short client timeout makes a hang a failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class McpUnanswerableRequestsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void aToolsCallWithNoNameIsAnsweredWithAJsonRpcError() throws Exception {
        assertAnswered("tools/call", "{}");
    }

    @Test
    void aToolsCallWithOnlyArgumentsIsAnsweredWithAJsonRpcError() throws Exception {
        assertAnswered("tools/call", "{\"arguments\":{}}");
    }

    @Test
    void aToolsCallWithANullNameIsAnsweredWithAJsonRpcError() throws Exception {
        assertAnswered("tools/call", "{\"name\":null}");
    }

    @Test
    void aToolsCallWithNullParamsIsAnsweredWithAJsonRpcError() throws Exception {
        assertAnswered("tools/call", "null");
    }

    @Test
    void aToolsCallWithArrayParamsIsAnsweredWithAJsonRpcError() throws Exception {
        assertAnswered("tools/call", "[]");
    }

    @Test
    void anUnknownMethodIsAnsweredWithAJsonRpcError() throws Exception {
        assertAnswered("no/such", "{}");
    }

    /** The filter's allow-list is the SDK's: every method it lets through completes with the SDK's own answer. */
    @Test
    void everyServedMethodStillGetsTheSdksOwnAnswer() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            String session = initialize(http);
            for (String method : McpRequestShapeFilter.SERVED_METHODS) {
                if (method.equals("initialize")) {
                    continue;
                }
                HttpResponse<String> response = post(
                        http, session, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":{}}");
                assertThat(response.body()).as(method).isNotBlank().doesNotContain("Method not found");
            }
        } finally {
            http.shutdownNow();
        }
    }

    private void assertAnswered(String method, String params) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            String session = initialize(http);
            HttpResponse<String> response = post(
                    http,
                    session,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}");
            String body = response.body();
            assertThat(body)
                    .doesNotContain("stack_trace", "stackTrace", "StackTrace", "Exception", "at io.", "at org.");
            JsonNode json = JSON.readTree(payload(body));
            assertThat(json.get("jsonrpc").asString()).as(body).isEqualTo("2.0");
            assertThat(json.get("id").asInt()).as(body).isEqualTo(7);
            assertThat(json.get("error").get("code").isInt()).as(body).isTrue();
            assertThat(json.get("error").get("message").isString()).as(body).isTrue();
        } finally {
            http.shutdownNow();
        }
    }

    private String initialize(HttpClient http) throws Exception {
        HttpResponse<String> init = post(
                http,
                null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}");
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(http, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return session;
    }

    private HttpResponse<String> post(HttpClient http, String session, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + TestCredentials.token("alice"))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (session != null) {
            builder.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18");
        }
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .get(5, TimeUnit.SECONDS);
    }

    /** The JSON of a plain body, or of the first SSE data line. */
    private static String payload(String body) {
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                return line.substring(5).trim();
            }
        }
        return body;
    }
}
