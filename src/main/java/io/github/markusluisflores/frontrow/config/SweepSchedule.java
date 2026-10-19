package io.github.markusluisflores.frontrow.config;

import io.github.markusluisflores.frontrow.service.ExpirySweepService;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the sweep and the key purge every {@code frontrow.sweep-interval}, as two tasks, so a failed sweep never
 * skips a purge (spec §5). The first run is one interval after startup. Off when {@code frontrow.sweeper-enabled} is
 * false: correctness never depends on it, and spec §5 test 7 must pass without it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "frontrow.sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class SweepSchedule implements SchedulingConfigurer {

    private final ExpirySweepService sweeper;
    private final Duration interval;

    public SweepSchedule(ExpirySweepService sweeper, FrontRowProperties properties) {
        this.sweeper = sweeper;
        this.interval = properties.sweepInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(sweeper::expireLapsedHolds, interval, interval));
        registrar.addFixedDelayTask(new FixedDelayTask(sweeper::purgeHoldRequests, interval, interval));
    }
}
