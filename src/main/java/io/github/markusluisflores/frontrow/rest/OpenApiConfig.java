package io.github.markusluisflores.frontrow.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document behind Swagger UI (spec §10: Swagger UI is the human-facing demo). Served anonymously by the
 * catch-all chain at /v3/api-docs and /swagger-ui/index.html (spec §3).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BASIC = "basic";

    /**
     * HTTP Basic for the whole document; the three event reads need no credentials. Swagger UI's Authorize button sends
     * the header itself, because no 401 carries a challenge (spec §3). The server URL is pinned to "/": it must not
     * come from a request's Host header.
     */
    @Bean
    OpenAPI frontRowOpenApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(new Info()
                        .title("FrontRow")
                        .version("phase-1")
                        .description("Event ticketing. Agents hold over MCP; only a human buys, here."))
                .components(new Components()
                        .addSecuritySchemes(
                                BASIC,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC));
    }

    /**
     * Schemas named as the wire names them (spec §6, snake_case). springdoc renders them with swagger-core's own Jackson
     * 2 mapper, which knows nothing of the application's Jackson 3 naming strategy.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json31.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
                .openapi31(true);
    }
}
