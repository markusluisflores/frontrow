package io.github.markusluisflores.frontrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The local run is local only (spec §3, §12): the app published on host loopback, Postgres not published at all, and
 * Postgres's password only from the generated, gitignored .env. Read from the committed files, so an edit that
 * publishes a port fails the build.
 */
class ComposeFileTest {

    private static final String PASSWORD = "${POSTGRES_PASSWORD:?run scripts/demo-credentials.sh first}";

    @Test
    void theAppIsPublishedOnLoopbackOnlyAndPostgresNotAtAll() throws IOException {
        Map<?, ?> services = (Map<?, ?>) compose().get("services");

        assertThat(List.<Object>copyOf(services.keySet())).containsExactlyInAnyOrder("app", "postgres");
        assertThat(((Map<?, ?>) services.get("app")).get("ports")).isEqualTo(List.of("127.0.0.1:8080:8080"));
        assertThat(((Map<?, ?>) services.get("postgres")).containsKey("ports")).isFalse();
        // network_mode: host would put a service on every host interface without touching ports.
        for (String name : List.of("app", "postgres")) {
            assertThat(((Map<?, ?>) services.get(name)).containsKey("network_mode"))
                    .as(name)
                    .isFalse();
        }
    }

    @Test
    void thePostgresPasswordComesOnlyFromTheGeneratedEnvFile() throws IOException {
        Map<?, ?> services = (Map<?, ?>) compose().get("services");
        Map<?, ?> postgres = (Map<?, ?>) ((Map<?, ?>) services.get("postgres")).get("environment");
        Map<?, ?> app = (Map<?, ?>) ((Map<?, ?>) services.get("app")).get("environment");

        assertThat(postgres.get("POSTGRES_PASSWORD")).isEqualTo(PASSWORD);
        assertThat(app.get("SPRING_DATASOURCE_PASSWORD")).isEqualTo(PASSWORD);

        // The committed example is a placeholder that does not work: compose's :? refuses an empty value.
        Properties example = new Properties();
        example.load(new StringReader(Files.readString(Path.of(".env.example"), StandardCharsets.UTF_8)));
        assertThat(example.getProperty("POSTGRES_PASSWORD")).isEmpty();
    }

    private static Map<?, ?> compose() throws IOException {
        Map<?, ?> compose = new Yaml().load(Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8));
        return compose;
    }
}
