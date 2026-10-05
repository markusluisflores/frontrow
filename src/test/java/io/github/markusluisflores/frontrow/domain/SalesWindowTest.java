package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SalesWindowTest {

    private static final Instant OPEN = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant CLOSE = Instant.parse("2026-10-08T10:00:00Z");
    private static final SalesWindow WINDOW = SalesWindow.of(OPEN, CLOSE);

    @Test
    void beforeTheWindowOpens() {
        assertThat(WINDOW.state(OPEN.minusMillis(1))).isEqualTo(SalesWindow.WindowState.BEFORE);
    }

    @Test
    void theOpeningInstantIsInsideTheWindow() {
        assertThat(WINDOW.state(OPEN)).isEqualTo(SalesWindow.WindowState.OPEN);
    }

    @Test
    void theClosingInstantIsOutsideTheWindow() {
        assertThat(WINDOW.state(CLOSE)).isEqualTo(SalesWindow.WindowState.AFTER);
    }

    @Test
    void oneMillisecondBeforeClosingIsStillOpen() {
        assertThat(WINDOW.state(CLOSE.minusMillis(1))).isEqualTo(SalesWindow.WindowState.OPEN);
    }

    @Test
    void rejectsAWindowThatClosesBeforeItOpens() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesWindow.of(CLOSE, OPEN));
    }
}
