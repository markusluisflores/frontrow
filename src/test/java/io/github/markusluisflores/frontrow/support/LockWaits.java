package io.github.markusluisflores.frontrow.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Observes lock waits from Postgres's side, scoped to one backend. "It waits" is only claimed once Postgres reports
 * that exact session blocked on a lock — never inferred from a timeout, and never from some other session's wait.
 */
public final class LockWaits {

    private LockWaits() {}

    /** The backend pid of the connection this JdbcClient uses here; inside a transaction, that transaction's. */
    public static int backendPid(JdbcClient jdbc) {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    /** Waits up to 10s for the given backend to report wait_event_type = 'Lock'. */
    public static void awaitBlockedOnLock(JdbcClient jdbc, int pid) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int blocked = jdbc.sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE pid = :pid AND wait_event_type = 'Lock'""").param("pid", pid).query(Integer.class).single();
            if (blocked == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("backend " + pid + " did not block on a lock within 10s");
    }

    /** Waits up to 10s for some other backend to report that the given backend blocks it. */
    public static void awaitBlockedBy(JdbcClient jdbc, int blockerPid) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int blocked = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :blocker = ANY (pg_blocking_pids(pid))")
                    .param("blocker", blockerPid)
                    .query(Integer.class)
                    .single();
            if (blocked > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no backend blocked behind " + blockerPid + " within 10s");
    }
}
