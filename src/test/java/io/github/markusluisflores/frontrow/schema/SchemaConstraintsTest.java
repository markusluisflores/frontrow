package io.github.markusluisflores.frontrow.schema;

import static io.github.markusluisflores.frontrow.schema.SchemaFixtures.constraintName;
import static io.github.markusluisflores.frontrow.schema.SchemaFixtures.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every constraint spec §5 names gets a violating-row test (spec §12). Each test does its setup, then exactly one
 * violating statement last, because Postgres aborts the transaction on the first error. Every test rolls back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintsTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    @Autowired
    JdbcTemplate jdbc;

    SchemaFixtures db;
    long venueId;
    long eventId;
    long eventSeatId;

    @BeforeEach
    void seed() {
        db = new SchemaFixtures(jdbc);
        venueId = db.venue("Main Hall");
        long seatId = db.seat(venueId, "Floor", "A", 1);
        eventId = db.event(venueId, "ON_SALE");
        eventSeatId = db.eventSeat(eventId, seatId, venueId);
    }

    // --- uq_claimed_seat (ADR-001): at most one ACTIVE or CONVERTED hold per seat ---

    @Test
    void secondActiveHoldOnASeatIsRejected() {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", "ACTIVE");
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "ACTIVE"), UNIQUE_VIOLATION, "uq_claimed_seat");
    }

    @Test
    void soldSeatCannotBeHeldAgain() {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", "CONVERTED");
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "ACTIVE"), UNIQUE_VIOLATION, "uq_claimed_seat");
    }

    @Test
    void activeHoldBlocksInsertingAConvertedHold() {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", "ACTIVE");
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "CONVERTED"), UNIQUE_VIOLATION, "uq_claimed_seat");
    }

    @ParameterizedTest
    @CsvSource({"EXPIRED", "RELEASED"})
    void endedHoldsDoNotBlockANewHold(String endedStatus) {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", endedStatus);
        assertThatCode(() -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "ACTIVE"))
                .doesNotThrowAnyException();
    }

    // --- uq_sold_once: a seat is on at most one order line ---

    @Test
    void seatCannotBeSoldOnTwoOrderLines() {
        long first = db.order(eventId, UUID.randomUUID(), "alice");
        long second = db.order(eventId, UUID.randomUUID(), "bob");
        db.orderLine(first, eventSeatId);
        assertViolation(() -> db.orderLine(second, eventSeatId), UNIQUE_VIOLATION, "uq_sold_once");
    }

    // --- uq_order_hold_group: one order per hold group ---

    @Test
    void holdGroupCannotHaveTwoOrders() {
        UUID group = UUID.randomUUID();
        db.order(eventId, group, "alice");
        assertViolation(() -> db.order(eventId, group, "alice"), UNIQUE_VIOLATION, "uq_order_hold_group");
    }

    // --- composite FKs: an event only offers seats from its own venue ---

    @Test
    void eventCannotOfferASeatFromAnotherVenue() {
        long otherVenue = db.venue("Annex");
        long foreignSeat = db.seat(otherVenue, "Floor", "A", 1);
        assertViolation(() -> db.eventSeat(eventId, foreignSeat, venueId), FK_VIOLATION, "fk_event_seat_seat_venue");
    }

    @Test
    void eventSeatCannotClaimTheWrongVenueForItsEvent() {
        long otherVenue = db.venue("Annex");
        long otherSeat = db.seat(otherVenue, "Floor", "B", 1);
        assertViolation(() -> db.eventSeat(eventId, otherSeat, otherVenue), FK_VIOLATION, "fk_event_seat_event_venue");
    }

    @Test
    void eventCannotOfferTheSameSeatTwice() {
        long seatId = jdbc.queryForObject("SELECT seat_id FROM event_seat WHERE id = ?", Long.class, eventSeatId);
        assertViolation(() -> db.eventSeat(eventId, seatId, venueId), UNIQUE_VIOLATION, "uq_event_seat");
    }

    // --- pk_hold_request: an idempotency key is unique per owner ---

    @Test
    void idempotencyKeyIsUniquePerOwner() {
        insertHoldRequest("alice", "key-1");
        assertViolation(() -> insertHoldRequest("alice", "key-1"), UNIQUE_VIOLATION, "pk_hold_request");
    }

    @Test
    void sameIdempotencyKeyIsAllowedForDifferentOwners() {
        insertHoldRequest("alice", "key-1");
        assertThatCode(() -> insertHoldRequest("bob", "key-1")).doesNotThrowAnyException();
    }

    // --- additions beyond spec §5 (plan-level): value checks and seat position ---

    @Test
    void seatPositionIsUniqueWithinAVenue() {
        assertViolation(() -> db.seat(venueId, "Floor", "A", 1), UNIQUE_VIOLATION, "uq_seat_position");
    }

    @Test
    void unknownHoldStatusIsRejected() {
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "alice", "PENDING"),
                CHECK_VIOLATION,
                "ck_seat_hold_status");
    }

    @Test
    void unknownEventStatusIsRejected() {
        assertViolation(() -> db.event(venueId, "SOLD_OUT"), CHECK_VIOLATION, "ck_event_status");
    }

    @Test
    void salesWindowMustOpenBeforeItCloses() {
        assertViolation(
                () -> jdbc.update(
                        """
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (?, 'Backwards', ?, ?, ?, 'DRAFT', 'CAD')""",
                        venueId,
                        SchemaFixtures.ts(SchemaFixtures.NOW),
                        SchemaFixtures.ts(SchemaFixtures.NOW),
                        SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_event_sales_window");
    }

    @Test
    void negativePriceIsRejected() {
        long seatId = db.seat(venueId, "Floor", "A", 2);
        assertViolation(
                () -> jdbc.update(
                        "INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents) VALUES (?, ?, ?, -1)",
                        eventId,
                        seatId,
                        venueId),
                CHECK_VIOLATION,
                "ck_event_seat_price");
    }

    @Test
    void currencyMustBeAThreeLetterCode() {
        assertViolation(
                () -> jdbc.update("""
                        INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                        VALUES (?, ?, 'alice', 'CONFIRMED', 0, 'cad', ?)""", eventId, UUID.randomUUID(), SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_ticket_order_currency");
    }

    @Test
    void eventCurrencyMustBeAThreeLetterCode() {
        assertViolation(
                () -> jdbc.update(
                        """
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (?, 'Lowercase', ?, ?, ?, 'DRAFT', 'cad')""",
                        venueId,
                        SchemaFixtures.ts(SchemaFixtures.NOW),
                        SchemaFixtures.ts(SchemaFixtures.NOW),
                        SchemaFixtures.ts(SchemaFixtures.NOW.plusSeconds(60))),
                CHECK_VIOLATION,
                "ck_event_currency");
    }

    @Test
    void negativeOrderTotalIsRejected() {
        assertViolation(
                () -> jdbc.update("""
                        INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                        VALUES (?, ?, 'alice', 'CONFIRMED', -1, 'CAD', ?)""", eventId, UUID.randomUUID(), SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_ticket_order_total");
    }

    @Test
    void negativeOrderLinePriceIsRejected() {
        long orderId = db.order(eventId, UUID.randomUUID(), "alice");
        assertViolation(
                () -> jdbc.update(
                        "INSERT INTO order_line (order_id, event_seat_id, price_cents) VALUES (?, ?, -1)",
                        orderId,
                        eventSeatId),
                CHECK_VIOLATION,
                "ck_order_line_price");
    }

    @Test
    void unknownOrderStatusIsRejected() {
        assertViolation(
                () -> jdbc.update("""
                        INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                        VALUES (?, ?, 'alice', 'REFUNDED', 0, 'CAD', ?)""", eventId, UUID.randomUUID(), SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_ticket_order_status");
    }

    private void insertHoldRequest(String owner, String key) {
        jdbc.update(
                "INSERT INTO hold_request (owner, idempotency_key, request_hash, created_at) VALUES (?, ?, 'h', ?)",
                owner,
                key,
                SchemaFixtures.ts(SchemaFixtures.NOW));
    }

    private static void assertViolation(Executable statement, String expectedSqlState, String expectedConstraint) {
        Throwable thrown = catchThrowable(statement::execute);
        assertThat(thrown)
                .as("expected %s to reject the row", expectedConstraint)
                .isNotNull();
        assertThat(sqlState(thrown)).as("SQLState").isEqualTo(expectedSqlState);
        assertThat(constraintName(thrown)).as("constraint name").isEqualTo(expectedConstraint);
    }
}
