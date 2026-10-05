package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.domain.HoldRules.ConfirmOutcome;
import io.github.markusluisflores.frontrow.domain.HoldRules.HoldRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoldRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final Instant EARLIER = NOW.minusSeconds(1);

    @Test
    void anActiveHoldInTheFutureIsLive() {
        assertThat(HoldRules.isLive(HoldStatus.ACTIVE, LATER, NOW)).isTrue();
    }

    @Test
    void anActiveHoldAtItsExpiryInstantIsNotLive() {
        assertThat(HoldRules.isLive(HoldStatus.ACTIVE, NOW, NOW)).isFalse();
    }

    @Test
    void endedStatusesAreNeverLive() {
        assertThat(HoldRules.isLive(HoldStatus.EXPIRED, LATER, NOW)).isFalse();
        assertThat(HoldRules.isLive(HoldStatus.RELEASED, LATER, NOW)).isFalse();
        assertThat(HoldRules.isLive(HoldStatus.CONVERTED, LATER, NOW)).isFalse();
    }

    @Test
    void confirmProceedsWhenEveryRowIsLive() {
        List<HoldRow> rows =
                List.of(new HoldRow(1L, HoldStatus.ACTIVE, LATER), new HoldRow(2L, HoldStatus.ACTIVE, LATER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.PROCEED);
    }

    @Test
    void releasedTakesPrecedenceOverExpired() {
        List<HoldRow> rows =
                List.of(new HoldRow(1L, HoldStatus.RELEASED, LATER), new HoldRow(2L, HoldStatus.ACTIVE, EARLIER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_NOT_ACTIVE);
    }

    @Test
    void aPartlyExpiredGroupIsExpired() {
        List<HoldRow> rows =
                List.of(new HoldRow(1L, HoldStatus.ACTIVE, LATER), new HoldRow(2L, HoldStatus.ACTIVE, EARLIER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_EXPIRED);
    }

    @Test
    void anExplicitlyExpiredRowIsExpired() {
        List<HoldRow> rows = List.of(new HoldRow(1L, HoldStatus.EXPIRED, LATER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_EXPIRED);
    }

    @Test
    void aConvertedRowIsNotActive() {
        List<HoldRow> rows = List.of(new HoldRow(1L, HoldStatus.CONVERTED, LATER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_NOT_ACTIVE);
    }
}
