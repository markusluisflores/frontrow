package io.github.markusluisflores.frontrow.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Caps and timeouts are configuration, not constants (spec §6). */
@Validated
@ConfigurationProperties(prefix = "frontrow")
public record FrontRowProperties(
        @NotNull Duration holdTtl,
        @Min(1) @Max(100) int maxActiveHoldGroups,
        @Min(1) @Max(100) int maxSeatsPerHold,
        @NotNull Duration lockTimeout) {}
