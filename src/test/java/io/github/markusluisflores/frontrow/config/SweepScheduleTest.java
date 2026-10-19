package io.github.markusluisflores.frontrow.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.service.ExpirySweepService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The schedule's wiring, with no database. The sweeper is a stub that counts its calls. The scheduler never runs it,
 * because the first run is an hour away, so the test runs each task once itself.
 */
class SweepScheduleTest {

    @Configuration
    @EnableConfigurationProperties(FrontRowProperties.class)
    static class PropertiesOnly {}

    /** Counts the sweeps and purges it is asked for, and touches nothing. */
    static final class RecordingSweeper extends ExpirySweepService {

        final AtomicInteger sweeps = new AtomicInteger();
        final AtomicInteger purges = new AtomicInteger();

        RecordingSweeper() {
            super(null, Clock.systemUTC());
        }

        @Override
        public int expireLapsedHolds() {
            sweeps.incrementAndGet();
            return 0;
        }

        @Override
        public int purgeHoldRequests() {
            purges.incrementAndGet();
            return 0;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class, SweepSchedule.class)
            .withBean(ExpirySweepService.class, RecordingSweeper::new)
            .withPropertyValues(
                    "frontrow.hold-ttl=10m",
                    "frontrow.max-active-hold-groups=3",
                    "frontrow.max-seats-per-hold=8",
                    "frontrow.lock-timeout=5s",
                    "frontrow.sweep-interval=1h");

    @Test
    void schedulesTheSweepAndThePurgeEachIntervalStartingOneIntervalIn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var scheduled = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(scheduled).hasSize(2).allSatisfy(each -> {
                FixedDelayTask task = (FixedDelayTask) each.getTask();
                assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
            });

            scheduled.stream()
                    .map(ScheduledTask::getTask)
                    .forEach(task -> task.getRunnable().run());

            RecordingSweeper sweeper = (RecordingSweeper) context.getBean(ExpirySweepService.class);
            assertThat(sweeper.sweeps).hasValue(1);
            assertThat(sweeper.purges).hasValue(1);
        });
    }

    @Test
    void isOffWhenTheSweeperIsDisabled() {
        runner.withPropertyValues("frontrow.sweeper-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SweepSchedule.class));
    }
}
