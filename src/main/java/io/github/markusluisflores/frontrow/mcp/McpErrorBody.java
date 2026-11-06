package io.github.markusluisflores.frontrow.mcp;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import org.springframework.boot.jackson.JacksonMixin;

/**
 * Plan 5 decision 10, the mechanism behind the stack_trace on /mcp transport errors. The Streamable HTTP transport
 * answers a malformed or unreadable request with {@code ServerResponse.status(...).body(mcpError)}, handing the
 * McpError exception itself to Spring MVC, whose message converter renders it with the application's mapper, as it
 * would any bean. Jackson reads an exception as a bean: its cause, message, localized message, suppressed list and
 * stack trace (class names, files and line numbers) all went on the wire. This mixin lets the mapper render an
 * McpError as its JSON-RPC error alone (code and the protocol's own message, which no exception supplies), so a
 * transport error stays a structured object and carries nothing from the throwable.
 */
@JacksonMixin(McpError.class)
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
abstract class McpErrorBody {

    @JsonProperty("json_rpc_error")
    abstract JSONRPCError getJsonRpcError();
}
