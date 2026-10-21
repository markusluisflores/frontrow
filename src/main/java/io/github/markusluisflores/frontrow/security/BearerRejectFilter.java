package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On /api/**, any bearer credential is refused with 401, on anonymous endpoints too (spec §3, ADR-002). Spring's Basic
 * filter silently ignores a Bearer header, so without this an agent token would pass as anonymous: the chains would
 * not be disjoint. The scheme is matched case-insensitively, as RFC 9110 says schemes are.
 */
final class BearerRejectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isBearer(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            Problems.write(request, response, Problems.plain(HttpStatus.UNAUTHORIZED));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isBearer(String authorization) {
        if (authorization == null) {
            return false;
        }
        String trimmed = authorization.strip();
        return trimmed.regionMatches(true, 0, "Bearer", 0, 6)
                && (trimmed.length() == 6 || Character.isWhitespace(trimmed.charAt(6)));
    }
}
