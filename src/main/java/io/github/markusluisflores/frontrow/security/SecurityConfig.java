package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Three chains, tried in order (spec §3, §9; ADR-002). /api/** takes HTTP Basic only; /mcp takes an agent bearer token
 * only; everything else falls to a last chain with no matcher, which opens four anonymous paths and denies the rest.
 * Without that last chain, a path matching neither of the first two would be served with no security at all.
 *
 * <p>All three are stateless and keep Spring's CSRF token off: nothing here uses a session or a cookie. Plan 4A
 * decision 6 is what stands in for it on /api/**: no 401 carries a Basic challenge, so a browser never caches the
 * credentials; and CrossSiteWriteFilter refuses an unsafe method that a browser's Fetch Metadata marks as sent from
 * another site, and any form-submittable content type. A browser that sends no Fetch Metadata, which includes any
 * browser on plain HTTP to a host other than localhost, relies on the first layer alone against a request with no form
 * type. No chain has a logout endpoint: there is no session to end.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** Ours replaces Boot's generated user, which would otherwise authenticate on /api/** as a fallback. */
    @Bean
    UserDetailsService userDetailsService(Credentials credentials) {
        return credentials.userDetailsService();
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                // The spec §6 REST table, whole, including the endpoints plan 4B adds; anything unlisted is denied.
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HttpMethod.GET, "/api/events", "/api/events/*", "/api/events/*/availability")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events/*/holds", "/api/holds/*/confirm")
                        .hasRole("BUYER")
                        .requestMatchers(HttpMethod.DELETE, "/api/holds/*")
                        .hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/me/holds", "/api/me/orders")
                        .hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/*")
                        .hasAnyRole("BUYER", "ADMIN")
                        // Any ORGANISER, for any event: Phase 1 has no per-event ownership (plan 4A decision 2).
                        .requestMatchers(HttpMethod.POST, "/api/events")
                        .hasRole("ORGANISER")
                        .requestMatchers(HttpMethod.PATCH, "/api/events/*")
                        .hasRole("ORGANISER")
                        .anyRequest()
                        .denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized()))
                .addFilterBefore(new BearerRejectFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new CrossSiteWriteFilter(), BasicAuthenticationFilter.class);
        return stateless(http).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain mcpChain(HttpSecurity http, Credentials credentials) throws Exception {
        http.securityMatcher("/mcp", "/mcp/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(AgentTokenFilter.AGENT))
                .addFilterBefore(new AgentTokenFilter(credentials), AnonymousAuthenticationFilter.class);
        return stateless(http).build();
    }

    /** No securityMatcher, and evaluated last (ADR-002): the four anonymous paths of spec §3, and nothing else. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain catchAllChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                auth -> auth.requestMatchers("/error", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return stateless(http).build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(unauthorized()).accessDeniedHandler(forbidden()));
    }

    /** 401 with no WWW-Authenticate challenge (plan 4A decision 6), and a Problem Details body. */
    static AuthenticationEntryPoint unauthorized() {
        return (request, response, failure) ->
                Problems.write(request, response, Problems.plain(HttpStatus.UNAUTHORIZED));
    }

    static AccessDeniedHandler forbidden() {
        return (request, response, failure) -> Problems.write(request, response, Problems.plain(HttpStatus.FORBIDDEN));
    }
}
