package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike (spec §3): how does an authenticated principal reach an {@code @McpTool} method over Streamable HTTP?
 * Real bearer token, real HTTP, real MCP client. Mechanism A is SecurityContextHolder, mechanism B is
 * McpTransportContext populated by a contextExtractor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, PrincipalPropagationSpikeTest.SpikeConfig.class})
class PrincipalPropagationSpikeTest {

    /** Generated per run: spec §12 allows no raw token in the repository, even a test one. */
    static final String SPIKE_TOKEN = UUID.randomUUID().toString();

    static final String SPIKE_USER = "alice";
    static final String PRINCIPAL_KEY = "frontrow.principal";
    static final String ANONYMOUS = "<anonymous>";

    @LocalServerPort
    int port;

    @Test
    void mechanismA_securityContextHolderSeesThePrincipal() {
        assertThat(callTool("whoami_security_context")).isEqualTo(SPIKE_USER);
    }

    @Test
    void mechanismB_transportContextCarriesThePrincipal() {
        assertThat(callTool("whoami_transport_context")).isEqualTo(SPIKE_USER);
    }

    @Test
    void unauthenticatedMcpRequestIsRejectedWith401() throws Exception {
        assertThat(postPing(null).statusCode()).isEqualTo(401);
    }

    /** Positive control: the spike's own bearer filter admits the token, so A/B results are about mechanism. */
    @Test
    void authenticatedMcpRequestPassesTheSecurityChain() throws Exception {
        assertThat(postPing("Bearer " + SPIKE_TOKEN).statusCode()).isNotIn(401, 403);
    }

    private HttpResponse<String> postPing(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String callTool(String toolName) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        "http://localhost:" + port)
                .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + SPIKE_TOKEN))
                .build();
        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            CallToolResult result = client.callTool(
                    CallToolRequest.builder(toolName).arguments(Map.of()).build());
            assertThat(result.isError()).as("tool error: %s", result.content()).isNotEqualTo(Boolean.TRUE);
            return ((TextContent) result.content().getFirst()).text();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SpikeConfig {

        @Bean
        WhoAmITools whoAmITools() {
            return new WhoAmITools();
        }

        @Bean
        SecurityFilterChain spikeMcpChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/mcp", "/mcp/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("AGENT"))
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(
                            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .addFilterBefore(new SpikeBearerFilter(), AnonymousAuthenticationFilter.class);
            return http.build();
        }

        /** Replaces the auto-configured provider (it is @ConditionalOnMissingBean) to add mechanism B. */
        @Bean
        WebMvcStreamableServerTransportProvider spikeTransportProvider(
                @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
            return WebMvcStreamableServerTransportProvider.builder()
                    .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                    .mcpEndpoint(properties.getMcpEndpoint())
                    .keepAliveInterval(properties.getKeepAliveInterval())
                    .disallowDelete(properties.isDisallowDelete())
                    .contextExtractor(request -> McpTransportContext.create(Map.of(
                            PRINCIPAL_KEY,
                            request.principal().map(Principal::getName).orElse(ANONYMOUS))))
                    .build();
        }
    }

    static class WhoAmITools {

        @McpTool(name = "whoami_security_context", description = "Spike: principal via SecurityContextHolder")
        public String whoAmIFromSecurityContext() {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication == null ? ANONYMOUS : authentication.getName();
        }

        @McpTool(name = "whoami_transport_context", description = "Spike: principal via McpTransportContext")
        public String whoAmIFromTransportContext(McpTransportContext context) {
            Object principal = context.get(PRINCIPAL_KEY);
            return principal == null ? ANONYMOUS : principal.toString();
        }
    }

    /** Spike-only: one fixed token maps to one user with the AGENT authority. Plan 2 builds the real chain. */
    static final class SpikeBearerFilter extends OncePerRequestFilter {

        private final RequestAttributeSecurityContextRepository repository =
                new RequestAttributeSecurityContextRepository();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (("Bearer " + SPIKE_TOKEN).equals(request.getHeader(HttpHeaders.AUTHORIZATION))) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        SPIKE_USER, null, List.of(new SimpleGrantedAuthority("AGENT"))));
                SecurityContextHolder.setContext(context);
                repository.saveContext(context, request, response);
            }
            chain.doFilter(request, response);
        }
    }
}
