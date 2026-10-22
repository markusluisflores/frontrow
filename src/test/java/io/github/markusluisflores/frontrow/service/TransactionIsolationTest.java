package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres defaults to READ COMMITTED, so a forgotten setting fails no behavioural test — which is why spec §5's
 * requirement is checked here instead. Plan 3B appends its services to the list. Joining a caller's transaction
 * would defeat the setting, so ServiceTransactionBoundaryTest proves each listed method refuses one (plan 4A). The list
 * itself is checked against a scan of this package, so a new service that nobody lists fails the build.
 */
class TransactionIsolationTest {

    static final List<Class<?>> SERVICES = List.of(
            HoldService.class,
            ReleaseService.class,
            ConfirmService.class,
            EventPatchService.class,
            ExpirySweepService.class);

    @Test
    void theListIsEveryServiceInThePackage() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        Set<String> scanned = scanner.findCandidateComponents(TransactionIsolationTest.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        assertThat(scanned)
                .isNotEmpty()
                .isEqualTo(SERVICES.stream().map(Class::getName).collect(Collectors.toSet()));
    }

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
