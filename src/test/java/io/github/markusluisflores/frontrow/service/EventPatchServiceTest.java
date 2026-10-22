package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.LockHolder;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class EventPatchServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    EventPatchService patches;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    LockingGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    @Test
    void publishesADraftSoItsSeatsCanBeHeld() {
        long draft = fixtures.event(venueId, "DRAFT", NOW.minusSeconds(60), NOW.plusSeconds(600));
        long seat = fixtures.eventSeats(draft, venueId, 1).getFirst();

        EventPatchResult result = patches.patch(new EventPatch(draft, EventStatus.ON_SALE, null, null));

        assertThat(result.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(result.salesOpenAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(holds.hold(new HoldCommand("alice", draft, List.of(seat), "k1"))
                        .seats())
                .hasSize(1);
    }

    @Test
    void movesTheSalesWindowAndHoldsFollowIt() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();

        EventPatchResult result = patches.patch(new EventPatch(eventId, null, null, NOW));

        assertThat(result.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(result.salesCloseAt()).isEqualTo(NOW);
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", eventId, List.of(seat), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void aWindowThatDoesNotOpenBeforeItClosesIsRefusedAsStored() {
        // 400ns apart, but timestamptz keeps microseconds: stored, these would be equal and break
        // ck_event_sales_window.
        Instant openAt = NOW.plusSeconds(60);
        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, null, openAt, openAt.plusNanos(400))));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "sales_window");
    }

    @Test
    void noPatchMovesAnEventBackToDraft() {
        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, EventStatus.DRAFT, null, null)));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "status");
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
    }

    @Test
    void aCancelledEventIsFinalButCancellingItAgainSucceeds() {
        patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null));

        assertThat(rejection(() -> patches.patch(new EventPatch(eventId, EventStatus.ON_SALE, null, null)))
                        .details())
                .containsEntry("parameter", "status");
        assertThat(rejection(() -> patches.patch(new EventPatch(eventId, null, null, NOW.plusSeconds(1))))
                        .details())
                .containsEntry("parameter", "sales_window");
        assertThat(patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null))
                        .releasedHolds())
                .isZero();
    }

    @Test
    void cancellingReleasesEveryActiveHoldOnTheEventLapsedOrNot() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 3);
        fixtures.hold(seats.get(0), "alice", "ACTIVE", NOW.plusSeconds(600));
        fixtures.hold(seats.get(1), "bob", "ACTIVE", NOW.minusSeconds(600));
        fixtures.hold(seats.get(2), "carol", "EXPIRED", NOW.minusSeconds(600));
        long otherEvent = fixtures.onSaleEvent(venueId);
        fixtures.hold(fixtures.eventSeats(otherEvent, venueId, 1).getFirst(), "dave", "ACTIVE", NOW.plusSeconds(600));

        EventPatchResult result = patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null));

        assertThat(result.releasedHolds()).isEqualTo(2);
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("CANCELLED");
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("RELEASED");
        assertThat(fixtures.statusesOwnedBy("carol")).containsExactly("EXPIRED");
        assertThat(fixtures.statusesOwnedBy("dave")).containsExactly("ACTIVE");
    }

    @Test
    void anEventWithAConfirmedOrderCannotBeCancelled() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult sold = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(0)), "k1"));
        confirms.confirm(new HoldGroupCommand("alice", sold.holdGroupId()));
        holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(1)), "k1"));

        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null)));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "status");
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("ACTIVE");
    }

    @Test
    void aMissingEventIsNotFound() {
        assertThat(rejection(() -> patches.patch(new EventPatch(-1L, EventStatus.CANCELLED, null, null)))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aPatchThatCannotGetTheEventLockAsksForARetry() throws Exception {
        // A hold creation or confirmation in flight holds the event FOR SHARE. The PATCH waits for lock_timeout (5s),
        // then gives up.
        try (LockHolder inFlight = LockHolder.start(pool, tx, jdbc, () -> gateway.lockEventForShare(eventId))) {
            Future<EventPatchResult> patch =
                    pool.submit(() -> patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null)));
            inFlight.awaitSomeoneBlocked();
            Throwable thrown = catchThrowable(() -> patch.get(30, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(((DomainException) thrown.getCause()).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        }
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
    }

    @Test
    void anInstantPostgresCannotStoreIsInvalidRequestNotA500() {
        // Year 300000 is past timestamptz's upper bound: unbounded, Postgres raises 22008, which surfaces as a 500
        // (PR #22 thread 4305094277). Instant.MIN is past the lower bound.
        for (EventPatch outOfRange : List.of(
                new EventPatch(eventId, null, null, Instant.parse("+300000-01-01T00:00:00Z")),
                new EventPatch(eventId, null, Instant.MIN, null),
                new EventPatch(eventId, null, EventPatchService.EARLIEST.minusNanos(1000), null),
                new EventPatch(eventId, null, EventPatchService.LATEST, EventPatchService.LATEST.plusNanos(1000)))) {
            DomainException rejected = rejection(() -> patches.patch(outOfRange));
            assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(rejected.details()).containsEntry("parameter", "sales_window");
        }
    }

    @Test
    void theWidestAllowedWindowIsStoredExactly() {
        EventPatchResult result =
                patches.patch(new EventPatch(eventId, null, EventPatchService.EARLIEST, EventPatchService.LATEST));

        assertThat(result.salesOpenAt()).isEqualTo(EventPatchService.EARLIEST);
        assertThat(result.salesCloseAt()).isEqualTo(EventPatchService.LATEST);
        assertThat(jdbc.sql("SELECT sales_open_at, sales_close_at FROM event WHERE id = :id")
                        .param("id", eventId)
                        .query((rs, row) -> List.of(
                                rs.getObject(1, OffsetDateTime.class).toInstant(),
                                rs.getObject(2, OffsetDateTime.class).toInstant()))
                        .single())
                .containsExactly(EventPatchService.EARLIEST, EventPatchService.LATEST);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
