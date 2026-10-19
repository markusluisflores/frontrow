package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres defaults to READ COMMITTED, so a forgotten setting fails no behavioural test — which is why spec §5's
 * requirement is checked here instead. Plan 3B appends its services to the list.
 */
class TransactionIsolationTest {

    private static final List<Class<?>> SERVICES = List.of(HoldService.class, ReleaseService.class);

    @Test
    void everyPublicServiceMethodRunsReadCommitted() {
        for (Class<?> service : SERVICES) {
            for (Method method : service.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                Transactional transactional = method.getAnnotation(Transactional.class);
                assertThat(transactional)
                        .as("%s#%s is @Transactional", service.getSimpleName(), method.getName())
                        .isNotNull();
                assertThat(transactional.isolation())
                        .as("%s#%s isolation", service.getSimpleName(), method.getName())
                        .isEqualTo(Isolation.READ_COMMITTED);
            }
        }
    }
}
