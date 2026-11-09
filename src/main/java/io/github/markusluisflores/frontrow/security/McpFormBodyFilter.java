package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 413 for a form POST to /mcp that Tomcat refuses to parse because it is over server.tomcat.max-http-form-post-size
 * (plan 5 decision 11). The MCP transport rebuilds a form body from request.getParameterMap(), which Tomcat parses from
 * its own buffer past RequestBodyLimitFilter's counting stream. When that parse fails, the transport answers 500.
 *
 * <p>This filter sits after the /mcp chain's authorization check, so only a request that carries a known agent token
 * makes the server read a form body at all: no token, or an unknown one, was already answered 401. The request
 * filter that bounds a declared Content-Length runs before every chain and reads nothing, so it stays where it is. The
 * 413 is the filter-error shape (Problems.plain, no code), the same as the one that filter writes.
 */
final class McpFormBodyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isFormPost(request) && !parses(request)) {
            Problems.write(request, response, Problems.plain(HttpStatus.CONTENT_TOO_LARGE));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isFormPost(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod()) || request.getContentType() == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(request.getContentType()));
        } catch (InvalidMediaTypeException unparseable) {
            return false;
        }
    }

    /** Tomcat caches the parsed parameters, so the transport's own later read gets the same map and the same answer. */
    private static boolean parses(HttpServletRequest request) {
        try {
            request.getParameterMap();
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }
}
