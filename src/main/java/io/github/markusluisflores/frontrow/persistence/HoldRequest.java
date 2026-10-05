package io.github.markusluisflores.frontrow.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hold_request")
@IdClass(HoldRequestId.class)
public class HoldRequest {

    @Id
    @Column(name = "owner", nullable = false)
    private String owner;

    @Id
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "hold_group_id")
    private UUID holdGroupId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HoldRequest() {}

    /** The first insert of spec section 5: group and response are filled in at commit. */
    public HoldRequest(String owner, String idempotencyKey, String requestHash, Instant createdAt) {
        this(owner, idempotencyKey, requestHash, null, null, createdAt);
    }

    public HoldRequest(
            String owner,
            String idempotencyKey,
            String requestHash,
            UUID holdGroupId,
            String responseJson,
            Instant createdAt) {
        this.owner = owner;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.holdGroupId = holdGroupId;
        this.responseJson = responseJson;
        this.createdAt = createdAt;
    }

    public String getOwner() {
        return owner;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getHoldGroupId() {
        return holdGroupId;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
