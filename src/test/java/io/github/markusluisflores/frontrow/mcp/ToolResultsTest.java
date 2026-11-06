package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Plan 5 task 2, decisions 4 and 5: what a tool result is made of, with no server and no network. */
class ToolResultsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper SNAKE_CASE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    record Sample(long holdGroupId, Instant expiresAt, List<Long> eventSeatIds) {}

    /** The structured content as it travels: parsed from its JSON, so a long and an int of one value compare equal. */
    private static JsonNode parsed(CallToolResult result) {
        return JSON.readTree(JSON.writeValueAsString(result.structuredContent()));
    }

    private final ToolResults results = new ToolResults(SNAKE_CASE);

    @Test
    void aSuccessIsAnObjectInTheSnakeCaseWireFormWithTheSameJsonAsText() throws Exception {
        CallToolResult result = results.success(new Sample(7, Instant.parse("2026-11-10T19:00:00Z"), List.of(1L, 2L)));

        JsonNode expected = JSON.readTree(
                "{\"hold_group_id\":7,\"expires_at\":\"2026-11-10T19:00:00Z\"," + "\"event_seat_ids\":[1,2]}");
        assertThat(parsed(result)).isEqualTo(expected);
        assertThat(JSON.readTree(((TextContent) result.content().getFirst()).text()))
                .isEqualTo(expected);
        assertThat(result.content()).hasSize(1);
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void aDomainFailureCarriesItsCodeHintAndDetails() throws Exception {
        DomainException failure = new DomainException(ErrorCode.SEAT_TAKEN, Map.of("seat_ids", List.of(412L)));

        CallToolResult result = results.error(failure);

        assertThat(result.isError()).isTrue();
        assertThat(parsed(result))
                .isEqualTo(JSON.readTree("{\"code\":\"seat_taken\",\"seat_ids\":[412],"
                        + "\"hint\":\"call check_availability for current seats\"}"));
    }

    @Test
    void anInvalidParameterNamesTheParameter() throws Exception {
        CallToolResult result = results.invalidParameter("event_seat_ids");

        assertThat(result.isError()).isTrue();
        assertThat(parsed(result))
                .isEqualTo(JSON.readTree("{\"code\":\"invalid_request\",\"parameter\":\"event_seat_ids\","
                        + "\"hint\":\"check the named parameter and try again\"}"));
    }

    @Test
    void theInternalErrorIsFixedAndCarriesNothingElse() throws Exception {
        CallToolResult result = results.internalError();

        assertThat(result.isError()).isTrue();
        JsonNode body = parsed(result);
        assertThat(body)
                .isEqualTo(JSON.readTree(
                        "{\"code\":\"internal_error\",\"hint\":\"unexpected failure; retry, or tell the operator\"}"));
        assertThat(JSON.readTree(((TextContent) result.content().getFirst()).text()))
                .isEqualTo(body);
    }
}
