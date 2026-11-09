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

    private static final java.util.Map<String, String> VALID_PARAMS = java.util.Map.of(
            "ping", "{}",
            "tools/list", "{}",
            "tools/call", "{\"name\":\"search_events\",\"arguments\":{}}",
            "resources/list", "{}",
            "resources/templates/list", "{}",
            "resources/read", "{\"uri\":\"x://y\"}",
            "prompts/list", "{}",
            "prompts/get", "{\"name\":\"x\"}",
            "completion/complete",
                    "{\"ref\":{\"type\":\"ref/prompt\",\"name\":\"x\"},\"argument\":{\"name\":\"a\",\"value\":\"b\"}}",
            "logging/setLevel", "{\"level\":\"info\"}");

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

    @Test
    void aToolsCallWithAStringParamsIsInvalidParams() throws Exception {
        assertThat(assertAnswered("tools/call", "\"x\"")
                        .get("error")
                        .get("code")
                        .asInt())
                .isEqualTo(-32602);
    }

    @Test
    void aToolsCallWithParamsAbsentIsAnswered() throws Exception {
        assertAnswered("tools/call", null);
    }

    @Test
    void anUnauthenticatedUnknownMethodIs401NotAMethodNotFound() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response =
                    send(http, null, null, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"no/such\",\"params\":{}}");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain("Method not found");
        } finally {
            http.shutdownNow();
        }
    }

    @Test
    void anOverCapChunkedJsonPostIs413() throws Exception {
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"" + " ".repeat(16 * 1024 + 1) + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response = http.sendAsync(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json, text/event-stream")
                                    .header("Authorization", "Bearer " + TestCredentials.token("alice"))
                                    .POST(HttpRequest.BodyPublishers.ofInputStream(
                                            () -> new java.io.ByteArrayInputStream(body)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(413);
        } finally {
            http.shutdownNow();
        }
    }

    /** A notification has no id, so it is no request: the filter hands it on and writes no error answer itself. */
    @Test
    void aNotificationWithAnUnknownMethodGetsNoErrorFromTheFilter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            String session = initialize(http);
            HttpResponse<String> response =
                    post(http, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/no/such\"}");
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.body()).isEmpty();
        } finally {
            http.shutdownNow();
        }
    }

    /**
     * Catches a method the SDK STOPS serving (the filter would still let it through and it would answer Method not found
     * or hang), not one it starts serving: the allow-list needs a manual look on an SDK upgrade. Each call carries valid
     * params, so a bare Problem Details 500 means the SDK failed to bind them.
     */
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
                        http,
                        session,
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":"
                                + VALID_PARAMS.get(method) + "}");
                assertThat(response.body())
                        .as(method)
                        .isNotBlank()
                        .doesNotContain("Method not found", "\"status\":500");
            }
        } finally {
            http.shutdownNow();
        }
    }

    private JsonNode assertAnswered(String method, String params) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            String session = initialize(http);
            HttpResponse<String> response = post(
                    http,
                    session,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\""
                            + (params == null ? "" : ",\"params\":" + params) + "}");
            String body = response.body();
            assertThat(body)
                    .doesNotContain("stack_trace", "stackTrace", "StackTrace", "Exception", "at io.", "at org.");
            JsonNode json = JSON.readTree(payload(body));
            assertThat(json.get("jsonrpc").asString()).as(body).isEqualTo("2.0");
            assertThat(json.get("id").asInt()).as(body).isEqualTo(7);
            assertThat(json.get("error").get("code").isInt()).as(body).isTrue();
            assertThat(json.get("error").get("message").isString()).as(body).isTrue();
            return json;
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
        return send(http, session, TestCredentials.token("alice"), json);
    }

    private HttpResponse<String> send(HttpClient http, String session, String token, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
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
