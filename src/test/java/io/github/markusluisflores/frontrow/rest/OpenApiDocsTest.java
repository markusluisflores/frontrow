package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The OpenAPI document and Swagger UI (spec §6, §10): anonymous, the whole REST table, and the wire's own names. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Test
    void theDocumentListsExactlyTheRestSurface() throws Exception {
        JsonNode docs = docs();
        List<String> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : docs.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                operations.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
            }
        }
        // Spec §6's REST table, row for row; nothing from /mcp, and nothing revision 2.7 cut.
        assertThat(operations)
                .containsExactlyInAnyOrder(
                        "GET /api/events",
                        "POST /api/events",
                        "GET /api/events/{eventId}",
                        "PATCH /api/events/{eventId}",
                        "GET /api/events/{eventId}/availability",
                        "POST /api/events/{eventId}/holds",
                        "DELETE /api/holds/{holdGroupId}",
                        "POST /api/holds/{holdGroupId}/confirm",
                        "GET /api/me/holds",
                        "GET /api/me/orders");
        assertThat(docs.at("/components/securitySchemes/basic/scheme").asString())
                .isEqualTo("basic");
    }

    @Test
    void schemasUseTheWireNames() throws Exception {
        // swagger-core renders schemas with its own Jackson 2 mapper; without OpenApiConfig's resolver these would be
        // camelCase, and Swagger UI's "Try it out" would send bodies the API refuses.
        JsonNode schemas = docs().at("/components/schemas");
        assertThat(names(schemas.at("/HoldRequest/properties"))).containsExactly("event_seat_ids");
        assertThat(names(schemas.at("/EventCreateRequest/properties")))
                .contains("venue_id", "starts_at", "sales_open_at", "sales_close_at", "prices")
                .doesNotContain("venueId", "startsAt");
        assertThat(names(schemas.at("/Availability/properties"))).contains("event_id", "seats");
        // swagger-core names a schema by its class's simple name, so two seat records must not share one: the hold
        // response's seat and me/holds' seat each keep their own fields.
        assertThat(names(schemas.at("/GroupSeat/properties"))).contains("event_seat_id", "section");
        assertThat(names(schemas.at("/HeldSeat/properties")))
                .contains("event_seat_id", "price_cents")
                .doesNotContain("section");
        // A 3.0-mode resolver under springdoc's default OpenAPI 3.1 document would drop the schema's type.
        assertThat(schemas.at("/EventCreateRequest/type").asString()).isEqualTo("object");
    }

    @Test
    void theServerUrlDoesNotComeFromTheHostHeader() throws Exception {
        // springdoc otherwise writes the requesting Host into the shared cached document, and Swagger UI's "Try it
        // out" would send Basic credentials there.
        MockHttpServletResponse response = mvc.perform(get("/v3/api-docs").header("Host", "evil.example"))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString())
                        .at("/servers/0/url")
                        .asString())
                .isEqualTo("/");
    }

    @Test
    void swaggerUiIsServedAnonymously() throws Exception {
        assertThat(mvc.perform(get("/swagger-ui/index.html"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    private JsonNode docs() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString());
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
