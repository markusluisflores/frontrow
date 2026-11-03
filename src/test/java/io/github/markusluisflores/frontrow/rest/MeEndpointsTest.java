package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.service.HoldCommand;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /api/me/holds and GET /api/me/orders return only the caller's rows, never another buyer's (spec §6, §12). The
 * role table admits any BUYER to both paths; the scoping under test is the service's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTestConfiguration.class)
class MeEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    long eventId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        HoldFixtures fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 4);
    }

    @Test
    void eachBuyerSeesOnlyTheirOwnHoldsAndOrders() throws Exception {
        String alicesHeld = groupOf(perform(hold("alice", "k1", seats.get(0))));
        String alicesBought = groupOf(perform(hold("alice", "k2", seats.get(1))));
        perform(post("/api/holds/" + alicesBought + "/confirm").with(as("alice")));
        String bobsHeld = groupOf(perform(hold("bob", "k1", seats.get(2))));
        // What alice's agent holds over MCP lands under the same owner: the service call an MCP tool makes.
        String agentHeld = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(3)), "agent-k1"))
                .holdGroupId()
                .toString();

        assertThat(groupIds(get("/api/me/holds"), "alice")).containsExactlyInAnyOrder(alicesHeld, agentHeld);
        assertThat(groupIds(get("/api/me/holds"), "bob")).containsExactly(bobsHeld);
        assertThat(groupIds(get("/api/me/orders"), "alice")).containsExactly(alicesBought);
        assertThat(groupIds(get("/api/me/orders"), "bob")).isEmpty();
    }

    private List<String> groupIds(MockHttpServletRequestBuilder request, String user) throws Exception {
        MockHttpServletResponse response = perform(request.with(as(user)));
        assertThat(response.getStatus()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode row : JSON.readTree(response.getContentAsString())) {
            ids.add(row.get("hold_group_id").asString());
        }
        return ids;
    }

    private MockHttpServletRequestBuilder hold(String user, String key, Long seatId) {
        return post("/api/events/" + eventId + "/holds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("event_seat_ids", List.of(seatId))))
                .with(as(user));
    }

    private static String groupOf(MockHttpServletResponse held) throws Exception {
        assertThat(held.getStatus()).isEqualTo(201);
        return JSON.readTree(held.getContentAsString()).get("hold_group_id").asString();
    }

    private static RequestPostProcessor as(String user) {
        return httpBasic(user, TestCredentials.password(user));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }
}
