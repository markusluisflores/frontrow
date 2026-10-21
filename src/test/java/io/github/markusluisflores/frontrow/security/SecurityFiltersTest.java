package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityFiltersTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anyBearerSchemeIsRecognisedWhateverItsCase() {
        for (String header : List.of("Bearer abc", "bearer abc", "BEARER abc", "  Bearer\tabc", "Bearer")) {
            assertThat(BearerRejectFilter.isBearer(header)).as(header).isTrue();
        }
        for (String header : List.of("Basic YWxpY2U6eA==", "Bearerabc", "Token abc", "")) {
            assertThat(BearerRejectFilter.isBearer(header)).as(header).isFalse();
        }
        assertThat(BearerRejectFilter.isBearer(null)).isFalse();
    }

    @Test
    void onlyACrossSiteOrSameSiteFetchIsRecognised() {
        for (String value : List.of("cross-site", "same-site", "CROSS-SITE", " Same-Site ")) {
            assertThat(CrossSiteWriteFilter.isCrossSite(value)).as(value).isTrue();
        }
        for (String value : List.of("same-origin", "none", "")) {
            assertThat(CrossSiteWriteFilter.isCrossSite(value)).as(value).isFalse();
        }
        assertThat(CrossSiteWriteFilter.isCrossSite(null)).isFalse();
    }

    @Test
    void anUnsafeCrossSiteRequestIsRefusedWith403WhateverItsContentType() throws Exception {
        // A no-cors fetch or a sendBeacon: no Content-Type at all, so only Fetch Metadata shows where it came from.
        assertThat(crossSiteFilterStatus("POST", "cross-site", null)).isEqualTo(403);
        assertThat(crossSiteFilterStatus("DELETE", "same-site", null)).isEqualTo(403);
        assertThat(crossSiteFilterStatus("PATCH", "cross-site", "application/json"))
                .isEqualTo(403);
        assertThat(crossSiteFilterStatus("POST", "cross-site", "text/plain")).isEqualTo(403);
        // A same-origin or user-initiated request, or a client that sends no Fetch Metadata, passes.
        assertThat(crossSiteFilterStatus("POST", "same-origin", null)).isEqualTo(200);
        assertThat(crossSiteFilterStatus("POST", "none", null)).isEqualTo(200);
        assertThat(crossSiteFilterStatus("POST", null, null)).isEqualTo(200);
        // A form type is still refused when Fetch Metadata passes, and a safe method is never refused.
        assertThat(crossSiteFilterStatus("POST", "same-origin", "text/plain")).isEqualTo(415);
        assertThat(crossSiteFilterStatus("GET", "cross-site", "text/plain")).isEqualTo(200);
    }

    @Test
    void onlyFormSubmittableContentTypesAreRefused() {
        for (String type : List.of(
                "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x",
                "text/plain;charset=UTF-8",
                "TEXT/PLAIN",
                "not a media type")) {
            assertThat(CrossSiteWriteFilter.isFormSubmittable(type)).as(type).isTrue();
        }
        for (String type : List.of("application/json", "application/merge-patch+json")) {
            assertThat(CrossSiteWriteFilter.isFormSubmittable(type)).as(type).isFalse();
        }
        assertThat(CrossSiteWriteFilter.isFormSubmittable(null)).isFalse();
    }

    @Test
    void aKnownTokenAuthenticatesItsUserWithAgentAndNothingElse() throws Exception {
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("pw");
        new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfig.class)
                .withPropertyValues(
                        "frontrow.security.users[0].username=carol",
                        "frontrow.security.users[0].password-hash=" + hash,
                        "frontrow.security.users[0].roles=BUYER,ORGANISER",
                        "frontrow.security.agent-tokens[0].sha256=" + Credentials.sha256Hex("carols-token"),
                        "frontrow.security.agent-tokens[0].username=carol")
                .run(context -> {
                    AgentTokenFilter filter = new AgentTokenFilter(context.getBean(Credentials.class));

                    assertThat(authenticationFor(filter, "Bearer carols-token")).hasValueSatisfying(authentication -> {
                        assertThat(authentication.getName()).isEqualTo("carol");
                        // carol is also an ORGANISER, but a token carries no role (spec §3).
                        assertThat(authentication.getAuthorities())
                                .extracting(Object::toString)
                                .containsExactly("AGENT");
                    });
                    for (String header :
                            new String[] {"Bearer wrong", "Basic Y2Fyb2w6cHc=", "Bearer", "carols-token"}) {
                        assertThat(authenticationFor(filter, header)).as(header).isEmpty();
                    }
                });
    }

    /** The filter's status for one request: 200 means it passed the request on. */
    private static int crossSiteFilterStatus(String method, String secFetchSite, String contentType) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/holds/x/confirm");
        if (secFetchSite != null) {
            request.addHeader("Sec-Fetch-Site", secFetchSite);
        }
        request.setContentType(contentType);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new CrossSiteWriteFilter().doFilter(request, response, chain);
        assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }

    private static Optional<Authentication> authenticationFor(AgentTokenFilter filter, String authorization)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", authorization);
        List<Authentication> seen = new ArrayList<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.add(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return Optional.ofNullable(seen.getFirst());
    }
}
