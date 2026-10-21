package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The handler and the body limit, with no application context: a test controller raises each kind of failure.
 * Responses are compared as parsed JSON trees, never as strings (PR #18 thread 4296749419).
 */
class RestExceptionHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new RestExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(
                    JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
            .addFilters(new RequestBodyLimitFilter())
            .build();

    @RestController
    static class FailingController {

        record Body(EventStatus status, List<Long> eventSeatIds) {}

        @GetMapping("/t/domain")
        void domain() {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST, Map.of("parameter", "idempotency_key", "seat_ids", List.of(7L)));
        }

        @GetMapping("/t/contention")
        void contention() {
            throw DomainException.of(ErrorCode.CONTENTION_RETRY);
        }

        @GetMapping("/t/bug")
        void bug() {
            throw new IllegalStateException("hold 42 belongs to bob");
        }

        @GetMapping("/t/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint uq_claimed_seat");
        }

        @PostMapping("/t/body")
        Body body(@RequestBody Body body) {
            return body;
        }

        @GetMapping("/t/groups/{holdGroupId}")
        UUID group(@PathVariable UUID holdGroupId) {
            return holdGroupId;
        }

        @GetMapping("/t/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }
    }

    @Test
    void aDomainFailureCarriesItsCodeHintAndRestNamedDetails() throws Exception {
        MockHttpServletResponse response = perform(get("/t/domain"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(body(response)).isEqualTo(JSON.readTree("""
                        {"title": "Bad Request", "status": 400, "instance": "/t/domain",
                         "code": "invalid_request", "hint": "check the named parameter and try again",
                         "parameter": "Idempotency-Key", "seat_ids": [7]}
                        """));
    }

    @Test
    void contentionAsksForARetry() throws Exception {
        MockHttpServletResponse response = perform(get("/t/contention"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(body(response).get("code").asString()).isEqualTo("contention_retry");
    }

    @Test
    void anythingElseIsAGeneric500ThatEchoesNothing() throws Exception {
        for (String path : List.of("/t/bug", "/t/integrity")) {
            MockHttpServletResponse response = perform(get(path));

            assertThat(response.getStatus()).as(path).isEqualTo(500);
            assertThat(body(response)).as(path).isEqualTo(JSON.readTree("""
                            {"title": "Internal Server Error", "status": 500, "instance": "%s"}
                            """.formatted(path)));
        }
    }

    @Test
    void malformedJsonNamesTheBody() throws Exception {
        assertInvalid(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{"), "body");
    }

    @Test
    void aBadFieldIsNamedAsTheWireNamesIt() throws Exception {
        assertInvalid(
                post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"SOLD_OUT\"}"),
                "status");
        assertInvalid(
                post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{\"event_seat_ids\": [\"x\"]}"),
                "event_seat_ids");
    }

    @Test
    void aBadPathVariableIsNamedAsTheWireNamesIt() throws Exception {
        assertInvalid(get("/t/groups/not-a-uuid"), "hold_group_id");
    }

    @Test
    void aMissingIdempotencyKeyNamesTheHeader() throws Exception {
        assertInvalid(get("/t/header"), "Idempotency-Key");
    }

    @Test
    void aDeclaredBodyOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        MockHttpServletResponse response = perform(post("/t/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[RequestBodyLimitFilter.MAX_BODY_BYTES + 1]));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(body(response)).isEqualTo(JSON.readTree("""
                        {"title": "Content Too Large", "status": 413, "instance": "/t/body"}
                        """));
    }

    @Test
    void aBodyAtTheLimitIsRead() throws Exception {
        String padded = "{\"status\": \"ON_SALE\"" + " ".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES - 21) + "}";
        assertThat(padded).hasSize(RequestBodyLimitFilter.MAX_BODY_BYTES);

        MockHttpServletResponse response =
                perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content(padded));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aBodyReadThroughTheReaderIsBoundedToo() throws Exception {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter();
        long[] read = new long[1];

        filter.doFilter(
                undeclaredLength(RequestBodyLimitFilter.MAX_BODY_BYTES),
                new MockHttpServletResponse(),
                (request, response) ->
                        read[0] = ((HttpServletRequest) request).getReader().transferTo(Writer.nullWriter()));
        assertThat(read[0]).isEqualTo(RequestBodyLimitFilter.MAX_BODY_BYTES);

        assertThatThrownBy(() -> filter.doFilter(
                        undeclaredLength(RequestBodyLimitFilter.MAX_BODY_BYTES + 1),
                        new MockHttpServletResponse(),
                        (request, response) ->
                                ((HttpServletRequest) request).getReader().transferTo(Writer.nullWriter())))
                .isInstanceOf(RequestBodyLimitFilter.BodyTooLargeException.class);
    }

    @Test
    void anUnknownCharsetIsTheServletContractsCheckedException() {
        MockHttpServletRequest unknown = new MockHttpServletRequest("POST", "/t/body");
        unknown.setContent(new byte[1]);
        unknown.setCharacterEncoding("no-such-charset");

        assertThatThrownBy(() -> new RequestBodyLimitFilter()
                        .doFilter(
                                unknown,
                                new MockHttpServletResponse(),
                                (request, response) -> ((HttpServletRequest) request).getReader()))
                .isInstanceOf(UnsupportedEncodingException.class)
                .hasMessage("no-such-charset");
    }

    @Test
    void wireNamesAreSnakeCase() {
        assertThat(RestExceptionHandler.snakeCase("holdGroupId")).isEqualTo("hold_group_id");
        assertThat(RestExceptionHandler.snakeCase("eventId")).isEqualTo("event_id");
    }

    private void assertInvalid(RequestBuilder request, String parameter) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = body(response);
        assertThat(body.get("code").asString()).isEqualTo("invalid_request");
        assertThat(body.get("parameter").asString()).isEqualTo(parameter);
    }

    /** A body that declares no Content-Length, as a chunked one does, so only the counting wrapper can refuse it. */
    private static HttpServletRequest undeclaredLength(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/t/body");
        request.setContent(new byte[bytes]);
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
