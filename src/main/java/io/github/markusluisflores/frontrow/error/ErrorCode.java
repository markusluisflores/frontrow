package io.github.markusluisflores.frontrow.error;

/**
 * The eleven error codes both adapters share (spec §6). The wire form is always under the key "code", and every code
 * carries a recovery hint — an unstructured string error is a review BLOCKER.
 */
public enum ErrorCode {
    SEAT_TAKEN("seat_taken", "call check_availability for current seats"),
    NOT_FOUND("not_found", "call search_events to find a valid id"),
    SALES_NOT_OPEN("sales_not_open", "sales have not opened for this event yet"),
    SALES_CLOSED("sales_closed", "this event is no longer on sale"),
    HOLD_LIMIT_EXCEEDED("hold_limit_exceeded", "release an existing hold first"),
    TOO_MANY_SEATS("too_many_seats", "request fewer seats in one hold"),
    HOLD_EXPIRED("hold_expired", "the hold lapsed; hold the seats again"),
    HOLD_NOT_ACTIVE("hold_not_active", "the hold was already released or confirmed"),
    IDEMPOTENCY_KEY_REUSED("idempotency_key_reused", "use a new key for a different request"),
    CONTENTION_RETRY("contention_retry", "transient conflict; retry the same request"),
    INVALID_REQUEST("invalid_request", "check the named parameter and try again");

    private final String code;
    private final String hint;

    ErrorCode(String code, String hint) {
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
