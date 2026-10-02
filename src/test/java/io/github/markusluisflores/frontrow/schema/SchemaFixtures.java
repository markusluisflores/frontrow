package io.github.markusluisflores.frontrow.schema;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Row inserts for schema tests. Times are fixed, never the database clock (spec §4). */
final class SchemaFixtures {

    static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private final JdbcTemplate jdbc;

    SchemaFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long venue(String name) {
        return jdbc.queryForObject("INSERT INTO venue (name) VALUES (?) RETURNING id", Long.class, name);
    }

    long seat(long venueId, String section, String row, int number) {
        return jdbc.queryForObject(
                "INSERT INTO seat (venue_id, section, row_label, seat_number) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                venueId,
                section,
                row,
                number);
    }

    long event(long venueId, String status) {
        return jdbc.queryForObject(
                """
                INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                VALUES (?, 'Test event', ?, ?, ?, ?, 'CAD') RETURNING id""",
                Long.class,
                venueId,
                ts(NOW.plus(30, ChronoUnit.DAYS)),
                ts(NOW.minus(1, ChronoUnit.DAYS)),
                ts(NOW.plus(29, ChronoUnit.DAYS)),
                status);
    }

    long eventSeat(long eventId, long seatId, long venueId) {
        return jdbc.queryForObject(
                "INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents) VALUES (?, ?, ?, 5000) RETURNING id",
                Long.class,
                eventId,
                seatId,
                venueId);
    }

    void hold(long eventSeatId, UUID groupId, String owner, String status) {
        jdbc.update("""
                INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""", eventSeatId, groupId, owner, status, ts(NOW.plus(10, ChronoUnit.MINUTES)), ts(NOW));
    }

    long order(long eventId, UUID groupId, String owner) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                VALUES (?, ?, ?, 'CONFIRMED', 5000, 'CAD', ?) RETURNING id""", Long.class, eventId, groupId, owner, ts(NOW));
    }

    void orderLine(long orderId, long eventSeatId) {
        jdbc.update(
                "INSERT INTO order_line (order_id, event_seat_id, price_cents) VALUES (?, ?, 5000)",
                orderId,
                eventSeatId);
    }

    static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    /** Constraint name from the driver's structured error, never from message text (spec §5). */
    static String constraintName(Throwable thrown) {
        PSQLException psql = findPsqlException(thrown);
        return psql == null || psql.getServerErrorMessage() == null
                ? null
                : psql.getServerErrorMessage().getConstraint();
    }

    static String sqlState(Throwable thrown) {
        PSQLException psql = findPsqlException(thrown);
        return psql == null ? null : psql.getSQLState();
    }

    private static PSQLException findPsqlException(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                return psql;
            }
            if (t instanceof BatchUpdateException batch) {
                for (SQLException next = batch.getNextException(); next != null; next = next.getNextException()) {
                    if (next instanceof PSQLException psql) {
                        return psql;
                    }
                }
            }
        }
        return null;
    }
}
