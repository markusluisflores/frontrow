package io.github.markusluisflores.frontrow.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests move by hand. Every thread reads one shared instant, unless it has set its own: spec §5 test 2 runs a
 * confirm and a competing hold at the same moment, each at its own time.
 */
public final class TestClock extends Clock {

    private volatile Instant instant;
    private final ThreadLocal<Instant> ownInstant = new ThreadLocal<>();

    public TestClock(Instant start) {
        this.instant = start;
    }

    /** Moves the shared instant. A thread that set its own instant keeps reading that one. */
    public void set(Instant next) {
        this.instant = next;
    }

    /** Until {@link #clearForThisThread()}, this thread alone reads the given instant. */
    public void setForThisThread(Instant own) {
        ownInstant.set(own);
    }

    public void clearForThisThread() {
        ownInstant.remove();
    }

    @Override
    public Instant instant() {
        Instant own = ownInstant.get();
        return own != null ? own : instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("FrontRow runs on UTC");
    }
}
