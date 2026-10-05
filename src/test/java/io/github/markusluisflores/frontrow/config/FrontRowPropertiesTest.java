package io.github.markusluisflores.frontrow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Runs the real @Validated binding, with no database and no full application context. */
class FrontRowPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(FrontRowProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues(
                    "frontrow.hold-ttl=10m",
                    "frontrow.max-active-hold-groups=3",
                    "frontrow.max-seats-per-hold=8",
                    "frontrow.lock-timeout=5s");

    @Test
    void theShippedDefaultsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FrontRowProperties bound = context.getBean(FrontRowProperties.class);
            assertThat(bound.holdTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(bound.maxActiveHoldGroups()).isEqualTo(3);
            assertThat(bound.maxSeatsPerHold()).isEqualTo(8);
            assertThat(bound.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void aZeroLockTimeoutIsRejectedBecausePostgresReadsZeroAsNoTimeout() {
        runner.withPropertyValues("frontrow.lock-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anOverlongLockTimeoutIsRejected() {
        runner.withPropertyValues("frontrow.lock-timeout=2m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aZeroOrOverlongHoldTtlIsRejected() {
        runner.withPropertyValues("frontrow.hold-ttl=0s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("frontrow.hold-ttl=2h")
                .run(context -> assertThat(context).hasFailed());
    }
}
