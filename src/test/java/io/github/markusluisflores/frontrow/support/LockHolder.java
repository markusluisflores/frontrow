package io.github.markusluisflores.frontrow.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes locks in its own transaction, on its own thread, and keeps them until closed. A test uses it to make a
 * service wait on a lock, change something while it waits, then let it go.
 */
public final class LockHolder implements AutoCloseable {

    private final JdbcClient jdbc;
    private final CountDownLatch release;
    private final Future<?> holder;
    private final int pid;

    private LockHolder(JdbcClient jdbc, CountDownLatch release, Future<?> holder, int pid) {
        this.jdbc = jdbc;
        this.release = release;
        this.holder = holder;
        this.pid = pid;
    }

    /** Runs {@code locks} in a new transaction and returns once they are held. */
    public static LockHolder start(ExecutorService pool, TransactionTemplate tx, JdbcClient jdbc, Runnable locks)
            throws Exception {
        CompletableFuture<Integer> held = new CompletableFuture<>();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            try {
                locks.run();
                held.complete(LockWaits.backendPid(jdbc));
            } catch (RuntimeException failure) {
                held.completeExceptionally(failure);
                throw failure;
            }
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        return new LockHolder(jdbc, release, holder, held.get(10, TimeUnit.SECONDS));
    }

    /** Returns once Postgres reports another session waiting on a lock this transaction holds. */
    public void awaitSomeoneBlocked() throws InterruptedException {
        LockWaits.awaitBlockedBy(jdbc, pid);
    }

    /**
     * Commits the holding transaction, which lets every waiter go. Declares no InterruptedException, which javac's
     * try-with-resources lint (and so -Werror) rejects on a close method.
     */
    @Override
    public void close() throws ExecutionException, TimeoutException {
        release.countDown();
        try {
            holder.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while releasing held locks", interrupted);
        }
    }
}
