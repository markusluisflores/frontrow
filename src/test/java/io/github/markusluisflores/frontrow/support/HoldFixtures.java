package io.github.markusluisflores.frontrow.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Seeds and reads rows for service tests through plain SQL, never through the service under test. */
public final class HoldFixtures {

    public static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    public static final long PRICE_CENTS = 5000;

    private final JdbcClient jdbc;
    private final AtomicInteger seatNumbers = new AtomicInteger();

    public HoldFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void truncateAll() {
        jdbc.sql("TRUNCATE order_line, ticket_order, hold_request, seat_hold, event_seat, event, seat, venue")
                .update();
    }

    public long venue() {
        return jdbc.sql("INSERT INTO venue (name) VALUES ('Fixture Hall') RETURNING id")
                .query(Long.class)
                .single();
    }

    public long event(long venueId, String status, Instant salesOpenAt, Instant salesCloseAt) {
        return jdbc.sql("""
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (:venue, 'Fixture event', :starts, :open, :close, :status, 'CAD') RETURNING id""")
                .param("venue", venueId)
                .param("starts", utc(NOW.plus(30, ChronoUnit.DAYS)))
                .param("open", utc(salesOpenAt))
                .param("close", utc(salesCloseAt))
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public long onSaleEvent(long venueId) {
        return event(venueId, "ON_SALE", NOW.minus(1, ChronoUnit.DAYS), NOW.plus(29, ChronoUnit.DAYS));
    }

    /** Fresh seats, each offered by the event at PRICE_CENTS, returned in ascending id order. */
    public List<Long> eventSeats(long eventId, long venueId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seatId = jdbc.sql("""
                            INSERT INTO seat (venue_id, section, row_label, seat_number)
                            VALUES (:venue, 'Floor', 'A', :number) RETURNING id""")
                    .param("venue", venueId)
                    .param("number", seatNumbers.incrementAndGet())
                    .query(Long.class)
                    .single();
            ids.add(jdbc.sql("""
                            INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
                            VALUES (:event, :seat, :venue, :price) RETURNING id""")
                    .param("event", eventId)
                    .param("seat", seatId)
                    .param("venue", venueId)
                    .param("price", PRICE_CENTS)
                    .query(Long.class)
                    .single());
        }
        return ids;
    }

    public void hold(long eventSeatId, String owner, String status, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                        VALUES (:seat, :group, :owner, :status, :expires, :created)""")
                .param("seat", eventSeatId)
                .param("group", UUID.randomUUID())
                .param("owner", owner)
                .param("status", status)
                .param("expires", utc(expiresAt))
                .param("created", utc(NOW))
                .update();
    }

    /** Rows on a seat that count as claimed: ACTIVE or CONVERTED, the claim index's predicate. */
    public int claimsOn(long eventSeatId) {
        return jdbc.sql(
                        "SELECT count(*) FROM seat_hold WHERE event_seat_id = :seat AND status IN ('ACTIVE', 'CONVERTED')")
                .param("seat", eventSeatId)
                .query(Integer.class)
                .single();
    }

    public List<Long> seatsInGroup(UUID holdGroupId) {
        return jdbc.sql("SELECT event_seat_id FROM seat_hold WHERE hold_group_id = :group ORDER BY event_seat_id")
                .param("group", holdGroupId)
                .query(Long.class)
                .list();
    }

    public List<String> statusesOwnedBy(String owner) {
        return jdbc.sql("SELECT status FROM seat_hold WHERE owner = :owner ORDER BY event_seat_id")
                .param("owner", owner)
                .query(String.class)
                .list();
    }

    public int groupsOwnedBy(String owner) {
        return jdbc.sql("SELECT count(DISTINCT hold_group_id) FROM seat_hold WHERE owner = :owner")
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    public int holdRequestsOwnedBy(String owner) {
        return jdbc.sql("SELECT count(*) FROM hold_request WHERE owner = :owner")
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    public String storedResponse(String owner, String idempotencyKey) {
        return jdbc.sql("SELECT response_json::text FROM hold_request WHERE owner = :owner AND idempotency_key = :key")
                .param("owner", owner)
                .param("key", idempotencyKey)
                .query(String.class)
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
