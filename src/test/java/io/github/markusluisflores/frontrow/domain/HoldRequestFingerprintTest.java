package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HoldRequestFingerprintTest {

    @Test
    void isTheSha256OfTheCanonicalForm() {
        // printf '7:1,2,' | sha256sum
        assertThat(HoldRequestFingerprint.of(7, List.of(1L, 2L)))
                .isEqualTo("84bf6d6da5425c1b07649b4ec41cda9bc3b12f545f726c9f8aa4f9c054680840");
    }

    @Test
    void ignoresTheOrderSeatsWereRequestedIn() {
        assertThat(HoldRequestFingerprint.of(7, List.of(2L, 1L)))
                .isEqualTo(HoldRequestFingerprint.of(7, List.of(1L, 2L)));
    }

    @Test
    void keepsDuplicatesSoTheyCannotMatchADistinctRequest() {
        // printf '7:1,2,2,' | sha256sum
        assertThat(HoldRequestFingerprint.of(7, List.of(2L, 1L, 2L)))
                .isEqualTo("a94df8728b028e1a8158d91c90558cf8819191280fff7f4f0d97e92ca63dc919");
    }

    @Test
    void differsByEventAndBySeats() {
        assertThat(HoldRequestFingerprint.of(8, List.of(1L, 2L)))
                .isNotEqualTo(HoldRequestFingerprint.of(7, List.of(1L, 2L)));
        assertThat(HoldRequestFingerprint.of(7, List.of(1L, 3L)))
                .isNotEqualTo(HoldRequestFingerprint.of(7, List.of(1L, 2L)));
    }
}
