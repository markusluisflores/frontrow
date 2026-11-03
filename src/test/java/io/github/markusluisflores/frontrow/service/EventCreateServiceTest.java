package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class EventCreateServiceTest {

    private static final Instant STARTS = Instant.parse("2030-06-14T19:00:00Z");
    private static final Instant OPENS = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-06-14T17:00:00Z");
    private static final Map<String, Long> PRICES = Map.of("Floor", 6500L, "Balcony", 4500L);

    @Autowired
    EventCreateService creations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        fixtures.seats(venueId, "Floor", 3);
        fixtures.seats(venueId, "Balcony", 2);
    }

    @Test
    void createsADraftThatOffersEverySeatAtItsSectionPrice() {
        EventDetail created = creations.create(draft(STARTS, OPENS, CLOSES, PRICES));

        assertThat(created.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(created.venueId()).isEqualTo(venueId);
        assertThat(created.venueName()).isEqualTo("Fixture Hall");
        assertThat(created.startsAt()).isEqualTo(STARTS);
        assertThat(created.salesCloseAt()).isEqualTo(CLOSES);
        assertThat(jdbc.sql("""
                        SELECT s.section, count(*) AS seats, min(es.price_cents) AS low, max(es.price_cents) AS high
                        FROM event_seat es JOIN seat s ON s.id = es.seat_id
                        WHERE es.event_id = :event GROUP BY s.section ORDER BY s.section""")
                        .param("event", created.eventId())
                        .query((rs, row) -> rs.getString("section") + " " + rs.getInt("seats") + " " + rs.getLong("low")
                                + " " + rs.getLong("high"))
                        .list())
                .containsExactly("Balcony 2 4500 4500", "Floor 3 6500 6500");
    }

    @Test
    void theWindowMayCloseAtTheStartButNotAfterIt() {
        assertThat(creations.create(draft(STARTS, OPENS, STARTS, PRICES)).salesCloseAt())
                .isEqualTo(STARTS);
        assertInvalid(() -> creations.create(draft(STARTS, OPENS, STARTS.plusNanos(1000), PRICES)), "sales_window");
        assertInvalid(() -> creations.create(draft(STARTS, CLOSES, CLOSES, PRICES)), "sales_window");
    }

    @Test
    void anInstantPostgresCannotStoreNamesItsParameter() {
        assertInvalid(
                () -> creations.create(draft(EventInstants.EARLIEST.minusNanos(1000), OPENS, CLOSES, PRICES)),
                "starts_at");
        assertInvalid(
                () -> creations.create(draft(Instant.parse("+300000-01-01T00:00:00Z"), OPENS, CLOSES, PRICES)),
                "starts_at");
        assertInvalid(() -> creations.create(draft(STARTS, Instant.MIN, CLOSES, PRICES)), "sales_window");
        assertInvalid(
                () -> creations.create(
                        draft(EventInstants.LATEST, OPENS, EventInstants.LATEST.plusNanos(1000), PRICES)),
                "sales_window");
        assertThat(eventCount()).isZero();
    }

    @Test
    void theWidestStorableEventIsStoredExactly() {
        EventDetail created =
                creations.create(draft(EventInstants.LATEST, EventInstants.EARLIEST, EventInstants.LATEST, PRICES));

        assertThat(jdbc.sql("SELECT starts_at, sales_open_at, sales_close_at FROM event WHERE id = :id")
                        .param("id", created.eventId())
                        .query((rs, row) -> List.of(
                                rs.getObject(1, OffsetDateTime.class).toInstant(),
                                rs.getObject(2, OffsetDateTime.class).toInstant(),
                                rs.getObject(3, OffsetDateTime.class).toInstant()))
                        .single())
                .containsExactly(EventInstants.LATEST, EventInstants.EARLIEST, EventInstants.LATEST);
    }

    @Test
    void thePricesMustCoverExactlyTheVenuesSections() {
        for (Map<String, Long> prices : List.of(
                Map.of("Floor", 6500L),
                Map.of("Floor", 6500L, "Balcony", 4500L, "Box", 9000L),
                Map.of("Floor", 6500L, "Balcony", -1L))) {
            assertInvalid(() -> creations.create(draft(STARTS, OPENS, CLOSES, prices)), "prices");
        }
        assertInvalid(() -> creations.create(draft(STARTS, OPENS, CLOSES, null)), "prices");
        assertThat(eventCount()).isZero();
    }

    @Test
    void eachMissingOrMalformedFieldIsNamed() {
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, " ", STARTS, OPENS, CLOSES, "CAD", PRICES)), "name");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, null, STARTS, OPENS, CLOSES, "CAD", PRICES)), "name");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, "Gig", STARTS, OPENS, CLOSES, "cad", PRICES)),
                "currency");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, "Gig", null, OPENS, CLOSES, "CAD", PRICES)),
                "starts_at");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, "Gig", STARTS, null, CLOSES, "CAD", PRICES)),
                "sales_window");
        assertInvalid(
                () -> creations.create(new EventDraft(null, "Gig", STARTS, OPENS, CLOSES, "CAD", PRICES)), "venue_id");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId + 1000, "Gig", STARTS, OPENS, CLOSES, "CAD", PRICES)),
                "venue_id");
        assertThat(eventCount()).isZero();
    }

    private EventDraft draft(Instant startsAt, Instant openAt, Instant closeAt, Map<String, Long> prices) {
        return new EventDraft(venueId, "Night Owls Live", startsAt, openAt, closeAt, "CAD", prices);
    }

    private int eventCount() {
        return jdbc.sql("SELECT count(*) FROM event").query(Integer.class).single();
    }

    private static void assertInvalid(ThrowingCallable call, String parameter) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        DomainException rejected = (DomainException) thrown;
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", parameter);
    }
}
