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
        // Switch expressions, no default: a new EventStatus or WindowState constant is a compile error, not a gate
        // that fails open.
        DomainException rejection =
                switch (event.status()) {
                    case DRAFT -> salesNotOpen(event);
                    case CANCELLED -> DomainException.of(ErrorCode.SALES_CLOSED);
                    case ON_SALE ->
                        switch (SalesWindow.of(event.salesOpenAt(), event.salesCloseAt())
                                .state(now)) {
                            case BEFORE -> salesNotOpen(event);
                            case AFTER -> DomainException.of(ErrorCode.SALES_CLOSED);
                            case OPEN -> null;
                        };
                };
        if (rejection != null) {
            throw rejection;
        }
    }

    private static DomainException salesNotOpen(EventRow event) {
        return new DomainException(ErrorCode.SALES_NOT_OPEN, Map.of("sales_open_at", event.salesOpenAt()));
    }
}
