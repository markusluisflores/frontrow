package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The three chains (spec §3, §8; ADR-002), through the application's real filter chain. These run before any write
 * endpoint exists, so a request that passes security reaches no handler and gets 404: that 404 is the positive control
 * that the chain let it through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityChainsTest {

    private static final String CONFIRM = "/api/holds/" + UUID.randomUUID() + "/confirm";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserDetailsService users;

    @Test
    void aBearerTokenIsRefusedOnAnonymousAndAuthenticatedApiEndpointsAlike() throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(get("/api/events"), post(CONFIRM), get("/api/orders/x"), post("/api/events"))) {
            MockHttpServletResponse response =
                    perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice")));
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void anAgentTokenCannotPatchEvenForAUserWhoIsAlsoAnOrganiser() throws Exception {
        assertThat(perform(patch("/api/events/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("carol")))
                        .getStatus())
                .isEqualTo(401);
        // The same user, with their password: the role works on /api/**, so only the credential type was refused.
        assertThat(perform(patch("/api/events/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .with(as("carol")))
                        .getStatus())
                .isNotIn(401, 403);
    }

    @Test
    void noUnauthorizedResponseCarriesABasicChallenge() throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(post(CONFIRM), post(CONFIRM).with(httpBasic("alice", "wrong")), get("/nowhere"))) {
            MockHttpServletResponse response = perform(request);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(response.getContentType()).isEqualTo("application/problem+json");
        }
    }

    @Test
    void eachWriteEndpointAdmitsOnlyItsRole() throws Exception {
        String group = "/api/holds/" + UUID.randomUUID();
        // A fresh builder per request: with() on a reused builder would stack a second Authorization header.
        List<Supplier<MockHttpServletRequestBuilder>> buyerOnly = List.of(
                () -> post("/api/events/1/holds"),
                () -> post(CONFIRM),
                () -> delete(group),
                () -> get("/api/me/holds"),
                () -> get("/api/me/orders"));
        for (Supplier<MockHttpServletRequestBuilder> request : buyerOnly) {
            assertThat(perform(request.get().with(as("olivia"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("adam"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("alice"))).getStatus()).isNotIn(401, 403);
        }
        // ORGANISER only: a buyer and an admin are both refused.
        for (Supplier<MockHttpServletRequestBuilder> request : List.<Supplier<MockHttpServletRequestBuilder>>of(
                () -> patch("/api/events/1"), () -> post("/api/events"))) {
            assertThat(perform(request.get().with(as("alice"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("adam"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("olivia"))).getStatus()).isNotIn(401, 403);
        }
        // BUYER or ADMIN, and not an organiser.
        assertThat(perform(get("/api/orders/x").with(as("alice"))).getStatus()).isNotIn(401, 403);
        assertThat(perform(get("/api/orders/x").with(as("adam"))).getStatus()).isNotIn(401, 403);
        assertThat(perform(get("/api/orders/x").with(as("olivia"))).getStatus()).isEqualTo(403);
    }

    @Test
    void anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser() throws Exception {
        assertThat(perform(get("/api/admin").with(as("adam"))).getStatus()).isEqualTo(403);
    }

    @Test
    void aFormSubmittableContentTypeIsRefusedOnUnsafeMethods() throws Exception {
        for (String type : List.of("application/x-www-form-urlencoded", "multipart/form-data", "text/plain")) {
            assertThat(perform(post(CONFIRM).contentType(type).content("x").with(as("alice")))
                            .getStatus())
                    .as(type)
                    .isEqualTo(415);
        }
        assertThat(perform(get("/api/events").contentType("text/plain")).getStatus())
                .isNotEqualTo(415);
    }

    @Test
    void aCrossSiteWriteIsRefusedEvenWithNoBody() throws Exception {
        // A no-cors fetch or a sendBeacon from another site: a bodyless POST, with credentials a browser attached.
        MockHttpServletResponse crossSite =
                perform(post(CONFIRM).header("Sec-Fetch-Site", "cross-site").with(as("alice")));
        assertThat(crossSite.getStatus()).isEqualTo(403);
        assertThat(crossSite.getContentType()).isEqualTo("application/problem+json");
        assertThat(perform(post(CONFIRM).header("Sec-Fetch-Site", "same-origin").with(as("alice")))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void mcpTakesOnlyAKnownBearerToken() throws Exception {
        assertThat(perform(mcpPing()).getStatus()).isEqualTo(401);
        assertThat(perform(mcpPing().with(as("alice"))).getStatus()).isEqualTo(401);
        assertThat(perform(mcpPing().header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                        .getStatus())
                .isEqualTo(401);
        assertThat(perform(mcpPing().header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice")))
                        .getStatus())
                .isNotIn(401, 403);
    }

    @Test
    void theCatchAllOpensOnlyItsAnonymousPaths() throws Exception {
        assertThat(perform(get("/actuator/health")).getStatus()).isEqualTo(200);
        for (MockHttpServletRequestBuilder request : List.of(
                get("/actuator/env"),
                get("/nowhere"),
                post("/nowhere"),
                get("/apix"),
                get("/mcpx"),
                // No chain keeps Spring's logout endpoint: there is no session to end.
                post("/logout"),
                get("/logout"))) {
            assertThat(perform(request).getStatus()).isEqualTo(401);
        }
        assertThat(perform(get("/actuator/env").with(as("adam"))).getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/logout")).getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/logout").with(as("alice"))).getStatus()).isEqualTo(403);
    }

    @Test
    void bootsGeneratedUserDoesNotExist() {
        assertThatThrownBy(() -> users.loadUserByUsername("user")).isInstanceOf(UsernameNotFoundException.class);
    }

    private static MockHttpServletRequestBuilder mcpPing() {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
    }

    private static RequestPostProcessor as(String user) {
        return httpBasic(user, TestCredentials.password(user));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }
}
