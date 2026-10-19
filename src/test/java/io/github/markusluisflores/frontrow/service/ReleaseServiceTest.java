package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ReleaseServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    ReleaseService releases;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

    HoldFixtures fixtures;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @Test
    void releasesALiveGroupAndFreesItsSeats() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, seats, "k1"));

        ReleaseResult result = releases.release(new HoldGroupCommand("alice", held.holdGroupId()));

        assertThat(result).isEqualTo(new ReleaseResult(held.holdGroupId(), 2, 0));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED", "RELEASED");
        assertThat(holds.hold(new HoldCommand("bob", eventId, seats, "k1")).seats())
                .hasSize(2);
    }

    @Test
    void aLapsedGroupIsExpiredNotReleased() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        clock.set(held.expiresAt());

        assertThat(releases.release(new HoldGroupCommand("alice", held.holdGroupId())))
                .isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 2));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("EXPIRED", "EXPIRED");
    }

    @Test
    void releasingTwiceSucceedsAndChangesNothingTheSecondTime() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));
        HoldGroupCommand release = new HoldGroupCommand("alice", held.holdGroupId());
        releases.release(release);

        assertThat(releases.release(release)).isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 0));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED");
    }

    @Test
    void aGroupWithASoldRowIsNotActiveAndIsLeftAlone() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        UUID group = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), group, "alice", "CONVERTED", NOW.plusSeconds(600));
        fixtures.holdInGroup(seats.get(1), group, "alice", "ACTIVE", NOW.plusSeconds(600));

        assertThat(rejection(() -> releases.release(new HoldGroupCommand("alice", group)))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("CONVERTED", "ACTIVE");
    }

    @Test
    void anotherOwnersGroupIsNotFoundAndIsLeftAlone() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));

        assertThat(rejection(() -> releases.release(new HoldGroupCommand("bob", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
    }

    @Test
    void anUnknownGroupIsNotFound() {
        assertThat(rejection(() -> releases.release(new HoldGroupCommand("alice", UUID.randomUUID())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void releasingAGroupFreesItsPlaceUnderTheActiveGroupCap() {
        int cap = properties.maxActiveHoldGroups();
        List<Long> seats = fixtures.eventSeats(eventId, venueId, cap + 1);
        HoldResult first = null;
        for (int i = 0; i < cap; i++) {
            HoldResult held = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(i)), "k" + i));
            first = first == null ? held : first;
        }
        releases.release(new HoldGroupCommand("alice", first.holdGroupId()));

        assertThat(holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(cap)), "next"))
                        .seats())
                .hasSize(1);
    }

    @Test
    void aGroupIdIsRequired() {
        DomainException rejected = rejection(() -> new HoldGroupCommand("alice", null));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "hold_group_id");
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
