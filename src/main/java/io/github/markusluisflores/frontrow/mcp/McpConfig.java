package io.github.markusluisflores.frontrow.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server's wiring (plan 5 decision 5, ADR-004). Three pieces:
 *
 * <ul>
 *   <li>The Streamable HTTP transport provider, with a context extractor that carries the authenticated principal's
 *       name under {@link #PRINCIPAL_KEY}. This is the only source of a tool call's owner: not the MCP session, not an
 *       argument. With no principal the extractor puts nothing, and ToolCall refuses. It replaces the auto-configured
 *       provider (which backs off) and keeps its settings; the /mcp transport-error rendering is a Jackson mixin on the
 *       application's mapper ({@link McpErrorBody}), not a property of the provider, so it is unaffected.
 *   <li>One {@code @Primary} server customizer. The auto-configured servlet customizer is a second
 *       McpSyncServerCustomizer bean and the server takes a single Optional, so this one wins and replaces it: it sets
 *       immediateExecution(true) (the handler runs on the servlet thread, as the built-in customizer did) and
 *       validateToolInputs(false), so the SDK never answers an argument failure in plain text before ToolCall's own
 *       strict binding runs.
 *   <li>The ONE {@code List<SyncToolSpecification>} bean: the server collects a list bean, and would silently not serve
 *       separate specification beans.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class McpConfig {

    /** The transport context key of the caller's username (the spike's key, ADR-004). */
    public static final String PRINCIPAL_KEY = "frontrow.principal";

    @Bean
    WebMvcStreamableServerTransportProvider frontrowStreamableTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(request -> request.principal()
                        .map(Principal::getName)
                        .map(name -> McpTransportContext.create(Map.<String, Object>of(PRINCIPAL_KEY, name)))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }

    @Bean
    @Primary
    McpSyncServerCustomizer frontrowServerCustomizer() {
        return server -> server.immediateExecution(true).validateToolInputs(false);
    }

    @Bean
    ToolResults toolResults(@Qualifier("jacksonJsonMapper") JsonMapper applicationMapper) {
        return new ToolResults(applicationMapper);
    }

    @Bean
    List<SyncToolSpecification> frontrowToolSpecifications(EventTools events) {
        return events.specifications();
    }
}
