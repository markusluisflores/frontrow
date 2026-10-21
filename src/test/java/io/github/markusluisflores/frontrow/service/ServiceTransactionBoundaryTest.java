package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every service entry point refuses to join a caller's transaction (plan 4A decision 4). The guard is the method's
 * first statement, so no call here needs real data. A new public service method fails everyEntryPointIsCovered until
 * it is added to calls().
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ServiceTransactionBoundaryTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    ReleaseService releases;

    @Autowired
    EventPatchService patches;

    @Autowired
    ExpirySweepService sweeper;

    private Map<String, Runnable> calls() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("HoldService#hold", () -> holds.hold(new HoldCommand("alice", 1, List.of(1L), "k1")));
        calls.put("ConfirmService#confirm", () -> confirms.confirm(new HoldGroupCommand("alice", UUID.randomUUID())));
        calls.put("ReleaseService#release", () -> releases.release(new HoldGroupCommand("alice", UUID.randomUUID())));
        calls.put("EventPatchService#patch", () -> patches.patch(new EventPatch(1, null, null, null)));
        calls.put("ExpirySweepService#expireLapsedHolds", sweeper::expireLapsedHolds);
        calls.put("ExpirySweepService#purgeHoldRequests", sweeper::purgeHoldRequests);
        return calls;
    }

    @Test
    void everyEntryPointRefusesAnOuterTransaction() {
        calls().forEach((name, call) -> assertThatThrownBy(() -> tx.executeWithoutResult(status -> call.run()))
                .as(name)
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessage("a service entry point must start its own transaction"));
    }

    @Test
    void everyEntryPointIsCovered() {
        assertThat(calls().keySet())
                .containsExactlyInAnyOrderElementsOf(TransactionIsolationTest.SERVICES.stream()
                        .flatMap(service -> Arrays.stream(service.getDeclaredMethods()))
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(ServiceTransactionBoundaryTest::name)
                        .collect(Collectors.toSet()));
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
