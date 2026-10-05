package io.github.markusluisflores.frontrow.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One Clock, injected everywhere. Tests replace this bean; nothing calls Instant.now() (spec §4). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FrontRowProperties.class)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
