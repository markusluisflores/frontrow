package io.github.markusluisflores.frontrow.error;

import java.util.Map;

/**
 * The one exception the application services throw. Carries a spec §6 code and the details that code promises — for
 * example seat_ids for SEAT_TAKEN, or sales_open_at for SALES_NOT_OPEN. Both adapters render it structurally.
 */
@SuppressWarnings("serial")
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public DomainException(ErrorCode errorCode, Map<String, Object> details) {
        super(errorCode.code());
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public static DomainException of(ErrorCode errorCode) {
        return new DomainException(errorCode, Map.of());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
