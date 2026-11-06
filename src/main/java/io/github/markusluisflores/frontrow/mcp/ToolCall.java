package io.github.markusluisflores.frontrow.mcp;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place a tool body runs and the one place that maps its failures to a result (plan 5 decisions 4 and 5). The
 * tools are hand-registered {@code SyncToolSpecification}s over typed methods, not {@code @McpTool} methods, because
 * Spring AI converts arguments before the method runs and so cannot name the offending parameter in a structured
 * result. The binding here is the adapter's own and strict: each argument, looked up by the method's
 * {@code Parameter#getName()} (the name the schema is generated from, so the two stay tied), converts only to the
 * declared type, and anything else is invalid_request naming that parameter. An argument the method does not declare is
 * never read, so nothing an agent sends can name an owner.
 */
@Component
public class ToolCall {

    private static final Logger log = LoggerFactory.getLogger(ToolCall.class);

    private final ToolResults results;
    private final McpJsonMapper schemaMapper;

    public ToolCall(ToolResults results, @Qualifier("mcpServerJsonMapper") JsonMapper schemaMapper) {
        this.results = results;
        this.schemaMapper = new JacksonMcpJsonMapper(schemaMapper);
    }

    /**
     * The specification of one tool: the input schema is generated from the method declaration, with the same
     * generator the {@code @McpTool} path uses, and the handler binds the raw arguments to that declaration.
     */
    public SyncToolSpecification specification(String name, String description, Object target, String methodName) {
        Method method = methodOf(target, methodName);
        Tool tool = Tool.builder(name, schemaMapper, McpJsonSchemaGenerator.generateForMethodInput(method))
                .description(description)
                .build();
        return new SyncToolSpecification(
                tool, (exchange, request) -> run(name, target, method, request, exchange.transportContext()));
    }

    /** The whole call. Never throws: every outcome is a structured result. */
    CallToolResult run(
            String tool, Object target, Method method, CallToolRequest request, McpTransportContext context) {
        try {
            if (!(context.get(McpConfig.PRINCIPAL_KEY) instanceof String owner) || owner.isBlank()) {
                // The /mcp chain makes this unreachable; refusing is defence in depth (ADR-004).
                log.error("tool {} refused: no authenticated principal in the transport context", tool);
                return results.internalError();
            }
            Object[] arguments = bind(method, request.arguments());
            Object body = invoke(target, method, arguments);
            return results.success((Record) body);
        } catch (InvalidArgument invalid) {
            return results.invalidParameter(invalid.parameter);
        } catch (DomainException failure) {
            return results.error(failure);
        } catch (Throwable unexpected) {
            log.error("tool {} failed unexpectedly", tool, unexpected);
            return results.internalError();
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
        }
    }

    /** Every declared parameter in order, each converted to its declared type or named in the failure. */
    static Object[] bind(Method method, Map<String, Object> raw) {
        Map<String, Object> given = raw == null ? Map.of() : raw;
        Parameter[] parameters = method.getParameters();
        Object[] bound = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String name = parameter.getName();
            McpToolParam declaration = parameter.getAnnotation(McpToolParam.class);
            boolean required = declaration == null || declaration.required();
            Object value = given.get(name);
            if (value == null) {
                if (required) {
                    throw new InvalidArgument(name);
                }
                bound[i] = null;
            } else {
                bound[i] = convert(name, parameter.getType(), value);
            }
        }
        return bound;
    }

    private static Object convert(String name, Class<?> type, Object value) {
        if (type == String.class && value instanceof String text) {
            return text;
        }
        if (type == LocalDate.class && value instanceof String text) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException malformed) {
                throw new InvalidArgument(name);
            }
        }
        if (type == Long.class && integral(value) instanceof BigInteger whole && fits(whole, Long.MAX_VALUE)) {
            return whole.longValueExact();
        }
        if (type == Integer.class && integral(value) instanceof BigInteger whole && fits(whole, Integer.MAX_VALUE)) {
            return whole.intValueExact();
        }
        throw new InvalidArgument(name);
    }

    /** A JSON integer, as Jackson parses it into a Map; a fraction, a string or a boolean is not one. */
    private static Object integral(Object value) {
        return switch (value) {
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            case BigInteger big -> big;
            default -> null;
        };
    }

    private static boolean fits(BigInteger whole, long max) {
        return whole.bitLength() < Long.SIZE && whole.longValue() >= -max - 1 && whole.longValue() <= max;
    }

    private static Method methodOf(Object target, String methodName) {
        Method found = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(methodName) && !Modifier.isStatic(candidate.getModifiers())) {
                if (found != null) {
                    throw new IllegalStateException("tool method " + methodName + " is overloaded");
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalStateException("no public tool method " + methodName + " on " + target.getClass());
        }
        return found;
    }

    /** An argument that is missing or cannot be the declared type; carries only the parameter's name. */
    private static final class InvalidArgument extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final transient String parameter;

        InvalidArgument(String parameter) {
            super(null, null, false, false);
            this.parameter = parameter;
        }
    }
}
