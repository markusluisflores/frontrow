package io.github.markusluisflores.frontrow.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CredentialProperties.class)
public class CredentialsConfig {

    @Bean
    Credentials credentials(CredentialProperties properties) {
        return Credentials.from(properties);
    }
}
