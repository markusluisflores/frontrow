package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HoldStatusTest {

    @ParameterizedTest
    @EnumSource(
            value = HoldStatus.class,
            names = {"EXPIRED", "RELEASED", "CONVERTED"})
    void activeCanReachEveryEndedStatus(HoldStatus target) {
        assertThat(HoldStatus.ACTIVE.canTransitionTo(target)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = HoldStatus.class,
            names = {"EXPIRED", "RELEASED", "CONVERTED"})
    void endedStatusesAreTerminal(HoldStatus from) {
        for (HoldStatus target : HoldStatus.values()) {
            assertThat(from.canTransitionTo(target))
                    .as("%s -> %s must be rejected", from, target)
                    .isFalse();
        }
    }

    @Test
    void activeCannotTransitionToItself() {
        assertThat(HoldStatus.ACTIVE.canTransitionTo(HoldStatus.ACTIVE)).isFalse();
    }
}
