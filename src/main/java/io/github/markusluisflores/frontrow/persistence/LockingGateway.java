package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every lock in the system, taken through explicit SQL in the one global order spec §5 defines: the hold_request key,
 * the per-owner advisory lock, the event row, then seat_hold rows ascending by event_seat_id.
 *
 * <p>These reads deliberately bypass the JPA persistence context. A locking query must return the row as currently
 * committed, not an entity Hibernate already cached, and lazy expiry must reach the database before the insert that
 * depends on it — Hibernate flushes inserts before updates (spec §5).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LockingGateway {

    private static final String EVENT_COLUMNS = "id, venue_id, status, sales_open_at, sales_close_at, currency";
    private static final String HOLD_COLUMNS = "id, event_seat_id, hold_group_id, owner, status, expires_at";

    private final JdbcClient jdbc;
    private final FrontRowProperties properties;

    public LockingGateway(JdbcClient jdbc, FrontRowProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Bounds every lock wait in this transaction. The value is bounded configuration (1 ms to 1 minute, enforced on
     * {@code FrontRowProperties}), so zero, which Postgres reads as "no timeout", cannot reach it. Used by the event PATCH path, where a steady stream of FOR SHARE
     * holders could otherwise starve a FOR UPDATE indefinitely (spec §5, Liveness). Postgres does not accept bind
     * parameters in SET, so the validated, bounded configuration value is formatted as a literal; no request data reaches it.
     */
    public void setLockTimeout() {
        long millis = properties.lockTimeout().toMillis();
        jdbc.sql("SET LOCAL lock_timeout = '" + millis + "ms'").update();
    }

    /** Step 2 of the lock order: serializes one owner's concurrent hold creations so the cap cannot be raced. */
    public void lockOwner(String owner) {
        // Wrapped in a SELECT 1 because pg_advisory_xact_lock returns void, and a void column's mapped value is not
        // a thing worth depending on.
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) locked")
                .param("key", AdvisoryLockKey.forOwner(owner))
                .query(Integer.class)
                .single();
    }

    /** Step 3, shared: hold creation and confirmation. Blocks a concurrent event PATCH without blocking each other. */
    public Optional<EventRow> lockEventForShare(long eventId) {
        return lockEvent(eventId, "FOR SHARE");
    }

    /** Step 3, exclusive: every event PATCH, including cancellation. */
    public Optional<EventRow> lockEventForUpdate(long eventId) {
        return lockEvent(eventId, "FOR UPDATE");
    }

    private Optional<EventRow> lockEvent(long eventId, String lockClause) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM event WHERE id = :id " + lockClause)
                .param("id", eventId)
                .query((rs, rowNum) -> new EventRow(
                        rs.getLong("id"),
                        rs.getLong("venue_id"),
                        EventStatus.valueOf(rs.getString("status")),
                        instantAt(rs, "sales_open_at"),
                        instantAt(rs, "sales_close_at"),
                        rs.getString("currency")))
                .optional();
    }

    /** Step 4, one seat: the ACTIVE hold on a seat, if any, locked so lazy expiry can decide on the committed row. */
    public Optional<HoldRow> lockActiveHoldForSeat(long eventSeatId) {
        return jdbc.sql("SELECT " + HOLD_COLUMNS
                        + " FROM seat_hold WHERE event_seat_id = :seat AND status = 'ACTIVE' FOR UPDATE")
                .param("seat", eventSeatId)
                .query(LockingGateway::mapHold)
                .optional();
    }

    /**
     * Lazy expiry, as an immediate SQL UPDATE (spec §5). Returns true when this call expired the row; false means it
     * was no longer ACTIVE, which is a legal race outcome, not an error.
     */
    public boolean expireHold(long holdId, Instant now) {
        return jdbc.sql("UPDATE seat_hold SET status = 'EXPIRED'"
                                + " WHERE id = :id AND status = 'ACTIVE' AND expires_at <= :now")
                        .param("id", holdId)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** Step 4, a whole group: ascending event_seat_id, which is what makes confirm and release deadlock-free. */
    public List<HoldRow> lockGroupRows(UUID holdGroupId, String owner) {
        return jdbc.sql("SELECT " + HOLD_COLUMNS
                        + " FROM seat_hold WHERE hold_group_id = :group AND owner = :owner"
                        + " ORDER BY event_seat_id FOR UPDATE")
                .param("group", holdGroupId)
                .param("owner", owner)
                .query(LockingGateway::mapHold)
                .list();
    }

    /** The per-owner cap counts groups with at least one live row, so lapsed-but-unswept groups never count (spec §4). */
    public int countActiveHoldGroups(String owner, Instant now) {
        return jdbc.sql("""
                        SELECT count(DISTINCT hold_group_id) FROM seat_hold
                        WHERE owner = :owner AND status = 'ACTIVE' AND expires_at > :now""")
                .param("owner", owner)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    private static HoldRow mapHold(ResultSet rs, int rowNum) throws SQLException {
        return new HoldRow(
                rs.getLong("id"),
                rs.getLong("event_seat_id"),
                rs.getObject("hold_group_id", UUID.class),
                rs.getString("owner"),
                HoldStatus.valueOf(rs.getString("status")),
                instantAt(rs, "expires_at"));
    }

    /**
     * pgjdbc 42.7.13 has no Instant branch in getObject(column, Class) — OffsetDateTime is the supported
     * offset-carrying type, and converting from it needs no zone assumption.
     */
    private static Instant instantAt(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
