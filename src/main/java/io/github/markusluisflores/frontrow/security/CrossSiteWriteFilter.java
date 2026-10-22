package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The second and third CSRF layers on /api/** (plan 4A decision 6). The first is that no 401 carries a Basic
 * challenge, so a browser never prompts for the credentials and never caches them. On an unsafe method, this filter
 * then refuses:
 *
 * <ul>
 *   <li>with 403, a Sec-Fetch-Site of cross-site or same-site. That catches what carries no form type at all: a
 *       no-cors fetch or a sendBeacon sends a bodyless POST with no Content-Type, and the browser attaches any cached
 *       credentials. same-origin, none and an absent header pass; curl and other non-browser clients send none;
 *   <li>with 415, the three content types a cross-site HTML form can send without a CORS preflight, and an unparseable
 *       one. No CORS is configured, so a preflight fails.
 * </ul>
 *
 * <p>A browser that sends no Fetch Metadata relies on the first layer alone against a request with no form type. That
 * includes every browser reaching the app over plain HTTP on a host other than localhost: browsers send Fetch Metadata
 * only to potentially trustworthy origins (HTTPS, or localhost).
 */
final class CrossSiteWriteFilter extends OncePerRequestFilter {

    private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final Set<MediaType> FORM_TYPES =
            Set.of(MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA, MediaType.TEXT_PLAIN);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod())) {
            if (isCrossSite(request.getHeader(SEC_FETCH_SITE))) {
                Problems.write(request, response, Problems.plain(HttpStatus.FORBIDDEN));
                return;
            }
            if (isFormSubmittable(request.getContentType())) {
                Problems.write(request, response, Problems.plain(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The two Fetch Metadata values that mean another site started the request; matched case-insensitively. */
    static boolean isCrossSite(String secFetchSite) {
        if (secFetchSite == null) {
            return false;
        }
        String value = secFetchSite.strip();
        return value.equalsIgnoreCase("cross-site") || value.equalsIgnoreCase("same-site");
    }

    static boolean isFormSubmittable(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return FORM_TYPES.stream().anyMatch(form -> form.equalsTypeAndSubtype(type));
        } catch (InvalidMediaTypeException unparseable) {
            // Spring MVC could not read it either; refuse it here rather than guess.
            return true;
        }
    }
}
