package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request bounds over a real socket (plan 4A decision 8), where MockMvc cannot reach: a chunked body declares no
 * length, so only the counting stream can stop it; a declared one is refused before security answers; and Tomcat's
 * own header limit bounds Idempotency-Key. Boot's FormContentFilter and Spring's multipart resolver are off, so nothing
 * reads a form or multipart body around the counting stream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class RequestBoundsOverHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long eventId;
    long seatId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seatId = fixtures.eventSeats(eventId, venueId, 1).getFirst();
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedAsItIsRead() throws Exception {
        byte[] body = paddedHold(RequestBodyLimitFilter.MAX_BODY_BYTES + 1);
        HttpRequest request = holdRequest("k1")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).get("title").asString()).isEqualTo("Content Too Large");
        assertThat(fixtures.groupsOwnedBy("alice")).isZero();
    }

    @Test
    void aChunkedBodyAtTheLimitIsServed() throws Exception {
        byte[] body = paddedHold(RequestBodyLimitFilter.MAX_BODY_BYTES);
        HttpRequest request = holdRequest("k1")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(201);
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefused() throws Exception {
        HttpRequest request = holdRequest("k1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(paddedHold(RequestBodyLimitFilter.MAX_BODY_BYTES + 1)))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(413);
    }

    @Test
    void anAnonymousChunkedFormBodyOverTheLimitIsRefusedNotA500() throws Exception {
        // Boot's FormContentFilter would read this PUT body before Spring Security, hit the bound and answer 500.
        byte[] form = ("a=" + "x".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES)).getBytes(StandardCharsets.UTF_8);
        for (String path : new String[] {"/api/events/1", "/nowhere"}) {
            HttpRequest request = request(path)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(form)))
                    .build();

            int expected = path.startsWith("/api/") ? 415 : 401;
            assertThat(send(request).statusCode()).as(path).isEqualTo(expected);
        }
    }

    @Test
    void anOversizedHeaderIsRefusedByTomcatBeforeTheApplication() throws Exception {
        HttpRequest request = holdRequest("k".repeat(9 * 1024))
                .POST(HttpRequest.BodyPublishers.ofByteArray(paddedHold(0)))
                .build();

        HttpResponse<String> response = send(request);

        // Tomcat's own refusal: a bare HTML error page, closed connection, nothing from the application's renderer.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(response.body()).doesNotContain("invalid_request");
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();
    }

    @Test
    void aKeyOverTheServiceLimitButUnderTomcatsIsRefusedByTheService() throws Exception {
        HttpRequest request = holdRequest("k".repeat(1000))
                .POST(HttpRequest.BodyPublishers.ofByteArray(paddedHold(0)))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("code").asString()).isEqualTo("invalid_request");
        assertThat(problem.get("parameter").asString()).isEqualTo("Idempotency-Key");
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedBeforeSecurityAnswers() throws Exception {
        // Anonymous, so security alone would answer 401: only the declared-length check can make it 413 unread.
        byte[] body = new byte[RequestBodyLimitFilter.MAX_BODY_BYTES + 1];
        for (String[] target :
                new String[][] {{"POST", "/api/holds/" + UUID.randomUUID() + "/confirm"}, {"PUT", "/nowhere"}}) {
            HttpResponse<String> response = send(request(target[1])
                    .method(target[0], HttpRequest.BodyPublishers.ofByteArray(body))
                    .build());

            assertThat(response.statusCode()).as(target[1]).isEqualTo(413);
            assertThat(JSON.readTree(response.body()).get("title").asString())
                    .as(target[1])
                    .isEqualTo("Content Too Large");
        }
    }

    @Test
    void anAnonymousChunkedMultipartBodyIsNeverParsed() throws Exception {
        // Spring's multipart resolver would have Tomcat parse this from its own buffer, past the counting stream, on
        // every anonymous path; over Boot's 1 MB part limit it answered "Maximum upload size exceeded". The resolver
        // is off, so nothing parses the body and no path answers with the multipart refusal.
        int size = 1536 * 1024;
        for (String[] target :
                new String[][] {{"POST", "/error"}, {"POST", "/actuator/health"}, {"GET", "/api/events"}}) {
            HttpResponse<String> response = send(multipart(target[0], target[1], size));

            assertThat(response.statusCode()).as(target[1]).isNotEqualTo(413);
            assertThat(response.body()).as(target[1]).doesNotContain("Maximum upload size exceeded");
        }
    }

    /** A chunked multipart/form-data body of exactly the given size: one file part of filler. */
    private HttpRequest multipart(String method, String path, int size) {
        String head = "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--b--\r\n";
        byte[] body = (head + "x".repeat(size - head.length() - tail.length()) + tail).getBytes(StandardCharsets.UTF_8);
        return request(path)
                .header("Content-Type", "multipart/form-data; boundary=b")
                .method(method, HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
    }

    /** A valid hold for the one seat, padded with whitespace to exactly the given size (or unpadded, for 0). */
    private byte[] paddedHold(int size) {
        String json = "{\"event_seat_ids\": [" + seatId + "]}";
        String padding = " ".repeat(Math.max(0, size - json.length()));
        return (json.substring(0, json.length() - 1) + padding + "}").getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder holdRequest(String idempotencyKey) {
        String basic = Base64.getEncoder()
                .encodeToString(("alice:" + TestCredentials.password("alice")).getBytes(StandardCharsets.UTF_8));
        return request("/api/events/" + eventId + "/holds")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + basic)
                .header("Idempotency-Key", idempotencyKey);
    }

    /** Every request carries a timeout, so a server that never answers fails the test instead of hanging the build. */
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(REQUEST_TIMEOUT);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        // HTTP/1.1, so a body with no declared length goes out chunked.
        try (HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
