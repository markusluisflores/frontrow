package io.github.markusluisflores.frontrow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guard behind spec §12's "every schema constraint has a violating-row test". It reads the migrated schema's catalog
 * and fails when a named constraint or unique index has neither a test method that names it nor an entry in
 * {@link #NOT_ROW_TESTED}.
 *
 * <p>Scope: primary-key, unique, foreign-key and check constraints (pg_constraint types p, u, f, c), plus unique indexes
 * that back none of those (the partial {@code uq_claimed_seat}). NOT NULL constraints (type n in Postgres 18) are column
 * modifiers, not spec §5 constraints, and are excluded. Flyway's own history table is excluded.
 *
 * <p>"Names it" means a double-quoted string literal equal to the constraint name, inside a {@code @Test} or
 * {@code @ParameterizedTest} method of a class in this package (other than this one). A name in a comment, a helper
 * method or a field does not count, so renaming a constraint without touching its test also fails here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConstraintCoverageTest {

    private static final String SURROGATE_ID = "surrogate identity id, GENERATED ALWAYS: the database assigns it";
    private static final String TARGET_ONLY = "exists only as the target of a composite foreign key; (id, venue_id)"
            + " cannot repeat while id is unique, so no row can violate it";

    /** Constraints that are deliberately not row-tested. Each needs a reason; a stale entry fails the guard. */
    private static final Map<String, String> NOT_ROW_TESTED = Map.ofEntries(
            Map.entry("venue_pkey", SURROGATE_ID),
            Map.entry("seat_pkey", SURROGATE_ID),
            Map.entry("event_pkey", SURROGATE_ID),
            Map.entry("event_seat_pkey", SURROGATE_ID),
            Map.entry("seat_hold_pkey", SURROGATE_ID),
            Map.entry("ticket_order_pkey", SURROGATE_ID),
            Map.entry("order_line_pkey", SURROGATE_ID),
            Map.entry("uq_seat_venue", TARGET_ONLY),
            Map.entry("uq_event_venue", TARGET_ONLY));

    private static final Path SCHEMA_TESTS = Path.of("src/test/java/io/github/markusluisflores/frontrow/schema");

    /** A test method: from its 4-space-indented annotation to the next 4-space-indented closing brace. */
    private static final Pattern TEST_METHOD =
            Pattern.compile("^    @(?:Test|ParameterizedTest)\\b.*?^    \\}", Pattern.MULTILINE | Pattern.DOTALL);

    private static final String CATALOG_SQL = """
            SELECT c.conname AS name
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = current_schema()
               AND t.relname <> 'flyway_schema_history'
               AND c.contype IN ('p', 'u', 'f', 'c')
            UNION
            SELECT i.indexrelid::regclass::text
              FROM pg_index i
              JOIN pg_class t ON t.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = current_schema()
               AND t.relname <> 'flyway_schema_history'
               AND i.indisunique
               AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
            ORDER BY 1""";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyConstraintAndUniqueIndexHasAViolatingRowTestOrAReasonedException() {
        List<String> catalog = jdbc.queryForList(CATALOG_SQL, String.class);
        assertThat(catalog).as("catalog read found the V1 schema").contains("uq_claimed_seat", "pk_hold_request");

        String testMethods = testMethodBodies();
        TreeSet<String> uncovered = new TreeSet<>();
        for (String name : catalog) {
            boolean named = testMethods.contains("\"" + name + "\"");
            if (!named && !NOT_ROW_TESTED.containsKey(name)) {
                uncovered.add(name);
            }
        }
        assertThat(uncovered)
                .as("constraints with no violating-row test; add one in SchemaConstraintsTest, or add an entry with a"
                        + " reason to NOT_ROW_TESTED")
                .isEmpty();
    }

    @Test
    void theExceptionListHoldsOnlyLiveConstraintsThatNoTestCovers() {
        List<String> catalog = jdbc.queryForList(CATALOG_SQL, String.class);
        String testMethods = testMethodBodies();
        for (Map.Entry<String, String> exception : NOT_ROW_TESTED.entrySet()) {
            assertThat(catalog)
                    .as("exception %s still exists in the schema", exception.getKey())
                    .contains(exception.getKey());
            assertThat(testMethods)
                    .as("exception %s is now row-tested, so drop it from the list", exception.getKey())
                    .doesNotContain("\"" + exception.getKey() + "\"");
            assertThat(exception.getValue())
                    .as("reason for %s", exception.getKey())
                    .isNotBlank();
        }
    }

    /** The text of every @Test / @ParameterizedTest method in the schema test package, except this class. */
    private static String testMethodBodies() {
        StringBuilder bodies = new StringBuilder();
        try (Stream<Path> files = Files.list(SCHEMA_TESTS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("ConstraintCoverageTest.java"))
                    .toList()) {
                Matcher m = TEST_METHOD.matcher(Files.readString(file).replace("\r\n", "\n"));
                while (m.find()) {
                    bodies.append(m.group()).append('\n');
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + SCHEMA_TESTS.toAbsolutePath(), e);
        }
        assertThat(bodies).as("found test methods to scan").isNotEmpty();
        return bodies.toString();
    }
}
