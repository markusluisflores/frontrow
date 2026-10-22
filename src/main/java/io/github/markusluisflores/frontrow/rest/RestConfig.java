package io.github.markusluisflores.frontrow.rest;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RestConfig {

    /** First of all filters, ahead of Spring Security's chains, on every path. */
    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter() {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
