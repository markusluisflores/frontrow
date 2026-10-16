package io.github.markusluisflores.frontrow.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. Safe to read from many threads; one instant for all of them. */
public final class TestClock extends Clock {

    private volatile Instant instant;

    public TestClock(Instant start) {
        this.instant = start;
    }

    public void set(Instant next) {
        this.instant = next;
    }

    @Override
    public Instant instant() {
        return instant;
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
