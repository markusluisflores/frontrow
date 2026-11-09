package io.github.markusluisflores.frontrow.mcp;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds every tool result (plan 5 decisions 4 and 5). The structured content is always a JSON object: a success takes
 * a record and goes through the application's snake_case mapper to a Map, so a bare array cannot be built, and an error
 * is the same members rest/Problems puts in a Problem Details body. The text content is the same JSON, for clients that
 * read only text. No result declares an outputSchema.
 */
public final class ToolResults {

    /** The generic body of any failure that is not a domain code: no detail, so nothing internal can leak. */
    static final String INTERNAL_ERROR = "internal_error";

    static final String INTERNAL_HINT = "unexpected failure; retry, or tell the operator";

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JsonMapper mapper;

    public ToolResults(JsonMapper mapper) {
        // A private copy with the same settings: the mapper is configured once, and no caller can change this one.
        this.mapper = mapper.rebuild().build();
    }

    /** A success: the record, as the REST body of the matching endpoint renders it. */
    public CallToolResult success(Record body) {
        return result(mapper.convertValue(body, OBJECT), false);
    }

    /** {"code", "hint", details}, from the same members rest/Problems renders. */
    public CallToolResult error(DomainException failure) {
        ErrorCode code = failure.errorCode();
        Map<String, Object> members = new TreeMap<>(failure.details());
        members.put("code", code.code());
        members.put("hint", code.hint());
        return result(members, true);
    }

    /** An argument that is missing or cannot be the declared type: invalid_request naming the parameter. */
    public CallToolResult invalidParameter(String parameter) {
        return error(new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter)));
    }

    /** Any other failure: a twelfth wire code (plan 5 decision 5) with a fixed hint and nothing from the cause. */
    public CallToolResult internalError() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("code", INTERNAL_ERROR);
        members.put("hint", INTERNAL_HINT);
        return result(members, true);
    }

    private CallToolResult result(Map<String, Object> structured, boolean isError) {
        return CallToolResult.builder()
                .structuredContent(structured)
                .addTextContent(mapper.writeValueAsString(structured))
                .isError(isError)
                .build();
    }
}
