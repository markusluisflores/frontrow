package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/** One hold group, named on its owner's behalf, for confirm and release. The owner comes from the principal. */
public record HoldGroupCommand(String owner, UUID holdGroupId) {

    public HoldGroupCommand {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner comes from the authenticated principal and must be present");
        }
        if (holdGroupId == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "hold_group_id"));
        }
    }
}
