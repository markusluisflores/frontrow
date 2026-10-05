package io.github.markusluisflores.frontrow.domain;

import java.time.Instant;

/** The half-open sales window: open <= now < close (spec §4). The same bound applies everywhere. */
public record SalesWindow(Instant openAt, Instant closeAt) {

    public enum WindowState {
        BEFORE,
        OPEN,
        AFTER
    }

    public SalesWindow {
        if (openAt == null || closeAt == null) {
            throw new IllegalArgumentException("sales window bounds must not be null");
        }
        if (!openAt.isBefore(closeAt)) {
            throw new IllegalArgumentException("sales window must open before it closes: " + openAt + ".." + closeAt);
        }
    }

    public static SalesWindow of(Instant openAt, Instant closeAt) {
        return new SalesWindow(openAt, closeAt);
    }

    public WindowState state(Instant now) {
        if (now.isBefore(openAt)) {
            return WindowState.BEFORE;
        }
        return now.isBefore(closeAt) ? WindowState.OPEN : WindowState.AFTER;
    }
}
