package io.github.markusluisflores.frontrow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On /mcp, a known bearer token authenticates the user it was issued to, with the single AGENT authority and no role
 * (spec §3). An unknown token, or a Basic header, authenticates nothing, so the chain answers 401. The context is
 * also saved as a request attribute, as plan 1's spike did, so the MCP transport sees it (ADR-004).
 */
final class AgentTokenFilter extends OncePerRequestFilter {

    static final String AGENT = "AGENT";

    private final Credentials credentials;
    private final RequestAttributeSecurityContextRepository repository =
            new RequestAttributeSecurityContextRepository();

    AgentTokenFilter(Credentials credentials) {
        this.credentials = credentials;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> owner =
                soleAuthorization(request).flatMap(AgentTokenFilter::rawToken).flatMap(credentials::ownerOfToken);
        if (owner.isPresent()) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    owner.get(), null, List.of(new SimpleGrantedAuthority(AGENT))));
            SecurityContextHolder.setContext(context);
            repository.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }

    /**
     * The one Authorization header, or nothing when there are none or several (plan 5 decision 9). Tomcat's
     * getHeader returns the first of a repeated header and the MCP transport could read another, so a second header
     * is never a way to carry a credential past this check. A comma-joined list is one header whose value is no known
     * token. HttpServletRequest#getHeaders matches the name case-insensitively and does not fold lines, so the
     * header's case and its repeats are counted here, not left to a library.
     */
    private static Optional<String> soleAuthorization(HttpServletRequest request) {
        List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        return headers.size() == 1 ? Optional.of(headers.getFirst()) : Optional.empty();
    }

    static Optional<String> rawToken(String authorization) {
        if (!BearerRejectFilter.isBearer(authorization)) {
            return Optional.empty();
        }
        String token = authorization.strip().substring(6).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
