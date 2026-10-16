package io.github.markusluisflores.frontrow.support;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Starts every call at once and collects what each one returned or threw. Use a dedicated virtual-thread executor,
 * never the common pool: a latch-blocked task there can starve it and turn a race into a queue.
 */
public final class Race {

    private Race() {}

    public record Outcome<T>(T result, Throwable failure) {

        public boolean won() {
            return failure == null;
        }

        public ErrorCode errorCode() {
            return failure instanceof DomainException domain ? domain.errorCode() : null;
        }
    }

    public static <T> List<Outcome<T>> run(ExecutorService pool, List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        for (Callable<T> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return new Outcome<>(call.call(), null);
                } catch (Exception failure) {
                    return new Outcome<T>(null, failure);
                }
            }));
        }
        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("racers did not all start within 10s");
        }
        go.countDown();
        List<Outcome<T>> outcomes = new ArrayList<>();
        try {
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        } catch (TimeoutException stuck) {
            // Interrupt every racer still running, so none outlives the test and holds its locks into the cleanup.
            futures.forEach(future -> future.cancel(true));
            throw stuck;
        }
        return outcomes;
    }
}
