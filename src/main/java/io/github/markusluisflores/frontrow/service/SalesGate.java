package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.SalesWindow;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import java.time.Instant;
import java.util.Map;

/** Holds and confirmations need ON_SALE and sales_open_at <= now < sales_close_at (spec §4). */
final class SalesGate {

    private SalesGate() {}

    static void requireOpen(EventRow event, Instant now) {
        switch (event.status()) {
            case DRAFT -> throw salesNotOpen(event);
            case CANCELLED -> throw DomainException.of(ErrorCode.SALES_CLOSED);
            case ON_SALE -> {
                switch (SalesWindow.of(event.salesOpenAt(), event.salesCloseAt())
                        .state(now)) {
                    case BEFORE -> throw salesNotOpen(event);
                    case AFTER -> throw DomainException.of(ErrorCode.SALES_CLOSED);
                    case OPEN -> {
                        // on sale
                    }
                }
            }
        }
    }

    private static DomainException salesNotOpen(EventRow event) {
        return new DomainException(ErrorCode.SALES_NOT_OPEN, Map.of("sales_open_at", event.salesOpenAt()));
    }
}
