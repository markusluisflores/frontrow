package io.github.markusluisflores.frontrow.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of hold_request: (owner, idempotency_key). */
public class HoldRequestId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String owner;
    private String idempotencyKey;

    public HoldRequestId() {}

    public HoldRequestId(String owner, String idempotencyKey) {
        this.owner = owner;
        this.idempotencyKey = idempotencyKey;
    }

    public String getOwner() {
        return owner;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HoldRequestId other)) {
            return false;
        }
        return Objects.equals(owner, other.owner) && Objects.equals(idempotencyKey, other.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, idempotencyKey);
    }
}
