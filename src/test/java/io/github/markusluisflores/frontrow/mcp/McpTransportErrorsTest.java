package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.rest.RequestBodyLimitFilter;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 5 task 1 (decisions 10 and 11): what /mcp answers when the transport itself fails, over a real socket. Every
 * case carries a valid agent token, so the failure comes from the transport and not from the chain. A body is never
 * an unstructured string, and never carries a stack trace or an exception's class name or message (spec §6, §12).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class McpTransportErrorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final int OVER_CAP = RequestBodyLimitFilter.MAX_BODY_BYTES + 1;

    @LocalServerPort
    int port;

    @Test
    void malformedJsonAnswersAStructuredBodyWithNoStackTrace() throws Exception {
        HttpResponse<String> response = send(post("application/json", "{not json".getBytes(StandardCharsets.UTF_8))
                .header("Authorization", "Bearer " + TestCredentials.token("alice")));

        assertStructuredAndClean(response);
    }

    @Test
    void aChunkedJsonBodyOverTheBoundAnswersAStructuredBodyWithNoStackTrace() throws Exception {
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"" + " ".repeat(OVER_CAP) + "}")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = send(
                chunked("application/json", body).header("Authorization", "Bearer " + TestCredentials.token("alice")));

        assertStructuredAndClean(response);
    }

    @Test
    void anOverCapFormPostAnswers413InTheFilterErrorShape() throws Exception {
        HttpResponse<String> response =
                send(chunked(FORM, form(OVER_CAP)).header("Authorization", "Bearer " + TestCredentials.token("alice")));

        assertStructuredAndClean(response);
        assertThat(response.statusCode()).isEqualTo(413);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body).isEqualTo(plain413());
        assertThat(body.has("code")).isFalse();
    }

    @Test
    void aDeclaredLengthOverTheBoundFormPostAnswers413() throws Exception {
        HttpResponse<String> response =
                send(post(FORM, form(OVER_CAP)).header("Authorization", "Bearer " + TestCredentials.token("alice")));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body())).isEqualTo(plain413());
    }

    @Test
    void anOverCapChunkedFormPostWithNoTokenAnswers401NotA413() throws Exception {
        HttpResponse<String> response = send(chunked(FORM, form(OVER_CAP)));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).get("title").asString()).isEqualTo("Unauthorized");
    }

    @Test
    void anOverCapChunkedFormPostWithAnUnknownTokenAnswers401NotA413() throws Exception {
        HttpResponse<String> response =
                send(chunked(FORM, form(OVER_CAP)).header("Authorization", "Bearer not-a-known-token"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).get("title").asString()).isEqualTo("Unauthorized");
    }

    /**
     * Decision 11: an under-cap form POST behaves as it did before the fix, whatever that is. Pinned so the 413
     * handling widens nothing. The body is not JSON-RPC, so the transport refuses it as a bad request.
     */
    @Test
    void anUnderCapFormPostIsRefusedByTheTransportAsABadRequest() throws Exception {
        HttpResponse<String> response =
                send(chunked(FORM, form(1024)).header("Authorization", "Bearer " + TestCredentials.token("alice")));

        assertStructuredAndClean(response);
        assertThat(response.statusCode()).isIn(400, 415);
    }

    /** Tomcat's own header handling, over a real socket: two Authorization lines are 401 whichever order. */
    @Test
    void repeatedAuthorizationLinesOverARealSocketAre401() throws Exception {
        String alice = "Bearer " + TestCredentials.token("alice");
        String carol = "Bearer " + TestCredentials.token("carol");
        for (String[] pair : new String[][] {{alice, carol}, {alice, alice}, {alice, "Basic YTpi"}}) {
            HttpResponse<String> response = send(post(
                            "application/json",
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8))
                    .header("Authorization", pair[0])
                    .header("Authorization", pair[1]));

            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    private static void assertStructuredAndClean(HttpResponse<String> response) {
        assertThat(response.body()).doesNotContain("stack_trace").doesNotContain("Exception");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.isObject()).as("a JSON object, not a string").isTrue();
    }

    private static JsonNode plain413() {
        return JSON.readTree("{\"title\":\"Content Too Large\",\"status\":413,\"instance\":\"/mcp\"}");
    }

    private static byte[] form(int size) {
        return ("a=" + "x".repeat(size - 2)).getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder base() {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/event-stream");
    }

    private HttpRequest.Builder post(String contentType, byte[] body) {
        return base().header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private HttpRequest.Builder chunked(String contentType, byte[] body) {
        return base().header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
