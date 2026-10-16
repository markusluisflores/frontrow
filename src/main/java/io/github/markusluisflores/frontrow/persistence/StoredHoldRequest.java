package io.github.markusluisflores.frontrow.persistence;

/** A committed hold_request row: the canonical request hash and the stored result (spec §5, Idempotent replay). */
public record StoredHoldRequest(String requestHash, String responseJson) {}
