package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdvisoryLockKeyTest {

    @Test
    void isStableForTheSameOwner() {
        assertThat(AdvisoryLockKey.forOwner("alice")).isEqualTo(AdvisoryLockKey.forOwner("alice"));
    }

    @Test
    void differsBetweenOwners() {
        assertThat(AdvisoryLockKey.forOwner("alice")).isNotEqualTo(AdvisoryLockKey.forOwner("bob"));
    }

    @Test
    void isPinnedSoTheKeyCannotChangeUnnoticed() {
        assertThat(AdvisoryLockKey.forOwner("alice")).isEqualTo(3_159_282_601_090_220_207L);
    }
}
