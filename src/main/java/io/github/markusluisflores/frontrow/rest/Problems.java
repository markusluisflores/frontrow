package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every REST error body is an RFC 9457 Problem Details object (spec §6). A domain failure adds its code, its hint and
 * its details as extension members, in key order: details() iterates in a per-JVM order (PR #18 thread 4296749419),
 * and a rendered body must not. A failure with no domain code — a 401, a 403, a 500 — has no code member.
 */
public final class Problems {

    /** The service names MCP's parameter; REST carries the key in a header (spec §6; PR #18 thread 4296541034). */
    static final Map<String, String> REST_PARAMETER_NAMES = Map.of("idempotency_key", "Idempotency-Key");

    /** For filters, which answer before Spring MVC's converters run; independent of the app's Jackson settings. */
    private static final JsonMapper FILTER_FORM = JsonMapper.builder().build();

    private Problems() {}

    /** Switch expression, no default: a new ErrorCode is a compile error here, not a silent 500. */
    public static HttpStatus status(ErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, TOO_MANY_SEATS -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SEAT_TAKEN, HOLD_LIMIT_EXCEEDED, HOLD_EXPIRED, HOLD_NOT_ACTIVE, SALES_NOT_OPEN, SALES_CLOSED ->
                HttpStatus.CONFLICT;
            // The IETF Idempotency-Key draft's status for a key reused with a different request.
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CONTENTION_RETRY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    public static ProblemDetail of(DomainException failure) {
        ErrorCode code = failure.errorCode();
        Map<String, Object> members = new TreeMap<>();
        failure.details().forEach((key, value) -> members.put(key, restName(key, value)));
        members.put("code", code.code());
        members.put("hint", code.hint());
        ProblemDetail problem = ProblemDetail.forStatus(status(code));
        members.forEach(problem::setProperty);
        return problem;
    }

    public static ProblemDetail invalidParameter(String parameter) {
        return of(new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter)));
    }

    /** A bare status: the reason phrase as title, and nothing else — no detail, no echo. */
    public static ProblemDetail plain(HttpStatus status) {
        return ProblemDetail.forStatus(status);
    }

    /**
     * Writes a problem from a filter, in the same RFC 9457 shape the MVC converter produces. The converter sets the
     * instance to the request URI, and so does this writer.
     */
    public static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        // Spring leaves type unset, which RFC 9457 reads as about:blank; this writer does the same.
        URI type = problem.getType();
        if (type != null) {
            body.put("type", type.toString());
        }
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("instance", request.getRequestURI());
        Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            body.putAll(properties);
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        FILTER_FORM.writeValue(response.getOutputStream(), body);
    }

    private static Object restName(String key, Object value) {
        return "parameter".equals(key) && value instanceof String parameter
                ? REST_PARAMETER_NAMES.getOrDefault(parameter, parameter)
                : value;
    }
}
