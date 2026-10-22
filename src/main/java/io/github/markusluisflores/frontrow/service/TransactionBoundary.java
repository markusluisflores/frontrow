package io.github.markusluisflores.frontrow.service;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Every service entry point runs in a transaction it started itself (PR #18 thread 4296540451). Joined to a caller's
 * transaction, Spring would neither apply nor check READ COMMITTED, and the caller's earlier locks would come before
 * step 1 of the lock order (spec §5). Spring has no propagation for "start one, and fail if one exists": NEVER runs the
 * method with no transaction at all (plan 4A decision 4). So each entry point keeps REQUIRED and calls this first.
 */
final class TransactionBoundary {

    private TransactionBoundary() {}

    /** Fails loudly — a 500 at the adapter — if the transaction this method runs in was joined, not started here. */
    static void requireOwnTransaction() {
        if (!TransactionAspectSupport.currentTransactionStatus().isNewTransaction()) {
            throw new IllegalTransactionStateException("a service entry point must start its own transaction");
        }
    }
}
