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
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request bounds over a real socket (plan 4A decision 8), where MockMvc cannot reach: a chunked body declares no
 * length, so only the counting stream can stop it; and Tomcat's own header limit bounds Idempotency-Key. Boot's
 * FormContentFilter is off, so nothing reads a form body before security answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class RequestBoundsOverHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(form)))
                    .build();

            int expected = path.startsWith("/api/") ? 415 : 401;
            assertThat(send(request).statusCode()).as(path).isEqualTo(expected);
        }
    }

    @Test
    void anOversizedIdempotencyKeyNeverReachesTheService() throws Exception {
        HttpRequest request = holdRequest("k".repeat(9 * 1024))
                .POST(HttpRequest.BodyPublishers.ofByteArray(paddedHold(0)))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(400);
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();
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
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + eventId + "/holds"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + basic)
                .header("Idempotency-Key", idempotencyKey);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        // HTTP/1.1, so a body with no declared length goes out chunked.
        try (HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
