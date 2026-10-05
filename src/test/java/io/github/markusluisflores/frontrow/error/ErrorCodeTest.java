package io.github.markusluisflores.frontrow.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ErrorCodeTest {

    @Test
    void containsExactlyTheElevenCodesTheSpecDefines() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code))
                .containsExactlyInAnyOrder(
                        "seat_taken",
                        "not_found",
                        "sales_not_open",
                        "sales_closed",
                        "hold_limit_exceeded",
                        "too_many_seats",
                        "hold_expired",
                        "hold_not_active",
                        "idempotency_key_reused",
                        "contention_retry",
                        "invalid_request");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeIsSnakeCaseAndCarriesARecoveryHint(ErrorCode errorCode) {
        assertThat(errorCode.code()).matches("[a-z][a-z_]*[a-z]");
        assertThat(errorCode.hint()).isNotBlank();
    }
}
