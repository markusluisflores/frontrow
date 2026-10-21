package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyCodeHasItsStatus() {
        Map<ErrorCode, HttpStatus> expected = new EnumMap<>(ErrorCode.class);
        expected.put(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST);
        expected.put(ErrorCode.TOO_MANY_SEATS, HttpStatus.BAD_REQUEST);
        expected.put(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND);
        expected.put(ErrorCode.SEAT_TAKEN, HttpStatus.CONFLICT);
        expected.put(ErrorCode.HOLD_LIMIT_EXCEEDED, HttpStatus.CONFLICT);
        expected.put(ErrorCode.HOLD_EXPIRED, HttpStatus.CONFLICT);
        expected.put(ErrorCode.HOLD_NOT_ACTIVE, HttpStatus.CONFLICT);
        expected.put(ErrorCode.SALES_NOT_OPEN, HttpStatus.CONFLICT);
        expected.put(ErrorCode.SALES_CLOSED, HttpStatus.CONFLICT);
        expected.put(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_CONTENT);
        expected.put(ErrorCode.CONTENTION_RETRY, HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(expected).containsOnlyKeys(ErrorCode.values());
        expected.forEach((code, status) ->
                assertThat(Problems.status(code)).as(code.code()).isEqualTo(status));
    }

    @Test
    void extensionMembersAreInKeyOrderWhateverOrderDetailsIteratesIn() {
        DomainException failure = new DomainException(
                ErrorCode.INVALID_REQUEST, Map.of("seat_ids", List.of(3L, 1L), "parameter", "event_seat_ids"));

        ProblemDetail problem = Problems.of(failure);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).isNotNull();
        assertThat(problem.getProperties().keySet()).containsExactly("code", "hint", "parameter", "seat_ids");
        assertThat(problem.getProperties())
                .containsEntry("code", "invalid_request")
                .containsEntry("hint", ErrorCode.INVALID_REQUEST.hint());
    }

    @Test
    void theIdempotencyKeyIsNamedAsRestCarriesIt() {
        ProblemDetail problem = Problems.invalidParameter("idempotency_key");

        assertThat(problem.getProperties()).containsEntry("parameter", "Idempotency-Key");
        assertThat(Problems.invalidParameter("event_seat_ids").getProperties())
                .containsEntry("parameter", "event_seat_ids");
    }

    @Test
    void aPlainProblemHasNoCodeAndNoDetail() {
        ProblemDetail problem = Problems.plain(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(problem.getProperties()).isNull();
        assertThat(problem.getDetail()).isNull();
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
    }

    @Test
    void aFilterWritesTheSameShapeAsTheConverter() throws Exception {
        DomainException failure = new DomainException(
                ErrorCode.SALES_NOT_OPEN, Map.of("sales_open_at", Instant.parse("2030-01-01T00:00:00Z")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        Problems.write(new MockHttpServletRequest("POST", "/api/holds/x/confirm"), response, Problems.of(failure));

        ProblemDetail rendered = Problems.of(failure);
        rendered.setInstance(URI.create("/api/holds/x/confirm"));
        MockHttpOutputMessage converted = new MockHttpOutputMessage();
        new JacksonJsonHttpMessageConverter(JsonMapper.builder())
                .write(rendered, MediaType.APPLICATION_PROBLEM_JSON, converted);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body).isEqualTo(JSON.readTree(converted.getBodyAsString()));
        assertThat(body).isEqualTo(JSON.readTree("""
                        {"title": "Conflict", "status": 409, "instance": "/api/holds/x/confirm",
                         "code": "sales_not_open", "hint": "sales have not opened for this event yet",
                         "sales_open_at": "2030-01-01T00:00:00Z"}
                        """));
    }
}
