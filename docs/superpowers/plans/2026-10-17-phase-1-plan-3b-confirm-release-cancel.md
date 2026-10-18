# FrontRow Phase 1, Plan 3B (Confirm, Release, Cancellation, the Sweeper) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build every write path after hold creation: confirm (the only purchase path), release, the event PATCH and cancellation service, and the expiry sweeper with the 24-hour key purge. Prove them against real Postgres with spec §5 concurrency tests 2 and 6. Bring hold creation up to spec revision 2.6.3.

**Architecture:** Plan 3A (PR #18, `4de410e`) built `HoldService` on top of plan 2's gateway and entities. This plan adds four application services beside it in `service/`: `ConfirmService`, `ReleaseService`, `EventPatchService` and `ExpirySweepService`. A small `config/SweepSchedule` runs the sweeper. The gateway gains the statements they need. Every write to a row the transaction has locked goes through the gateway as SQL, and the new order rows go through JPA (decision 1). It adds no REST, no MCP and no security chain; those are plans 4 and 5.

Phase 1 is six plans:
- 1, 2 and 3A are built;
- **3B (this plan):** confirm, release, the event PATCH/cancel *service* (Markus, 2026-10-14: build it; whether REST exposes it is Plan 4's call), the expiry sweeper and `hold_request` purge, and concurrency tests 2 and 6;
- **4:** REST, OpenAPI and the two security chains (Mandatory tier);
- **5:** the MCP tools and the §7 evidence artifacts.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Data JPA (Hibernate 7.4.5), `JdbcClient` (Spring Framework 7.0.9), Postgres 18, JUnit Jupiter 6, AssertJ, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.6.3). Read:
- §4: the statuses and their transitions, the live-hold predicate, the half-open window, and the `DRAFT` rule;
- §5: *Locking discipline* (including **When `now` is read**), *Hold confirmation*, *Release*, *Event cancellation*, *Hold expiry*, and *The portfolio centrepiece tests* 2, 6 and 7;
- §6: *Guardrails on the write path*, *Error contracts* and the REST surface rows for confirm, release and `PATCH /api/events/{id}`.

**Other inputs**, each closed by a task named in the coverage tables at the end:
- the two spec 2.6.3 code changes, which plan 3A's erratum E5 hands to this plan;
- PR #18's "Carried to Plan 3B / Plan 4" list, and its three open 3B threads (4296540451, 4296540462, 4296540470);
- PR #16's last open thread, 4285688140 (the per-thread `TestClock`);
- the 3B inputs in the frontrow handoff memory, which repeat PR #10's carry-forward items 3A left to 3B.

## Global Constraints

- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). The `domain` package imports only `java.*`; check with the grep in `CLAUDE.md`.
- **All lock SQL lives in `persistence/LockingGateway.java`** (ADR-005). No `FOR UPDATE`, `FOR SHARE`, `SKIP LOCKED`, advisory lock or `LOCK TABLE` anywhere else in `src/main`, **comments included**. Check with the grep in `CLAUDE.md`. Plan authoring tripped this twice, in javadoc, and Task 4 and Task 5's code is the corrected text.
- **One global lock order** (spec §5): the `hold_request` key, the owner's advisory lock, the event row, then `seat_hold` rows ascending by `event_seat_id`. A transaction skips the steps it doesn't need and never takes them out of order. The sweeper's `SKIP LOCKED` is the one exception, because it never waits.
- **Time comes from the injected `Clock`.** Never `now()` in SQL, never `Instant.now()` in main code (spec §4). Each transaction reads it at the point spec §5 *When `now` is read* names, and truncates it to microseconds once.
- **A hold is live iff `status = 'ACTIVE' AND expires_at > :now`**, decided by `HoldRules.isLive` in Java and by the gateway's SQL. There is no third definition (spec §4).
- **The sales window is half-open:** `sales_open_at <= :now < sales_close_at`, and the event must be `ON_SALE`. A `DRAFT` event answers `not_found` (spec §4, rev 2.6.3).
- **Every public service method sets READ COMMITTED explicitly** (spec §5). `TransactionIsolationTest.SERVICES` lists every service, and each task appends its own.
- **No service catches a gateway exception and carries on.** Every caught `DataAccessException` is translated by `SqlFailures` and rethrown (plan 3A decision 2).
- **Owner scoping is the service's job.** No relation in the schema ties an order to its holds. Confirm and release read and lock a group only through queries that filter on the owner, and another owner's group answers `not_found` (spec §6).
- Real Postgres only (`postgres:18-alpine` via Testcontainers). Never H2, never a mocked repository.
- Java 25, `-Xlint:all,-processing -Werror` (tests included), Spotless (palantir), SpotBugs. `./mvnw verify` is the test command, and it must be green at every commit.
- **The formatter decides layout.** Every code block below is the formatter's output, taken from a build of this plan. If `verify` stops on formatting anyway, run `./mvnw spotless:apply` and re-run it. Palantir does **not** reflow javadoc, so keep new javadoc within 120 columns by hand.
- Commit messages: `<type>(<scope>): <subject>`, 72 characters max, no trailing period (`.githooks/commit-msg`). No local device path in a committed file.
- **Racers stay at 10 or fewer per race.** Hikari's default pool is 10 connections (PR #18 carry-forward). This plan's races use 2.

## Plan-level decisions the spec leaves open

Each is listed so a reviewer can reject it individually.

1. **Writes to locked rows go through the gateway; new rows go through JPA.**
   - **Gateway SQL:** `endHolds` moves locked `ACTIVE` rows to `CONVERTED`, `RELEASED` or `EXPIRED`. It is the only statement that ends a hold outside lazy expiry and the sweeper. `updateEvent` writes a PATCH to the event row the transaction holds exclusively.
   - **JPA:** confirm writes `ticket_order` and `order_line` with `saveAndFlush` and `saveAllAndFlush`, inside its translation `try`. A constraint violation therefore surfaces inside the service, not at commit (PR #10 carry-forward, and plan 3A decision 1's rule for 3B).
   - **No `SeatHold` entity is loaded** in any transaction here. **`SeatHold#transitionTo` is deleted**, because nothing calls it: confirm and release were its only possible callers, and they write through the gateway. The transition rule it carried is still enforced, by `HoldStatus.canTransitionTo` inside `endHolds`, and `HoldStatusTest` still covers it.
   - **`persistence.HoldRow` gains `toRule()`**, which returns a `HoldRules.HoldRow`. The conversion sits in `persistence`, so `domain` still imports nothing but `java.*` (PR #10 carry-forward).
   - ADR-005 gets a plan 3B amendment that records this (Task 2).
2. **One service per write path, each with one public method.** `ConfirmService#confirm`, `ReleaseService#release`, `EventPatchService#patch`, and `ExpirySweepService#expireLapsedHolds` and `#purgeHoldRequests`. Each method is `@Transactional(isolation = READ_COMMITTED)` and keeps Spring's default REQUIRED propagation. **Whether service entry points become `Propagation.NEVER`, or adapters are never `@Transactional`, stays Plan 4's decision**, as PR #18 thread 4296540451's disposition says, so that thread stays open. Nothing in this plan calls a service inside an outer transaction: the tests call services directly, and `SweepSchedule` calls them from a scheduler thread.
3. **Where each transaction reads `now`** (spec §5 *When `now` is read*, rev 2.6.3):

   | Transaction | Reads `now` | Writes |
   |---|---|---|
   | hold creation | after the event-row lock, before `SalesGate` | `hold_request.created_at` keeps a separate arrival-time read, bound to the key claim |
   | confirm | after the event lock **and** the group's row locks (confirm's step 1) | `ticket_order.created_at` |
   | release | after the group's row locks | — |
   | event PATCH | **never** | — |
   | sweep, purge | at the start | — |

   **The PATCH reads no clock** because it makes no time-dependent decision and writes no timestamp. Cancellation releases every `ACTIVE` row, lapsed or not. Spec §5 places the PATCH's read after the event lock, and that holds vacuously. If a later change gives the PATCH a time-dependent decision, it reads the clock there.
4. **Confirm.**
   - **Input:** `HoldGroupCommand(owner, holdGroupId)`, which release shares. A null group id is `invalid_request` with `parameter: hold_group_id`. A blank owner is an `IllegalArgumentException`, as in `HoldCommand`, because the owner comes from the principal and never from a parameter.
   - **Step 1's unlocked read** is `SeatHoldRepository#findEventIdOfGroup`, a native scalar query filtered on the owner, so no `SeatHold` entity is loaded (decision 1). A missing group, or another owner's group, is `not_found`. The event row is then locked `FOR SHARE` (`lockEventForShare`). If that row is missing, it is an `IllegalStateException`: `seat_hold` → `event_seat` → `event` foreign keys make it impossible.
   - **The existing-order check returns the order as stored**, rebuilt from `ticket_order` and `order_line`. A repeated confirm is therefore record-equal to the first, and it is answered before the event and the rows are checked. A confirm after expiry or after `sales_close_at` still returns the order it already made (spec §5 step 2).
   - **A `CONVERTED` row in a group with no order** can't exist, because conversion and the order commit together. `HoldRules.confirmOutcome` would report `hold_not_active` for one (plan 2's precedence).
   - **A refused confirm changes nothing.** It doesn't expire lapsed rows, because the transaction rolls back on the throw anyway. Release and lazy expiry do that.
   - **Line prices are read from `event_seat` when confirming.** No code writes `event_seat` rows, so this is the price the hold showed.
   - **`OrderResult` is not stored.** Unlike `HoldResult`, it has no replay form to keep compatible; the tables are the record. `HoldResult` itself is unchanged, so PR #18's note that a field change needs a backward-deserialization test does not come into play here.
5. **Release.** `ReleaseResult(holdGroupId, released, expired)` counts what it did: live rows released, lapsed `ACTIVE` rows expired. A group with any `CONVERTED` row is `hold_not_active`, and nothing in it moves (spec §5 rule 1). A group with nothing live left returns `(0, 0)`.
6. **The event PATCH.**
   - **`EventPatch(eventId, status, salesOpenAt, salesCloseAt)`:** a null field keeps its current value, and an all-null patch succeeds and changes nothing. `EventPatchResult` returns the event as left, plus `releasedHolds`.
   - **Status transitions:**

     | From | Allowed targets |
     |---|---|
     | `DRAFT` | any, including `CANCELLED` |
     | `ON_SALE` | `ON_SALE`, `CANCELLED`. No PATCH moves an event back to `DRAFT` (spec §5 confirm step 3) |
     | `CANCELLED` | `CANCELLED` only, as a no-op: cancellation is final, and repeating it succeeds |

     A disallowed transition is `invalid_request` with `parameter: status`. The check is a switch expression, so a new `EventStatus` is a compile error, not a gate that fails open (plan 3A erratum E3).
   - **The window** is merged with the current one and truncated to microseconds, **then** it must satisfy `open < close`. Two instants 500 ns apart would otherwise pass in Java and break `ck_event_sales_window` as a 500. A cancelled event's window can't change. Either failure is `invalid_request` with `parameter: sales_window`.
   - **Cancelling an event with any `ticket_order`** is `invalid_request` with `parameter: status`, and nothing changes (spec §5). Otherwise every `ACTIVE` hold on the event moves to `RELEASED`, locked ascending (`lockActiveHoldsForEvent`, `FOR UPDATE OF h`, so the joined `event_seat` rows aren't locked).
   - **`setLockTimeout()` is the transaction's first statement**, so it bounds every wait, a cancellation's seat-row waits included. `55P03` becomes `contention_retry` through `SqlFailures.translateOnPatchPath`, and on no other path (spec §5).
   - **Who may PATCH is Plan 4's concern.** The service doesn't check roles.
   - **The service checks only `open < close`.** It doesn't check that each instant fits Postgres's `timestamptz` range. An instant outside it (year 300000, say) fails in Postgres with `22008` and surfaces as a 500. Plan 4's adapter bounds request instants, as it already bounds request size (PR #22 round 1).
7. **The sweeper.**
   - **`ExpirySweepService`** has two methods, each its own transaction: `expireLapsedHolds()` (the `SKIP LOCKED` `UPDATE`) and `purgeHoldRequests()` (it deletes keys created more than 24 hours ago, strictly). The purge reads only `hold_request.created_at`.
   - **`config/SweepSchedule`** registers the two methods as separate fixed-delay tasks, so a failed sweep never skips a purge. It uses `frontrow.sweep-interval` (bounded 1 s to 1 h, default 1 m) with an initial delay of one interval, so nothing runs during startup.
   - **`frontrow.sweeper-enabled`** (default `true`) gates the whole configuration with `@ConditionalOnProperty`. **Every test context sets it `false`**, in `src/test/resources/config/application.properties`. Boot reads `classpath:/config/` after `classpath:/`, so this overrides the main `application.yml` without hiding it. Test 7 asserts that no `SweepSchedule` bean exists. `SweepScheduleTest` checks the wiring with no database: its sweeper is a stub that counts calls, and the test runs each scheduled task once, so a schedule that runs the wrong method fails.
   - **Rejected:** `@Scheduled(fixedDelayString = …)` on the service. It would bypass `FrontRowProperties`' validation, and it would put scheduling onto a `@Transactional` bean.
8. **The purge versus a replay (PR #18 thread 4296540462): re-claim once.** If hold creation's key claim conflicts but the row is gone by the time it reads it, the purge deleted it in between, so the key has expired. Hold creation claims the key again and runs as a new request. If that second claim conflicts too, another request with the same key has just committed, and that one is replayed.
   - **Rejected:** giving the purge a margin. Any cutoff has the same boundary; a margin only moves it.
   - **Rejected:** an `ON CONFLICT DO UPDATE` claim that locks and returns the row in one statement. It would write a dead tuple on every replay and change a statement plan 3A built and proved.
9. **`TestClock` gets a per-thread override** (PR #18 thread 4296540470, PR #16 thread 4285688140). `setForThisThread(Instant)` and `clearForThisThread()`; `instant()` prefers the thread's own instant. Test 2's racers set it inside their own task and clear it in a `finally`. They run on fresh virtual threads, so nothing leaks to a later test.
10. **Two new test helpers.**
    - **`support/LockHolder`** takes locks in its own transaction and keeps them until closed. A test uses it to make a service wait, change the clock while the service waits, then let it go.
    - **`LockWaits.awaitBlockedBy(jdbc, blockerPid)`** waits until some backend reports that the holder blocks it. The service opens its own transaction, so its pid can't be captured from outside; the holder's pid can.

    Both helpers serve `ClockReadPointTest`, which proves each read point in decision 3 by making the read land on the far side of a wait.
11. **SpotBugs: no new exclusion.** The four new services end in `Service`, so plan 3A decision 11's constructor-only `EI_EXPOSE_REP2` exclusion covers them. `SweepSchedule` is in `config`, so the exclusion doesn't apply to it, and it draws no finding: the authoring build reported `BugInstance size is 0` at every commit.
12. **Spec 2.6.3's `DRAFT` rule lives in `SalesGate`.** `case DRAFT` returns `not_found` with no details (plan 3A erratum E5). The test `aDraftEventIsNotOpenYet` is replaced by `aDraftEventIsNotFoundAndDisclosesNothing`. That test's draft opens in the future, so the old code would have disclosed its `sales_open_at`. Confirm can't meet a draft: a group's event is never `DRAFT`.

**Review and execution tier: Mandatory.** Confirm and cancellation carry application-enforced data-integrity guarantees: spec §5's rows "a cancelled event never has a confirmed order" and "an expired, released or sold hold cannot be confirmed or released". So execution gets the full layer set: per-task two-stage reviews, the `reviewer` agent and `/security-review`. Markus may lower this; the plan text doesn't depend on it.

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `src/main/java/.../service/HoldService.java` | `now` after the event lock (Task 1); re-claim a purged key (Task 6) | 1, 6 |
| `src/main/java/.../service/SalesGate.java` | `DRAFT` → `not_found` | 1 |
| `src/test/java/.../support/TestClock.java` | Per-thread override | 1 |
| `src/test/java/.../support/LockHolder.java`, `LockWaits.java` | Hold locks in a background transaction; wait until something blocks behind them | 1 |
| `src/test/java/.../support/HoldFixtures.java` | New seeding and read-back helpers, added task by task | 1, 3, 4, 5, 6 |
| `src/test/java/.../service/ClockReadPointTest.java` | One test per read point in decision 3 | 1, 3, 4 |
| `src/main/java/.../persistence/LockingGateway.java` | `lockActiveHoldsForEvent`, `endHolds`, `expireLapsedHolds`, `updateEvent`, `purgeHoldRequestsCreatedBefore` | 2 |
| `src/main/java/.../persistence/HoldRow.java`, `SeatHold.java`, `SeatHoldRepository.java` | `toRule()`; `transitionTo` deleted; confirm's unlocked read | 2 |
| `docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md` | Plan 3B amendment | 2 |
| `src/main/java/.../service/HoldGroupCommand.java`, `LockedHolds.java` | Confirm and release's input; "every locked row must move" | 3 |
| `src/main/java/.../service/ReleaseService.java`, `ReleaseResult.java` | Release | 3 |
| `src/main/java/.../service/ConfirmService.java`, `OrderResult.java`, `persistence/OrderLineRepository.java` | Confirm | 4 |
| `src/main/java/.../service/EventPatchService.java`, `EventPatch.java`, `EventPatchResult.java`, `persistence/TicketOrderRepository.java`, `service/SqlFailures.java` | The event PATCH and cancellation; `55P03` on that path | 5 |
| `src/main/java/.../service/ExpirySweepService.java`, `config/SweepSchedule.java`, `config/FrontRowProperties.java`, `src/main/resources/application.yml`, `src/test/resources/config/application.properties` | The sweeper, the purge, their schedule and its switch | 6 |
| `src/test/java/.../service/ConfirmConcurrencyTest.java` | Spec §5 tests 2 and 6, and a double-clicked confirm | 7 |
| `CLAUDE.md` | Current state | 7 |

`...` is `io/github/markusluisflores/frontrow`. Each task's **Files** list is exact.

**How the code blocks below work.** A new file is given whole. A modified file of 25 lines or fewer is given whole too. A larger file is changed by **find and replace**: each "find" block occurs exactly once in the file as the previous task left it, and the "replace" block includes its surrounding lines.

---

### Task 1: Hold creation meets spec rev 2.6.3, and the clock becomes per-thread

This task makes the two code changes spec 2.6.3 left for this plan (plan 3A erratum E5). It also adds the test support every later task uses.

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/SalesGate.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/HoldServiceTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/support/LockHolder.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/LockWaits.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/TestClock.java`

**Interfaces:**
- Consumes: `HoldService#hold`, `HoldFixtures`, `ServiceTestConfiguration` (plan 3A); `LockingGateway#lockEventForUpdate` (plan 2).
- Produces:
  - `TestClock#setForThisThread(Instant)` and `#clearForThisThread()`;
  - `LockHolder.start(ExecutorService pool, TransactionTemplate tx, JdbcClient jdbc, Runnable locks) : LockHolder`, with `awaitSomeoneBlocked()` and `close()` (it commits and releases);
  - `LockWaits.awaitBlockedBy(JdbcClient, int blockerPid)`;
  - `HoldFixtures#holdRequestCreatedAt(String owner, String idempotencyKey) : Instant`;
  - `ClockReadPointTest`, which Tasks 3 and 4 extend.

- [ ] **Step 1: Write the failing tests and their support**

Create `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.LockHolder;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spec §5, rev 2.6.3, "When now is read": each transaction reads the clock once the locks before its first
 * time-dependent decision are held. Each test makes a service wait on one of those locks, moves the clock while it
 * waits, then lets it go. A clock read on arrival would give a different answer.
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ClockReadPointTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    LockingGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    @Autowired
    FrontRowProperties properties;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    @Test
    void aHoldThatWaitsOnTheEventLockAcrossSalesCloseIsRefused() throws Exception {
        Instant close = NOW.plusSeconds(30);
        long event = fixtures.event(venueId, "ON_SALE", NOW.minusSeconds(60), close);
        long seat = fixtures.eventSeats(event, venueId, 1).getFirst();

        Future<HoldResult> waiting;
        try (LockHolder patch = LockHolder.start(pool, tx, jdbc, () -> gateway.lockEventForUpdate(event))) {
            waiting = pool.submit(() -> holds.hold(new HoldCommand("alice", event, List.of(seat), "k1")));
            patch.awaitSomeoneBlocked();
            clock.set(close);
        }

        assertThat(rejection(waiting).errorCode()).isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void aHoldThatWaitsOnTheEventLockGetsAFullTtlButRecordsItsArrival() throws Exception {
        long event = fixtures.onSaleEvent(venueId);
        long seat = fixtures.eventSeats(event, venueId, 1).getFirst();
        Instant afterTheWait = NOW.plusSeconds(30);

        Future<HoldResult> waiting;
        try (LockHolder patch = LockHolder.start(pool, tx, jdbc, () -> gateway.lockEventForUpdate(event))) {
            waiting = pool.submit(() -> holds.hold(new HoldCommand("alice", event, List.of(seat), "k1")));
            patch.awaitSomeoneBlocked();
            clock.set(afterTheWait);
        }

        assertThat(waiting.get(10, TimeUnit.SECONDS).expiresAt()).isEqualTo(afterTheWait.plus(properties.holdTtl()));
        assertThat(fixtures.holdRequestCreatedAt("alice", "k1")).isEqualTo(NOW);
    }

    private static DomainException rejection(Future<?> call) {
        Throwable thrown = catchThrowable(() -> call.get(10, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).isInstanceOf(DomainException.class);
        return (DomainException) thrown.getCause();
    }
}
```

In `src/test/java/io/github/markusluisflores/frontrow/service/HoldServiceTest.java`, find:

```java
    void aDraftEventIsNotOpenYet() {
        long draft = fixtures.event(venueId, "DRAFT", NOW.minusSeconds(60), NOW.plusSeconds(60));
        long seat = fixtures.eventSeats(draft, venueId, 1).getFirst();
        DomainException rejected = rejection(() -> holds.hold(new HoldCommand("alice", draft, List.of(seat), "k1")));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.SALES_NOT_OPEN);
        assertThat(rejected.details()).containsEntry("sales_open_at", NOW.minusSeconds(60));
```

and replace it with:

```java
    void aDraftEventIsNotFoundAndDisclosesNothing() {
        // Its window opens later, so before spec rev 2.6.3 this answered sales_not_open with that opening time.
        long draft = fixtures.event(venueId, "DRAFT", NOW.plusSeconds(60), NOW.plusSeconds(120));
        long seat = fixtures.eventSeats(draft, venueId, 1).getFirst();
        DomainException rejected = rejection(() -> holds.hold(new HoldCommand("alice", draft, List.of(seat), "k1")));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejected.details()).isEmpty();
```

In `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`, after:

```java
                .query(String.class)
                .single();
    }
```

add:

```java

    public Instant holdRequestCreatedAt(String owner, String idempotencyKey) {
        return jdbc.sql("SELECT created_at FROM hold_request WHERE owner = :owner AND idempotency_key = :key")
                .param("owner", owner)
                .param("key", idempotencyKey)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }
```

Create `src/test/java/io/github/markusluisflores/frontrow/support/LockHolder.java`:

```java
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
```

In `src/test/java/io/github/markusluisflores/frontrow/support/LockWaits.java`, after:

```java
        throw new AssertionError("backend " + pid + " did not block on a lock within 10s");
    }
```

add:

```java

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
```

Replace the whole of `src/test/java/io/github/markusluisflores/frontrow/support/TestClock.java` with:

```java
package io.github.markusluisflores.frontrow.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests move by hand. Every thread reads one shared instant, unless it has set its own: spec §5 test 2 runs a
 * confirm and a competing hold at the same moment, each at its own time.
 */
public final class TestClock extends Clock {

    private volatile Instant instant;
    private final ThreadLocal<Instant> ownInstant = new ThreadLocal<>();

    public TestClock(Instant start) {
        this.instant = start;
    }

    /** Moves the shared instant. A thread that set its own instant keeps reading that one. */
    public void set(Instant next) {
        this.instant = next;
    }

    /** Until {@link #clearForThisThread()}, this thread alone reads the given instant. */
    public void setForThisThread(Instant own) {
        ownInstant.set(own);
    }

    public void clearForThisThread() {
        ownInstant.remove();
    }

    @Override
    public Instant instant() {
        Instant own = ownInstant.get();
        return own != null ? own : instant;
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

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='ClockReadPointTest,HoldServiceTest'`
Expected: 3 failures, and every other test passes.
- `aDraftEventIsNotFoundAndDisclosesNothing`: `expected: NOT_FOUND but was: SALES_NOT_OPEN`.
- `aHoldThatWaitsOnTheEventLockAcrossSalesCloseIsRefused`: the hold succeeds, so the assertion on its exception fails.
- `aHoldThatWaitsOnTheEventLockGetsAFullTtlButRecordsItsArrival`: `expected: 2026-10-01T12:10:30Z`, because the expiry is 30 seconds short.

- [ ] **Step 3: Move the clock read, and hide drafts**

In `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, find:

```java
        // timestamptz keeps microseconds; one truncation here keeps every Java check and SQL bind on one instant.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = HoldRequestFingerprint.of(command.eventId(), command.eventSeatIds());
        try {
            if (!gateway.claimIdempotencyKey(command.owner(), command.idempotencyKey(), fingerprint, now)) {
```

and replace it with:

```java
        // hold_request.created_at records when the request arrived, and only the 24-hour purge reads it (spec §5).
        // timestamptz keeps microseconds, so every instant is truncated once, where it is read.
        Instant arrivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = HoldRequestFingerprint.of(command.eventId(), command.eventSeatIds());
        try {
            if (!gateway.claimIdempotencyKey(command.owner(), command.idempotencyKey(), fingerprint, arrivedAt)) {
```

In `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, after:

```java
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
```

add:

```java
            // Every decision and every other timestamp uses one instant, read once the event lock is held: a request
            // that waited across sales_close_at is refused, and expires_at is not short by the wait (spec §5).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
```

In `src/main/java/io/github/markusluisflores/frontrow/service/SalesGate.java`, find:

```java
/** Holds and confirmations need ON_SALE and sales_open_at <= now < sales_close_at (spec §4). */
```

and replace it with:

```java
/**
 * Holds and confirmations need ON_SALE and sales_open_at <= now < sales_close_at (spec §4). A DRAFT event is not
 * disclosed: it answers not_found, the same as a missing id, and nothing about it (spec §4, rev 2.6.3).
 */
```

In `src/main/java/io/github/markusluisflores/frontrow/service/SalesGate.java`, find:

```java
                    case DRAFT -> salesNotOpen(event);
```

and replace it with:

```java
                    case DRAFT -> DomainException.of(ErrorCode.NOT_FOUND);
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='ClockReadPointTest,HoldServiceTest'`
Expected: PASS, 21 tests (2 + 19).

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **144 test runs** (142 before, plus 2; the draft test was replaced, not added).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java \
  src/main/java/io/github/markusluisflores/frontrow/service/SalesGate.java \
  src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/HoldServiceTest.java \
  src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java \
  src/test/java/io/github/markusluisflores/frontrow/support/LockHolder.java \
  src/test/java/io/github/markusluisflores/frontrow/support/LockWaits.java \
  src/test/java/io/github/markusluisflores/frontrow/support/TestClock.java
git commit -m "fix(service): read the hold clock after the event lock; hide drafts" \
  -m "Spec rev 2.6.3. Hold creation reads now once the event row is locked, keeping the arrival time for hold_request.created_at, and SalesGate answers a DRAFT event with not_found. TestClock gains a per-thread override for spec section 5 test 2."
```

- [ ] **Step 6: Mutation check. The read-point tests must fail on an arrival-time read**

**Every mutation run skips the Spotless check** (`-Dspotless.check.skip=true`). An edit can change the layout Palantir wants, and then `spotless:check` stops the build before any test runs. That failure means nothing about the test. Match the failure message below; a red build alone is not evidence. Plan 3A's authoring hit this.

In `HoldService.java`, temporarily replace the line `Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);` with `Instant now = arrivedAt;`.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ClockReadPointTest`
Expected: 2 failures, with the messages Step 2 gives for the two read-point tests.

Restore the file with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, and check that `git status --short` is empty. Record the observed messages in the task report.

---

### Task 2: The gateway statements confirm, release, cancellation and the sweeper need

**Files:**
- Modify: `docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/HoldRow.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHold.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHoldRepository.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`

**Interfaces:**
- Consumes: `LockingGateway`'s existing statements and helpers (`mapHold`, `utc`), `HoldStatus#canTransitionTo` (plan 2).
- Produces:
  - `LockingGateway#lockActiveHoldsForEvent(long eventId) : List<HoldRow>`. Every `ACTIVE` row on the event, ascending, locked `FOR UPDATE OF h`.
  - `LockingGateway#endHolds(Collection<Long> holdIds, HoldStatus target) : int`. It moves only `ACTIVE` rows, and returns how many moved. An empty list returns 0 and runs no SQL. `target == ACTIVE` throws `IllegalArgumentException`.
  - `LockingGateway#expireLapsedHolds(Instant now) : int`, the `SKIP LOCKED` sweep.
  - `LockingGateway#updateEvent(long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt)`. It throws `IllegalStateException` unless exactly one row is updated.
  - `LockingGateway#purgeHoldRequestsCreatedBefore(Instant cutoff) : int`. The cutoff is strict.
  - `HoldRow#toRule() : HoldRules.HoldRow`.
  - `SeatHoldRepository#findEventIdOfGroup(UUID holdGroupId, String owner) : Optional<Long>`.

- [ ] **Step 1: Write the failing gateway tests**

In `src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`, after:

```java
import io.github.markusluisflores.frontrow.domain.EventStatus;
```

add:

```java
import io.github.markusluisflores.frontrow.domain.HoldRules;
```

In `src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`, after:

```java
    @Autowired
    JsonMapper json;
```

add:

```java

    @Autowired
    SeatHoldRepository seatHolds;
```

In `src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`, after:

```java
        return second.get(10, TimeUnit.SECONDS);
    }
```

add:

```java

    @Test
    void endsOnlyActiveHoldsAndCountsThem() {
        long active = insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        long released =
                insertHold(secondEventSeatId, UUID.randomUUID(), "alice", HoldStatus.RELEASED, NOW.plusSeconds(600));

        tx.executeWithoutResult(status -> {
            assertThat(gateway.endHolds(List.of(active, released), HoldStatus.CONVERTED))
                    .isEqualTo(1);
            assertThat(statusOf(active)).isEqualTo("CONVERTED");
            assertThat(statusOf(released)).isEqualTo("RELEASED");
            assertThat(gateway.endHolds(List.of(), HoldStatus.RELEASED)).isZero();
        });
    }

    @Test
    void refusesToMoveAHoldToActive() {
        long active = insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        Throwable thrown = catchThrowable(
                () -> tx.executeWithoutResult(status -> gateway.endHolds(List.of(active), HoldStatus.ACTIVE)));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locksEveryActiveHoldOnTheEventAscendingLapsedOrNot() {
        long otherEvent = insertEvent();
        long otherEventsSeat = insertEventSeat(otherEvent, insertSeat("C", 1));
        // Higher seat first, so ascending order cannot come from insertion order.
        long live = insertHold(secondEventSeatId, UUID.randomUUID(), "bob", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        long lapsed = insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.minusSeconds(600));
        insertHold(eventSeatId, UUID.randomUUID(), "carol", HoldStatus.EXPIRED, NOW.minusSeconds(900));
        insertHold(otherEventsSeat, UUID.randomUUID(), "dave", HoldStatus.ACTIVE, NOW.plusSeconds(600));

        List<HoldRow> rows = tx.execute(status -> gateway.lockActiveHoldsForEvent(eventId));
        assertThat(rows).extracting(HoldRow::id).containsExactly(lapsed, live);
    }

    @Test
    void anEventsLockedHoldMakesTheCancellationLockWait() throws Exception {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockActiveHoldForSeat(eventSeatId);
            locked.countDown();
            awaitQuietly(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Integer> waiter = pool.submit(() -> tx.execute(status -> {
            waiterPid.complete(LockWaits.backendPid(jdbc));
            return gateway.lockActiveHoldsForEvent(eventId).size();
        }));
        LockWaits.awaitBlockedOnLock(jdbc, waiterPid.get(10, TimeUnit.SECONDS));
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void theSweepExpiresLapsedActiveHoldsAndSkipsLockedOnesWithoutWaiting() throws Exception {
        long[] seats = new long[4];
        for (int i = 0; i < seats.length; i++) {
            seats[i] = insertEventSeat(insertSeat("D", i + 1));
        }
        long lapsed = insertHold(seats[0], UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW);
        long lockedLapsed = insertHold(seats[1], UUID.randomUUID(), "bob", HoldStatus.ACTIVE, NOW.minusSeconds(1));
        long live = insertHold(seats[2], UUID.randomUUID(), "carol", HoldStatus.ACTIVE, NOW.plusMillis(1));
        long released = insertHold(seats[3], UUID.randomUUID(), "dave", HoldStatus.RELEASED, NOW.minusSeconds(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockActiveHoldForSeat(seats[1]);
            locked.countDown();
            awaitQuietly(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            // SKIP LOCKED: the sweep returns at once instead of waiting for the holder.
            Future<Integer> sweep = pool.submit(() -> tx.execute(status -> gateway.expireLapsedHolds(NOW)));
            assertThat(sweep.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(statusOf(lapsed)).isEqualTo("EXPIRED");
            assertThat(statusOf(lockedLapsed)).isEqualTo("ACTIVE");
            assertThat(statusOf(live)).isEqualTo("ACTIVE");
            assertThat(statusOf(released)).isEqualTo("RELEASED");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(tx.<Integer>execute(status -> gateway.expireLapsedHolds(NOW)))
                .isEqualTo(1);
        assertThat(statusOf(lockedLapsed)).isEqualTo("EXPIRED");
    }

    @Test
    void updatesTheEventRow() {
        tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            gateway.updateEvent(eventId, EventStatus.CANCELLED, NOW, NOW.plusSeconds(60));
        });
        EventRow row = tx.execute(status -> gateway.lockEventForShare(eventId)).orElseThrow();
        assertThat(row.status()).isEqualTo(EventStatus.CANCELLED);
        assertThat(row.salesOpenAt()).isEqualTo(NOW);
        assertThat(row.salesCloseAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void purgesOnlyKeysCreatedBeforeTheCutoff() {
        tx.executeWithoutResult(status -> {
            gateway.claimIdempotencyKey("alice", "old", "h", NOW.minusMillis(1));
            gateway.claimIdempotencyKey("alice", "boundary", "h", NOW);
        });
        assertThat(tx.<Integer>execute(status -> gateway.purgeHoldRequestsCreatedBefore(NOW)))
                .isEqualTo(1);
        assertThat(tx.<Boolean>execute(
                        status -> gateway.findHoldRequest("alice", "boundary").isPresent()))
                .isTrue();
    }

    @Test
    void findsAGroupsEventOnlyForItsOwner() {
        UUID group = UUID.randomUUID();
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(secondEventSeatId, group, "alice", HoldStatus.EXPIRED, NOW.plusSeconds(600));

        assertThat(seatHolds.findEventIdOfGroup(group, "alice")).contains(eventId);
        assertThat(seatHolds.findEventIdOfGroup(group, "bob")).isEmpty();
        assertThat(seatHolds.findEventIdOfGroup(UUID.randomUUID(), "alice")).isEmpty();
    }

    @Test
    void aHoldRowConvertsToTheDomainView() {
        HoldRow row = new HoldRow(1L, 7L, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW);
        assertThat(row.toRule()).isEqualTo(new HoldRules.HoldRow(7L, HoldStatus.ACTIVE, NOW));
    }

    private long insertEvent() {
        return jdbc.sql("""
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (:venue, 'Other', :starts, :open, :close, 'ON_SALE', 'CAD') RETURNING id""")
                .param("venue", venueId)
                .param("starts", utc(NOW.plus(30, ChronoUnit.DAYS)))
                .param("open", utc(NOW.minus(1, ChronoUnit.DAYS)))
                .param("close", utc(NOW.plus(29, ChronoUnit.DAYS)))
                .query(Long.class)
                .single();
    }

    private long insertEventSeat(long event, long seatId) {
        return jdbc.sql("""
                        INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
                        VALUES (:event, :seat, :venue, 5000) RETURNING id""")
                .param("event", event)
                .param("seat", seatId)
                .param("venue", venueId)
                .query(Long.class)
                .single();
    }
```

- [ ] **Step 2: Run them to see compilation fail**

Run: `./mvnw -q test -Dtest=LockingGatewayTest`
Expected: compilation FAILS: `cannot find symbol` for `endHolds`, `lockActiveHoldsForEvent`, `expireLapsedHolds`, `updateEvent`, `purgeHoldRequestsCreatedBefore`, `toRule` and `findEventIdOfGroup`.

- [ ] **Step 3: Add the statements, the conversion and the repository read; record the ADR-005 amendment**

Append to the end of `docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md`:

```markdown
## Amendment — Plan 3B (confirm, release, cancellation, the sweeper)

The gateway also holds every write to a row the transaction has already locked:
- `endHolds`, which moves locked `ACTIVE` rows to `CONVERTED`, `RELEASED` or `EXPIRED` for confirm, release and cancellation;
- `updateEvent`, which writes an event `PATCH` to the row it holds `FOR UPDATE`.

The sweeper's `SKIP LOCKED` update is there because it is lock SQL. The `hold_request` purge is there because it completes the protocol the key claim starts. New rows that no other transaction can be locking yet, `ticket_order` and `order_line`, go through JPA. They are written with `saveAndFlush` inside the service's translation `try`, so a constraint violation surfaces there and not at commit. `SeatHold#transitionTo` is deleted. Its only possible callers were confirm and release, and both write through the gateway, so an entity-side transition would only invite the dirty-entity overwrite this ADR warns about. Source: `docs/superpowers/plans/2026-10-17-phase-1-plan-3b-confirm-release-cancel.md`, decision 1.
```

Replace the whole of `src/main/java/io/github/markusluisflores/frontrow/persistence/HoldRow.java` with:

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/** A seat_hold row as committed, read under a lock — deliberately not an entity (spec §5). */
public record HoldRow(long id, long eventSeatId, UUID holdGroupId, String owner, HoldStatus status, Instant expiresAt) {

    /** The domain's view of this row. The conversion lives here because domain imports nothing but java.*. */
    public HoldRules.HoldRow toRule() {
        return new HoldRules.HoldRow(eventSeatId, status, expiresAt);
    }
}
```

In `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`, after:

```java
import java.time.ZoneOffset;
```

add:

```java
import java.util.Collection;
```

In `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`, after:

```java
                .list();
    }

    /**
```

add:

```java
     * Step 4, a whole event, for cancellation: every ACTIVE hold on the event, lapsed or not, ascending by
     * event_seat_id. Only the seat_hold rows are locked; the event_seat rows the join reads are not.
     */
    public List<HoldRow> lockActiveHoldsForEvent(long eventId) {
        return jdbc.sql("""
                        SELECT h.id, h.event_seat_id, h.hold_group_id, h.owner, h.status, h.expires_at
                        FROM seat_hold h JOIN event_seat s ON s.id = h.event_seat_id
                        WHERE s.event_id = :event AND h.status = 'ACTIVE'
                        ORDER BY h.event_seat_id FOR UPDATE OF h""")
                .param("event", eventId)
                .query(LockingGateway::mapHold)
                .list();
    }

    /**
     * Ends hold rows this transaction has already locked: confirm, release and cancel decide under the lock, then
     * write (spec §5). Only ACTIVE rows move, and only to an ended status, so a row is never rewritten once it has
     * ended. Returns how many rows moved; the caller checks it against what it locked.
     */
    public int endHolds(Collection<Long> holdIds, HoldStatus target) {
        if (!HoldStatus.ACTIVE.canTransitionTo(target)) {
            throw new IllegalArgumentException("an ACTIVE hold cannot move to " + target);
        }
        if (holdIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE seat_hold SET status = :target WHERE id IN (:ids) AND status = 'ACTIVE'")
                .param("target", target.name())
                .param("ids", holdIds)
                .update();
    }

    /**
     * The sweeper (spec §5, Hold expiry): expires every lapsed ACTIVE hold it can lock without waiting. SKIP LOCKED
     * means it never waits, so it takes no part in the lock order and cannot deadlock. A row another transaction
     * holds is left to lazy expiry or the next sweep. Postgres re-checks the inner predicate on each row it locks,
     * and the outer one repeats it, so a hold confirmed a moment earlier is never expired.
     */
    public int expireLapsedHolds(Instant now) {
        return jdbc.sql("""
                        UPDATE seat_hold SET status = 'EXPIRED'
                        WHERE status = 'ACTIVE' AND expires_at <= :now AND id IN (
                            SELECT id FROM seat_hold WHERE status = 'ACTIVE' AND expires_at <= :now
                            FOR UPDATE SKIP LOCKED)""").param("now", utc(now)).update();
    }

    /** Writes an event PATCH to the row this transaction holds FOR UPDATE (spec §5, Event cancellation). */
    public void updateEvent(long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {
        int updated = jdbc.sql("""
                        UPDATE event SET status = :status, sales_open_at = :open, sales_close_at = :close
                        WHERE id = :id""")
                .param("status", status.name())
                .param("open", utc(salesOpenAt))
                .param("close", utc(salesCloseAt))
                .param("id", eventId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("no event row " + eventId + " to update");
        }
    }

    /**
```

In `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`, after:

```java
                .query(Long.class)
                .single();
    }
```

add:

```java

    /**
     * The 24-hour key purge (spec §5): deletes hold_request rows created before the cutoff. It runs in a transaction
     * of its own, and hold creation tolerates a row it saw vanishing before it read it (HoldService).
     */
    public int purgeHoldRequestsCreatedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM hold_request WHERE created_at < :cutoff")
                .param("cutoff", utc(cutoff))
                .update();
    }
```

In `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHold.java`, find:

```java
    }

    /** Only ACTIVE may move, and only to an ended status (domain rule). */
    public void transitionTo(HoldStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException("illegal hold transition " + this.status + " -> " + target);
        }
        this.status = target;
    }
}
```

and replace it with:

```java
    }
}
```

Replace the whole of `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHoldRepository.java` with:

```java
package io.github.markusluisflores.frontrow.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * Confirm's first, unlocked read (spec §5): the group's event, if the group exists and is this owner's. Safe
     * without a lock, because a group's event never changes. A scalar, so no SeatHold entity is loaded.
     */
    @Query(value = """
                    SELECT DISTINCT s.event_id FROM seat_hold h JOIN event_seat s ON s.id = h.event_seat_id
                    WHERE h.hold_group_id = :holdGroupId AND h.owner = :owner""", nativeQuery = true)
    Optional<Long> findEventIdOfGroup(UUID holdGroupId, String owner);
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=LockingGatewayTest`
Expected: PASS, 35 tests (26 + 9).

Run the two `CLAUDE.md` greps.
Expected: `domain` imports only `java.*`, and lock vocabulary appears only in `LockingGateway.java`.

Run: `grep -rn "transitionTo" src`
Expected: no output. Nothing called it.

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **153 test runs**.

- [ ] **Step 5: Commit**

```bash
git add docs/adr/ADR-005-jpa-plus-jdbc-for-locks.md \
  src/main/java/io/github/markusluisflores/frontrow/persistence/HoldRow.java \
  src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java \
  src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHold.java \
  src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHoldRepository.java \
  src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java
git commit -m "feat(persistence): add the gateway statements confirm and cancel need" \
  -m "endHolds, lockActiveHoldsForEvent, updateEvent, the SKIP LOCKED sweep and the 24-hour key purge; HoldRow converts to HoldRules.HoldRow; SeatHold#transitionTo is removed. ADR-005 gains a plan 3B amendment."
```

- [ ] **Step 6: Mutation checks. The cancellation lock must block, and the sweep must not wait**

In `LockingGateway.lockActiveHoldsForEvent`, temporarily delete ` FOR UPDATE OF h` (the SQL then ends `ORDER BY h.event_seat_id`).

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=LockingGatewayTest#anEventsLockedHoldMakesTheCancellationLockWait`
Expected: FAIL with `AssertionError: backend <pid> did not block on a lock within 10s`.

Restore the file with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`. Then, in `expireLapsedHolds`, replace `FOR UPDATE SKIP LOCKED)` with `FOR UPDATE)`.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=LockingGatewayTest#theSweepExpiresLapsedActiveHoldsAndSkipsLockedOnesWithoutWaiting`
Expected: FAIL with `java.util.concurrent.TimeoutException`: the sweep now waits for the holder.

Restore the file the same way, check that `git status --short` is empty, and record both messages.

---

### Task 3: Release

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/HoldGroupCommand.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/LockedHolds.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/ReleaseResult.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/ReleaseServiceTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`

**Interfaces:**
- Consumes: `LockingGateway#lockGroupRows` (plan 2), `#endHolds` (Task 2); `HoldRules#isLive`; `SqlFailures#translate` (plan 3A); `LockHolder`, `ClockReadPointTest` (Task 1).
- Produces:
  - `HoldGroupCommand(String owner, UUID holdGroupId)`, which Task 4 reuses;
  - `LockedHolds.end(LockingGateway, List<Long> holdIds, HoldStatus target)`, package-private. It throws `IllegalStateException` unless every id moved. Tasks 4 and 5 reuse it;
  - `ReleaseService#release(HoldGroupCommand) : ReleaseResult`;
  - `ReleaseResult(UUID holdGroupId, int released, int expired)`;
  - `HoldFixtures#holdInGroup(long eventSeatId, UUID holdGroupId, String owner, String status, Instant expiresAt)`. The existing `hold(...)` now delegates to it.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`, after:

```java
    @Autowired
    HoldService holds;
```

add:

```java

    @Autowired
    ReleaseService releases;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`, after:

```java
        assertThat(fixtures.holdRequestCreatedAt("alice", "k1")).isEqualTo(NOW);
    }
```

add:

```java

    @Test
    void aReleaseThatWaitsOnItsRowsPastExpiryExpiresThemInsteadOfReleasing() throws Exception {
        long event = fixtures.onSaleEvent(venueId);
        HoldResult held = holds.hold(new HoldCommand("alice", event, fixtures.eventSeats(event, venueId, 2), "k1"));

        Future<ReleaseResult> waiting;
        try (LockHolder rows =
                LockHolder.start(pool, tx, jdbc, () -> gateway.lockGroupRows(held.holdGroupId(), "alice"))) {
            waiting = pool.submit(() -> releases.release(new HoldGroupCommand("alice", held.holdGroupId())));
            rows.awaitSomeoneBlocked();
            clock.set(held.expiresAt());
        }

        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 2));
    }
```

Create `src/test/java/io/github/markusluisflores/frontrow/service/ReleaseServiceTest.java`:

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
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ReleaseServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    ReleaseService releases;

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
    void releasesALiveGroupAndFreesItsSeats() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, seats, "k1"));

        ReleaseResult result = releases.release(new HoldGroupCommand("alice", held.holdGroupId()));

        assertThat(result).isEqualTo(new ReleaseResult(held.holdGroupId(), 2, 0));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED", "RELEASED");
        assertThat(holds.hold(new HoldCommand("bob", eventId, seats, "k1")).seats())
                .hasSize(2);
    }

    @Test
    void aLapsedGroupIsExpiredNotReleased() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        clock.set(held.expiresAt());

        assertThat(releases.release(new HoldGroupCommand("alice", held.holdGroupId())))
                .isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 2));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("EXPIRED", "EXPIRED");
    }

    @Test
    void releasingTwiceSucceedsAndChangesNothingTheSecondTime() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));
        HoldGroupCommand release = new HoldGroupCommand("alice", held.holdGroupId());
        releases.release(release);

        assertThat(releases.release(release)).isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 0));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED");
    }

    @Test
    void aGroupWithASoldRowIsNotActiveAndIsLeftAlone() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        UUID group = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), group, "alice", "CONVERTED", NOW.plusSeconds(600));
        fixtures.holdInGroup(seats.get(1), group, "alice", "ACTIVE", NOW.plusSeconds(600));

        assertThat(rejection(() -> releases.release(new HoldGroupCommand("alice", group)))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("CONVERTED", "ACTIVE");
    }

    @Test
    void anotherOwnersGroupIsNotFoundAndIsLeftAlone() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));

        assertThat(rejection(() -> releases.release(new HoldGroupCommand("bob", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
    }

    @Test
    void anUnknownGroupIsNotFound() {
        assertThat(rejection(() -> releases.release(new HoldGroupCommand("alice", UUID.randomUUID())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void releasingAGroupFreesItsPlaceUnderTheActiveGroupCap() {
        int cap = properties.maxActiveHoldGroups();
        List<Long> seats = fixtures.eventSeats(eventId, venueId, cap + 1);
        HoldResult first = null;
        for (int i = 0; i < cap; i++) {
            HoldResult held = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(i)), "k" + i));
            first = first == null ? held : first;
        }
        releases.release(new HoldGroupCommand("alice", first.holdGroupId()));

        assertThat(holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(cap)), "next"))
                        .seats())
                .hasSize(1);
    }

    @Test
    void aGroupIdIsRequired() {
        DomainException rejected = rejection(() -> new HoldGroupCommand("alice", null));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "hold_group_id");
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
    private static final List<Class<?>> SERVICES = List.of(HoldService.class);
```

and replace it with:

```java
    private static final List<Class<?>> SERVICES = List.of(HoldService.class, ReleaseService.class);
```

In `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`, after:

```java
    public void hold(long eventSeatId, String owner, String status, Instant expiresAt) {
```

add:

```java
        holdInGroup(eventSeatId, UUID.randomUUID(), owner, status, expiresAt);
    }

    public void holdInGroup(long eventSeatId, UUID holdGroupId, String owner, String status, Instant expiresAt) {
```

In `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`, find:

```java
                .param("group", UUID.randomUUID())
```

and replace it with:

```java
                .param("group", holdGroupId)
```

- [ ] **Step 2: Run them to see compilation fail**

Run: `./mvnw -q test -Dtest='ReleaseServiceTest,ClockReadPointTest,TransactionIsolationTest'`
Expected: compilation FAILS: `cannot find symbol` for `ReleaseService`, `ReleaseResult` and `HoldGroupCommand`.

- [ ] **Step 3: Write release**

Create `src/main/java/io/github/markusluisflores/frontrow/service/HoldGroupCommand.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.Map;
import java.util.UUID;

/** One hold group, named on its owner's behalf, for confirm and release. The owner comes from the principal. */
public record HoldGroupCommand(String owner, UUID holdGroupId) {

    public HoldGroupCommand {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner comes from the authenticated principal and must be present");
        }
        if (holdGroupId == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "hold_group_id"));
        }
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/LockedHolds.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import java.util.List;

/** Ends rows the caller has locked. Every one must move: a locked ACTIVE row cannot have ended under another writer. */
final class LockedHolds {

    private LockedHolds() {}

    static void end(LockingGateway gateway, List<Long> holdIds, HoldStatus target) {
        int moved = gateway.endHolds(holdIds, target);
        if (moved != holdIds.size()) {
            throw new IllegalStateException(moved + " of " + holdIds.size() + " locked holds moved to " + target);
        }
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/ReleaseResult.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.util.UUID;

/** What a release did: how many live rows it released and how many lapsed rows it expired (spec §5, Release). */
public record ReleaseResult(UUID holdGroupId, int released, int expired) {}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.HoldRow;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release, spec §5: lock the group's rows (step 4 only; release never reads the event), then decide. A group with a
 * sold row is left alone. Otherwise live rows become RELEASED and lapsed ACTIVE rows become EXPIRED, as lazy expiry
 * would have made them, so a later confirm's answer never depends on the sweeper. A group with nothing live left
 * succeeds and changes nothing.
 */
@Service
public class ReleaseService {

    private final LockingGateway gateway;
    private final Clock clock;

    public ReleaseService(LockingGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReleaseResult release(HoldGroupCommand command) {
        try {
            List<HoldRow> rows = gateway.lockGroupRows(command.holdGroupId(), command.owner());
            if (rows.isEmpty()) {
                // Another owner's group answers the same as no group, so it is not disclosed (spec §6).
                throw DomainException.of(ErrorCode.NOT_FOUND);
            }
            // Read once the group's rows are locked (spec §5).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (rows.stream().anyMatch(row -> row.status() == HoldStatus.CONVERTED)) {
                // Releasing a sold row would take it out of uq_claimed_seat and make the seat holdable again.
                throw DomainException.of(ErrorCode.HOLD_NOT_ACTIVE);
            }
            List<Long> live = new ArrayList<>();
            List<Long> lapsed = new ArrayList<>();
            for (HoldRow row : rows) {
                if (HoldRules.isLive(row.status(), row.expiresAt(), now)) {
                    live.add(row.id());
                } else if (row.status() == HoldStatus.ACTIVE) {
                    lapsed.add(row.id());
                }
            }
            LockedHolds.end(gateway, live, HoldStatus.RELEASED);
            LockedHolds.end(gateway, lapsed, HoldStatus.EXPIRED);
            return new ReleaseResult(command.holdGroupId(), live.size(), lapsed.size());
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='ReleaseServiceTest,ClockReadPointTest,TransactionIsolationTest'`
Expected: PASS, 12 tests (8 + 3 + 1).

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **162 test runs**.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/service/HoldGroupCommand.java \
  src/main/java/io/github/markusluisflores/frontrow/service/LockedHolds.java \
  src/main/java/io/github/markusluisflores/frontrow/service/ReleaseResult.java \
  src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java \
  src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/ReleaseServiceTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java \
  src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java
git commit -m "feat(service): add hold release" \
  -m "ReleaseService locks the group's rows, then releases live rows and expires lapsed ones; a sold group is hold_not_active and another owner's group is not_found."
```

- [ ] **Step 6: Mutation check. Release must read the clock after its row locks**

In `ReleaseService.release`, temporarily move the line `Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);` (and its comment) from inside the `try` to just before `try {`.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ClockReadPointTest`
Expected: 1 failure, in `aReleaseThatWaitsOnItsRowsPastExpiryExpiresThemInsteadOfReleasing`: `expected: ReleaseResult[…, released=0, expired=2]` `but was: ReleaseResult[…, released=2, expired=0]`.

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java`, check that `git status --short` is empty, and record the message.

---

### Task 4: Confirm, the only purchase path

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/OrderLineRepository.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/OrderResult.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/ConfirmServiceTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`

**Interfaces:**
- Consumes:
  - `SeatHoldRepository#findEventIdOfGroup`, `LockingGateway#lockEventForShare`, `#lockGroupRows` and `#endHolds`, and `HoldRow#toRule` (Task 2 and plan 2);
  - `HoldRules#confirmOutcome` (plan 2), `SalesGate#requireOpen` and `SqlFailures#translate` (plan 3A);
  - `HoldGroupCommand`, `LockedHolds` and `ReleaseService` (Task 3);
  - `TicketOrderRepository#findByHoldGroupId`, `EventRepository#getReferenceById` and `EventSeatRepository#findOfferedSeats` (plans 2 and 3A).
- Produces:
  - `ConfirmService#confirm(HoldGroupCommand) : OrderResult`;
  - `OrderResult(long orderId, UUID holdGroupId, long eventId, List<OrderResult.Line> lines, long totalCents, String currency, Instant createdAt)`, with `OrderResult.Line(long eventSeatId, long priceCents)`. Lines are in ascending seat order;
  - `OrderLineRepository#findByOrderIdInSeatOrder(long orderId)`;
  - `HoldFixtures#ordersForGroup(UUID)`, `#orderedSeatsOfGroup(UUID)` and `#setEventStatus(long, String)`.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`, after:

```java
    ReleaseService releases;
```

add:

```java

    @Autowired
    ConfirmService confirms;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java`, after:

```java
        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(new ReleaseResult(held.holdGroupId(), 0, 2));
    }
```

add:

```java

    @Test
    void aConfirmThatWaitsOnItsRowsPastExpiryIsExpired() throws Exception {
        long event = fixtures.onSaleEvent(venueId);
        HoldResult held = holds.hold(new HoldCommand("alice", event, fixtures.eventSeats(event, venueId, 2), "k1"));

        Future<OrderResult> waiting;
        try (LockHolder rows =
                LockHolder.start(pool, tx, jdbc, () -> gateway.lockGroupRows(held.holdGroupId(), "alice"))) {
            waiting = pool.submit(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())));
            rows.awaitSomeoneBlocked();
            clock.set(held.expiresAt());
        }

        assertThat(rejection(waiting).errorCode()).isEqualTo(ErrorCode.HOLD_EXPIRED);
    }
```

Create `src/test/java/io/github/markusluisflores/frontrow/service/ConfirmServiceTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ConfirmServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    ReleaseService releases;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

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
    void confirmsALiveGroupIntoOneOrderAtEachSeatsPrice() {
        long cheap = fixtures.eventSeats(eventId, venueId, 1, 1000).getFirst();
        long dear = fixtures.eventSeats(eventId, venueId, 1, 2500).getFirst();
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, List.of(dear, cheap), "k1"));
        clock.set(NOW.plusSeconds(60));

        OrderResult order = confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId()));

        assertThat(order.holdGroupId()).isEqualTo(held.holdGroupId());
        assertThat(order.eventId()).isEqualTo(eventId);
        assertThat(order.lines()).containsExactly(new OrderResult.Line(cheap, 1000), new OrderResult.Line(dear, 2500));
        assertThat(order.totalCents()).isEqualTo(3500);
        assertThat(order.currency()).isEqualTo("CAD");
        assertThat(order.createdAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("CONVERTED", "CONVERTED");
        assertThat(fixtures.orderedSeatsOfGroup(held.holdGroupId())).containsExactly(cheap, dear);
    }

    @Test
    void aRepeatedConfirmReturnsTheSameOrderEvenAfterExpiryAndSalesClose() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        HoldGroupCommand confirm = new HoldGroupCommand("alice", held.holdGroupId());
        OrderResult first = confirms.confirm(confirm);

        // The existing-order check comes first, so neither the lapsed hold nor the closed window changes the answer.
        clock.set(NOW.plus(60, ChronoUnit.DAYS));
        assertThat(confirms.confirm(confirm)).isEqualTo(first);
        assertThat(fixtures.ordersForGroup(held.holdGroupId())).isEqualTo(1);
    }

    @Test
    void anotherOwnersGroupIsNotFoundAndIsLeftAlone() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("bob", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
        assertThat(fixtures.ordersForGroup(held.holdGroupId())).isZero();
    }

    @Test
    void anUnknownGroupIsNotFound() {
        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", UUID.randomUUID())))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aGroupLapsingExactlyNowIsExpiredAndNothingIsSold() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        clock.set(held.expiresAt());

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE", "ACTIVE");
        assertThat(fixtures.ordersForGroup(held.holdGroupId())).isZero();
    }

    @Test
    void aGroupAnOverlappingHoldPartlyExpiredIsExpired() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, seats, "k1"));
        clock.set(held.expiresAt().plusSeconds(1));
        // Bob's hold lazily expires only the seat it overlaps; alice's other row stays ACTIVE.
        holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(0)), "k1"));
        // Back to before the expiry: alice's other row is live, so only the EXPIRED row makes the group expired.
        clock.set(held.expiresAt().minusMillis(1));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    @Test
    void aReleasedGroupIsNotActive() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));
        releases.release(new HoldGroupCommand("alice", held.holdGroupId()));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);
    }

    @Test
    void aCancelledEventIsClosedEvenThoughItsHoldsWereReleased() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 1), "k1"));
        // What a cancellation leaves behind (spec §5): the event CANCELLED, its holds RELEASED.
        releases.release(new HoldGroupCommand("alice", held.holdGroupId()));
        fixtures.setEventStatus(eventId, "CANCELLED");

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void aGroupStillLiveAfterSalesCloseIsClosed() {
        long closing = fixtures.event(venueId, "ON_SALE", NOW.minusSeconds(60), NOW.plusSeconds(60));
        HoldResult held = holds.hold(new HoldCommand("alice", closing, fixtures.eventSeats(closing, venueId, 1), "k1"));
        clock.set(NOW.plusSeconds(60));

        assertThat(rejection(() -> confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId())))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("ACTIVE");
    }

    @Test
    void aSoldSeatStaysClaimedSoItCannotBeHeldAgain() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 1);
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, seats, "k1"));
        confirms.confirm(new HoldGroupCommand("alice", held.holdGroupId()));

        clock.set(held.expiresAt().plusSeconds(1));
        assertThat(rejection(() -> holds.hold(new HoldCommand("bob", eventId, seats, "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SEAT_TAKEN);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
    private static final List<Class<?>> SERVICES = List.of(HoldService.class, ReleaseService.class);
```

and replace it with:

```java
    private static final List<Class<?>> SERVICES =
            List.of(HoldService.class, ReleaseService.class, ConfirmService.class);
```

In `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`, after:

```java
                .query(String.class)
                .single();
    }
```

add:

```java

    public int ordersForGroup(UUID holdGroupId) {
        return jdbc.sql("SELECT count(*) FROM ticket_order WHERE hold_group_id = :group")
                .param("group", holdGroupId)
                .query(Integer.class)
                .single();
    }

    public List<Long> orderedSeatsOfGroup(UUID holdGroupId) {
        return jdbc.sql("""
                        SELECT l.event_seat_id FROM order_line l JOIN ticket_order o ON o.id = l.order_id
                        WHERE o.hold_group_id = :group ORDER BY l.event_seat_id""").param("group", holdGroupId).query(Long.class).list();
    }

    public void setEventStatus(long eventId, String status) {
        jdbc.sql("UPDATE event SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", eventId)
                .update();
    }
```

- [ ] **Step 2: Run them to see compilation fail**

Run: `./mvnw -q test -Dtest='ConfirmServiceTest,ClockReadPointTest,TransactionIsolationTest'`
Expected: compilation FAILS: `cannot find symbol` for `ConfirmService` and `OrderResult`.

- [ ] **Step 3: Write confirm**

Replace the whole of `src/main/java/io/github/markusluisflores/frontrow/persistence/OrderLineRepository.java` with:

```java
package io.github.markusluisflores.frontrow.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    @Query("SELECT l FROM OrderLine l WHERE l.order.id = :orderId ORDER BY l.eventSeat.id")
    List<OrderLine> findByOrderIdInSeatOrder(long orderId);
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldRules;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.domain.Money;
import io.github.markusluisflores.frontrow.domain.OrderStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRepository;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.HoldRow;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.persistence.OrderLine;
import io.github.markusluisflores.frontrow.persistence.OrderLineRepository;
import io.github.markusluisflores.frontrow.persistence.SeatHoldRepository;
import io.github.markusluisflores.frontrow.persistence.TicketOrder;
import io.github.markusluisflores.frontrow.persistence.TicketOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hold confirmation, spec §5: the only purchase path, REST only. One transaction: an unlocked read of the group's
 * event, a shared lock on the event row, the group's rows ascending, then the decision: the existing order first,
 * the event second and the rows' state last.
 */
@Service
public class ConfirmService {

    private final LockingGateway gateway;
    private final SeatHoldRepository seatHolds;
    private final TicketOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final Clock clock;

    public ConfirmService(
            LockingGateway gateway,
            SeatHoldRepository seatHolds,
            TicketOrderRepository orders,
            OrderLineRepository orderLines,
            EventRepository events,
            EventSeatRepository eventSeats,
            Clock clock) {
        this.gateway = gateway;
        this.seatHolds = seatHolds;
        this.orders = orders;
        this.orderLines = orderLines;
        this.events = events;
        this.eventSeats = eventSeats;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResult confirm(HoldGroupCommand command) {
        UUID group = command.holdGroupId();
        String owner = command.owner();
        try {
            // Step 1. Another owner's group answers the same as no group (spec §5, §6).
            long eventId = seatHolds
                    .findEventIdOfGroup(group, owner)
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
            EventRow event = gateway.lockEventForShare(eventId)
                    .orElseThrow(() -> new IllegalStateException("hold group " + group + " has no event row"));
            List<HoldRow> rows = gateway.lockGroupRows(group, owner);
            // Read once step 1's locks are held (spec §5).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

            // Step 2. A repeated or double-clicked confirm waited on step 1's locks; it returns the order (spec §5).
            Optional<TicketOrder> existing = orders.findByHoldGroupId(group);
            if (existing.isPresent()) {
                return result(
                        existing.get(),
                        orderLines.findByOrderIdInSeatOrder(existing.get().getId()));
            }
            // Step 3, before the rows: a cancellation also releases the holds, and "the event is closed" is the
            // true answer then, not "your hold was released".
            SalesGate.requireOpen(event, now);
            // Step 4.
            DomainException refusal =
                    switch (HoldRules.confirmOutcome(
                            rows.stream().map(HoldRow::toRule).toList(), now)) {
                        case PROCEED -> null;
                        case HOLD_NOT_ACTIVE -> DomainException.of(ErrorCode.HOLD_NOT_ACTIVE);
                        case HOLD_EXPIRED -> DomainException.of(ErrorCode.HOLD_EXPIRED);
                    };
            if (refusal != null) {
                throw refusal;
            }
            // Step 5.
            LockedHolds.end(gateway, rows.stream().map(HoldRow::id).toList(), HoldStatus.CONVERTED);
            return placeOrder(event, group, owner, rows, now);
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    /**
     * New rows, so JPA writes them, flushed here so that a constraint violation surfaces inside confirm's
     * translation scope rather than at commit (ADR-005, plan 3B amendment).
     */
    private OrderResult placeOrder(EventRow event, UUID group, String owner, List<HoldRow> rows, Instant now) {
        List<Long> seatIds = rows.stream().map(HoldRow::eventSeatId).toList();
        Map<Long, EventSeat> seats = new HashMap<>();
        for (EventSeat seat : eventSeats.findOfferedSeats(event.id(), seatIds)) {
            seats.put(seat.getId(), seat);
        }
        Money total = Money.ofCents(0, event.currency());
        for (long seatId : seatIds) {
            total = total.plus(seats.get(seatId).price(event.currency()));
        }
        TicketOrder order = orders.saveAndFlush(new TicketOrder(
                events.getReferenceById(event.id()),
                group,
                owner,
                OrderStatus.CONFIRMED,
                total.cents(),
                total.currency(),
                now));
        List<OrderLine> lines = new ArrayList<>();
        for (long seatId : seatIds) {
            EventSeat seat = seats.get(seatId);
            lines.add(new OrderLine(order, seat, seat.getPriceCents()));
        }
        return result(order, orderLines.saveAllAndFlush(lines));
    }

    private static OrderResult result(TicketOrder order, List<OrderLine> lines) {
        return new OrderResult(
                order.getId(),
                order.getHoldGroupId(),
                order.getEvent().getId(),
                lines.stream()
                        .map(line -> new OrderResult.Line(line.getEventSeat().getId(), line.getPriceCents()))
                        .toList(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getCreatedAt());
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/OrderResult.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A confirmed order: the only purchase there is (spec §6). A repeated confirm returns the same one. */
public record OrderResult(
        long orderId,
        UUID holdGroupId,
        long eventId,
        List<Line> lines,
        long totalCents,
        String currency,
        Instant createdAt) {

    public OrderResult {
        lines = List.copyOf(lines);
    }

    public record Line(long eventSeatId, long priceCents) {}
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='ConfirmServiceTest,ClockReadPointTest,TransactionIsolationTest'`
Expected: PASS, 15 tests (10 + 4 + 1).

Run the lock grep from `CLAUDE.md`.
Expected: only `LockingGateway.java`. `ConfirmService`'s javadoc says "a shared lock on the event row" for exactly this reason.

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **173 test runs**.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/persistence/OrderLineRepository.java \
  src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java \
  src/main/java/io/github/markusluisflores/frontrow/service/OrderResult.java \
  src/test/java/io/github/markusluisflores/frontrow/service/ClockReadPointTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/ConfirmServiceTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java \
  src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java
git commit -m "feat(service): add hold confirmation, the only purchase path" \
  -m "ConfirmService follows spec section 5: unlocked event read, event FOR SHARE, group rows, then the existing order, the event check and the rows' state. It converts the rows and writes ticket_order and order_line through JPA, flushed inside the translation scope."
```

- [ ] **Step 6: Mutation checks. The read point, and the event check before the rows**

In `ConfirmService.confirm`, temporarily move the line `Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);` from after `lockGroupRows` to just before `try {`.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ClockReadPointTest#aConfirmThatWaitsOnItsRowsPastExpiryIsExpired`
Expected: FAIL: the confirm succeeds, so the assertion on its exception fails (`Expecting actual not to be null`).

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java`. Then move the line `SalesGate.requireOpen(event, now);` to just before the `// Step 5.` comment, so the rows are judged first.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ConfirmServiceTest`
Expected: 1 failure, in `aCancelledEventIsClosedEvenThoughItsHoldsWereReleased`: `expected: SALES_CLOSED but was: HOLD_NOT_ACTIVE`.

Restore the file the same way, check that `git status --short` is empty, and record both messages.

---

### Task 5: The event PATCH and cancellation

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/TicketOrderRepository.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventPatch.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchResult.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/SqlFailures.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/SqlFailuresTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`

**Interfaces:**
- Consumes: `LockingGateway#setLockTimeout` and `#lockEventForUpdate` (plan 2); `#lockActiveHoldsForEvent`, `#endHolds` and `#updateEvent` (Task 2); `LockedHolds` (Task 3); `HoldService`, `ConfirmService`, `LockHolder`.
- Produces:
  - `EventPatchService#patch(EventPatch) : EventPatchResult`;
  - `EventPatch(long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt)`, where null means unchanged;
  - `EventPatchResult(long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt, int releasedHolds)`;
  - `TicketOrderRepository#existsByEventId(long)`;
  - `SqlFailures.translateOnPatchPath(DataAccessException)`, package-private;
  - `HoldFixtures#eventStatus(long) : String`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.LockHolder;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class EventPatchServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    EventPatchService patches;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    LockingGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;
    long eventId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    @Test
    void publishesADraftSoItsSeatsCanBeHeld() {
        long draft = fixtures.event(venueId, "DRAFT", NOW.minusSeconds(60), NOW.plusSeconds(600));
        long seat = fixtures.eventSeats(draft, venueId, 1).getFirst();

        EventPatchResult result = patches.patch(new EventPatch(draft, EventStatus.ON_SALE, null, null));

        assertThat(result.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(result.salesOpenAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(holds.hold(new HoldCommand("alice", draft, List.of(seat), "k1"))
                        .seats())
                .hasSize(1);
    }

    @Test
    void movesTheSalesWindowAndHoldsFollowIt() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();

        EventPatchResult result = patches.patch(new EventPatch(eventId, null, null, NOW));

        assertThat(result.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(result.salesCloseAt()).isEqualTo(NOW);
        assertThat(rejection(() -> holds.hold(new HoldCommand("alice", eventId, List.of(seat), "k1")))
                        .errorCode())
                .isEqualTo(ErrorCode.SALES_CLOSED);
    }

    @Test
    void aWindowThatDoesNotOpenBeforeItClosesIsRefusedAsStored() {
        // 500ns apart, but timestamptz keeps microseconds: stored, these would be equal and break
        // ck_event_sales_window.
        Instant openAt = NOW.plusSeconds(60);
        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, null, openAt, openAt.plusNanos(500))));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "sales_window");
    }

    @Test
    void noPatchMovesAnEventBackToDraft() {
        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, EventStatus.DRAFT, null, null)));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "status");
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
    }

    @Test
    void aCancelledEventIsFinalButCancellingItAgainSucceeds() {
        patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null));

        assertThat(rejection(() -> patches.patch(new EventPatch(eventId, EventStatus.ON_SALE, null, null)))
                        .details())
                .containsEntry("parameter", "status");
        assertThat(rejection(() -> patches.patch(new EventPatch(eventId, null, null, NOW.plusSeconds(1))))
                        .details())
                .containsEntry("parameter", "sales_window");
        assertThat(patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null))
                        .releasedHolds())
                .isZero();
    }

    @Test
    void cancellingReleasesEveryActiveHoldOnTheEventLapsedOrNot() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 3);
        fixtures.hold(seats.get(0), "alice", "ACTIVE", NOW.plusSeconds(600));
        fixtures.hold(seats.get(1), "bob", "ACTIVE", NOW.minusSeconds(600));
        fixtures.hold(seats.get(2), "carol", "EXPIRED", NOW.minusSeconds(600));
        long otherEvent = fixtures.onSaleEvent(venueId);
        fixtures.hold(fixtures.eventSeats(otherEvent, venueId, 1).getFirst(), "dave", "ACTIVE", NOW.plusSeconds(600));

        EventPatchResult result = patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null));

        assertThat(result.releasedHolds()).isEqualTo(2);
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("CANCELLED");
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("RELEASED");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("RELEASED");
        assertThat(fixtures.statusesOwnedBy("carol")).containsExactly("EXPIRED");
        assertThat(fixtures.statusesOwnedBy("dave")).containsExactly("ACTIVE");
    }

    @Test
    void anEventWithAConfirmedOrderCannotBeCancelled() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 2);
        HoldResult sold = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(0)), "k1"));
        confirms.confirm(new HoldGroupCommand("alice", sold.holdGroupId()));
        holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(1)), "k1"));

        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null)));

        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "status");
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("ACTIVE");
    }

    @Test
    void aMissingEventIsNotFound() {
        assertThat(rejection(() -> patches.patch(new EventPatch(-1L, EventStatus.CANCELLED, null, null)))
                        .errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aPatchThatCannotGetTheEventLockAsksForARetry() throws Exception {
        // A hold creation or confirmation in flight holds the event FOR SHARE. The PATCH waits for lock_timeout (5s),
        // then gives up.
        try (LockHolder inFlight = LockHolder.start(pool, tx, jdbc, () -> gateway.lockEventForShare(eventId))) {
            Future<EventPatchResult> patch =
                    pool.submit(() -> patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null)));
            inFlight.awaitSomeoneBlocked();
            Throwable thrown = catchThrowable(() -> patch.get(30, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(((DomainException) thrown.getCause()).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        }
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
```

In `src/test/java/io/github/markusluisflores/frontrow/service/SqlFailuresTest.java`, after:

```java
            assertThat(((DomainException) translated).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        }
    }

    @Test
```

add:

```java
    void aLockTimeoutOnThePatchPathAsksTheCallerToRetry() {
        RuntimeException translated =
                SqlFailures.translateOnPatchPath(new CannotAcquireLockException("timeout", psql("55P03", null)));
        assertThat(translated).isInstanceOf(DomainException.class);
        assertThat(((DomainException) translated).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
    }

    @Test
    void aLockTimeoutAnywhereElseIsABugAndIsRethrownUnchanged() {
        DataAccessException timeout = new CannotAcquireLockException("timeout", psql("55P03", null));
        assertThat(SqlFailures.translate(timeout)).isSameAs(timeout);
    }

    @Test
    void thePatchPathStillMapsDeadlocksAndRethrowsTheRest() {
        RuntimeException deadlock =
                SqlFailures.translateOnPatchPath(new CannotAcquireLockException("lock", psql("40P01", null)));
        assertThat(((DomainException) deadlock).errorCode()).isEqualTo(ErrorCode.CONTENTION_RETRY);
        DataAccessException other = duplicate("uq_sold_once");
        assertThat(SqlFailures.translateOnPatchPath(other)).isSameAs(other);
    }

    @Test
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
            List.of(HoldService.class, ReleaseService.class, ConfirmService.class);
```

and replace it with:

```java
            List.of(HoldService.class, ReleaseService.class, ConfirmService.class, EventPatchService.class);
```

In `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`, after:

```java
                        WHERE o.hold_group_id = :group ORDER BY l.event_seat_id""").param("group", holdGroupId).query(Long.class).list();
    }
```

add:

```java

    public String eventStatus(long eventId) {
        return jdbc.sql("SELECT status FROM event WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }
```

- [ ] **Step 2: Run them to see compilation fail**

Run: `./mvnw -q test -Dtest='EventPatchServiceTest,SqlFailuresTest,TransactionIsolationTest'`
Expected: compilation FAILS: `cannot find symbol` for `EventPatchService`, `EventPatch`, `EventPatchResult` and `translateOnPatchPath`.

- [ ] **Step 3: Write the PATCH**

Replace the whole of `src/main/java/io/github/markusluisflores/frontrow/persistence/TicketOrderRepository.java` with:

```java
package io.github.markusluisflores.frontrow.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketOrderRepository extends JpaRepository<TicketOrder, Long> {

    Optional<TicketOrder> findByHoldGroupId(UUID holdGroupId);

    boolean existsByEventId(long eventId);
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/EventPatch.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/**
 * An organiser's change to one event (spec §6, {@code PATCH /api/events/{id}}): a status, a sales window, or both. A
 * null field leaves that value as it is.
 */
public record EventPatch(long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchResult.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/** The event as the PATCH left it, and how many holds a cancellation released. */
public record EventPatchResult(
        long eventId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt, int releasedHolds) {}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.EventRow;
import io.github.markusluisflores.frontrow.persistence.HoldRow;
import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import io.github.markusluisflores.frontrow.persistence.TicketOrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event PATCH, cancellation included (spec §5, Event cancellation). It locks the event row exclusively, which
 * waits for every hold creation and confirmation in flight on the event and blocks new ones until it commits. So no
 * confirm can commit against a window it has just closed, or an event it has just cancelled. A lock timeout bounds
 * every wait, and a timeout asks the organiser to retry (spec §5, Liveness). Who may call this is Plan 4's concern.
 */
@Service
public class EventPatchService {

    private final LockingGateway gateway;
    private final TicketOrderRepository orders;

    public EventPatchService(LockingGateway gateway, TicketOrderRepository orders) {
        this.gateway = gateway;
        this.orders = orders;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventPatchResult patch(EventPatch patch) {
        try {
            // First, so that it bounds every lock below, including a cancellation's seat rows.
            gateway.setLockTimeout();
            EventRow event = gateway.lockEventForUpdate(patch.eventId())
                    .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
            EventStatus status = patch.status() == null ? event.status() : patch.status();
            // timestamptz keeps microseconds; the window is checked as it will be stored.
            Instant openAt = patch.salesOpenAt() == null
                    ? event.salesOpenAt()
                    : patch.salesOpenAt().truncatedTo(ChronoUnit.MICROS);
            Instant closeAt = patch.salesCloseAt() == null
                    ? event.salesCloseAt()
                    : patch.salesCloseAt().truncatedTo(ChronoUnit.MICROS);
            requireAllowed(event, status, openAt, closeAt);

            int released = status == EventStatus.CANCELLED && event.status() != EventStatus.CANCELLED
                    ? releaseForCancellation(event.id())
                    : 0;
            gateway.updateEvent(event.id(), status, openAt, closeAt);
            return new EventPatchResult(event.id(), status, openAt, closeAt, released);
        } catch (DataAccessException failure) {
            throw SqlFailures.translateOnPatchPath(failure);
        }
    }

    private static void requireAllowed(EventRow event, EventStatus status, Instant openAt, Instant closeAt) {
        boolean statusAllowed =
                switch (event.status()) {
                    case DRAFT -> true;
                    // No PATCH moves an event back to DRAFT (spec §5, confirmation step 3).
                    case ON_SALE -> status != EventStatus.DRAFT;
                    case CANCELLED -> status == EventStatus.CANCELLED;
                };
        if (!statusAllowed) {
            throw invalid("status");
        }
        boolean windowChanged = !openAt.equals(event.salesOpenAt()) || !closeAt.equals(event.salesCloseAt());
        if ((event.status() == EventStatus.CANCELLED && windowChanged) || !openAt.isBefore(closeAt)) {
            throw invalid("sales_window");
        }
    }

    /**
     * Refused once anything is sold: cancelling sold events is part of the cancellation non-goal (spec §2).
     * Otherwise every ACTIVE hold on the event moves to RELEASED, lapsed or not, locked ascending.
     */
    private int releaseForCancellation(long eventId) {
        // After the event-row wait, so it sees every confirmation that committed first (READ COMMITTED, spec §5).
        if (orders.existsByEventId(eventId)) {
            throw invalid("status");
        }
        List<Long> holdIds = gateway.lockActiveHoldsForEvent(eventId).stream()
                .map(HoldRow::id)
                .toList();
        LockedHolds.end(gateway, holdIds, HoldStatus.RELEASED);
        return holdIds.size();
    }

    private static DomainException invalid(String parameter) {
        return new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }
}
```

In `src/main/java/io/github/markusluisflores/frontrow/service/SqlFailures.java`, after:

```java
        return translate(failure);
    }
```

add:

```java

    /** The event PATCH, the one path that sets lock_timeout: a timeout there also asks for a retry (spec §5). */
    static RuntimeException translateOnPatchPath(DataAccessException failure) {
        if (PostgresErrors.LOCK_NOT_AVAILABLE.equals(PostgresErrors.sqlState(failure))) {
            return DomainException.of(ErrorCode.CONTENTION_RETRY);
        }
        return translate(failure);
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='EventPatchServiceTest,SqlFailuresTest,TransactionIsolationTest'`
Expected: PASS, 18 tests (9 + 8 + 1). `aPatchThatCannotGetTheEventLockAsksForARetry` takes about 5 seconds, the configured `lock-timeout`.

Run the lock grep from `CLAUDE.md`.
Expected: only `LockingGateway.java`. `EventPatchService`'s javadoc says "locks the event row exclusively" for this reason.

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **185 test runs**.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/persistence/TicketOrderRepository.java \
  src/main/java/io/github/markusluisflores/frontrow/service/EventPatch.java \
  src/main/java/io/github/markusluisflores/frontrow/service/EventPatchResult.java \
  src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java \
  src/main/java/io/github/markusluisflores/frontrow/service/SqlFailures.java \
  src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/SqlFailuresTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java \
  src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java
git commit -m "feat(service): add the event PATCH and cancellation service" \
  -m "EventPatchService locks the event FOR UPDATE under lock_timeout, enforces the status transitions and the sales window, and on cancellation refuses a sold event or releases every ACTIVE hold. A lock timeout there is contention_retry."
```

- [ ] **Step 6: Mutation checks. Sold events can't be cancelled, and the PATCH can't wait forever**

In `EventPatchService.releaseForCancellation`, temporarily change `if (orders.existsByEventId(eventId)) {` to `if (false && orders.existsByEventId(eventId)) {`.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=EventPatchServiceTest`
Expected: 1 failure, in `anEventWithAConfirmedOrderCannotBeCancelled`: the cancellation succeeds, so the assertion on its exception fails (`Expecting actual not to be null`).

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`. Then delete the line `gateway.setLockTimeout();`.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=EventPatchServiceTest#aPatchThatCannotGetTheEventLockAsksForARetry`
Expected: FAIL with `java.util.concurrent.TimeoutException` after about 30 seconds: with no timeout, the PATCH waits for as long as the holder does.

Restore the file the same way, check that `git status --short` is empty, and record both messages.

---

### Task 6: The sweeper, the 24-hour purge, and replay that survives it

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/config/FrontRowProperties.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/config/SweepSchedule.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/config/FrontRowPropertiesTest.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/config/SweepScheduleTest.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/ExpirySweepServiceTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`
- Create: `src/test/resources/config/application.properties`

**Interfaces:**
- Consumes: `LockingGateway#expireLapsedHolds`, `#purgeHoldRequestsCreatedBefore` (Task 2), `#claimIdempotencyKey`, `#findHoldRequest` (plan 3A); `ConfirmService` (Task 4).
- Produces:
  - `ExpirySweepService#expireLapsedHolds() : int` and `#purgeHoldRequests() : int`;
  - `SweepSchedule`, present unless `frontrow.sweeper-enabled=false`;
  - `FrontRowProperties#sweepInterval() : Duration`;
  - `HoldFixtures#holdRequest(String owner, String idempotencyKey, Instant createdAt)`.

- [ ] **Step 1: Write the failing tests, and turn the sweeper off in tests**

Replace the whole of `src/test/java/io/github/markusluisflores/frontrow/config/FrontRowPropertiesTest.java` with:

```java
package io.github.markusluisflores.frontrow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Runs the real @Validated binding, with no database and no full application context. */
class FrontRowPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(FrontRowProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues(
                    "frontrow.hold-ttl=10m",
                    "frontrow.max-active-hold-groups=3",
                    "frontrow.max-seats-per-hold=8",
                    "frontrow.lock-timeout=5s",
                    "frontrow.sweep-interval=1m");

    @Test
    void theShippedDefaultsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FrontRowProperties bound = context.getBean(FrontRowProperties.class);
            assertThat(bound.holdTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(bound.maxActiveHoldGroups()).isEqualTo(3);
            assertThat(bound.maxSeatsPerHold()).isEqualTo(8);
            assertThat(bound.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(bound.sweepInterval()).isEqualTo(Duration.ofMinutes(1));
        });
    }

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

    @Test
    void aSubSecondOrOverlongSweepIntervalIsRejected() {
        runner.withPropertyValues("frontrow.sweep-interval=500ms")
                .run(context -> assertRejected(context, "sweepInterval"));
        runner.withPropertyValues("frontrow.sweep-interval=2h")
                .run(context -> assertRejected(context, "sweepInterval"));
    }

    /** Fails for the named property's bound, not for some unrelated startup error. */
    private static void assertRejected(AssertableApplicationContext context, String field) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause()
                .hasMessageContaining(field);
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/config/SweepScheduleTest.java`:

```java
package io.github.markusluisflores.frontrow.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.service.ExpirySweepService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The schedule's wiring, with no database. The sweeper is a stub that counts its calls. The scheduler never runs it,
 * because the first run is an hour away, so the test runs each task once itself.
 */
class SweepScheduleTest {

    @Configuration
    @EnableConfigurationProperties(FrontRowProperties.class)
    static class PropertiesOnly {}

    /** Counts the sweeps and purges it is asked for, and touches nothing. */
    static final class RecordingSweeper extends ExpirySweepService {

        final AtomicInteger sweeps = new AtomicInteger();
        final AtomicInteger purges = new AtomicInteger();

        RecordingSweeper() {
            super(null, Clock.systemUTC());
        }

        @Override
        public int expireLapsedHolds() {
            sweeps.incrementAndGet();
            return 0;
        }

        @Override
        public int purgeHoldRequests() {
            purges.incrementAndGet();
            return 0;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class, SweepSchedule.class)
            .withBean(ExpirySweepService.class, RecordingSweeper::new)
            .withPropertyValues(
                    "frontrow.hold-ttl=10m",
                    "frontrow.max-active-hold-groups=3",
                    "frontrow.max-seats-per-hold=8",
                    "frontrow.lock-timeout=5s",
                    "frontrow.sweep-interval=1h");

    @Test
    void schedulesTheSweepAndThePurgeEachIntervalStartingOneIntervalIn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var scheduled = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(scheduled).hasSize(2).allSatisfy(each -> {
                FixedDelayTask task = (FixedDelayTask) each.getTask();
                assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
            });

            scheduled.stream()
                    .map(ScheduledTask::getTask)
                    .forEach(task -> task.getRunnable().run());

            RecordingSweeper sweeper = (RecordingSweeper) context.getBean(ExpirySweepService.class);
            assertThat(sweeper.sweeps).hasValue(1);
            assertThat(sweeper.purges).hasValue(1);
        });
    }

    @Test
    void isOffWhenTheSweeperIsDisabled() {
        runner.withPropertyValues("frontrow.sweeper-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SweepSchedule.class));
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/service/ExpirySweepServiceTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ExpirySweepServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    ExpirySweepService sweeper;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

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
    void expiresOnlyLapsedActiveHolds() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 3);
        fixtures.hold(seats.get(0), "alice", "ACTIVE", NOW);
        fixtures.hold(seats.get(1), "bob", "ACTIVE", NOW.plusMillis(1));
        fixtures.hold(seats.get(2), "carol", "CONVERTED", NOW.minusSeconds(1));

        assertThat(sweeper.expireLapsedHolds()).isEqualTo(1);
        assertThat(fixtures.statusesOwnedBy("alice")).containsExactly("EXPIRED");
        assertThat(fixtures.statusesOwnedBy("bob")).containsExactly("ACTIVE");
        assertThat(fixtures.statusesOwnedBy("carol")).containsExactly("CONVERTED");
    }

    @Test
    void whetherTheSweepRanNeverChangesWhatConfirmAnswers() {
        HoldResult held = holds.hold(new HoldCommand("alice", eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
        clock.set(held.expiresAt());
        HoldGroupCommand confirm = new HoldGroupCommand("alice", held.holdGroupId());

        assertThat(confirmCode(confirm)).isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(sweeper.expireLapsedHolds()).isEqualTo(2);
        assertThat(confirmCode(confirm)).isEqualTo(ErrorCode.HOLD_EXPIRED);
    }

    @Test
    void purgesKeysOnceTheyAreMoreThanTwentyFourHoursOld() {
        List<Long> seats = fixtures.eventSeats(eventId, venueId, 1);
        holds.hold(new HoldCommand("alice", eventId, seats, "k1"));

        clock.set(NOW.plus(24, ChronoUnit.HOURS));
        assertThat(sweeper.purgeHoldRequests()).isZero();
        clock.set(NOW.plus(24, ChronoUnit.HOURS).plus(1, ChronoUnit.MICROS));
        assertThat(sweeper.purgeHoldRequests()).isEqualTo(1);
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();
    }

    /**
     * PR #18 thread 4296540462: a key purged between hold creation's claim and its read must not become a 500. A
     * statement-level trigger stands in for the purge committing in between. It fires even when ON CONFLICT DO
     * NOTHING inserts nothing, so the claim conflicts with the old row, then the trigger deletes that row before the
     * read. The request must then run as new, under a fresh claim.
     */
    @Test
    void aKeyPurgedBetweenTheClaimAndTheReadRunsAsANewRequest() {
        long seat = fixtures.eventSeats(eventId, venueId, 1).getFirst();
        fixtures.holdRequest("alice", "k1", NOW.minus(25, ChronoUnit.HOURS));
        jdbc.sql("""
                        CREATE FUNCTION purge_old_keys() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            DELETE FROM hold_request WHERE created_at < '2026-10-01T00:00:00Z';
                            RETURN NULL;
                        END $$""").update();
        jdbc.sql("CREATE TRIGGER purge_old_keys AFTER INSERT ON hold_request"
                        + " FOR EACH STATEMENT EXECUTE FUNCTION purge_old_keys()")
                .update();
        try {
            assertThat(holds.hold(new HoldCommand("alice", eventId, List.of(seat), "k1"))
                            .seats())
                    .hasSize(1);
            assertThat(fixtures.holdRequestCreatedAt("alice", "k1")).isEqualTo(NOW);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS purge_old_keys ON hold_request").update();
            jdbc.sql("DROP FUNCTION IF EXISTS purge_old_keys()").update();
        }
    }

    private ErrorCode confirmCode(HoldGroupCommand command) {
        Throwable thrown = catchThrowable(() -> confirms.confirm(command));
        assertThat(thrown).isInstanceOf(DomainException.class);
        return ((DomainException) thrown).errorCode();
    }
}
```

In `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`, after:

```java
import io.github.markusluisflores.frontrow.config.FrontRowProperties;
```

add:

```java
import io.github.markusluisflores.frontrow.config.SweepSchedule;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`, after:

```java
import org.springframework.boot.test.context.SpringBootTest;
```

add:

```java
import org.springframework.context.ApplicationContext;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`, after:

```java
    FrontRowProperties properties;
```

add:

```java

    @Autowired
    ApplicationContext context;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`, find:

```java
     * buyer. Plan 3A has no sweeper at all; Plan 3B must keep this test running with its sweeper disabled.
```

and replace it with:

```java
     * buyer. Every test context runs with the sweeper off (src/test/resources/config/application.properties), and this
     * test checks that it is.
```

In `src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java`, after:

```java
    void lazyExpiryFreesSeatsWithoutTheSweeper() {
```

add:

```java
        assertThat(context.getBeansOfType(SweepSchedule.class)).isEmpty();
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
    private static final List<Class<?>> SERVICES =
            List.of(HoldService.class, ReleaseService.class, ConfirmService.class, EventPatchService.class);
```

and replace it with:

```java
    private static final List<Class<?>> SERVICES = List.of(
            HoldService.class,
            ReleaseService.class,
            ConfirmService.class,
            EventPatchService.class,
            ExpirySweepService.class);
```

In `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`, after:

```java
                .param("id", eventId)
                .update();
    }
```

add:

```java

    /** A stored key whose row no request has completed with a real result: for purge tests only. */
    public void holdRequest(String owner, String idempotencyKey, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO hold_request (owner, idempotency_key, request_hash, response_json, created_at)
                        VALUES (:owner, :key, 'stale', '{}', :created)""")
                .param("owner", owner)
                .param("key", idempotencyKey)
                .param("created", utc(createdAt))
                .update();
    }
```

Create `src/test/resources/config/application.properties`:

```properties
# Every test context runs without the scheduled sweeper. Spec section 5 test 7 must pass without it, and no test may
# race a sweep it did not start. SweepScheduleTest covers the schedule itself.
frontrow.sweeper-enabled=false
```

- [ ] **Step 2: Run them to see compilation fail**

Run: `./mvnw -q test -Dtest='ExpirySweepServiceTest,SweepScheduleTest,FrontRowPropertiesTest,HoldConcurrencyTest,TransactionIsolationTest'`
Expected: compilation FAILS: `cannot find symbol` for `ExpirySweepService`, `SweepSchedule` and `sweepInterval()`.

- [ ] **Step 3: Write the sweeper and its schedule, and re-claim a purged key**

In `src/main/java/io/github/markusluisflores/frontrow/config/FrontRowProperties.java`, find:

```java
        Duration lockTimeout) {}
```

and replace it with:

```java
        Duration lockTimeout,

        @NotNull @DurationMin(seconds = 1) @DurationMax(hours = 1)
        Duration sweepInterval) {}
```

Create `src/main/java/io/github/markusluisflores/frontrow/config/SweepSchedule.java`:

```java
package io.github.markusluisflores.frontrow.config;

import io.github.markusluisflores.frontrow.service.ExpirySweepService;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the sweep and the key purge every {@code frontrow.sweep-interval}, as two tasks, so a failed sweep never
 * skips a purge (spec §5). The first run is one interval after startup. Off when {@code frontrow.sweeper-enabled} is
 * false: correctness never depends on it, and spec §5 test 7 must pass without it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "frontrow.sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class SweepSchedule implements SchedulingConfigurer {

    private final ExpirySweepService sweeper;
    private final Duration interval;

    public SweepSchedule(ExpirySweepService sweeper, FrontRowProperties properties) {
        this.sweeper = sweeper;
        this.interval = properties.sweepInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(sweeper::expireLapsedHolds, interval, interval));
        registrar.addFixedDelayTask(new FixedDelayTask(sweeper::purgeHoldRequests, interval, interval));
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.persistence.LockingGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduled, hygiene half of hold expiry (spec §5). Correctness never depends on it: hold creation expires lapsed
 * rows itself. Each method is its own transaction, so the key purge runs apart from the sweep, and each reads the
 * clock at its start, because neither waits on a lock.
 */
@Service
public class ExpirySweepService {

    /** Keys live in hold_request for 24 hours (spec §6). */
    static final Duration KEY_RETENTION = Duration.ofHours(24);

    private final LockingGateway gateway;
    private final Clock clock;

    public ExpirySweepService(LockingGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int expireLapsedHolds() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            return gateway.expireLapsedHolds(now);
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int purgeHoldRequests() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            return gateway.purgeHoldRequestsCreatedBefore(now.minus(KEY_RETENTION));
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }
}
```

In `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, after:

```java
import java.util.Map;
```

add:

```java
import java.util.Optional;
```

In `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, find:

```java
            if (!gateway.claimIdempotencyKey(command.owner(), command.idempotencyKey(), fingerprint, arrivedAt)) {
                return replay(command, fingerprint);
```

and replace it with:

```java
            Optional<HoldResult> replayed = claimOrReplay(command, fingerprint, arrivedAt);
            if (replayed.isPresent()) {
                return replayed.get();
```

In `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, find:

```java
    private HoldResult replay(HoldCommand command, String fingerprint) {
        StoredHoldRequest stored = gateway.findHoldRequest(command.owner(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("a conflicting hold_request row was not found"));
```

and replace it with:

```java
    /** Step 1. Empty when this call now owns the key; otherwise the stored result to replay (spec §5). */
    private Optional<HoldResult> claimOrReplay(HoldCommand command, String fingerprint, Instant arrivedAt) {
        String owner = command.owner();
        String key = command.idempotencyKey();
        if (gateway.claimIdempotencyKey(owner, key, fingerprint, arrivedAt)) {
            return Optional.empty();
        }
        Optional<StoredHoldRequest> stored = gateway.findHoldRequest(owner, key);
        if (stored.isEmpty()) {
            // The 24-hour purge deleted the row between the claim and this read, so the key has expired: claim it
            // afresh. If that claim conflicts too, another request with this key has just committed; replay it.
            if (gateway.claimIdempotencyKey(owner, key, fingerprint, arrivedAt)) {
                return Optional.empty();
            }
            stored = gateway.findHoldRequest(owner, key);
        }
        return Optional.of(replay(
                stored.orElseThrow(() -> new IllegalStateException("a conflicting hold_request row was not found")),
                fingerprint));
    }

    private static HoldResult replay(StoredHoldRequest stored, String fingerprint) {
```

Replace the whole of `src/main/resources/application.yml` with:

```yaml
spring:
  application:
    name: frontrow
  ai:
    mcp:
      server:
        name: frontrow
        protocol: STREAMABLE
        type: SYNC
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.batch_size: 0

frontrow:
  hold-ttl: 10m
  max-active-hold-groups: 3
  max-seats-per-hold: 8
  lock-timeout: 5s
  sweep-interval: 1m
  # Off in tests (src/test/resources/config/application.properties). Correctness never depends on the sweeper.
  sweeper-enabled: true
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='ExpirySweepServiceTest,SweepScheduleTest,FrontRowPropertiesTest,HoldConcurrencyTest,TransactionIsolationTest'`
Expected: PASS, 18 tests (4 + 2 + 5 + 6 + 1).

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **192 test runs**.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/config/FrontRowProperties.java \
  src/main/java/io/github/markusluisflores/frontrow/config/SweepSchedule.java \
  src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java \
  src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java \
  src/main/resources/application.yml \
  src/test/java/io/github/markusluisflores/frontrow/config/FrontRowPropertiesTest.java \
  src/test/java/io/github/markusluisflores/frontrow/config/SweepScheduleTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/ExpirySweepServiceTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/HoldConcurrencyTest.java \
  src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java \
  src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java \
  src/test/resources/config/application.properties
git commit -m "feat(service): add the expiry sweeper and the 24-hour key purge" \
  -m "ExpirySweepService expires lapsed holds with SKIP LOCKED and purges keys older than 24 hours, each in its own transaction. SweepSchedule runs both on frontrow.sweep-interval and is off in every test context. Hold creation re-claims a key purged between its claim and its read."
```

- [ ] **Step 6: Mutation checks. A purged key must not be a 500, the schedule must run both jobs, and no test context may run the sweeper**

In `HoldService.claimOrReplay`, temporarily delete the whole `if (stored.isEmpty()) { … }` block.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ExpirySweepServiceTest`
Expected: 1 error, in `aKeyPurgedBetweenTheClaimAndTheReadRunsAsANewRequest`: `IllegalStateException: a conflicting hold_request row was not found`.

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`. Then, in `SweepSchedule.configureTasks`, change the second task's `sweeper::purgeHoldRequests` to `sweeper::expireLapsedHolds`, so the sweep is scheduled twice and the purge never.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=SweepScheduleTest`
Expected: 1 failure, in `schedulesTheSweepAndThePurgeEachIntervalStartingOneIntervalIn`: `Expecting AtomicInteger(2) to have value: 1 but did not.`

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/config/SweepSchedule.java`. Then move `src/test/resources/config/application.properties` out of the tree, **and delete its copy at `target/test-classes/config/application.properties`**. Maven copies resources but never deletes a stale copy. Without the second deletion the test still passes, a false pass this plan's authoring hit.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=HoldConcurrencyTest#lazyExpiryFreesSeatsWithoutTheSweeper`
Expected: FAIL: `Expecting empty but was: {"sweepSchedule"=…}`.

Move the file back, run `./mvnw -q test -Dtest=HoldConcurrencyTest` (PASS, 6 tests, which also restores the copy in `target`), check that `git status --short` is empty, and record all three messages.

---

### Task 7: The concurrency tests that need confirm and cancellation (spec §5 tests 2 and 6)

Production code doesn't change in this task. The tests race the services Tasks 3-6 built, so they pass on first run. Their value is shown by the mutation checks in Step 3.

**Files:**
- Modify: `CLAUDE.md`
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/ConfirmConcurrencyTest.java`

**Interfaces:**
- Consumes: `Race` (plan 3A), `TestClock#setForThisThread` (Task 1), `HoldService`, `ConfirmService`, `EventPatchService`, and `HoldFixtures`.
- Produces: nothing later tasks use.

- [ ] **Step 1: Write the race tests**

Create `src/test/java/io/github/markusluisflores/frontrow/service/ConfirmConcurrencyTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.Race;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec §5's centrepiece tests that need confirm and cancellation: 2 and 6, plus the double-clicked confirm the READ
 * COMMITTED list depends on. Every racer runs in its own transaction on its own virtual thread, against real Postgres.
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ConfirmConcurrencyTest {

    private static final int REPETITIONS = 20;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    EventPatchService patches;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    ExecutorService pool;
    long venueId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    /**
     * Test 2. A 3-seat group, and two racers straddling its expiry, each on its own clock: the owner confirms at
     * expires_at - 1ms, and a rival holds an overlapping set at expires_at + 1ms. Either may win, never both.
     */
    @Test
    void aConfirmAndAnOverlappingHoldAcrossExpiryHaveExactlyOneWinner() throws Exception {
        long eventId = fixtures.onSaleEvent(venueId);
        for (int rep = 0; rep < REPETITIONS; rep++) {
            List<Long> s = fixtures.eventSeats(eventId, venueId, 4);
            String owner = "owner-r" + rep;
            String rivalOwner = "rival-r" + rep;
            HoldResult group = holds.hold(new HoldCommand(owner, eventId, s.subList(0, 3), "group"));
            Instant expiry = group.expiresAt();
            List<Long> overlap = List.of(s.get(3), s.get(2), s.get(1));

            List<Race.Outcome<Object>> outcomes = Race.run(
                    pool,
                    List.of(
                            at(
                                    expiry.minusMillis(1),
                                    () -> confirms.confirm(new HoldGroupCommand(owner, group.holdGroupId()))),
                            at(
                                    expiry.plusMillis(1),
                                    () -> holds.hold(new HoldCommand(rivalOwner, eventId, overlap, "rival")))));

            Race.Outcome<Object> confirm = outcomes.get(0);
            Race.Outcome<Object> rival = outcomes.get(1);
            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(1);
            if (confirm.won()) {
                assertThat(rival.errorCode()).as("%s", rival.failure()).isEqualTo(ErrorCode.SEAT_TAKEN);
            } else {
                assertThat(confirm.errorCode()).as("%s", confirm.failure()).isEqualTo(ErrorCode.HOLD_EXPIRED);
            }
            // Never both a sale and a new claim on the overlapping seats.
            boolean sold = fixtures.ordersForGroup(group.holdGroupId()) == 1;
            boolean reclaimed = !fixtures.statusesOwnedBy(rivalOwner).isEmpty();
            assertThat(sold).as("repetition %d: sold xor reclaimed", rep).isNotEqualTo(reclaimed);
            for (long seat : s.subList(1, 3)) {
                assertThat(fixtures.claimsOn(seat)).isEqualTo(1);
            }
        }
    }

    /**
     * Test 6. An organiser cancels while the owner confirms. Either the confirm gets sales_closed and the event is
     * CANCELLED, or the order exists and the cancellation gets invalid_request. Never both, and no deadlock.
     */
    @Test
    void aCancellationAndAConfirmNeverBothSucceed() throws Exception {
        for (int rep = 0; rep < REPETITIONS; rep++) {
            long eventId = fixtures.onSaleEvent(venueId);
            String owner = "buyer-r" + rep;
            HoldResult group =
                    holds.hold(new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));

            List<Race.Outcome<Object>> outcomes = Race.run(
                    pool,
                    List.<Callable<Object>>of(
                            () -> confirms.confirm(new HoldGroupCommand(owner, group.holdGroupId())),
                            () -> patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null))));

            Race.Outcome<Object> confirm = outcomes.get(0);
            Race.Outcome<Object> cancel = outcomes.get(1);
            assertThat(outcomes)
                    .as("repetition %d", rep)
                    .filteredOn(Race.Outcome::won)
                    .hasSize(1);
            if (cancel.won()) {
                assertThat(confirm.errorCode()).as("%s", confirm.failure()).isEqualTo(ErrorCode.SALES_CLOSED);
                assertThat(fixtures.eventStatus(eventId)).isEqualTo("CANCELLED");
                assertThat(fixtures.statusesOwnedBy(owner)).containsOnly("RELEASED");
                assertThat(fixtures.ordersForGroup(group.holdGroupId())).isZero();
            } else {
                assertThat(cancel.errorCode()).as("%s", cancel.failure()).isEqualTo(ErrorCode.INVALID_REQUEST);
                assertThat(fixtures.eventStatus(eventId)).isEqualTo("ON_SALE");
                assertThat(fixtures.ordersForGroup(group.holdGroupId())).isEqualTo(1);
            }
        }
    }

    /** A double-clicked confirm: the second waits on the first's locks, then returns the same order. */
    @Test
    void twoConfirmsOfOneGroupInParallelYieldOneOrder() throws Exception {
        long eventId = fixtures.onSaleEvent(venueId);
        for (int rep = 0; rep < REPETITIONS; rep++) {
            String owner = "clicker-r" + rep;
            HoldResult group =
                    holds.hold(new HoldCommand(owner, eventId, fixtures.eventSeats(eventId, venueId, 2), "k1"));
            HoldGroupCommand command = new HoldGroupCommand(owner, group.holdGroupId());

            List<Race.Outcome<OrderResult>> outcomes =
                    Race.run(pool, List.of(() -> confirms.confirm(command), () -> confirms.confirm(command)));

            assertThat(outcomes).as("repetition %d", rep).allMatch(Race.Outcome::won);
            assertThat(outcomes.get(0).result()).isEqualTo(outcomes.get(1).result());
            assertThat(fixtures.ordersForGroup(group.holdGroupId())).isEqualTo(1);
        }
    }

    /** Runs the call on this racer's own clock: test 2 needs two instants at once. */
    private Callable<Object> at(Instant instant, Callable<?> call) {
        return () -> {
            clock.setForThisThread(instant);
            try {
                return call.call();
            } finally {
                clock.clearForThisThread();
            }
        };
    }
}
```

- [ ] **Step 2: Run them**

Run: `./mvnw -q test -Dtest=ConfirmConcurrencyTest`
Expected: PASS, 3 tests.

Then run the class **three more times** in a row: `for i in 1 2 3; do ./mvnw -q test -Dtest=ConfirmConcurrencyTest || break; done`.
Expected: three PASS runs. A flake here is a finding, not noise. Report it with the failing assertion text, and don't retry until green.

**Both outcomes should occur.** In the authoring runs, test 2's confirm won 37 of 40 repetitions and the rival won 3. Test 6's cancellation won 39 of 40. A run where one side always wins is still a pass, because spec §5 allows either order. Test 6's confirm-first path is also covered sequentially, by `EventPatchServiceTest#anEventWithAConfirmedOrderCannotBeCancelled`.

- [ ] **Step 3: Mutation checks. Test 6 needs both event locks, and test 2 needs the claim index to cover sold seats**

In `LockingGateway.lockEventForShare`, temporarily change `return lockEvent(eventId, "FOR SHARE");` to `return lockEvent(eventId, "");`. Confirm then reads the event without locking it.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ConfirmConcurrencyTest#aCancellationAndAConfirmNeverBothSucceed`
Expected: FAIL: `expected: SALES_CLOSED but was: HOLD_NOT_ACTIVE`. The confirm passed the event check before the cancellation committed, then found its rows released.

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`. Then, in `LockingGateway.lockEventForUpdate`, change `return lockEvent(eventId, "FOR UPDATE");` to `return lockEvent(eventId, "FOR SHARE");`. The PATCH then shares the event row with the confirm, so it can look for an order before the confirm commits.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ConfirmConcurrencyTest#aCancellationAndAConfirmNeverBothSucceed`
Expected: FAIL, in one of three ways. First, `[repetition N] Expected size: 1 but was: 2`: the order and the cancellation both went through. Second, `expected: INVALID_REQUEST but was: CONTENTION_RETRY`: the PATCH's `UPDATE` waited on the confirm's share lock until `lock_timeout`. Third, `expected: SALES_CLOSED but was: CONTENTION_RETRY`: the cancellation won and the confirm was the deadlock victim (`40P01` becomes `contention_retry`). In five authoring runs, every run failed: three times the first way, each at repetition 0, and twice the second. In two review runs, both failed: once the first way, at repetition 0, and once the third. Every `EventPatchServiceTest` test still passes under this mutation, so test 6 is the only test of the lock's strength.

Restore with `git checkout -- src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`. Then, in `src/main/resources/db/migration/V1__core_schema.sql`, change the index predicate `WHERE status IN ('ACTIVE', 'CONVERTED');` to `WHERE status = 'ACTIVE';`. Each test context starts a fresh container, so Flyway applies the mutated migration.

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=ConfirmConcurrencyTest#aConfirmAndAnOverlappingHoldAcrossExpiryHaveExactlyOneWinner`
Expected: FAIL: `[repetition N] Expected size: 1 but was: 2`. Both the sale and the rival's hold went through.

Restore with `git checkout -- src/main/resources/db/migration/V1__core_schema.sql`, then delete `target/classes/db/migration/V1__core_schema.sql` so no stale copy survives, and check that `git status --short` is empty. Record all three messages.

**What test 2 does not catch, honestly stated:** reversing `lockGroupRows`' order to `DESC` passed test 2 in 20 repetitions during authoring, because the deadlock window is narrow. That mutation is caught deterministically by the existing `LockingGatewayTest#locksGroupRowsInAscendingSeatOrder`.

- [ ] **Step 4: Update the current state in `CLAUDE.md`, final checks, and commit**

In `CLAUDE.md`, find:

```markdown
**Phase 1 plans 1 (foundation), 2 (domain foundations) and 3A (hold creation) are complete.
Together they provide: a Maven build, CI, the V1 schema migration, a principal-propagation spike, a pure
`domain` package (money, statuses, sales window, hold rules, the request fingerprint), the shared error
contract (`error`), JPA entities and repositories over all eight V1 tables (`persistence`), the locking
gateway (`persistence/LockingGateway.java`), and the first write path: `service/HoldService`, hold creation
with idempotent replay. No confirm, release, sweeper, REST endpoint or MCP tool exists yet.**
142 test runs pass, including spec §5 concurrency tests 1, 3, 4, 5 and 7 and a proof that test 1 fails
without `uq_claimed_seat`. Phase 1 is now six plans: 3B (confirm, release, cancel service, sweeper,
tests 2 and 6), 4 (REST + security chains) and 5 (MCP) remain.
```

and replace it with:

```markdown
**Phase 1 plans 1 (foundation), 2 (domain foundations), 3A (hold creation) and 3B (confirm, release,
cancellation, the sweeper) are complete. Together they provide: a Maven build, CI, the V1 schema
migration, a principal-propagation spike, a pure `domain` package (money, statuses, sales window, hold
rules, the request fingerprint), the shared error contract (`error`), JPA entities and repositories over
all eight V1 tables (`persistence`), the locking gateway (`persistence/LockingGateway.java`), and every
write path as a service in `service/`: hold creation with idempotent replay (`HoldService`), confirmation,
the only purchase path (`ConfirmService`), release (`ReleaseService`), the event PATCH and cancellation
(`EventPatchService`), and the expiry sweeper and 24-hour key purge (`ExpirySweepService`, scheduled by
`config/SweepSchedule`). No REST endpoint or MCP tool exists yet.**
195 test runs pass, including all seven spec §5 concurrency tests and a proof that test 1 fails without
`uq_claimed_seat`. Plans 4 (REST + security chains) and 5 (MCP) remain.
```

In `CLAUDE.md`, find:

```markdown
`not_found` — that `main` meets only once plan 3B lands; the spec header lists them).
```

and replace it with:

```markdown
`not_found` — which plan 3B implements; the spec header's "Where `main` differs" block is stale until the
next spec sync).
```

In `CLAUDE.md`, find:

```markdown
`docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`.
```

and replace it with:

```markdown
`docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`. Plan 3B:
`docs/superpowers/plans/2026-10-17-phase-1-plan-3b-confirm-release-cancel.md`.
```

Run both `CLAUDE.md` greps.
Expected: `domain` imports only `java.*`, and lock vocabulary appears only in `LockingGateway.java`.

Run: `./mvnw verify`
Expected: BUILD SUCCESS, SpotBugs 0, **195 test runs**, the number now in `CLAUDE.md`. If the total differs, stop and report it: a test was lost or added somewhere.

```bash
git add CLAUDE.md \
  src/test/java/io/github/markusluisflores/frontrow/service/ConfirmConcurrencyTest.java
git commit -m "test(service): add spec section 5 concurrency tests 2 and 6" \
  -m "ConfirmConcurrencyTest races a confirm against an overlapping hold across expiry on per-thread clocks (test 2), a cancellation against a confirm (test 6), and two confirms of one group. CLAUDE.md records plan 3B as built."
```

---

## Spec coverage (this plan's share)

| Requirement | Where |
|---|---|
| §4 (rev 2.6.3): a `DRAFT` event answers `not_found` on a hold by id, disclosing nothing | Task 1 (`SalesGate`; `aDraftEventIsNotFoundAndDisclosesNothing`) |
| §4: hold transitions only `ACTIVE → EXPIRED / RELEASED / CONVERTED` | Task 2 (`endHolds` refuses `ACTIVE`, moves only `ACTIVE` rows); `HoldStatusTest` (plan 2) |
| §4: no PATCH moves an event back to `DRAFT`; cancellation is final | Task 5 (`noPatchMovesAnEventBackToDraft`, `aCancelledEventIsFinalButCancellingItAgainSucceeds`) |
| §5 *When `now` is read*: hold after the event lock, `created_at` at arrival | Task 1 (`ClockReadPointTest`, two tests and a mutation) |
| §5 *When `now` is read*: confirm after step 1's locks; release after the row locks | Tasks 4 and 3 (`ClockReadPointTest`, each with a mutation) |
| §5 *When `now` is read*: PATCH after the event lock; sweep and purge at the start | Decision 3 (the PATCH reads none); Task 6 (`ExpirySweepService`) |
| §5 *Hold confirmation* step 1: unlocked event read, `not_found` for a missing or foreign group, event `FOR SHARE`, rows ascending | Tasks 2 and 4 (`findEventIdOfGroup`, `anotherOwnersGroupIsNotFoundAndIsLeftAlone`, `anUnknownGroupIsNotFound`) |
| §5 step 2: an existing order is returned; a double-clicked confirm waits, then returns it | Task 4 (`aRepeatedConfirmReturnsTheSameOrderEvenAfterExpiryAndSalesClose`); Task 7 (`twoConfirmsOfOneGroupInParallelYieldOneOrder`) |
| §5 step 3: the event check comes before hold state | Task 4 (`aCancelledEventIsClosedEvenThoughItsHoldsWereReleased`, with a mutation; `aGroupStillLiveAfterSalesCloseIsClosed`) |
| §5 step 4: every row live → continue; `RELEASED` → `hold_not_active`; otherwise `hold_expired`, including a partly expired group | Task 4 (`aReleasedGroupIsNotActive`, `aGroupLapsingExactlyNowIsExpiredAndNothingIsSold`, `aGroupAnOverlappingHoldPartlyExpiredIsExpired`, which confirms with one `EXPIRED` row beside a live one); `HoldRulesTest` (plan 2) |
| §5 step 5: convert, then insert `ticket_order` and its lines | Task 4 (`confirmsALiveGroupIntoOneOrderAtEachSeatsPrice`, `aSoldSeatStaysClaimedSoItCannotBeHeldAgain`) |
| §5 *Release* rules 1-3; §6 another owner's hold is `not_found` | Task 3 (one test per rule, and `anotherOwnersGroupIsNotFoundAndIsLeftAlone`) |
| §5 *Event cancellation*: exclusive event lock; refuse when an order exists; release every `ACTIVE` hold ascending; every PATCH takes the same lock | Task 2 (`lockActiveHoldsForEvent` wait proof, with a mutation); Task 5 (`cancellingReleasesEveryActiveHoldOnTheEventLapsedOrNot`, `anEventWithAConfirmedOrderCannotBeCancelled`). Every PATCH runs through the same `lockEventForUpdate` call in `EventPatchService#patch`: one code path for status and window changes. Task 7's test 6 (`ConfirmConcurrencyTest#aCancellationAndAConfirmNeverBothSucceed`) proves the lock is exclusive, with a mutation that weakens it to `FOR SHARE`. `aPatchThatCannotGetTheEventLockAsksForARetry` proves only the lock timeout: a PATCH behind a share holder times out into `contention_retry`, whatever lock it asks for. No test races a window-only PATCH |
| §5 *Liveness*: `SET LOCAL lock_timeout` first; `55P03` → `contention_retry` on the PATCH path only | Task 5 (`aPatchThatCannotGetTheEventLockAsksForARetry`, with a mutation; `SqlFailuresTest`) |
| §5 *Hold expiry*: the sweeper's `SKIP LOCKED` `UPDATE`, which re-checks its predicate; the purge in its own transaction | Task 2 (`theSweepExpiresLapsedActiveHoldsAndSkipsLockedOnesWithoutWaiting`, with a mutation; `purgesOnlyKeysCreatedBeforeTheCutoff`); Task 6 (`SweepScheduleTest` runs each scheduled task once, with a mutation) |
| §5: correctness never depends on the sweeper | Task 6 (`whetherTheSweepRanNeverChangesWhatConfirmAnswers`); test 7's no-sweeper assertion |
| §5: every path that changes `seat_hold` rows locks ascending | Task 2 (`lockActiveHoldsForEvent`); `lockGroupRows` (plan 2, `locksGroupRowsInAscendingSeatOrder`) |
| §5 READ COMMITTED on every locking transaction | Every task appends to `TransactionIsolationTest.SERVICES` |
| §5 centrepiece test 2 (confirm vs. expiry, multi-seat, per-thread clock) | Task 7, with a mutation |
| §5 centrepiece test 6 (cancel vs. confirm) | Task 7, with a mutation |
| §5 centrepiece test 7 runs with the sweeper disabled | Task 6 (test config, and the assertion in `lazyExpiryFreesSeatsWithoutTheSweeper`, with a mutation) |
| §6: keys live in `hold_request` for 24 hours | Task 6 (`purgesKeysOnceTheyAreMoreThanTwentyFourHoursOld`) |
| §6: confirm is REST-only, and no MCP tool can complete a purchase | **Plans 4 and 5.** This plan adds no adapter |
| §6 / §8: owner-only release and confirm at the HTTP layer; `ORGANISER`-only PATCH | **Plan 4.** The services are owner-scoped (Tasks 3 and 4) |
| §12: test 6 runs only if the `ORGANISER` endpoints are built | Markus chose on 2026-10-14 to build the service. Test 6 races it, whether or not REST exposes it |

## Carry-forward coverage

| Item | Source | Where |
|---|---|---|
| Move `now` in `HoldService#hold` to after `lockEventForShare`; keep arrival for `created_at` | Spec 2.6.3; plan 3A E5 | Task 1 |
| `SalesGate`: `DRAFT` → `not_found`; replace `aDraftEventIsNotOpenYet` | Spec 2.6.3; plan 3A E5 | Task 1 |
| `now` sampled before the lock waits; 3B decides confirm's sampling point | PR #18 carry-forward and thread 4296540439 (closed by spec 2.6.3) | Decision 3; Tasks 1, 3, 4 |
| Per-thread `TestClock` | PR #16 thread 4285688140; PR #18 thread 4296540470 | Task 1 (decision 9). Both threads close when this plan's execution PR merges |
| The purge racing a replay | PR #18 thread 4296540462 | Task 6 (decision 8). The thread closes when the execution PR merges |
| REQUIRED propagation: `Propagation.NEVER`, or no `@Transactional` adapters | PR #18 thread 4296540451 | **Plan 4** (decision 2). The thread stays open |
| Append 3B's services to `TransactionIsolationTest.SERVICES` | Handoff memory | Tasks 3-6 |
| Keep test 7 running with the sweeper disabled | Handoff memory; plan 3A Task 4 | Task 6 |
| Decide `SeatHold#transitionTo` | PR #10; plan 3A decision 1 | Decision 1: deleted (Task 2) |
| Convert `persistence.HoldRow` → `HoldRules.HoldRow` without `domain` importing `persistence` | PR #10 | Decision 1: `HoldRow#toRule` (Task 2) |
| If confirm inserts `ticket_order` through JPA, `saveAndFlush` inside the translation `try` | PR #10; plan 3A decision 1 | Decision 1; Task 4 |
| No `SeatHold` entities in locking transactions | PR #10 | Decision 1. None is loaded; confirm's unlocked read is a scalar |
| Reuse `SalesGate` and `Race` | Handoff memory; plan 3A | Task 4 (`SalesGate`); Task 7 (`Race`) |
| Owner-scoping between an order and its holds is a service duty | Plan 2 review | Global constraint; Tasks 3 and 4 |
| Hikari's pool of 10: keep racers ≤ 10 | PR #18 carry-forward | Global constraint (2 racers per race) |
| `HoldResult` is a stored schema; a field change needs a backward-deserialization test | PR #18 carry-forward | Not triggered: `HoldResult` is unchanged (decision 4) |
| REST `Idempotency-Key` naming; owner NUL/surrogate validation; `details()` iteration order; comparing rendered errors as parsed JSON | PR #18 threads 4296541034, 4296749409, 4296749419 | **Plan 4** |
| An organiser can't GET their own draft until it is `ON_SALE` | PR #20 round 2 | **Plan 4** (no read path exists yet) |
| PATCH instants outside `timestamptz`'s range are a 500 (`22008`) | PR #22 round 1 | **Plan 4**: the adapter bounds request instants |

## Not in this plan

- **The spec header sync.** Once this plan's execution merges, `main` meets spec 2.6.3, and the spec header's "Where `main` differs" block is stale. A spec revision gets its own docs branch and review cycle, so it is a follow-up, not part of the execution PR. Task 7's `CLAUDE.md` text says the block is stale until then.
- **The journal and interview-guide sync** after the execution PR merges, as with PRs #9, #11, #17 and #19.
- **Any adapter, and any role check.** Plans 4 and 5.

## Self-review record

Run by the plan author on 2026-10-17 against the `superpowers:writing-plans` checklist.

1. **Spec coverage.** The two tables above give every §5 requirement this plan owns, every spec 2.6.3 change, and every open carry-forward item a task or a named later plan. No row is blank.
2. **Placeholder scan.** A grep for `TBD|TODO|implement later|fill in|Similar to Task|appropriate error` finds nothing.
3. **Type consistency.** Every name in an **Interfaces** block was checked against the code block that defines it. That code compiled, so the names and signatures match by construction.
4. **Sibling reuse.**
   - Confirm reuses `SalesGate`, as plan 3A designed it to.
   - Every new service translates driver failures through `SqlFailures`. The PATCH path adds one method there, rather than a second translator.
   - `LockedHolds.end` is the one "every locked row must move" check, shared by release, confirm and cancellation.
   - `LockHolder` reuses `LockWaits` and adds one query to it.
   - `HoldFixtures` gains helpers rather than a second fixture class.
   - Rejected: `SweepSchedule` reusing `@Scheduled`; decision 7 says why.
5. **Tooling self-consistency.**
   - **The code in this plan is the code that was built.** Every block was generated from a scratch commit chain built on `main` (`d93ef7f`), one commit per task. New files and short modified files are those commits' file contents. Each find/replace pair is a hunk of that task's diff, checked to occur exactly once in the file as the previous task left it.
   - **Each commit was built from clean** (`./mvnw clean verify`): Task 1 144 runs, Task 2 153, Task 3 162, Task 4 173, Task 5 185, Task 6 192, Task 7 195. Every commit had SpotBugs 0 and BUILD SUCCESS.
   - **Every Step 2 "fails" claim was observed:** each task's test changes were applied alone to the previous commit, and the named command run.
   - **Every mutation in Step 6 (Task 7: Step 3) was run,** 14 in all, with the Spotless check skipped, and failed with the message the step gives. Review round 2 added two of them: Task 6's doubled sweep, and Task 7's `FOR SHARE` PATCH lock. It also moved `aGroupAnOverlappingHoldPartlyExpiredIsExpired`'s clock back before the confirm, so the test really has an `EXPIRED` row beside a live one: a `confirmOutcome` that skipped `EXPIRED` rows passed the old test and fails the new one. Two traps surfaced:
     - deleting a test resource left a stale copy in `target/test-classes`, and the check passed falsely (Task 6 Step 6 now deletes the copy);
     - the first draft of a test-2 mutation, `DESC` lock order, was not caught in 20 repetitions (Task 7 Step 3 says so).
   - **The races were repeated:** `HoldConcurrencyTest`, `ConfirmConcurrencyTest`, `ClockReadPointTest`, `LockingGatewayTest` and `EventPatchServiceTest` passed 3 more runs in a row, 57 tests each, with no flakes.
   - **The commit messages** are the chain's own, and each passed `.githooks/commit-msg`.
   - **The greps:** at the final commit, lock vocabulary appears only in `LockingGateway.java`, and `domain` imports only `java.*`. The authoring build tripped the lock grep twice, on javadoc in `ConfirmService` and `EventPatchService`; that javadoc is fixed in the code above.
6. **Verification-matrix crossing.** The races have two dimensions likely to interact: which side commits first, and whose clock is earlier. Test 2 crosses them on purpose. The confirm runs on the earlier clock and the rival on the later one, and either may commit first; the authoring runs saw both orders. Test 6's dimension is lock strength (an exclusive event lock against a shared one), crossed with commit order in the same way.
