# FrontRow Phase 1, Plan 3A (Hold Creation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first write path, hold creation with idempotent replay. Prove it against real Postgres with the five §5 concurrency tests that need only holds. Close the test gaps Plan 2 carried forward, starting with the seat_hold row locks, which no test yet shows to block.

**Architecture:** Plans 1 and 2 (merged, `b24d94a` and `e432c3c`) built the schema, the pure domain, the error contract, the JPA entities and the `LockingGateway`. This plan adds a `service` package with one application service, `HoldService`, and extends the gateway with the statements hold creation needs. It adds no REST, no MCP, no confirm, no release and no sweeper.

Phase 1 was five plans. **On 2026-10-14 Markus split Plan 3 in two**, because the whole write-path plan was estimated at ~3,400 lines, 1.7× the size of the plans he had reviewed before. Phase 1 is now six plans:
- **3A (this plan):** hold creation, idempotent replay, and concurrency tests 1, 3, 4, 5 and 7.
- **3B:** confirm, release, the event PATCH/cancel *service* (Markus, 2026-10-14: build it; whether REST exposes it is Plan 4's call), the expiry sweeper and `hold_request` purge, and concurrency tests 2 and 6.
- **4:** REST, OpenAPI and the two security chains (Mandatory tier).
- **5:** the MCP tools and the §7 evidence artifacts.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Data JPA (Hibernate 7.4.5), `JdbcClient` (Spring Framework 7.0.9), Jackson 3.1.5 (`tools.jackson.*`; Boot 4.1.1 auto-configures a `JsonMapper` bean named `jacksonJsonMapper`), Postgres 18, JUnit Jupiter 6, AssertJ, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.6.2). Read:
- §4: the live-hold predicate, the active group, the half-open window;
- §5: *Locking discipline*, *Hold creation*, *Idempotent replay*, and *The portfolio centrepiece tests* 1, 3, 4, 5 and 7;
- §6: *Guardrails on the write path* and the error-code table.

Also read `docs/adr/ADR-005` and PR #10's description, whose "Carried to Plan 3" list this plan works through. The spec coverage table at the end says which task closes each item.

## Errata (2026-10-16, with spec rev 2.6.3)

This plan was executed and merged as PR #18 (`4de410e`). The text below the errata is left as it was reviewed. Where it is wrong, or where `src/main` differs from it, the code on `main` is authoritative, and the affected passage carries a marker pointing here. `src/test` on `main` is authoritative too, and its code can differ from this plan's test blocks beyond what the errata name: `HoldServiceTest` gained the per-seat price test (`db228bc`) and E2's malformed-key cases (`89a1f6c`), `HoldFixtures` gained an `eventSeats` overload that takes a price (`db228bc`), and `Race`'s interrupt comment was rewritten (`89a1f6c`).

- **E1. Decision 3: "no connection" is wrong.** An oversized request does not cost "no connection". By the time `hold()` runs its first check, the `@Transactional` proxy has begun the transaction, and Hibernate's begin has already called `setAutoCommit(false)` on a checked-out connection (`javap` of hibernate-core 7.4.5, `AbstractLogicalConnectionImplementor.begin()`). The early check still saves the sort, every lock and the oversized `IN` list. The code comment in `HoldService` says only "no sort and no lock", which is right. *(PR #18, final whole-branch review, NIT 4.)*
- **E2. Decision 8: "characters" means UTF-16 code units.** The limit is `String.length() <= 200`, so a character outside the Basic Multilingual Plane counts as two. That errs strict, and it stays. The built check also requires a **well-formed** UTF-16 key. pgjdbc encodes parameters with the JDK's UTF-8 encoder, which turns an unpaired surrogate into `?`, so `"k\uD800"` and `"k?"` from one owner were the same key. Such keys are now `invalid_request` with `parameter: idempotency_key`. *(PR #18 fresh round 1; fixed in `89a1f6c`.)*
- **E3. `SalesGate` uses switch expressions, not the arrow-form switch statements in Task 3's code block.** A switch statement over an enum doesn't have to be exhaustive. A new `EventStatus` or `WindowState` constant would therefore compile, match no case, and let the hold through. With switch expressions and no `default`, the same change is a compile error. This matters beyond 3A, because 3B's confirm reuses the gate (self-review item 4). *(Flagged by PR #18's Task 3 reviewer; proved by the final whole-branch review with a javac 25 probe; fixed in `de7cae8`.)*
- **E4. The restore guarantee in Task 4's test and in self-review item 5 is overstated.** The javadoc on `withoutTheClaimIndexTheSameRaceHasSeveralWinners` says the restore "must run even when the race or the cleanup fails, and the timeout stops a stuck restore from hanging the build". Self-review item 5 says its "failure paths are reasoned, not reproduced". Both failure paths have since been reproduced, and neither holds as written:
  - **A failed scoped `DELETE`.** The nested `finally` still runs `CREATE UNIQUE INDEX`. But the race's duplicate `ACTIVE` rows are still there, so the `CREATE` fails too. Its error replaces the original one, and the index stays dropped for every later test in the shared context. A mutation that made the `DELETE` fail left `HoldServiceTest` with 3 failures.
  - **The timeout.** `@Timeout(SEPARATE_THREAD)` fails the test and interrupts its thread. A pgjdbc call waiting on a lock ignores the interrupt, so the statement stays queued in Postgres, and later `seat_hold` writers queue behind it.

  What holds: the restore is always *attempted*, the timeout *fails* the test, and neither gap can produce a false green. The test's javadoc on `main` says exactly this. *(PR #16 round 3, review threads 4287478587 and 4287902752; javadoc corrected in `af6daa6` and `de7cae8`.)*
- **E5. Two decisions superseded by spec rev 2.6.3.** These were right when written. The spec has since decided otherwise, and Plan 3B changes the code:
  - **Decision 5:** `now` is read "once, at the top of `hold()`", on arrival. Spec 2.6.3 (§5 *Locking discipline*) reads it once the event-row lock is held. `hold_request.created_at` keeps the arrival time. The microsecond truncation is unchanged.
  - **`SalesGate`, and the test `aDraftEventIsNotOpenYet`:** a `DRAFT` event answers `sales_not_open` with its `sales_open_at`. Spec 2.6.3 (§4, §5 step 3, §6) makes it `not_found`, so that a draft isn't disclosed.

## Global Constraints

- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). The `domain` package imports only `java.*`; check with the grep in `CLAUDE.md`.
- **All lock SQL lives in `persistence/LockingGateway.java`** (ADR-005). No `FOR UPDATE`, `FOR SHARE`, advisory lock or `LOCK TABLE` anywhere else in `src/main`, comments included. Check with the grep in `CLAUDE.md`, which Task 1 widens.
- **Time comes from the injected `Clock`.** Never `now()` in SQL, never `Instant.now()` in main code (spec §4).
- **A hold is live iff `status = 'ACTIVE' AND expires_at > :now`**, decided by `HoldRules.isLive` in Java and by the gateway's SQL. There is no third definition (spec §4).
- **The sales window is half-open:** `sales_open_at <= :now < sales_close_at`, and the event must be `ON_SALE` (spec §4).
- **Every locking service method sets READ COMMITTED explicitly** (spec §5). Task 3 adds a test that fails when one doesn't.
- **Only `uq_claimed_seat` maps to `seat_taken`.** Any other `23505` surfaces as a 500. `40P01` and `40001` map to `contention_retry` (spec §5). Constraint names come from the driver via `PostgresErrors`, never from message text.
- **Only successful holds are recorded.** A failed request rolls back its `hold_request` row with everything else (spec §5).
- **A replay is JSON-equal to the original, not byte-equal** (spec §5, rev 2.6.2). Tests compare parsed JSON or deserialized records, never raw strings.
- Real Postgres only (`postgres:18-alpine` via Testcontainers). Never H2, never a mocked repository.
- Java 25, `-Xlint:all,-processing -Werror` (tests included), Spotless (palantir), SpotBugs. `./mvnw verify` is the test command, and it must be green at every commit.
- **The formatter decides layout, not this plan.** The code blocks below give content. `spotless:check` runs in `verify`'s validate phase, and the project's `PostToolUse` hook formats each file as it is edited. If `verify` stops on formatting, run `./mvnw spotless:apply` and re-run it.
- Commit messages: `<type>(<scope>): <subject>`, 72 characters max, no trailing period (`.githooks/commit-msg`). No local device path in a committed file.

## Plan-level decisions the spec leaves open

Each is listed so a reviewer can reject it individually.

1. **Every write that hold creation makes goes through the gateway as SQL, and no `SeatHold` entity is touched.**
   - **What moves into the gateway:** the four new statements are the `hold_request` key claim, the stored-response read and write, and the `seat_hold` insert.
   - **Why the key claim and the seat insert belong there:** both can wait on a lock. They are steps 1 and 4 of spec §5's lock order, and the gateway exists so that every lock wait is visible in one file.
   - **Why the response read and write go with them:** they complete the `hold_request` protocol the claim starts.
   - **What this settles from the carry-forward list:**
     - No `SeatHold` entity is ever loaded inside a locking transaction. That is ADR-005's warning about a full-row dirty update overwriting a gateway `UPDATE`.
     - The hold transaction makes **no JPA write at all**, so there is nothing to flush. "Flush explicitly inside the translation scope" therefore becomes a rule for **3B**: confirm inserts `ticket_order` and `order_line`, and if it does so through JPA it must `saveAndFlush` inside its translation `try`.
     - Whether `SeatHold#transitionTo` survives is also decided in 3B, because release and confirm are its only possible callers.
   - The JPA read of `event_seat` rows (prices, and whether the event offers each seat) is fine. Those rows are never written.
2. **No service catches a gateway exception and carries on.** The gateway is `@Transactional(propagation = MANDATORY)`, so an exception that crosses its proxy marks the whole transaction rollback-only (PR #10 carry-forward). Every caught `DataAccessException` is translated and **rethrown**. Rethrowing aborts the transaction, which spec §5 requires anyway: Postgres aborts it at the first error.
3. **Validation order.**
   1. Parameter shape, with no database access: the idempotency key (decision 8), a non-empty seat list, then `too_many_seats` on the **raw** list's length. A null seat id is rejected earlier still, by `HoldCommand`'s constructor, as the same `invalid_request` on `event_seat_ids`. It keeps `List.copyOf`, which SpotBugs recognises as immutable. *(PR #16 round 1, S2. A first fix used `Collections.unmodifiableList(new ArrayList<>(...))`, which SpotBugs reports as `EI_EXPOSE_REP` on the record's accessor.)*
   2. The key claim, then the advisory lock, then the event lock (spec §5 steps 1-3).
   3. `not_found`, then `sales_not_open` or `sales_closed`.
   4. Duplicate seat ids, then seats the event doesn't offer.
   5. The per-owner group cap.

   Spec §5's list maps failures to codes but doesn't fix their order. `too_many_seats` is checked on the raw list **before the fingerprint and the key claim**, so an oversized request costs no sort, no lock, no connection and no oversized `IN` list. *(PR #16 round 3, S3.)* *(Erratum E1: a connection is already checked out.)* One consequence: an oversized list that also contains duplicates gets `too_many_seats`, not the duplicate error. A list within the cap that contains duplicates still gets the duplicate error. The cap bounds only the service's own work: `HoldCommand` still copies whatever list it is given, so Plans 4 and 5 bound the request size at the adapter (see the coverage table).

   A request with the same key as a committed one is replayed **before** any check of current state (steps 3-5). Replays report what happened; they don't re-check current state (spec §5). The step 1 checks run first even then, so a malformed or oversized request is never compared with a stored one: it gets `invalid_request` or `too_many_seats`, not a replay or `idempotency_key_reused`. Every stored request passed those checks, so a retry of one passes them again, unless `max-seats-per-hold` has since been lowered below its size.
4. **A seat that already has a live hold is not rejected early. The insert is left to fail on `uq_claimed_seat`.** That keeps `seat_taken` decided in exactly one place, the schema (ADR-001). It also makes the Task 4 mutation test deterministic: with the index dropped, a second racer always succeeds, so "the race needs the index" is proven rather than likely.
5. **`now` and `expires_at` are truncated to microseconds.** `timestamptz` stores microseconds, and the injected clock can carry nanoseconds. Without truncation, the returned `expiresAt` would differ from the stored one. And because pgjdbc rounds the instants it binds, Java's `HoldRules.isLive` and the SQL cap count could disagree within half a microsecond. `now` is therefore truncated once, at the top of `hold()`. *(PR #16 round 1, N1.)* *(Erratum E5: spec rev 2.6.3 moves the read to after the event-row lock.)*
6. **The stored response is the service result, not an adapter's wire form.** `hold_request.response_json` holds `HoldResult` as Jackson 3 JSON, and a replay deserializes it back into a `HoldResult`. Plans 4 and 5 render that record their own way, so the stored form never ties the two adapters together.
   - **The stored form uses a mapper `HoldService` builds itself** (`HoldService.STORED_FORM`, a default `JsonMapper.builder().build()`), **not the Boot-configured bean**. If Plan 4 changes the application-wide Jackson settings (for example a global snake_case naming strategy, since the spec's wire names are snake_case), rows already stored inside their 24-hour replay window would otherwise stop deserializing, and a replay would become a 500. *(PR #16 round 1, S1.)*
7. **The request fingerprint** (`hold_request.request_hash`) is SHA-256, in lowercase hex, over the text `<event_id>:` followed by each requested seat id in ascending order, each with a `,` after it. Duplicates are kept: a request with duplicates is rejected, and a rejected request is never stored, so they can't cause a false replay. The function lives in `domain`, because `java.security` is JDK-only.
8. **Idempotency keys must be 1 to 200 characters, not blank, and free of U+0000.** The spec requires a key but sets no format. Anything outside that is `invalid_request` with `parameter: idempotency_key`. Postgres `text` cannot store NUL: binding a key that contains one fails with SQLState `22021`, which `SqlFailures` doesn't translate, so without this check a JSON string such as `"k\u0000ey"` would reach the caller as a 500. *(PR #16 round 3, S1.)* *(Erratum E2: "characters" means UTF-16 code units, and the key must also be well-formed UTF-16.)*
9. **Error detail keys**, which both adapters will render (spec §6):
   - `seat_taken` → `seat_ids`: the seat whose insert failed;
   - `sales_not_open` → `sales_open_at`;
   - `too_many_seats` → `max_seats`;
   - `hold_limit_exceeded` → `max_active_hold_groups`;
   - `invalid_request` → `parameter`, plus `seat_ids` when seats are the problem.
10. **Service tests use their own Spring context, with a settable `TestClock` as the primary `Clock`.** That context starts its own Postgres container next to the one the repository tests use, because Spring caches contexts per configuration. This costs one container start, and keeps the existing tests on the real clock.
11. **SpotBugs `EI_EXPOSE_REP2` is excluded on the constructors of `service` classes named `*Service` only.** With no exclusion, `HoldService`'s constructor raises a Medium finding because it stores the injected `LockingGateway`. SpotBugs 4.10.4 (the analyzer run by spotbugs-maven-plugin 4.10.4.1) judges it mutable by a name heuristic: `MutableClasses.looksLikeASetter` counts a method as setter-like when its name starts with one of a list of prefixes and it doesn't return the class's own type. The prefixes are `set`, `put`, `add`, `insert`, `delete`, `remove`, `erase`, `clear`, `push`, `pop`, `enqueue`, `dequeue`, `write`, `append` and `replace`, so the gateway's `setLockTimeout` matches, and from Task 2 on so does `insertHold`. *(PR #16 round 3, N1; read from the pinned jar with `javap`.)* The draft also stored the Boot `JsonMapper`, which drew a second finding; decision 6 removed that field. This was observed when the plan's code was built in a scratch worktree on 2026-10-14. A Spring service keeping its injected singletons is intended, and copying them would be wrong.
   - **The exclusion is narrow:** that bug pattern, `<init>` methods, classes matching `io.github.markusluisflores.frontrow.service.*Service`. Records in that package (`HoldCommand`, `HoldResult`) stay covered. Round 1 found that the first version, matching the whole package, also silenced record constructors. *(PR #16 round 1, N2.)*
   - **Probe:** a temporary non-constructor setter and getter on `HoldService`, storing and returning a `java.util.Date`, were both still reported (`EI_EXPOSE_REP2` and `EI_EXPOSE_REP`).
   - **Rejected alternatives:**
     - Renaming gateway methods to dodge the heuristic. Renaming `setLockTimeout` alone would not clear the finding, because `insertHold` matches too. Renaming every match would bend the gateway's names to a tool, and the next service with an injected collaborator could hit the same finding.
     - `@SuppressFBWarnings` would need a new annotations dependency on the main classpath.

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `src/test/java/.../support/LockWaits.java` | Backend-pid lookup and a pid-scoped "blocked on a lock" wait | 1 |
| `src/test/java/.../persistence/LockingGatewayTest.java` | seat_hold row-lock wait proofs; expiry boundary; pid-scoped waits; then Task 2's new-statement tests | 1, 2 |
| `src/test/java/.../config/FrontRowPropertiesTest.java` | Asserts *why* startup fails, not only that it does | 1 |
| `src/main/java/.../persistence/AdvisoryLockKey.java` | Javadoc reflowed (PR #10 commit-body erratum) | 1 |
| `src/main/java/.../persistence/LockingGateway.java` | Javadoc reflow (Task 1); key claim, stored response, seat insert (Task 2) | 1, 2 |
| `src/main/java/.../persistence/StoredHoldRequest.java` | A `hold_request` row as committed: hash and response | 2 |
| `CLAUDE.md` | Lock grep gains `lock table` (Task 1); current state (Task 4) | 1, 4 |
| `docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md` | Row locks marked proven; amendment for the gateway's new boundary | 2 |
| `src/main/java/.../domain/HoldRequestFingerprint.java` | The canonical request hash | 3 |
| `src/main/java/.../persistence/EventSeatRepository.java` | `findOfferedSeats` | 3 |
| `src/main/java/.../service/HoldCommand.java`, `HoldResult.java` | The service's input and its (stored, replayable) result | 3 |
| `src/main/java/.../service/HoldService.java` | Hold creation, spec §5 steps 1-5, and replay | 3 |
| `src/main/java/.../service/SalesGate.java` | The status-plus-window check (3B's confirm reuses it) | 3 |
| `src/main/java/.../service/SqlFailures.java` | Driver error → `seat_taken` / `contention_retry`, or rethrow unchanged | 3 |
| `spotbugs-exclude.xml`, `pom.xml` | The decision 11 exclusion, wired into the SpotBugs plugin | 3 |
| `src/test/java/.../support/TestClock.java`, `ServiceTestConfiguration.java`, `HoldFixtures.java` | Settable clock, the service-test context, row seeding and read-back | 3 |
| `src/test/java/.../domain/HoldRequestFingerprintTest.java`, `service/SqlFailuresTest.java`, `service/TransactionIsolationTest.java`, `service/HoldServiceTest.java` | Unit and integration tests | 3 |
| `src/test/java/.../support/Race.java` | Start N calls together on virtual threads; collect outcomes | 4 |
| `src/test/java/.../service/HoldConcurrencyTest.java` | §5 tests 1, 3, 4, 5, 7 and the claim-index mutation proof | 4 |

`...` is `io/github/markusluisflores/frontrow`.

---

### Task 1: Prove the seat_hold row locks block, and harden the gateway tests

Production behaviour doesn't change in this task, apart from reflowed javadoc. It closes the gap ADR-005 records: PR #10's posting review deleted both seat_hold `FOR UPDATE` clauses in a throwaway export, and all 14 gateway tests still passed.

**Files:**
- Create: `src/test/java/io/github/markusluisflores/frontrow/support/LockWaits.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/config/FrontRowPropertiesTest.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/AdvisoryLockKey.java:8-12`, `LockingGateway.java:42-47`, `LockingGateway.java:119`
- Modify: `CLAUDE.md` (the lock grep)

**Interfaces:**
- Consumes: `LockingGateway#lockActiveHoldForSeat(long)`, `#lockGroupRows(UUID, String)`, `#expireHold(long, Instant)`, `#countActiveHoldGroups(String, Instant)` (Plan 2).
- Produces: `LockWaits.backendPid(JdbcClient) : int` and `LockWaits.awaitBlockedOnLock(JdbcClient, int pid) : void` (throws `InterruptedException`), used by Task 2.

- [ ] **Step 1: Create the pid-scoped wait helper**

The old helper counted *every* session in the database that was waiting on a lock. A stray waiter from another test, or any lock wait in the container, could satisfy it. This one waits on one backend pid only.

`src/test/java/io/github/markusluisflores/frontrow/support/LockWaits.java`:

```java
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
                            WHERE pid = :pid AND wait_event_type = 'Lock'""")
                    .param("pid", pid)
                    .query(Integer.class)
                    .single();
            if (blocked == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("backend " + pid + " did not block on a lock within 10s");
    }
}
```

- [ ] **Step 2: Move the two existing wait proofs to the helper**

In `LockingGatewayTest`, add these imports:

```java
import io.github.markusluisflores.frontrow.support.LockWaits;
import java.util.concurrent.CompletableFuture;
```

Replace `anExclusiveEventLockMakesAShareLockWait` and `theAdvisoryLockSerializesOneOwnerAndNotTwo` with:

```java
    @Test
    void anExclusiveEventLockMakesAShareLockWait() throws Exception {
        CountDownLatch exclusiveHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            exclusiveHeld.countDown();
            awaitQuietly(release);
        }));
        assertThat(exclusiveHeld.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockEventForShare(eventId).isPresent();
        }));

        // Postgres's own view: this waiter's session is blocked on a lock, not merely unscheduled.
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theAdvisoryLockSerializesOneOwnerAndNotTwo() throws Exception {
        CountDownLatch aliceHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> secondAlicePid = new CompletableFuture<>();

        Future<?> alice = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockOwner("alice");
            aliceHolds.countDown();
            awaitQuietly(release);
        }));
        assertThat(aliceHolds.await(10, TimeUnit.SECONDS)).isTrue();

        // A different owner is a different key, so this must not wait at all.
        pool.submit(() -> tx.executeWithoutResult(status -> gateway.lockOwner("bob")))
                .get(10, TimeUnit.SECONDS);

        Future<?> secondAlice = pool.submit(() -> tx.executeWithoutResult(status -> {
            secondAlicePid.complete(LockWaits.backendPid(jdbc));
            gateway.lockOwner("alice");
        }));
        LockWaits.awaitBlockedOnLock(jdbc, secondAlicePid.get(10, TimeUnit.SECONDS));
        assertThat(secondAlice.isDone()).isFalse();

        release.countDown();
        secondAlice.get(10, TimeUnit.SECONDS);
        alice.get(10, TimeUnit.SECONDS);
    }
```

Then delete the private `awaitSessionsWaitingOnLocks` method. It has no callers left.

- [ ] **Step 3: Write the seat_hold wait tests and the expiry-boundary tests**

Add these to `LockingGatewayTest`:

```java
    @Test
    void aLockedActiveHoldMakesASecondLockerWait() throws Exception {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            assertThat(gateway.lockActiveHoldForSeat(eventSeatId)).isPresent();
            held.countDown();
            awaitQuietly(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockActiveHoldForSeat(eventSeatId).isPresent();
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aLockedGroupMakesASecondGroupLockerWait() throws Exception {
        UUID group = UUID.randomUUID();
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(secondEventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            assertThat(gateway.lockGroupRows(group, "alice")).hasSize(2);
            held.countDown();
            awaitQuietly(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Integer> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockGroupRows(group, "alice").size();
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo(2);
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aHoldExpiringExactlyNowIsExpired() {
        long holdId = insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW);

        tx.executeWithoutResult(status -> {
            assertThat(gateway.expireHold(holdId, NOW)).isTrue();
            assertThat(statusOf(holdId)).isEqualTo("EXPIRED");
        });
    }

    @Test
    void aGroupExpiringExactlyNowDoesNotCountTowardTheCap() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW);
        assertThat(tx.<Integer>execute(status -> gateway.countActiveHoldGroups("alice", NOW)))
                .isZero();
    }
```

- [ ] **Step 4: Run the gateway tests**

Run: `./mvnw -q test -Dtest=LockingGatewayTest`
Expected: PASS, 18 tests. The two wait proofs pass against the shipped code because both `FOR UPDATE` clauses are present.

- [ ] **Step 5: Mutation check. The wait tests must fail without the lock clauses**

This step is what gives the new tests their value, so it is not optional.

**Every mutation run skips the Spotless check.** Deleting text changes the line layout Palantir wants, so without the flag `spotless:check` stops the build before any test runs. That failure means nothing about the test. Confirm that the failure message matches the one expected below; a red build alone is not the evidence. This happened during plan authoring.

In `LockingGateway.java`, delete ` FOR UPDATE` from **`lockActiveHoldForSeat` only** (keep the closing `"`).

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=LockingGatewayTest#aLockedActiveHoldMakesASecondLockerWait`
Expected: FAIL with `AssertionError: backend <pid> did not block on a lock within 10s`.

Restore the clause. Then delete ` FOR UPDATE` from `lockGroupRows` only (it follows `ORDER BY event_seat_id`).

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=LockingGatewayTest#aLockedGroupMakesASecondGroupLockerWait`
Expected: FAIL with the same message.

Restore it, then run `git diff src/main` and confirm it shows **no** change to either SQL string. Record both observed failure messages in the task report.

- [ ] **Step 6: Assert why the properties binding fails**

In `FrontRowPropertiesTest`, add these imports:

```java
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
```

Replace the three rejection tests with:

```java
    @Test
    void aZeroLockTimeoutIsRejectedBecausePostgresReadsZeroAsNoTimeout() {
        runner.withPropertyValues("frontrow.lock-timeout=0s").run(context -> assertRejected(context, "lockTimeout"));
    }

    @Test
    void anOverlongLockTimeoutIsRejected() {
        runner.withPropertyValues("frontrow.lock-timeout=2m").run(context -> assertRejected(context, "lockTimeout"));
    }

    @Test
    void aZeroOrOverlongHoldTtlIsRejected() {
        runner.withPropertyValues("frontrow.hold-ttl=0s").run(context -> assertRejected(context, "holdTtl"));
        runner.withPropertyValues("frontrow.hold-ttl=2h").run(context -> assertRejected(context, "holdTtl"));
    }

    /** Fails for the named property's bound, not for some unrelated startup error. */
    private static void assertRejected(AssertableApplicationContext context, String field) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause()
                .hasMessageContaining(field);
    }
```

Run: `./mvnw -q test -Dtest=FrontRowPropertiesTest`
Expected: PASS, 4 tests. **If an assertion fails on the message**, print `context.getStartupFailure()` and read how the root cause names the field. If it uses another spelling (for example `lock-timeout`), use that spelling in the two calls and say so in the task report. What matters is that the failure names the right property. If the root cause **isn't** a `BindValidationException`, stop and report BLOCKED. Don't weaken the assertion.

- [ ] **Step 7: Reflow the over-long javadoc lines**

These are the lines PR #10's commit `a648936` claimed to reflow and didn't.

`AdvisoryLockKey.java`, the class javadoc becomes:

```java
/**
 * The bigint key for the per-owner transaction-level advisory lock. Derived in Java from SHA-256 rather than from
 * Postgres's undocumented hashtext(), whose value has changed between major versions — a per-owner lock must mean
 * the same thing on every server and in every process.
 */
```

`LockingGateway.java`, the `setLockTimeout` javadoc becomes:

```java
    /**
     * Bounds every lock wait in this transaction. The value is bounded configuration (1 ms to 1 minute, enforced on
     * {@code FrontRowProperties}), so zero, which Postgres reads as "no timeout", cannot reach it. Used by the event
     * PATCH path, where a steady stream of FOR SHARE holders could otherwise starve a FOR UPDATE indefinitely (spec
     * §5, Liveness). Postgres does not accept bind parameters in SET, so the validated, bounded configuration value
     * is formatted as a literal; no request data reaches it.
     */
```

and the `countActiveHoldGroups` javadoc becomes:

```java
    /**
     * The per-owner cap counts groups with at least one live row, so lapsed-but-unswept groups never count (spec §4).
     */
```

Run: `awk 'length > 120 {print FILENAME":"FNR}' src/main/java/io/github/markusluisflores/frontrow/persistence/*.java`
Expected: no output.

- [ ] **Step 8: Widen the lock grep**

In `CLAUDE.md`, under "Architecture rules", replace the grep line with:

```
  `grep -rlniE "for (no key )?update|for (key )?share|pg_[a-z_]*advisory|@Lock\b|LockModeType|PESSIMISTIC|skip locked|nowait|lock table" src/main/java/`
```

Run the new grep.
Expected: exactly one file, `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`.

- [ ] **Step 9: Full build and commit**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, and 4 more test runs than before this task (2 wait proofs and 2 boundary tests added, none removed).

```bash
git add src/test/java/io/github/markusluisflores/frontrow/support/LockWaits.java \
  src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java \
  src/test/java/io/github/markusluisflores/frontrow/config/FrontRowPropertiesTest.java \
  src/main/java/io/github/markusluisflores/frontrow/persistence/AdvisoryLockKey.java \
  src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java CLAUDE.md
git commit -m "test(persistence): prove the seat_hold row locks make writers wait"
```

---

### Task 2: The gateway statements hold creation needs

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/persistence/StoredHoldRequest.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`

**Interfaces:**
- Consumes: `LockWaits` (Task 1).
- Produces, for Task 3:
  - `LockingGateway#claimIdempotencyKey(String owner, String idempotencyKey, String requestHash, Instant now) : boolean`. True means this call inserted the key. False means the key already exists: committed by another request, or claimed earlier in this same transaction. If another transaction holding the key is still in flight, the statement waits for it first.
  - `LockingGateway#findHoldRequest(String owner, String idempotencyKey) : Optional<StoredHoldRequest>`
  - `LockingGateway#recordHoldResponse(String owner, String idempotencyKey, UUID holdGroupId, String responseJson) : void`
  - `LockingGateway#insertHold(long eventSeatId, UUID holdGroupId, String owner, Instant expiresAt, Instant now) : long` (the new row's id)
  - `record StoredHoldRequest(String requestHash, String responseJson)`

- [ ] **Step 1: Write the failing tests**

Add these imports to `LockingGatewayTest`:

```java
import tools.jackson.databind.json.JsonMapper;
```

Add this field next to the other `@Autowired` fields:

```java
    @Autowired
    JsonMapper json;
```

Add these tests:

```java
    @Test
    void claimsAnIdempotencyKeyOnlyOnce() {
        tx.executeWithoutResult(status -> {
            assertThat(gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW)).isTrue();
            // Its own uncommitted row conflicts at once: a transaction never waits on itself.
            assertThat(gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW)).isFalse();
            // Keys are per owner.
            assertThat(gateway.claimIdempotencyKey("bob", "k1", "hash-a", NOW)).isTrue();
        });
    }

    @Test
    void aConcurrentClaimOfTheSameKeyWaitsThenSeesTheCommittedRow() throws Exception {
        assertThat(racingClaimAfterFirstClaim(false)).isFalse();
    }

    @Test
    void aConcurrentClaimOfTheSameKeyProceedsWhenTheFirstRollsBack() throws Exception {
        assertThat(racingClaimAfterFirstClaim(true)).isTrue();
    }

    @Test
    void readsBackTheStoredResponseAsEqualJson() {
        UUID group = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW);
            gateway.recordHoldResponse("alice", "k1", group, "{\"ok\":true,\"seats\":[3,1]}");
        });

        StoredHoldRequest stored = tx.execute(status -> gateway.findHoldRequest("alice", "k1"))
                .orElseThrow();
        assertThat(stored.requestHash()).isEqualTo("hash-a");
        // jsonb normalises whitespace and key order, so compare parsed trees, never raw text (spec §5, rev 2.6.2).
        assertThat(json.readTree(stored.responseJson())).isEqualTo(json.readTree("{\"seats\":[3,1],\"ok\":true}"));
        assertThat(tx.<Optional<StoredHoldRequest>>execute(status -> gateway.findHoldRequest("alice", "other")))
                .isEmpty();
    }

    @Test
    void recordingAResponseForAnUnclaimedKeyFails() {
        assertThat(catchThrowable(() -> tx.executeWithoutResult(
                        status -> gateway.recordHoldResponse("alice", "never", UUID.randomUUID(), "{}"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void insertsAnActiveHoldImmediately() {
        tx.executeWithoutResult(status -> {
            long holdId = gateway.insertHold(eventSeatId, UUID.randomUUID(), "alice", NOW.plusSeconds(600), NOW);
            // Read back through SQL inside the same transaction: the INSERT already reached the database.
            assertThat(statusOf(holdId)).isEqualTo("ACTIVE");
        });
    }

    @Test
    void aSecondClaimOnAClaimedSeatViolatesTheClaimIndex() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));

        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(
                status -> gateway.insertHold(eventSeatId, UUID.randomUUID(), "bob", NOW.plusSeconds(600), NOW)));

        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(PostgresErrors.UNIQUE_VIOLATION);
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo(PostgresErrors.UQ_CLAIMED_SEAT);
    }

    @Test
    void anUncommittedClaimMakesAConcurrentInsertOnTheSameSeatWait() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.insertHold(eventSeatId, UUID.randomUUID(), "alice", NOW.plusSeconds(600), NOW);
            inserted.countDown();
            awaitQuietly(release);
            status.setRollbackOnly();
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Long> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.insertHold(eventSeatId, UUID.randomUUID(), "bob", NOW.plusSeconds(600), NOW);
        }));
        // Spec §5 step 4 counts unique-index insert waits in the lock order; this is that wait, observed.
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isPositive();
    }

    /** A first transaction claims "k1" and holds it open; a second claims the same key and must wait for it. */
    private boolean racingClaimAfterFirstClaim(boolean firstRollsBack) throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW);
            claimed.countDown();
            awaitQuietly(release);
            if (firstRollsBack) {
                status.setRollbackOnly();
            }
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> second = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.claimIdempotencyKey("alice", "k1", "hash-a", NOW);
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(second.isDone()).isFalse();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        return second.get(10, TimeUnit.SECONDS);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=LockingGatewayTest`
Expected: compilation FAILS: `cannot find symbol: method claimIdempotencyKey`, plus `StoredHoldRequest`, `recordHoldResponse`, `findHoldRequest` and `insertHold`.

- [ ] **Step 3: Implement**

`src/main/java/io/github/markusluisflores/frontrow/persistence/StoredHoldRequest.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

/** A committed hold_request row: the canonical request hash and the stored result (spec §5, Idempotent replay). */
public record StoredHoldRequest(String requestHash, String responseJson) {}
```

In `LockingGateway.java`:
- Replace the class javadoc's first paragraph with:

```java
/**
 * Every lock in the system, taken through explicit SQL in the one global order spec §5 defines: the hold_request key,
 * the per-owner advisory lock, the event row, then seat_hold rows ascending by event_seat_id. The statements that can
 * wait on a lock without saying so — the hold_request key insert (step 1) and the seat_hold insert, which waits on
 * uq_claimed_seat (step 4) — live here too, so every wait in the lock order is visible in this one file.
 *
```

  Keep the existing second paragraph (`<p>These reads deliberately bypass…`) unchanged.
- Add a private helper at the bottom, and use it in place of the two existing `OffsetDateTime.ofInstant(now, ZoneOffset.UTC)` calls in `expireHold` and `countActiveHoldGroups`:

```java
    /** pgjdbc 42.7.13 infers no SQL type for a bare Instant, so every bind goes through OffsetDateTime. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
```

- Add these methods after `countActiveHoldGroups`:

```java
    /**
     * Step 1 (hold creation only): inserts the idempotency key, or does nothing when it exists. While another
     * transaction holding the same key is still running, Postgres makes this insert wait for it (spec §5). Returns
     * true when this call inserted the key; false means the key already exists: committed by another request, or
     * claimed earlier in this transaction.
     */
    public boolean claimIdempotencyKey(String owner, String idempotencyKey, String requestHash, Instant now) {
        return jdbc.sql("""
                        INSERT INTO hold_request (owner, idempotency_key, request_hash, created_at)
                        VALUES (:owner, :key, :hash, :now)
                        ON CONFLICT (owner, idempotency_key) DO NOTHING""")
                        .param("owner", owner)
                        .param("key", idempotencyKey)
                        .param("hash", requestHash)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** The stored request, read in a fresh statement after a key conflict, so READ COMMITTED sees the committed row. */
    public Optional<StoredHoldRequest> findHoldRequest(String owner, String idempotencyKey) {
        return jdbc.sql("""
                        SELECT request_hash, response_json::text AS response_json FROM hold_request
                        WHERE owner = :owner AND idempotency_key = :key""")
                .param("owner", owner)
                .param("key", idempotencyKey)
                .query((rs, rowNum) ->
                        new StoredHoldRequest(rs.getString("request_hash"), rs.getString("response_json")))
                .optional();
    }

    /** Completes this transaction's claimed hold_request row. jsonb normalises the text: replays are JSON-equal. */
    public void recordHoldResponse(String owner, String idempotencyKey, UUID holdGroupId, String responseJson) {
        int updated = jdbc.sql("""
                        UPDATE hold_request SET hold_group_id = :group, response_json = CAST(:json AS jsonb)
                        WHERE owner = :owner AND idempotency_key = :key""")
                .param("group", holdGroupId)
                .param("json", responseJson)
                .param("owner", owner)
                .param("key", idempotencyKey)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("no claimed hold_request row to complete");
        }
    }

    /**
     * Step 4, one seat: inserts the new ACTIVE row at once, so a conflict surfaces here, inside the service, rather
     * than at commit. An uncommitted claim on the same seat makes this wait on uq_claimed_seat; a committed claim
     * makes it fail with 23505.
     */
    public long insertHold(long eventSeatId, UUID holdGroupId, String owner, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                        INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                        VALUES (:seat, :group, :owner, 'ACTIVE', :expires, :now) RETURNING id""")
                .param("seat", eventSeatId)
                .param("group", holdGroupId)
                .param("owner", owner)
                .param("expires", utc(expiresAt))
                .param("now", utc(now))
                .query(Long.class)
                .single();
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=LockingGatewayTest`
Expected: PASS, 26 tests (Task 1's 18 plus these 8).

- [ ] **Step 5: Amend ADR-005 to match the gateway's new boundary**

ADR-005 describes the gateway as holding locking reads, lazy expiry and `SET LOCAL`. Decision 1 widens that to the statements that wait on a lock implicitly, and Task 1 has now proven what ADR-005 lists as unproven. An ADR records a decision, so it is amended rather than rewritten. *(PR #16 round 1, S3.)*

In `docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md`, replace the consequence bullet that begins `* ⚠️ Not yet proven:` with:

```markdown
* ✅ The seat-hold row locks are proven to block a concurrent writer (Plan 3A, Task 1). `LockingGatewayTest#aLockedActiveHoldMakesASecondLockerWait` and `#aLockedGroupMakesASecondGroupLockerWait` each fail with `did not block on a lock within 10s` when their `FOR UPDATE` clause is removed. *(Until Plan 3A this line read "Not yet proven": PR #10's posting review had deleted both clauses in a throwaway export and every test still passed.)*
```

Then append this section at the end of the file:

```markdown
## Amendment — Plan 3A (hold creation)

The gateway also holds the statements that wait on a lock **implicitly**: the `hold_request` key insert (`ON CONFLICT DO NOTHING`, lock-order step 1, which waits on an in-flight duplicate key) and the `seat_hold` insert (step 4, which waits on `uq_claimed_seat` behind an uncommitted claim). The stored-response read and write that complete the `hold_request` protocol sit beside them. Hold creation therefore makes **no JPA write at all**, which is how it meets this ADR's warning about dirty `SeatHold` entities. Ordinary reads, such as `event_seat` prices, still go through JPA. Source: `docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`, decision 1.
```

Run: `grep -n "Not yet proven\|Amendment" docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md`
Expected: exactly two lines. One is the new `* ✅` bullet, which matches because its parenthesis quotes the old wording. The other is the `## Amendment — Plan 3A (hold creation)` heading. No line may begin `* ⚠️ Not yet proven`.

- [ ] **Step 6: Check the lock grep and the build, then commit**

Run the `CLAUDE.md` lock grep.
Expected: only `LockingGateway.java`. `ON CONFLICT` is not lock vocabulary.

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0.

```bash
git add src/main/java/io/github/markusluisflores/frontrow/persistence/StoredHoldRequest.java \
  src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java \
  src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java \
  docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md
git commit -m "feat(persistence): add the key claim and seat insert to the gateway"
```

---

### Task 3: `HoldService`, hold creation and idempotent replay (spec §5)

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/domain/HoldRequestFingerprint.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/HoldCommand.java`, `HoldResult.java`, `HoldService.java`, `SalesGate.java`, `SqlFailures.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/EventSeatRepository.java`
- Create (test): `src/test/java/io/github/markusluisflores/frontrow/support/TestClock.java`, `ServiceTestConfiguration.java`, `HoldFixtures.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/domain/HoldRequestFingerprintTest.java`, `service/SqlFailuresTest.java`, `service/TransactionIsolationTest.java`, `service/HoldServiceTest.java`

**Interfaces:**
- Consumes: Task 2's gateway methods. From Plan 2: `LockingGateway#lockOwner(String)`, `#lockEventForShare(long) : Optional<EventRow>`, `#lockActiveHoldForSeat(long) : Optional<HoldRow>`, `#expireHold(long, Instant) : boolean`, `#countActiveHoldGroups(String, Instant) : int`, plus `HoldRules.isLive`, `SalesWindow`, `Money`, `ErrorCode`, `DomainException`, `PostgresErrors` and `FrontRowProperties`.
- Produces, for Task 4 and Plan 3B:
  - `HoldService#hold(HoldCommand) : HoldResult`
  - `record HoldCommand(String owner, long eventId, List<Long> eventSeatIds, String idempotencyKey)`
  - `record HoldResult(UUID holdGroupId, long eventId, List<HoldResult.HeldSeat> seats, long totalCents, String currency, Instant expiresAt)`, with nested `record HeldSeat(long eventSeatId, long priceCents)`
  - `SalesGate.requireOpen(EventRow event, Instant now)` (package-private; 3B's confirm reuses it)
  - `SqlFailures.translate(DataAccessException, long seatBeingClaimed)` and `SqlFailures.translate(DataAccessException)` (package-private)
  - Test support: `TestClock` (`set(Instant)`, `instant()`), `ServiceTestConfiguration`, `HoldFixtures` (see its code)

- [ ] **Step 1: Write the failing unit tests**

`src/test/java/io/github/markusluisflores/frontrow/domain/HoldRequestFingerprintTest.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HoldRequestFingerprintTest {

    @Test
    void isTheSha256OfTheCanonicalForm() {
        // printf '7:1,2,' | sha256sum
        assertThat(HoldRequestFingerprint.of(7, List.of(1L, 2L)))
                .isEqualTo("84bf6d6da5425c1b07649b4ec41cda9bc3b12f545f726c9f8aa4f9c054680840");
    }

    @Test
    void ignoresTheOrderSeatsWereRequestedIn() {
        assertThat(HoldRequestFingerprint.of(7, List.of(2L, 1L))).isEqualTo(HoldRequestFingerprint.of(7, List.of(1L, 2L)));
    }

    @Test
    void keepsDuplicatesSoTheyCannotMatchADistinctRequest() {
        // printf '7:1,2,2,' | sha256sum
        assertThat(HoldRequestFingerprint.of(7, List.of(2L, 1L, 2L)))
                .isEqualTo("a94df8728b028e1a8158d91c90558cf8819191280fff7f4f0d97e92ca63dc919");
    }

    @Test
    void differsByEventAndBySeats() {
        assertThat(HoldRequestFingerprint.of(8, List.of(1L, 2L))).isNotEqualTo(HoldRequestFingerprint.of(7, List.of(1L, 2L)));
        assertThat(HoldRequestFingerprint.of(7, List.of(1L, 3L))).isNotEqualTo(HoldRequestFingerprint.of(7, List.of(1L, 2L)));
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/service/SqlFailuresTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

class SqlFailuresTest {

    @Test
    void aClaimIndexViolationIsSeatTakenNamingTheSeat() {
        RuntimeException translated = SqlFailures.translate(duplicate("uq_claimed_seat"), 42L);
        assertThat(translated).isInstanceOf(DomainException.class);
        DomainException domain = (DomainException) translated;
        assertThat(domain.errorCode()).isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(domain.details()).containsEntry("seat_ids", List.of(42L));
    }

    @Test
    void anyOtherUniqueViolationIsABugAndIsRethrownUnchanged() {
        DataAccessException other = duplicate("uq_sold_once");
        assertThat(SqlFailures.translate(other, 42L)).isSameAs(other);
    }

    @Test
    void aClaimIndexViolationOutsideTheClaimLoopIsAlsoRethrownUnchanged() {
        DataAccessException outside = duplicate("uq_claimed_seat");
        assertThat(SqlFailures.translate(outside)).isSameAs(outside);
    }

    @Test
    void deadlockAndSerializationFailuresAskTheCallerToRetry() {
        for (String state : List.of("40P01", "40001")) {
            RuntimeException translated =
                    SqlFailures.translate(new CannotAcquireLockException("lock", psql(state, null)));
            assertThat(translated).isInstanceOf(DomainException.class);
            assertThat(((DomainException) translated).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        }
    }

    @Test
    void aFailureWithNoPostgresCauseIsRethrownUnchanged() {
        DataAccessException plain = new DuplicateKeyException("no driver error inside");
        assertThat(SqlFailures.translate(plain, 42L)).isSameAs(plain);
    }

    private static DataAccessException duplicate(String constraint) {
        return new DuplicateKeyException("duplicate", psql("23505", constraint));
    }

    private static PSQLException psql(String state, String constraint) {
        String serverMessage = "SERROR\u0000C" + state + "\u0000Mfailed\u0000"
                + (constraint == null ? "" : "n" + constraint + "\u0000");
        return new PSQLException(new ServerErrorMessage(serverMessage));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='HoldRequestFingerprintTest,SqlFailuresTest'`
Expected: compilation FAILS: `cannot find symbol: class HoldRequestFingerprint`, `SqlFailures`.

- [ ] **Step 3: Implement the fingerprint, the translator, the sales gate and the records**

`src/main/java/io/github/markusluisflores/frontrow/domain/HoldRequestFingerprint.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/**
 * The canonical hash of a hold request: the event id plus the seat ids in ascending order (spec §5, Idempotent
 * replay). Duplicates are kept — such a request is rejected and never stored, so it cannot match a distinct one.
 */
public final class HoldRequestFingerprint {

    private HoldRequestFingerprint() {}

    public static String of(long eventId, Collection<Long> eventSeatIds) {
        StringBuilder canonical = new StringBuilder().append(eventId).append(':');
        eventSeatIds.stream().sorted().forEach(id -> canonical.append(id).append(','));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JRE", impossible);
        }
    }
}
```

`src/main/java/io/github/markusluisflores/frontrow/service/SqlFailures.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.error.PostgresErrors;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;

/**
 * Turns a driver failure into a spec §6 code, or hands it back unchanged — an unexpected failure is a bug and must
 * surface as one. The caller always throws the result; nothing catches a database failure and carries on.
 */
final class SqlFailures {

    private SqlFailures() {}

    /** Inside the claim loop: only uq_claimed_seat means seat_taken, naming the seat whose insert failed. */
    static RuntimeException translate(DataAccessException failure, long seatBeingClaimed) {
        if (PostgresErrors.UNIQUE_VIOLATION.equals(PostgresErrors.sqlState(failure))
                && PostgresErrors.UQ_CLAIMED_SEAT.equals(PostgresErrors.constraintName(failure))) {
            return new DomainException(ErrorCode.SEAT_TAKEN, Map.of("seat_ids", List.of(seatBeingClaimed)));
        }
        return translate(failure);
    }

    /** Anywhere: deadlock and serialization failures are mapped defensively; the lock order should prevent both. */
    static RuntimeException translate(DataAccessException failure) {
        String state = PostgresErrors.sqlState(failure);
        if (PostgresErrors.DEADLOCK_DETECTED.equals(state) || PostgresErrors.SERIALIZATION_FAILURE.equals(state)) {
            return DomainException.of(ErrorCode.CONTENTION_RETRY);
        }
        return failure;
    }
}
```

`src/main/java/io/github/markusluisflores/frontrow/service/SalesGate.java` *(Erratum E3: built with switch expressions. Erratum E5: spec rev 2.6.3 makes `DRAFT` `not_found`.)*:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.SalesWindow;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import java.time.Instant;
import java.util.Map;

/** Holds and confirmations need ON_SALE and sales_open_at <= now < sales_close_at (spec §4). */
final class SalesGate {

    private SalesGate() {}

    static void requireOpen(EventRow event, Instant now) {
        switch (event.status()) {
            case DRAFT -> throw salesNotOpen(event);
            case CANCELLED -> throw DomainException.of(ErrorCode.SALES_CLOSED);
            case ON_SALE -> {
                switch (SalesWindow.of(event.salesOpenAt(), event.salesCloseAt()).state(now)) {
                    case BEFORE -> throw salesNotOpen(event);
                    case AFTER -> throw DomainException.of(ErrorCode.SALES_CLOSED);
                    case OPEN -> {
                        // on sale
                    }
                }
            }
        }
    }

    private static DomainException salesNotOpen(EventRow event) {
        return new DomainException(ErrorCode.SALES_NOT_OPEN, Map.of("sales_open_at", event.salesOpenAt()));
    }
}
```

`src/main/java/io/github/markusluisflores/frontrow/service/HoldCommand.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** A request to hold specific seats. The owner always comes from the authenticated principal, never a parameter. */
public record HoldCommand(String owner, long eventId, List<Long> eventSeatIds, String idempotencyKey) {

    public HoldCommand {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner comes from the authenticated principal and must be present");
        }
        if (eventSeatIds == null) {
            eventSeatIds = List.of();
        }
        // A null id must be invalid_request, not the NullPointerException List.copyOf would throw. A loop, because
        // List.of(...).contains(null) itself throws.
        for (Long id : eventSeatIds) {
            if (id == null) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "event_seat_ids"));
            }
        }
        eventSeatIds = List.copyOf(eventSeatIds);
    }
}
```

`src/main/java/io/github/markusluisflores/frontrow/service/HoldResult.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A successful hold: what the caller relays to its human. Stored as JSON in hold_request and returned unchanged on
 * replay, so this record is the replay contract (plan 3A decision 6).
 */
public record HoldResult(
        UUID holdGroupId, long eventId, List<HeldSeat> seats, long totalCents, String currency, Instant expiresAt) {

    public HoldResult {
        seats = List.copyOf(seats);
    }

    public record HeldSeat(long eventSeatId, long priceCents) {}
}
```

- [ ] **Step 4: Run the unit tests**

Run: `./mvnw -q test -Dtest='HoldRequestFingerprintTest,SqlFailuresTest'`
Expected: PASS, 9 tests. Nothing in `src/test` refers to `HoldService` yet. Maven compiles every test file whatever `-Dtest` selects, which is why `TransactionIsolationTest` waits until Step 6.

- [ ] **Step 5: Create the service-test support**

`src/test/java/io/github/markusluisflores/frontrow/support/TestClock.java`:

```java
package io.github.markusluisflores.frontrow.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. Safe to read from many threads; one instant for all of them. */
public final class TestClock extends Clock {

    private volatile Instant instant;

    public TestClock(Instant start) {
        this.instant = start;
    }

    public void set(Instant next) {
        this.instant = next;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("FrontRow runs on UTC");
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/support/ServiceTestConfiguration.java`:

```java
package io.github.markusluisflores.frontrow.support;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Service tests: real Postgres plus a clock the test controls (plan 3A decision 10). */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class ServiceTestConfiguration {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock(HoldFixtures.NOW);
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`:

```java
package io.github.markusluisflores.frontrow.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Seeds and reads rows for service tests through plain SQL, never through the service under test. */
public final class HoldFixtures {

    public static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    public static final long PRICE_CENTS = 5000;

    private final JdbcClient jdbc;
    private final AtomicInteger seatNumbers = new AtomicInteger();

    public HoldFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void truncateAll() {
        jdbc.sql("TRUNCATE order_line, ticket_order, hold_request, seat_hold, event_seat, event, seat, venue")
                .update();
    }

    public long venue() {
        return jdbc.sql("INSERT INTO venue (name) VALUES ('Fixture Hall') RETURNING id")
                .query(Long.class)
                .single();
    }

    public long event(long venueId, String status, Instant salesOpenAt, Instant salesCloseAt) {
        return jdbc.sql("""
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (:venue, 'Fixture event', :starts, :open, :close, :status, 'CAD') RETURNING id""")
                .param("venue", venueId)
                .param("starts", utc(NOW.plus(30, ChronoUnit.DAYS)))
                .param("open", utc(salesOpenAt))
                .param("close", utc(salesCloseAt))
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public long onSaleEvent(long venueId) {
        return event(venueId, "ON_SALE", NOW.minus(1, ChronoUnit.DAYS), NOW.plus(29, ChronoUnit.DAYS));
    }

    /** Fresh seats, each offered by the event at PRICE_CENTS, returned in ascending id order. */
    public List<Long> eventSeats(long eventId, long venueId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seatId = jdbc.sql("""
                            INSERT INTO seat (venue_id, section, row_label, seat_number)
                            VALUES (:venue, 'Floor', 'A', :number) RETURNING id""")
                    .param("venue", venueId)
                    .param("number", seatNumbers.incrementAndGet())
                    .query(Long.class)
                    .single();
            ids.add(jdbc.sql("""
                            INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
                            VALUES (:event, :seat, :venue, :price) RETURNING id""")
                    .param("event", eventId)
                    .param("seat", seatId)
                    .param("venue", venueId)
                    .param("price", PRICE_CENTS)
                    .query(Long.class)
                    .single());
        }
        return ids;
    }

    public void hold(long eventSeatId, String owner, String status, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                        VALUES (:seat, :group, :owner, :status, :expires, :created)""")
                .param("seat", eventSeatId)
                .param("group", UUID.randomUUID())
                .param("owner", owner)
                .param("status", status)
                .param("expires", utc(expiresAt))
                .param("created", utc(NOW))
                .update();
    }

    /** Rows on a seat that count as claimed: ACTIVE or CONVERTED, the claim index's predicate. */
    public int claimsOn(long eventSeatId) {
        return jdbc.sql("SELECT count(*) FROM seat_hold WHERE event_seat_id = :seat AND status IN ('ACTIVE', 'CONVERTED')")
                .param("seat", eventSeatId)
                .query(Integer.class)
                .single();
    }

    public List<Long> seatsInGroup(UUID holdGroupId) {
        return jdbc.sql("SELECT event_seat_id FROM seat_hold WHERE hold_group_id = :group ORDER BY event_seat_id")
                .param("group", holdGroupId)
                .query(Long.class)
                .list();
    }

    public List<String> statusesOwnedBy(String owner) {
        return jdbc.sql("SELECT status FROM seat_hold WHERE owner = :owner ORDER BY event_seat_id")
                .param("owner", owner)
                .query(String.class)
                .list();
    }

    public int groupsOwnedBy(String owner) {
        return jdbc.sql("SELECT count(DISTINCT hold_group_id) FROM seat_hold WHERE owner = :owner")
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    public int holdRequestsOwnedBy(String owner) {
        return jdbc.sql("SELECT count(*) FROM hold_request WHERE owner = :owner")
                .param("owner", owner)
                .query(Integer.class)
                .single();
    }

    public String storedResponse(String owner, String idempotencyKey) {
        return jdbc.sql("SELECT response_json::text FROM hold_request WHERE owner = :owner AND idempotency_key = :key")
                .param("owner", owner)
                .param("key", idempotencyKey)
                .query(String.class)
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
```

- [ ] **Step 6: Write the failing service tests**

`src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`:

```java
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

    private static final List<Class<?>> SERVICES = List.of(HoldService.class);

    @Test
    void everyPublicServiceMethodRunsReadCommitted() {
        for (Class<?> service : SERVICES) {
            for (Method method : service.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                Transactional transactional = method.getAnnotation(Transactional.class);
                assertThat(transactional).as("%s#%s is @Transactional", service.getSimpleName(), method.getName())
                        .isNotNull();
                assertThat(transactional.isolation())
                        .as("%s#%s isolation", service.getSimpleName(), method.getName())
                        .isEqualTo(Isolation.READ_COMMITTED);
            }
        }
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/service/HoldServiceTest.java` *(Erratum E5: spec rev 2.6.3 makes `DRAFT` `not_found`, which supersedes `aDraftEventIsNotOpenYet`.)*:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class HoldServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

    HoldFixtures fixtures;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @Test
    void holdsSeatsInAscendingOrderAndRecordsTheResult() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);

        HoldResult result = holds.hold(command("alice", List.of(seats.get(1), seats.get(0)), "k1"));

        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.seats())
                .extracting(HoldResult.HeldSeat::eventSeatId)
                .containsExactly(seats.get(0), seats.get(1));
        assertThat(result.totalCents()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
        assertThat(result.currency()).isEqualTo("CAD");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(properties.holdTtl()));
        assertThat(fixtures.seatsInGroup(result.holdGroupId())).containsExactlyElementsOf(seats);
        assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("ACTIVE");
        // The stored response is the result. Read it back the way replay does, never as raw text (spec §5, rev 2.6.2).
        // Not readTree-vs-valueToTree: parsing yields IntNode for small numbers, valueToTree keeps the long fields as
        // LongNode, and IntNode(9) is not equal to LongNode(9) — observed with Jackson 3.1.5.
        assertThat(HoldService.STORED_FORM.readValue(fixtures.storedResponse("alice", "k1"), HoldResult.class))
                .isEqualTo(result);
    }

    @Test
    void storesExpiryAtTheDatabasesPrecision() {
        clock.set(NOW.plusNanos(1_234_567));
        HoldResult result = holds.hold(command("alice", fixtures.eventSeats(eventId, venueId, 1), "k1"));
        assertThat(result.expiresAt().getNano() % 1_000).isZero();
    }

    @Test
    void replaysTheStoredResultForTheSameKeyAndRequest() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult first = holds.hold(command("alice", seats, "k1"));

        // Past expiry: a replay reports what happened, including the original expires_at; it does not re-check.
        clock.set(first.expiresAt().plusSeconds(60));
        HoldResult replayed = holds.hold(command("alice", List.of(seats.get(1), seats.get(0)), "k1"));

        assertThat(replayed).isEqualTo(first);
        assertThat(fixtures.groupsOwnedBy("alice")).isEqualTo(1);
    }

    @Test
    void theSameKeyForADifferentRequestIsRejected() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        holds.hold(command("alice", List.of(seats.get(0)), "k1"));

        assertThat(rejection(() -> holds.hold(command("alice", List.of(seats.get(1)), "k1"))).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aFailedRequestIsNotRecordedSoItsRetryRunsAgain() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.hold(seat, "bob", "ACTIVE", NOW.plusSeconds(600));

        assertThat(rejection(() -> holds.hold(command("alice", List.of(seat), "k1"))).errorCode())
                .isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();

        // Bob's hold lapses; the same key now runs the request for real instead of replaying a failure.
        clock.set(NOW.plusSeconds(600));
        assertThat(holds.hold(command("alice", List.of(seat), "k1")).seats()).hasSize(1);
    }

    @Test
    void aTakenSeatIsNamedAndNothingIsHeld() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        fixtures.hold(seats.get(1), "bob", "ACTIVE", NOW.plusSeconds(600));

        DomainException rejected = rejection(() -> holds.hold(command("alice", seats, "k1")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(rejected.details()).containsEntry("seat_ids", List.of(seats.get(1)));
        // All-or-nothing: the free seat claimed before the conflict was rolled back with it.
        assertThat(fixtures.statusesOwnedBy("alice")).isEmpty();
    }

    @Test
    void aSoldSeatCannotBeHeld() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.hold(seat, "bob", "CONVERTED", NOW.minusSeconds(60));

        assertThat(rejection(() -> holds.hold(command("alice", List.of(seat), "k1"))).errorCode())
                .isEqualTo(ErrorCode.SEAT_TAKEN);
    }

    @Test
    void aHoldLapsingExactlyNowIsExpiredAndItsSeatReclaimed() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.hold(seat, "bob", "ACTIVE", NOW);

        holds.hold(command("alice", List.of(seat), "k1"));

        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("EXPIRED");
        assertThat(fixtures.claimsOn(seat)).isEqualTo(1);
    }

    @Test
    void aMissingEventIsNotFound() {
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", -1L, List.of(1L), "k1"))).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aDraftEventIsNotOpenYet() {
        long draft = fixtures.event(venueId, "DRAFT", NOW.minusSeconds(60), NOW.plusSeconds(60));
        long seat = fixtures.eventSeats(draft, venueId, 1).getFirst();
        DomainException rejected = rejection(() -> holds.hold(new HoldCommand("alice", draft, List.of(seat), "k1")));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.SALES_NOT_OPEN);
        assertThat(rejected.details()).containsEntry("sales_open_at", NOW.minusSeconds(60));
    }

    @Test
    void theWindowIsHalfOpen() {
        long later = fixtures.event(venueId, "ON_SALE", NOW.plusMillis(1), NOW.plusSeconds(60));
        long seatLater = fixtures.eventSeats(later, venueId, 1).getFirst();
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", later, List.of(seatLater), "k1"))).errorCode())
                .isEqualTo(ErrorCode.SALES_NOT_OPEN);

        long closing = fixtures.event(venueId, "ON_SALE", NOW.minusSeconds(60), NOW);
        long seatClosing = fixtures.eventSeats(closing, venueId, 1).getFirst();
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", closing, List.of(seatClosing), "k2"))).errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);

        long opening = fixtures.event(venueId, "ON_SALE", NOW, NOW.plusSeconds(60));
        long seatOpening = fixtures.eventSeats(opening, venueId, 1).getFirst();
        assertThat(holds.hold(new HoldCommand("alice", opening, List.of(seatOpening), "k3")).seats()).hasSize(1);
    }

    @Test
    void aCancelledEventIsClosed() {
        long cancelled = fixtures.event(venueId, "CANCELLED", NOW.minusSeconds(60), NOW.plusSeconds(60));
        long seat = fixtures.eventSeats(cancelled, venueId, 1).getFirst();
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", cancelled, List.of(seat), "k1"))).errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void duplicateSeatIdsAreNamed() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(seat, seat), "k1")));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "event_seat_ids").containsEntry("seat_ids", List.of(seat));
    }

    @Test
    void aSeatThisEventDoesNotOfferIsNamed() {
        long otherEvent = fixtures.onSaleEvent(venueId);
        long foreign = fixtures.eventSeats(otherEvent, venueId, 1).getFirst();
        long own = fixtures.eventSeats(eventId, venueId, 1).getFirst();

        DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(own, foreign), "k1")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("seat_ids", List.of(foreign));
    }

    @Test
    void tooManySeatsIsRejectedBeforeTheKeyIsClaimed() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, properties.maxSeatsPerHold() + 1);
        holds.hold(command("alice", List.of(seats.getFirst()), "k1"));

        // Reusing a committed key for a different request: had the claim run first, this would be
        // idempotency_key_reused (decision 3).
        DomainException rejected = rejection(() -> holds.hold(command("alice", seats, "k1")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.TOO_MANY_SEATS);
        assertThat(rejected.details()).containsEntry("max_seats", properties.maxSeatsPerHold());
    }

    @Test
    void theActiveGroupCapCountsOnlyLiveGroups() {
        int cap = properties.maxActiveHoldGroups();
        List<Long> seats = fixtures.eventSeats(eventId, venueId, cap + 2);
        // A lapsed group does not count (spec §4).
        fixtures.hold(seats.get(cap + 1), "alice", "ACTIVE", NOW.minusSeconds(1));
        for (int i = 0; i < cap; i++) {
            holds.hold(command("alice", List.of(seats.get(i)), "k" + i));
        }

        DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(seats.get(cap)), "over")));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.HOLD_LIMIT_EXCEEDED);
        assertThat(rejected.details()).containsEntry("max_active_hold_groups", cap);
    }

    @Test
    void malformedParametersAreNamedBeforeTheKeyIsClaimed() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        holds.hold(command("alice", List.of(seat), "k1"));

        // Reuses the committed key for a different request: had the claim run first, this would be
        // idempotency_key_reused. So this case fails if validation ever moves behind the claim (decision 3).
        DomainException noSeats = rejection(() -> holds.hold(command("alice", List.of(), "k1")));
        assertThat(noSeats.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(noSeats.details()).containsEntry("parameter", "event_seat_ids");

        // The last key holds a NUL: Postgres text cannot store one, so binding it would fail with 22021 (decision 8).
        List<String> badKeys = new ArrayList<>(
                List.of("", "   ", "x".repeat(HoldService.MAX_IDEMPOTENCY_KEY_LENGTH + 1), "k\u0000ey"));
        badKeys.add(null);
        for (String key : badKeys) {
            DomainException rejected = rejection(() -> holds.hold(command("alice", List.of(seat), key)));
            assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(rejected.details()).containsEntry("parameter", "idempotency_key");
        }
        DomainException nullSeat = rejection(() -> holds.hold(command("alice", Arrays.asList(seat, null), "k1")));
        assertThat(nullSeat.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(nullSeat.details()).containsEntry("parameter", "event_seat_ids");
        // Only the first, well-formed request was recorded.
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isEqualTo(1);
    }

    private HoldCommand command(String owner, List<Long> seats, String key) {
        return new HoldCommand(owner, eventId, seats, key);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
```

Run: `./mvnw -q test -Dtest='TransactionIsolationTest,HoldServiceTest'`
Expected: compilation FAILS: `cannot find symbol: class HoldService`.

- [ ] **Step 7: Implement the service and the seat lookup**

In `EventSeatRepository.java`, replace the interface body:

```java
package io.github.markusluisflores.frontrow.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    /** The requested seats this event actually offers. event_seat rows are never written, so a JPA read is safe. */
    @Query("SELECT s FROM EventSeat s WHERE s.event.id = :eventId AND s.id IN :ids")
    List<EventSeat> findOfferedSeats(long eventId, Collection<Long> ids);
}
```

`src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java` *(Erratum E2: the built `requireWellFormed` also rejects a key that is not well-formed UTF-16. Erratum E5: spec rev 2.6.3 moves the `now` read to after the event-row lock.)*:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.domain.HoldRequestFingerprint;
import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.Money;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.persistence.StoredHoldRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hold creation, spec §5: one all-or-nothing transaction that takes its locks in the global order — the
 * idempotency key, the owner's advisory lock, the event row, then each seat ascending — and records only success.
 */
@Service
public class HoldService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    /**
     * The stored replay form, deliberately independent of the application's Jackson configuration: a later global
     * setting (a naming strategy, say) must not break replay of rows stored before it (plan 3A decision 6).
     */
    static final JsonMapper STORED_FORM = JsonMapper.builder().build();

    private final LockingGateway gateway;
    private final EventSeatRepository eventSeats;
    private final FrontRowProperties properties;
    private final Clock clock;

    public HoldService(
            LockingGateway gateway, EventSeatRepository eventSeats, FrontRowProperties properties, Clock clock) {
        this.gateway = gateway;
        this.eventSeats = eventSeats;
        this.properties = properties;
        this.clock = clock;
    }

    /** READ COMMITTED, explicitly: every check after a lock wait must see what committed during the wait (spec §5). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HoldResult hold(HoldCommand command) {
        requireWellFormed(command);
        // On the raw list, before the fingerprint and the key claim: an oversized request costs no sort and no lock.
        if (command.eventSeatIds().size() > properties.maxSeatsPerHold()) {
            throw new DomainException(ErrorCode.TOO_MANY_SEATS, Map.of("max_seats", properties.maxSeatsPerHold()));
        }
        // timestamptz keeps microseconds; one truncation here keeps every Java check and SQL bind on one instant.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = HoldRequestFingerprint.of(command.eventId(), command.eventSeatIds());
        try {
            if (!gateway.claimIdempotencyKey(command.owner(), command.idempotencyKey(), fingerprint, now)) {
                return replay(command, fingerprint);
            }
            gateway.lockOwner(command.owner());
            EventRow event = gateway.lockEventForShare(command.eventId())
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
            SalesGate.requireOpen(event, now);
            List<Long> seatIds = distinctAscending(command.eventSeatIds());
            Map<Long, EventSeat> offered = offeredSeats(event.id(), seatIds);
            requireUnderGroupCap(command.owner(), now);

            UUID groupId = UUID.randomUUID();
            // Truncated again in case a configured TTL carries sub-microsecond precision.
            Instant expiresAt = now.plus(properties.holdTtl()).truncatedTo(ChronoUnit.MICROS);
            claimSeats(seatIds, groupId, command.owner(), expiresAt, now);

            HoldResult result = result(groupId, event, seatIds, offered, expiresAt);
            gateway.recordHoldResponse(
                    command.owner(), command.idempotencyKey(), groupId, STORED_FORM.writeValueAsString(result));
            return result;
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    /** Each seat is finished — lazy expiry, then insert — before the next one is touched (spec §5 step 4). */
    private void claimSeats(List<Long> seatIds, UUID groupId, String owner, Instant expiresAt, Instant now) {
        for (long seatId : seatIds) {
            try {
                gateway.lockActiveHoldForSeat(seatId)
                        .filter(hold -> !HoldRules.isLive(hold.status(), hold.expiresAt(), now))
                        .ifPresent(lapsed -> gateway.expireHold(lapsed.id(), now));
                // A live hold is left alone: this insert then fails on uq_claimed_seat, where seat_taken is decided.
                gateway.insertHold(seatId, groupId, owner, expiresAt, now);
            } catch (DataAccessException failure) {
                throw SqlFailures.translate(failure, seatId);
            }
        }
    }

    private HoldResult replay(HoldCommand command, String fingerprint) {
        StoredHoldRequest stored = gateway.findHoldRequest(command.owner(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("a conflicting hold_request row was not found"));
        if (!stored.requestHash().equals(fingerprint)) {
            throw DomainException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return STORED_FORM.readValue(stored.responseJson(), HoldResult.class);
    }

    private List<Long> distinctAscending(List<Long> requested) {
        Set<Long> seen = new HashSet<>();
        Set<Long> duplicates = new TreeSet<>();
        for (Long id : requested) {
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        if (!duplicates.isEmpty()) {
            throw invalidSeats(duplicates);
        }
        return new ArrayList<>(new TreeSet<>(seen));
    }

    private Map<Long, EventSeat> offeredSeats(long eventId, List<Long> seatIds) {
        Map<Long, EventSeat> offered = new HashMap<>();
        for (EventSeat seat : eventSeats.findOfferedSeats(eventId, seatIds)) {
            offered.put(seat.getId(), seat);
        }
        Set<Long> missing = new TreeSet<>(seatIds);
        missing.removeAll(offered.keySet());
        if (!missing.isEmpty()) {
            throw invalidSeats(missing);
        }
        return offered;
    }

    private void requireUnderGroupCap(String owner, Instant now) {
        if (gateway.countActiveHoldGroups(owner, now) >= properties.maxActiveHoldGroups()) {
            throw new DomainException(
                    ErrorCode.HOLD_LIMIT_EXCEEDED, Map.of("max_active_hold_groups", properties.maxActiveHoldGroups()));
        }
    }

    private static HoldResult result(
            UUID groupId, EventRow event, List<Long> seatIds, Map<Long, EventSeat> offered, Instant expiresAt) {
        List<HoldResult.HeldSeat> seats = new ArrayList<>();
        Money total = Money.ofCents(0, event.currency());
        for (long seatId : seatIds) {
            long price = offered.get(seatId).getPriceCents();
            seats.add(new HoldResult.HeldSeat(seatId, price));
            total = total.plus(Money.ofCents(price, event.currency()));
        }
        return new HoldResult(groupId, event.id(), seats, total.cents(), total.currency(), expiresAt);
    }

    private static void requireWellFormed(HoldCommand command) {
        String key = command.idempotencyKey();
        // Postgres text cannot hold U+0000: binding it fails with 22021, a 500 rather than invalid_request.
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH || key.indexOf('\u0000') >= 0) {
            throw invalidParameter("idempotency_key");
        }
        if (command.eventSeatIds().isEmpty()) {
            throw invalidParameter("event_seat_ids");
        }
    }

    private static DomainException invalidParameter(String parameter) {
        return new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }

    private static DomainException invalidSeats(Set<Long> seatIds) {
        return new DomainException(
                ErrorCode.INVALID_REQUEST, Map.of("parameter", "event_seat_ids", "seat_ids", List.copyOf(seatIds)));
    }
}
```

- [ ] **Step 8: Run the Task 3 tests**

Run: `./mvnw -q test -Dtest='HoldRequestFingerprintTest,SqlFailuresTest,TransactionIsolationTest,HoldServiceTest'`
Expected: PASS, 27 tests (4 + 5 + 1 + 17).

**If `findOfferedSeats` fails to start** with "parameter name not found", the build isn't passing `-parameters` to the compiler. In that case add `@Param("eventId")` and `@Param("ids")` (`org.springframework.data.repository.query.Param`) and say so in the task report. `EventRepository.findStatusTextById` binding `:id` with no `@Param` suggests the flag is on.

- [ ] **Step 9: Architecture checks and full build**

Run the two greps from `CLAUDE.md`.
Expected: `domain` imports only `java.*`, and lock vocabulary appears only in `LockingGateway.java`.

Run: `./mvnw verify`
Expected: BUILD FAILURE from SpotBugs, with one Medium `EI_EXPOSE_REP2` finding on `new HoldService(...)`, for the field `gateway`. Tests are already green at this point (134 runs). This failure is expected; the next step addresses it (decision 11).

- [ ] **Step 10: Scope the SpotBugs exclusion to service constructors, then commit**

Create `spotbugs-exclude.xml` at the repository root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Application services are Spring singletons. Their constructors receive other singletons (the locking gateway) and
  keep them by design: sharing is the point, and copying would be wrong. Scoped to that one pattern, on constructors
  only, of classes named *Service in the service package - records there, and a service storing a caller's
  mutable argument anywhere else, are still reported. (Plan 3A decision 11.)
-->
<FindBugsFilter
        xmlns="https://github.com/spotbugs/filter/3.0.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="https://github.com/spotbugs/filter/3.0.0 https://raw.githubusercontent.com/spotbugs/spotbugs/3.1.0/spotbugs/etc/findbugsfilter.xsd">
    <Match>
        <Class name="~io\.github\.markusluisflores\.frontrow\.service\.[A-Za-z]+Service"/>
        <Method name="&lt;init&gt;"/>
        <Bug pattern="EI_EXPOSE_REP2"/>
    </Match>
</FindBugsFilter>
```

In `pom.xml`, in the `spotbugs-maven-plugin` `<configuration>`, add this line after `<includeTests>false</includeTests>`:

```xml
                    <excludeFilterFile>spotbugs-exclude.xml</excludeFilterFile>
```

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0. If any finding **other than** that one remains, stop and report BLOCKED. Don't widen the filter.

```bash
git add src/main/java/io/github/markusluisflores/frontrow/domain/HoldRequestFingerprint.java \
  src/main/java/io/github/markusluisflores/frontrow/service \
  src/main/java/io/github/markusluisflores/frontrow/persistence/EventSeatRepository.java \
  src/test/java/io/github/markusluisflores/frontrow/support/TestClock.java \
  src/test/java/io/github/markusluisflores/frontrow/support/ServiceTestConfiguration.java \
  src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java \
  src/test/java/io/github/markusluisflores/frontrow/domain/HoldRequestFingerprintTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service spotbugs-exclude.xml pom.xml
git commit -m "feat(service): add hold creation with idempotent replay"
```

---

### Task 4: The concurrency tests that need only holds (spec §5 tests 1, 3, 4, 5, 7)

**Files:**
- Create: `src/test/java/io/github/markusluisflores/frontrow/support/Race.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`
- Modify: `CLAUDE.md` ("Current state")

**Interfaces:**
- Consumes: `HoldService#hold`, `HoldCommand`, `HoldResult`, `TestClock`, `ServiceTestConfiguration` and `HoldFixtures` (Task 3).
- Produces, for Plan 3B's tests 2 and 6: `Race.run(ExecutorService pool, List<Callable<T>> calls) : List<Race.Outcome<T>>`, and `Race.Outcome<T>(T result, Throwable failure)` with `errorCode() : ErrorCode` (null when the failure isn't a `DomainException`).

- [ ] **Step 1: Create the race harness**

`src/test/java/io/github/markusluisflores/frontrow/support/Race.java`:

```java
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
```

- [ ] **Step 2: Write the concurrency tests**

`src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java` *(Erratum E4: the restore javadoc on `withoutTheClaimIndexTheSameRaceHasSeveralWinners` overstates its guarantee.)*:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.Race;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec §5's centrepiece tests that need only hold creation: 1, 3, 4, 5 and 7. Tests 2 and 6 need confirm and cancel
 * and arrive with Plan 3B. Every racer runs in its own transaction on its own virtual thread, against real Postgres.
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class HoldConcurrencyTest {

    private static final int RACERS = 8;
    private static final int REPETITIONS = 20;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    /** Test 1. Distinct owners and keys, so neither the advisory lock nor idempotency serialises the race. */
    @Test
    void holdRaceHasExactlyOneWinnerPerSeat() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();

            List<Race.Outcome<HoldResult>> outcomes = raceForOneSeat(seat, "r" + rep);

            assertThat(outcomes).as("repetition %d", rep).filteredOn(Race.Outcome::won).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.won()).allSatisfy(outcome -> {
                assertThat(outcome.errorCode()).as("%s", outcome.failure()).isEqualTo(ErrorCode.SEAT_TAKEN);
            });
            assertThat(fixtures.claimsOn(seat)).isEqualTo(1);
        }
    }

    /**
     * Test 1's own proof: drop uq_claimed_seat and the same race lets several racers through (decision 4). The index
     * is shared by every later test in this context, so its restore must run even when the race or the cleanup fails,
     * and the timeout stops a stuck restore from hanging the build.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void withoutTheClaimIndexTheSameRaceHasSeveralWinners() throws Exception {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        jdbc.sql("DROP INDEX uq_claimed_seat").update();
        try {
            List<Race.Outcome<HoldResult>> outcomes = raceForOneSeat(seat, "no-index");
            assertThat(outcomes)
                    .as("only uq_claimed_seat makes the race safe")
                    .filteredOn(Race.Outcome::won)
                    .hasSizeGreaterThan(1);
        } finally {
            try {
                // Only this test's rows, which would block the index. A TRUNCATE would need an exclusive table lock.
                jdbc.sql("DELETE FROM seat_hold WHERE event_seat_id = :seat")
                        .param("seat", seat)
                        .update();
            } finally {
                jdbc.sql("CREATE UNIQUE INDEX IF NOT EXISTS uq_claimed_seat ON seat_hold (event_seat_id)"
                                + " WHERE status IN ('ACTIVE', 'CONVERTED')")
                        .update();
            }
        }
    }

    /** Test 3. Overlapping seat sets, requested in different orders: no deadlock, no partial hold. */
    @Test
    void overlappingMultiSeatHoldsNeverDeadlockOrHoldPartially() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            List<Long> s = fixtures.eventSeats(eventId, venueId, 4);
            List<List<Long>> requests = List.of(
                    List.of(s.get(0), s.get(1), s.get(2)),
                    List.of(s.get(2), s.get(1), s.get(3)),
                    List.of(s.get(3), s.get(0)),
                    List.of(s.get(1), s.get(3), s.get(0), s.get(2)));
            List<Callable<HoldResult>> calls = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                String owner = "overlap-r" + rep + "-" + i;
                List<Long> seats = requests.get(i);
                calls.add(() -> holds.hold(new HoldCommand(owner, eventId, seats, "key-" + owner)));
            }

            List<Race.Outcome<HoldResult>> outcomes = Race.run(pool, calls);

            assertThat(outcomes).as("repetition %d", rep).anyMatch(Race.Outcome::won);
            for (int i = 0; i < outcomes.size(); i++) {
                Race.Outcome<HoldResult> outcome = outcomes.get(i);
                String owner = "overlap-r" + rep + "-" + i;
                if (outcome.won()) {
                    assertThat(fixtures.seatsInGroup(outcome.result().holdGroupId()))
                            .containsExactlyInAnyOrderElementsOf(requests.get(i));
                } else {
                    // seat_taken only: a deadlock would surface as contention_retry, anything else as unexpected.
                    assertThat(outcome.errorCode()).as("%s", outcome.failure()).isEqualTo(ErrorCode.SEAT_TAKEN);
                    assertThat(fixtures.statusesOwnedBy(owner)).as("no partial hold").isEmpty();
                }
            }
        }
    }

    /** Test 4. One owner, more parallel calls than the cap, disjoint seats: only the cap can reject them. */
    @Test
    void theActiveGroupCapHoldsUnderConcurrency() throws Exception {
        int cap = properties.maxActiveHoldGroups();
        int callers = cap + 5;
        for (int rep = 0; rep < REPETITIONS / 2; rep++) {
            String owner = "capped-r" + rep;
            List<Long> seats = fixtures.eventSeats(eventId, venueId, callers);
            List<Callable<HoldResult>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                List<Long> one = List.of(seats.get(i));
                String key = "key-" + i;
                calls.add(() -> holds.hold(new HoldCommand(owner, eventId, one, key)));
            }

            List<Race.Outcome<HoldResult>> outcomes = Race.run(pool, calls);

            assertThat(outcomes).as("repetition %d", rep).filteredOn(Race.Outcome::won).hasSize(cap);
            assertThat(outcomes).filteredOn(outcome -> !outcome.won()).allSatisfy(outcome -> {
                assertThat(outcome.errorCode()).as("%s", outcome.failure()).isEqualTo(ErrorCode.HOLD_LIMIT_EXCEEDED);
            });
            assertThat(fixtures.groupsOwnedBy(owner)).isEqualTo(cap);
        }
    }

    /** Test 5. The same key and request, twice in parallel: one group, and both callers get it. */
    @Test
    void aConcurrentIdempotentRetryYieldsOneGroup() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            String owner = "retry-r" + rep;
            HoldCommand command = new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "same-key");
            List<Callable<HoldResult>> calls = List.of(() -> holds.hold(command), () -> holds.hold(command));

            List<Race.Outcome<HoldResult>> outcomes = Race.run(pool, calls);

            assertThat(outcomes).as("repetition %d", rep).allMatch(Race.Outcome::won);
            // Record equality: the replayed result is the stored one, deserialized — JSON-equal by construction.
            assertThat(outcomes.get(0).result()).isEqualTo(outcomes.get(1).result());
            assertThat(fixtures.groupsOwnedBy(owner)).isEqualTo(1);
            assertThat(fixtures.holdRequestsOwnedBy(owner)).isEqualTo(1);
        }
    }

    /**
     * Test 7. Sequential, but it guards the correctness claim: with no sweeper, a lapsed hold must not block the next
     * buyer. Plan 3A has no sweeper at all; Plan 3B must keep this test running with its sweeper disabled.
     */
    @Test
    void lazyExpiryFreesSeatsWithoutTheSweeper() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult first = holds.hold(new HoldCommand("alice", eventId, seats, "a-1"));

        clock.set(first.expiresAt().plusMillis(1));
        HoldResult second = holds.hold(new HoldCommand("bob", eventId, seats, "b-1"));

        assertThat(fixtures.seatsInGroup(second.holdGroupId())).containsExactlyElementsOf(seats);
        assertThat(fixtures.statusesOwnedBy("alice")).containsOnly("EXPIRED");
    }

    private List<Race.Outcome<HoldResult>> raceForOneSeat(long seat, String tag) throws Exception {
        List<Callable<HoldResult>> calls = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            String owner = "racer-" + tag + "-" + i;
            calls.add(() -> holds.hold(new HoldCommand(owner, eventId, List.of(seat), "key-" + owner)));
        }
        return Race.run(pool, calls);
    }
}
```

- [ ] **Step 3: Run the concurrency tests**

Run: `./mvnw -q test -Dtest=HoldConcurrencyTest`
Expected: PASS, 6 tests.

Then run the class **three more times** in a row: `for i in 1 2 3; do ./mvnw -q test -Dtest=HoldConcurrencyTest || break; done`.
Expected: three PASS runs. A flake here is a finding, not noise. Report it with the failing assertion text, and don't retry until green.

- [ ] **Step 4: Mutation check. Test 7 must fail if lazy expiry is broken**

In `HoldService.claimSeats`, temporarily replace the `.ifPresent(...)` line with `.ifPresent(lapsed -> {});`, so the lapsed hold is no longer expired.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=HoldConcurrencyTest#lazyExpiryFreesSeatsWithoutTheSweeper`
Expected: FAIL with `DomainException: seat_taken` on Bob's hold. The Spotless flag is needed for the same reason as in Task 1 Step 5; a Spotless `format violations` failure is not this result.

Restore the line, and run `git diff src/main` to confirm no change remains. Record the observed failure in the task report.

(Test 1's own mutation proof doesn't need a manual step. `withoutTheClaimIndexTheSameRaceHasSeveralWinners` runs on every build.)

- [ ] **Step 5: Update the current state in `CLAUDE.md`**

Run `./mvnw verify` and note the total `Tests run:` figure the Surefire summary reports. Call it **T**.

Replace the bold paragraph and the test-count sentence under "## Current state" with the text below. Use the measured **T**; never an estimate.

```markdown
**Phase 1 plans 1 (foundation), 2 (domain foundations) and 3A (hold creation) are complete.
Together they provide: a Maven build, CI, the V1 schema migration, a principal-propagation spike, a pure
`domain` package (money, statuses, sales window, hold rules, the request fingerprint), the shared error
contract (`error`), JPA entities and repositories over all eight V1 tables (`persistence`), the locking
gateway (`persistence/LockingGateway.java`), and the first write path: `service/HoldService`, hold creation
with idempotent replay. No confirm, release, sweeper, REST endpoint or MCP tool exists yet.**
T test runs pass, including spec §5 concurrency tests 1, 3, 4, 5 and 7 and a proof that test 1 fails
without `uq_claimed_seat`. Phase 1 is now six plans: 3B (confirm, release, cancel service, sweeper,
tests 2 and 6), 4 (REST + security chains) and 5 (MCP) remain.
```

Replace the literal `T` with the number. Leave the rest of the section (spec revision, ADRs, plan paths) as it is, and add one sentence after the Plan 2 sentence: `Plan 3A: docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md.`

- [ ] **Step 6: Final checks and commit**

Run both `CLAUDE.md` greps again.
Expected: `domain` imports only `java.*`, and lock vocabulary appears only in `LockingGateway.java`.

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, and the test count equals the **T** written into `CLAUDE.md`.

```bash
git add src/test/java/io/github/markusluisflores/frontrow/support/Race.java \
  src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java CLAUDE.md
git commit -m "test(service): race hold creation against real Postgres"
```

---

## Spec coverage (this plan's share)

| Requirement | Where |
|---|---|
| §5 hold creation step 1: key inserted first, `ON CONFLICT DO NOTHING`, waits on an in-flight duplicate | Task 2 (`claimIdempotencyKey`, wait and rollback tests); Task 3 |
| §5 step 2: per-owner advisory lock | Task 3 (`HoldService`); lock proven by Plan 2 |
| §5 step 3: event `FOR SHARE`, then `not_found` / `sales_not_open` / `sales_closed` / `invalid_request` / `too_many_seats` / `hold_limit_exceeded` | Task 3 (one test per code; half-open window boundaries) |
| §5 step 4: seat by seat, ascending; lazy expiry as an immediate `UPDATE`; insert reaches the database before the next seat | Task 3 (`claimSeats`); Task 2 (`insertHold`, insert-wait proof); Task 4 test 7 and its mutation check |
| §5 step 5: store the response, commit | Task 2 (`recordHoldResponse`); Task 3 |
| §5: only `uq_claimed_seat` → `seat_taken`, naming the seat; other `23505` → 500; `40P01`/`40001` → `contention_retry` | Task 3 (`SqlFailures`, unit-tested; `aTakenSeatIsNamedAndNothingIsHeld`) |
| §5: READ COMMITTED set explicitly | Task 3 (`@Transactional(isolation = READ_COMMITTED)`; `TransactionIsolationTest`) |
| §5 *Idempotent replay*: canonical hash; only successes recorded; concurrent retry waits then replays; different hash → `idempotency_key_reused`; replay after expiry returns the original | Task 3 (fingerprint tests; replay, reuse, failed-then-retried and post-expiry tests); Task 4 test 5 |
| §5 rev 2.6.2: replay is JSON-equal, not byte-equal | Task 2 (`readsBackTheStoredResponseAsEqualJson`); Task 3 (stored JSON deserialized to `HoldResult` and compared as a record); Task 4 test 5 (record equality) |
| §5 centrepiece tests 1, 3, 4, 5, 7 | Task 4 |
| §5 / §12: test 1 genuinely fails if `uq_claimed_seat` is removed | Task 4 (`withoutTheClaimIndexTheSameRaceHasSeveralWinners`, every build) |
| §6 guardrails: seats-per-hold cap, active-group cap, idempotency key required | Task 3 |
| §6: `hold_seats` returns group id, seats, total, `expires_at` | Task 3 (`HoldResult`) |
| §6 guardrails: request size. `hold()` rejects more than `max-seats-per-hold` ids before the fingerprint and the key claim (decision 3), but `HoldCommand` copies whatever list it is given, and nothing bounds the request body | Task 3 (`tooManySeatsIsRejectedBeforeTheKeyIsClaimed`); **Plans 4 and 5** bound the request at the adapter: the `event_seat_ids` JSON array length and the request body size |
| §5 centrepiece tests 2 and 6; confirm; release; event PATCH/cancel; sweeper; `hold_request` purge; `55P03` → `contention_retry` on PATCH | **Plan 3B** |
| PR #10: seat_hold row-lock wait test | Task 1 (both clauses, each with a mutation check); ADR-005 updated in Task 2 Step 5 |
| PR #10: READ COMMITTED explicitly | Task 3 |
| PR #10: flush inside the translation scope | Decision 1: no JPA write in 3A; **3B** confirm owns it |
| PR #10: no `SeatHold` entities in locking transactions; does `transitionTo` survive | Decision 1: none loaded in 3A; `transitionTo` decided in **3B** |
| PR #10: a caught gateway exception marks the transaction rollback-only | Decision 2; `SqlFailures` always rethrows |
| PR #10: `jsonb` replay is JSON-equal | Task 2, Task 3, Task 4 |
| PR #10: pid-scoped wait helper; assert the properties failure cause; `expires_at == now` boundary; `LOCK TABLE` in the grep | Task 1 |
| PR #10: convert `persistence.HoldRow` → `HoldRules.HoldRow` without `domain` importing `persistence` | **3B** (confirm is the first caller of `confirmOutcome`) |
| PR #10: `a648936` javadoc reflow | Task 1 Step 7 |
| Plan 2 review: `hold_request.created_at` has no default, so the `Clock` value is passed | Task 2 (`claimIdempotencyKey` binds `now`) |
| Plan 2 review: `uq_claimed_seat` is an index, so `ON CONFLICT ON CONSTRAINT` can't name it | Decision 4: no `ON CONFLICT` on `seat_hold` at all; the insert fails and is translated |
| Plan 2 review: owner-scoping between an order and its holds is a service duty | **3B** (confirm and release) |

## Self-review record

Run by the plan author on 2026-10-14 against the `superpowers:writing-plans` checklist.

1. **Spec coverage.** The table above has a row for every §5 hold-creation requirement, every PR #10 carry-forward item and every Plan 2 review carry-forward. Items outside 3A's scope are marked **3B** or **Plans 4 and 5**, never left blank.
2. **Placeholder scan.** A grep for `TBD|TODO|implement later|fill in|Similar to Task|appropriate error` finds nothing. Task 4's `T` is a measured value the implementer reads from Surefire, not an estimate.
3. **Type consistency.** Every gateway, fixture and support method a later task calls appears with the same name and signature in the task that defines it, checked by grep.
4. **Sibling reuse.**
   - `HoldFixtures` repeats the `utc(Instant)` binding helper that `LockingGateway` and `LockingGatewayTest` already have. That is deliberate: test support must not reach into a main-code private method.
   - `SqlFailures` reuses `PostgresErrors` rather than walking the cause chain again.
   - `SalesGate` is written once so that 3B's confirm can reuse it. *(Erratum E3: as built, with switch expressions.)*
5. **Tooling self-consistency.**
   - All four commit messages pass the real `.githooks/commit-msg`, at 66, 67, 55 and 55 characters.
   - Every code block was applied to a scratch worktree of `main` (`e432c3c`'s descendant `8e2f57f`) by script, verbatim from this file, and then run through `./mvnw spotless:apply` and `./mvnw verify`.
   - **Results:**
     - Compilation under `-Xlint:all -Werror` passed, for main and test code.
     - Spotless accepted the formatted result.
     - All non-database tests passed: `HoldRequestFingerprintTest` 4/4, `SqlFailuresTest` 5/5, `TransactionIsolationTest` 1/1, and `FrontRowPropertiesTest` 4/4. That last one confirms that the root cause is a `BindValidationException` naming the field, on Boot 4.1.1.
     - **The first Docker-backed run caught a defect, which is now fixed.** `holdsSeatsInAscendingOrderAndRecordsTheResult` compared `readTree(stored)` with `valueToTree(result)`. Jackson 3.1.5 parses small numbers as `IntNode`, but `valueToTree` keeps the record's `long` fields as `LongNode`, so two trees with the same content compared unequal. The test now deserializes the stored JSON into `HoldResult`, which is how replay reads it.
     - **The same run surfaced decision 11.** SpotBugs raised two `EI_EXPOSE_REP2` findings on `HoldService`'s constructor. They're now handled by Task 3 Step 10's scoped exclusion, and a probe confirmed the exclusion doesn't hide the same pattern outside a constructor.
     - **The final run,** on a fresh worktree rebuilt from this file's final text: `./mvnw verify` → **140 test runs, 0 failures, SpotBugs 0, BUILD SUCCESS**.
     - `HoldConcurrencyTest` and `LockingGatewayTest` were then run 3 more times in a row. All passed, with no flakes.
     - **The three mutation checks were run, and each failed for the predicted reason:**
       - Task 1 Step 5, both seat_hold `FOR UPDATE` clauses: `did not block on a lock within 10s`.
       - Task 4 Step 4, lazy expiry: `DomainException: seat_taken`.
     - **Defect caught by those runs:** without `-Dspotless.check.skip=true`, all three "failed" on Spotless `format violations`, before any test ran. That was a false pass of the check, so the flag and a "match the message, not just the red build" note are now in both steps.
     - The test-count predictions were checked against these runs: Task 1 → 18 gateway tests, Task 2 → 26, Task 3 → 27 new and 134 in total, Task 4 → 140.
   - **After PR #16 round 1 (review 5535951723: 1 BLOCKER, 3 SUGGESTION, 4 NIT, all adopted).**
     - **Why the gap existed.** The checks above built only the plan's **end state**, so they could not catch the round-1 BLOCKER: Task 3 Step 4 can't compile while `TransactionIsolationTest` refers to a class that doesn't exist yet. The check that replaced them applies the plan to a fresh worktree of `main` (`8e2f57f`) **step by step, in order**. It runs every step that names a command, and asserts the outcome that step states.
     - **What it asserted at each step:**
       - each intermediate compile failure, including the missing symbols named;
       - each per-step test count: Task 1 18 and 4; Task 2 26; Task 3 9 and 27; Task 4 6;
       - the `verify` totals after each task: 99, 107, 134 (failing on exactly the one SpotBugs finding Step 9 predicts), then 134, then 140 with SpotBugs 0;
       - the three mutation checks, with their exact messages;
       - the line-width, lock-grep and domain-import checks;
       - the two-line ADR-005 grep.
     - **Result: 24/24 checks passed.**
     - **That check found one defect in the round-1 fixes themselves.** The first S2 fix (`Collections.unmodifiableList(new ArrayList<>(...))` in `HoldCommand`) drew a new SpotBugs `EI_EXPOSE_REP` on the record's accessor. Two points about it:
       - The narrowed N2 filter is what reported it. The round-1 filter, which covered the whole package, would have silenced it.
       - The fix: `HoldCommand` keeps `List.copyOf` and rejects a null id first, with a loop, because `List.of(...).contains(null)` throws.
     - **N2 probe.** With the final filter, a public `record ProbeRecord(java.util.Date when)` placed in `service` has both its constructor (`EI_EXPOSE_REP2`) and its accessor (`EI_EXPOSE_REP`) reported. A probe must run `spotbugs:spotbugs` before `spotbugs:check`: `spotbugs:check` on its own reads the last report and analyses nothing.
   - **After PR #16 round 3 (review 5538889630: 0 BLOCKER, 3 SUGGESTION, 5 NIT; S1-S3 and N1-N4 adopted, N5 parked for the spec and Plan 4).**
     - The same step-by-step check was re-run on a fresh worktree of `main` (`8e2f57f`): **24/24 checks passed**, ending at `./mvnw verify` with **140 test runs, SpotBugs 0**. No test count changed. The round-3 tests were adjusted in place: `tooManySeatsIsRejectedBeforeTheKeyIsClaimed` replaces `tooManySeatsReportsTheMaximum`, and `malformedParametersAreNamedBeforeTheKeyIsClaimed` replaces `malformedParametersAreNamedBeforeTheDatabaseIsTouched`.
     - **Three new mutation checks, each run on the end state and each failing for the predicted reason:**
       - S1, the NUL check removed from `requireWellFormed`: the key claim throws `DataIntegrityViolationException` (`invalid byte sequence for encoding "UTF8": 0x00`) instead of a `DomainException`.
       - S3, the raw size check removed from `hold()`: `expected: TOO_MANY_SEATS but was: IDEMPOTENCY_KEY_REUSED`.
       - N4, `requireWellFormed` moved behind the key claim: `expected: INVALID_REQUEST but was: IDEMPOTENCY_KEY_REUSED`.
     - The S2 restore (`@Timeout` in `SEPARATE_THREAD` mode, a scoped `DELETE`, then `CREATE UNIQUE INDEX IF NOT EXISTS` in a nested `finally`) passed in every run. Its failure paths are reasoned, not reproduced. *(Erratum E4: both failure paths were later reproduced, and the restore guarantee is weaker than stated.)*
6. **Verification-matrix crossing.** Task 4 has two independent dimensions: concurrency (sequential or raced) and identity (distinct owners, or one owner). The two most likely to interact are crossed on purpose. Test 4 races **one owner**, so the advisory lock and the cap check interact under contention. Test 5 races **one owner with one key**, so the key wait and the replay read interact. Tests 1 and 3 race distinct owners, so that neither lock serializes the race they exist to prove.
