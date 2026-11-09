package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan 5 task 2: ToolCall's own guarantees, with no server: the refusal with no principal, the owner coming only from
 * the transport context, and the binding of a null or partial arguments map.
 */
class ToolCallTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final McpTransportContext ALICE =
            McpTransportContext.create(Map.<String, Object>of(McpConfig.PRINCIPAL_KEY, "alice"));

    public record Echoed(Long event_id, String section) {}

    public static class Target {

        public Echoed echo(
                @McpToolParam(description = "the id") Long event_id,
                @McpToolParam(required = false, description = "the section") String section) {
            return new Echoed(event_id, section);
        }
    }

    /** The structured content as it travels: parsed from its JSON, so a long and an int of one value compare equal. */
    private static JsonNode parsed(CallToolResult result) {
        return JSON.readTree(JSON.writeValueAsString(result.structuredContent()));
    }

    private final ToolCall calls = new ToolCall(
            new ToolResults(JsonMapper.builder().build()), JsonMapper.builder().build());
    private final Method echo = methodOfEcho();
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void captureLog() {
        log = new ListAppender<>();
        log.start();
        ((Logger) LoggerFactory.getLogger(ToolCall.class)).addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        ((Logger) LoggerFactory.getLogger(ToolCall.class)).detachAppender(log);
        log.stop();
    }

    @Test
    void withNoPrincipalAToolRefusesWithTheInternalErrorBodyAndALogLine() {
        CallToolResult result =
                calls.run("echo", new Target(), echo, request(Map.of("event_id", 1)), McpTransportContext.EMPTY);

        assertThat(result.isError()).isTrue();
        assertThat(parsed(result).get("code").asString()).isEqualTo("internal_error");
        assertThat(log.list).singleElement().satisfies(logged -> {
            assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
            assertThat(logged.getFormattedMessage()).contains("echo", "no authenticated principal");
        });
    }

    @Test
    void aBlankPrincipalIsNoPrincipal() {
        McpTransportContext blank = McpTransportContext.create(Map.<String, Object>of(McpConfig.PRINCIPAL_KEY, " "));

        CallToolResult result = calls.run("echo", new Target(), echo, request(Map.of("event_id", 1)), blank);

        assertThat(parsed(result).get("code").asString()).isEqualTo("internal_error");
    }

    @Test
    void anOmittedArgumentsMapIsNoArguments() {
        CallToolResult result = calls.run("echo", new Target(), echo, request(null), ALICE);

        JsonNode body = parsed(result);
        assertThat(result.isError()).isTrue();
        assertThat(body.get("code").asString()).isEqualTo("invalid_request");
        assertThat(body.get("parameter").asString()).isEqualTo("event_id");
    }

    @Test
    void anArgumentNamedLikeAnIdentityIsNeverReadAndNeverBound() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("event_id", 5);
        arguments.put("owner", "bob");
        arguments.put("section", "Floor");
        arguments.put("principal", "bob");

        CallToolResult result = calls.run("echo", new Target(), echo, request(arguments), ALICE);

        assertThat(parsed(result))
                .isEqualTo(JSON.createObjectNode().put("event_id", 5).put("section", "Floor"));
    }

    @Test
    void theBoundNamesAreTheJavaParameterNames() {
        // -parameters is on, and the snake_case names are the wire names (plan 5 decision 3).
        assertThat(echo.getParameters()[0].getName()).isEqualTo("event_id");
    }

    private static CallToolRequest request(Map<String, Object> arguments) {
        return CallToolRequest.builder("echo").arguments(arguments).build();
    }

    private static Method methodOfEcho() {
        try {
            return Target.class.getMethod("echo", Long.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
