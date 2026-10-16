package io.github.markusluisflores.frontrow.support;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Service tests: real Postgres plus a clock the test controls (plan 3A decision 10). */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class ServiceTestConfiguration {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock(HoldFixtures.NOW);
    }
}
