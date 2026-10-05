package io.github.markusluisflores.frontrow.domain;

/** Order lifecycle. CONFIRMED is the only value in Phase 1 — cancellation is an explicit non-goal (spec §2). */
public enum OrderStatus {
    CONFIRMED
}
