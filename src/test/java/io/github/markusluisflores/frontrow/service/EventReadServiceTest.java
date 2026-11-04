package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class EventReadServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    EventReadService reads;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;
    long owls;
    long draft;
    long cancelled;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        owls = fixtures.namedEvent(venueId, "Night Owls Live", "ON_SALE", Instant.parse("2026-11-10T19:00:00Z"));
        fixtures.namedEvent(venueId, "Night owls 100% Unplugged", "ON_SALE", Instant.parse("2026-11-12T19:00:00Z"));
        fixtures.namedEvent(venueId, "Daybreak", "ON_SALE", Instant.parse("2026-12-01T10:00:00Z"));
        draft = fixtures.namedEvent(venueId, "Secret Draft Owls", "DRAFT", Instant.parse("2026-11-11T19:00:00Z"));
        cancelled = fixtures.namedEvent(venueId, "Cancelled Owls", "CANCELLED", Instant.parse("2026-11-11T20:00:00Z"));
    }

    @Test
    void searchListsOnlyOnSaleEventsSoonestFirst() {
        assertThat(names(new EventQuery(null, null, null, null)))
                .containsExactly("Night Owls Live", "Night owls 100% Unplugged", "Daybreak");
    }

    @Test
    void searchMatchesTheNameCaseInsensitivelyAndLiterally() {
        assertThat(names(new EventQuery("OWLS", null, null, null)))
                .containsExactly("Night Owls Live", "Night owls 100% Unplugged");
        // %, _ and the escape character ! are characters to match, not wildcards. Each empty result below would match
        // a name if that character went to LIKE unescaped.
        assertThat(names(new EventQuery("100%", null, null, null))).containsExactly("Night owls 100% Unplugged");
        assertThat(names(new EventQuery("owls%live", null, null, null))).isEmpty();
        assertThat(names(new EventQuery("_", null, null, null))).isEmpty();
        assertThat(names(new EventQuery("owl!s", null, null, null))).isEmpty();
    }

    @Test
    void searchLowerCasesTheQueryAndTheNameWithOneEngine() {
        // Java lower-cases a word-final Σ to ς; Postgres's lower() gives σ. If the two sides were lowered by different
        // engines, the exact name would not find itself.
        fixtures.namedEvent(venueId, "ΟΔΟΣ", "ON_SALE", Instant.parse("2027-02-01T19:00:00Z"));
        assertThat(names(new EventQuery("ΟΔΟΣ", null, null, null))).containsExactly("ΟΔΟΣ");
    }

    @Test
    void searchFiltersOnTheStartDateWithBothEndsInclusive() {
        LocalDate twelfth = LocalDate.parse("2026-11-12");
        assertThat(names(new EventQuery(null, twelfth, twelfth, null))).containsExactly("Night owls 100% Unplugged");
        assertThat(names(new EventQuery(null, LocalDate.parse("2026-11-11"), null, null)))
                .containsExactly("Night owls 100% Unplugged", "Daybreak");
        // Dates no timestamptz can hold are clamped to the storable range, never sent to SQL.
        assertThat(names(new EventQuery(null, LocalDate.MIN, LocalDate.MAX, null)))
                .hasSize(3);
    }

    @Test
    void searchLimitDefaultsToTenAndIsBoundedToFifty() {
        for (int i = 0; i < 10; i++) {
            fixtures.namedEvent(venueId, "Extra " + i, "ON_SALE", Instant.parse("2027-01-01T00:00:00Z"));
        }
        assertThat(reads.search(new EventQuery(null, null, null, null))).hasSize(10);
        assertThat(reads.search(new EventQuery(null, null, null, 2))).hasSize(2);
        assertThat(reads.search(new EventQuery(null, null, null, 50))).hasSize(13);
        assertInvalid(() -> reads.search(new EventQuery(null, null, null, 0)), "limit");
        assertInvalid(() -> reads.search(new EventQuery(null, null, null, 51)), "limit");
    }

    @Test
    void aNulInTheSearchQueryIsInvalidNotAServerError() {
        assertInvalid(() -> reads.search(new EventQuery("a\u0000b", null, null, null)), "query");
    }

    @Test
    void onlyAnOnSaleEventIsReadableById() {
        assertThat(rejection(() -> reads.event(draft)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.availability(draft, null)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.event(draft + 1000)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.event(cancelled)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.availability(cancelled, null)).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.availability(draft + 1000, null)).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(reads.event(owls).venueName()).isEqualTo("Fixture Hall");
    }

    @Test
    void availabilitySaysWhetherEachSeatIsClaimedNow() {
        List<Long> seats = fixtures.eventSeats(owls, venueId, 4);
        fixtures.hold(seats.get(0), "bob", "ACTIVE", NOW.plusSeconds(60));
        fixtures.hold(seats.get(1), "alice", "ACTIVE", NOW.minusSeconds(1));
        fixtures.hold(seats.get(2), "alice", "CONVERTED", NOW.minusSeconds(1));

        Availability availability = reads.availability(owls, null);

        assertThat(availability.eventId()).isEqualTo(owls);
        assertThat(availability.currency()).isEqualTo("CAD");
        assertThat(availability.seats())
                .extracting(Availability.SeatState::eventSeatId, Availability.SeatState::available)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(seats.get(0), false),
                        org.assertj.core.groups.Tuple.tuple(seats.get(1), true),
                        org.assertj.core.groups.Tuple.tuple(seats.get(2), false),
                        org.assertj.core.groups.Tuple.tuple(seats.get(3), true));
        assertThat(reads.availability(owls, "Floor").seats()).hasSize(4);
        assertThat(reads.availability(owls, "Balcony").seats()).isEmpty();
    }

    @Test
    void theAvailabilityReadCarriesNoHoldOrOwnerField() {
        // Spec §6 and §12: availability shows whether a seat is free, never whose hold it is.
        assertThat(componentNames(Availability.class)).containsExactly("eventId", "currency", "seats");
        assertThat(componentNames(Availability.SeatState.class))
                .containsExactly("eventSeatId", "section", "rowLabel", "seatNumber", "priceCents", "available");
    }

    private List<String> names(EventQuery query) {
        return reads.search(query).stream().map(EventDetail::name).toList();
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static void assertInvalid(ThrowingCallable call, String parameter) {
        DomainException rejected = rejection(call);
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", parameter);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
