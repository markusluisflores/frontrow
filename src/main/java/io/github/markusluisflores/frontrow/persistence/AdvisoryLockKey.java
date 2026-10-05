package io.github.markusluisflores.frontrow.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The bigint key for the per-owner transaction-level advisory lock. Derived in Java from SHA-256 rather than from Postgres's undocumented
 * hashtext(), whose value has changed between major versions — a per-owner lock must mean the same thing on every
 * server and in every process.
 */
final class AdvisoryLockKey {

    private AdvisoryLockKey() {}

    static long forOwner(String owner) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JRE", impossible);
        }
    }
}
