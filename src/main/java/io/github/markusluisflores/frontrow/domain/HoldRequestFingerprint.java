package io.github.markusluisflores.frontrow.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/**
 * The canonical hash of a hold request: the event id plus the seat ids in ascending order (spec §5, Idempotent
 * replay). Duplicates are kept — such a request is rejected and never stored, so it cannot match a distinct one.
 */
public final class HoldRequestFingerprint {

    private HoldRequestFingerprint() {}

    public static String of(long eventId, Collection<Long> eventSeatIds) {
        StringBuilder canonical = new StringBuilder().append(eventId).append(':');
        eventSeatIds.stream().sorted().forEach(id -> canonical.append(id).append(','));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JRE", impossible);
        }
    }
}
