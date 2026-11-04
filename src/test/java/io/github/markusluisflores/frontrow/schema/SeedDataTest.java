package io.github.markusluisflores.frontrow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The demo profile's seed (plan 4B decision 2). There is no schema CHECK for sales_close_at <= starts_at (Markus,
 * 2026-10-28), so this test guards the seeded rows instead (spec §5, §12). Its own context and database: no other test
 * runs with the demo profile, so every other test database starts empty.
 */
@SpringBootTest
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class SeedDataTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void everySeededEventClosesSalesByItsStart() {
        List<String> late = jdbc.sql("SELECT name FROM event WHERE sales_close_at > starts_at")
                .query(String.class)
                .list();
        assertThat(eventCount()).isEqualTo(3);
        assertThat(late).isEmpty();
    }

    @Test
    void everySeededEventIsOnSaleAndOffersEverySeatOfItsVenue() {
        assertThat(jdbc.sql("SELECT DISTINCT status FROM event")
                        .query(String.class)
                        .list())
                .containsExactly("ON_SALE");
        int unoffered = jdbc.sql("""
                        SELECT count(*) FROM event e JOIN seat s ON s.venue_id = e.venue_id
                        LEFT JOIN event_seat es ON es.event_id = e.id AND es.seat_id = s.id
                        WHERE es.id IS NULL""").query(Integer.class).single();
        assertThat(unoffered).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM event_seat")
                        .query(Integer.class)
                        .single())
                .isEqualTo(68);
    }

    private int eventCount() {
        return jdbc.sql("SELECT count(*) FROM event").query(Integer.class).single();
    }
}
