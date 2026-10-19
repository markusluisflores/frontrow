package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.domain.HoldRequestFingerprint;
import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.Money;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.persistence.StoredHoldRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hold creation, spec §5: one all-or-nothing transaction that takes its locks in the global order — the
 * idempotency key, the owner's advisory lock, the event row, then each seat ascending — and records only success.
 */
@Service
public class HoldService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    /**
     * The stored replay form, deliberately independent of the application's Jackson configuration: a later global
     * setting (a naming strategy, say) must not break replay of rows stored before it (plan 3A decision 6).
     */
    static final JsonMapper STORED_FORM = JsonMapper.builder().build();

    private final LockingGateway gateway;
    private final EventSeatRepository eventSeats;
    private final FrontRowProperties properties;
    private final Clock clock;

    public HoldService(
            LockingGateway gateway, EventSeatRepository eventSeats, FrontRowProperties properties, Clock clock) {
        this.gateway = gateway;
        this.eventSeats = eventSeats;
        this.properties = properties;
        this.clock = clock;
    }

    /** READ COMMITTED, explicitly: every check after a lock wait must see what committed during the wait (spec §5). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HoldResult hold(HoldCommand command) {
        requireWellFormed(command);
        // On the raw list, before the fingerprint and the key claim: an oversized request costs no sort and no lock.
        if (command.eventSeatIds().size() > properties.maxSeatsPerHold()) {
            throw new DomainException(ErrorCode.TOO_MANY_SEATS, Map.of("max_seats", properties.maxSeatsPerHold()));
        }
        // hold_request.created_at records when the request arrived, and only the 24-hour purge reads it (spec §5).
        // timestamptz keeps microseconds, so every instant is truncated once, where it is read.
        Instant arrivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = HoldRequestFingerprint.of(command.eventId(), command.eventSeatIds());
        try {
            if (!gateway.claimIdempotencyKey(command.owner(), command.idempotencyKey(), fingerprint, arrivedAt)) {
                return replay(command, fingerprint);
            }
            gateway.lockOwner(command.owner());
            EventRow event = gateway.lockEventForShare(command.eventId())
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
            // Every decision and every other timestamp uses one instant, read once the event lock is held: a request
            // that waited across sales_close_at is refused, and expires_at is not short by the wait (spec §5).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            SalesGate.requireOpen(event, now);
            List<Long> seatIds = distinctAscending(command.eventSeatIds());
            Map<Long, EventSeat> offered = offeredSeats(event.id(), seatIds);
            requireUnderGroupCap(command.owner(), now);

            UUID groupId = UUID.randomUUID();
            // Truncated again in case a configured TTL carries sub-microsecond precision.
            Instant expiresAt = now.plus(properties.holdTtl()).truncatedTo(ChronoUnit.MICROS);
            claimSeats(seatIds, groupId, command.owner(), expiresAt, now);

            HoldResult result = result(groupId, event, seatIds, offered, expiresAt);
            gateway.recordHoldResponse(
                    command.owner(), command.idempotencyKey(), groupId, STORED_FORM.writeValueAsString(result));
            return result;
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    /** Each seat is finished — lazy expiry, then insert — before the next one is touched (spec §5 step 4). */
    private void claimSeats(List<Long> seatIds, UUID groupId, String owner, Instant expiresAt, Instant now) {
        for (long seatId : seatIds) {
            try {
                gateway.lockActiveHoldForSeat(seatId)
                        .filter(hold -> !HoldRules.isLive(hold.status(), hold.expiresAt(), now))
                        .ifPresent(lapsed -> gateway.expireHold(lapsed.id(), now));
                // A live hold is left alone: this insert then fails on uq_claimed_seat, where seat_taken is decided.
                gateway.insertHold(seatId, groupId, owner, expiresAt, now);
            } catch (DataAccessException failure) {
                throw SqlFailures.translate(failure, seatId);
            }
        }
    }

    private HoldResult replay(HoldCommand command, String fingerprint) {
        StoredHoldRequest stored = gateway.findHoldRequest(command.owner(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("a conflicting hold_request row was not found"));
        if (!stored.requestHash().equals(fingerprint)) {
            throw DomainException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return STORED_FORM.readValue(stored.responseJson(), HoldResult.class);
    }

    private List<Long> distinctAscending(List<Long> requested) {
        Set<Long> seen = new HashSet<>();
        Set<Long> duplicates = new TreeSet<>();
        for (Long id : requested) {
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        if (!duplicates.isEmpty()) {
            throw invalidSeats(duplicates);
        }
        return new ArrayList<>(new TreeSet<>(seen));
    }

    private Map<Long, EventSeat> offeredSeats(long eventId, List<Long> seatIds) {
        Map<Long, EventSeat> offered = new HashMap<>();
        for (EventSeat seat : eventSeats.findOfferedSeats(eventId, seatIds)) {
            offered.put(seat.getId(), seat);
        }
        Set<Long> missing = new TreeSet<>(seatIds);
        missing.removeAll(offered.keySet());
        if (!missing.isEmpty()) {
            throw invalidSeats(missing);
        }
        return offered;
    }

    private void requireUnderGroupCap(String owner, Instant now) {
        if (gateway.countActiveHoldGroups(owner, now) >= properties.maxActiveHoldGroups()) {
            throw new DomainException(
                    ErrorCode.HOLD_LIMIT_EXCEEDED, Map.of("max_active_hold_groups", properties.maxActiveHoldGroups()));
        }
    }

    private static HoldResult result(
            UUID groupId, EventRow event, List<Long> seatIds, Map<Long, EventSeat> offered, Instant expiresAt) {
        List<HoldResult.HeldSeat> seats = new ArrayList<>();
        Money total = Money.ofCents(0, event.currency());
        for (long seatId : seatIds) {
            long price = offered.get(seatId).getPriceCents();
            seats.add(new HoldResult.HeldSeat(seatId, price));
            total = total.plus(Money.ofCents(price, event.currency()));
        }
        return new HoldResult(groupId, event.id(), seats, total.cents(), total.currency(), expiresAt);
    }

    private static void requireWellFormed(HoldCommand command) {
        String key = command.idempotencyKey();
        // Postgres text cannot hold U+0000: binding it fails with 22021, a 500 rather than invalid_request.
        // pgjdbc's String.getBytes(UTF_8) would silently turn an unpaired surrogate into '?', aliasing a different key.
        if (key == null
                || key.isBlank()
                || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || key.indexOf('\u0000') >= 0
                || !isWellFormedUtf16(key)) {
            throw invalidParameter("idempotency_key");
        }
        if (command.eventSeatIds().isEmpty()) {
            throw invalidParameter("event_seat_ids");
        }
    }

    private static boolean isWellFormedUtf16(String s) {
        return s.equals(new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    private static DomainException invalidParameter(String parameter) {
        return new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }

    private static DomainException invalidSeats(Set<Long> seatIds) {
        return new DomainException(
                ErrorCode.INVALID_REQUEST, Map.of("parameter", "event_seat_ids", "seat_ids", List.copyOf(seatIds)));
    }
}
