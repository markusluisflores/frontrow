# ADR-005: JPA for ordinary persistence, explicit JDBC for every lock

**Date:** 2026-10-02 (decided) · recorded 2026-10-05
**Status:** Accepted

Decided by Markus on 2026-10-02 while Plan 2 was being written, choosing from three options. Built in PR #10 (`e432c3c`):
- `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`
- the eight JPA entities beside it

## Context and Problem Statement

FrontRow needs two kinds of database access:

- **Ordinary reads and writes** of venues, events, seats, holds and orders.
- **The concurrency protocol in spec §5:** an advisory lock per owner, `FOR SHARE`/`FOR UPDATE` on the event row, and seat-hold row locks in ascending `event_seat_id` order. Lazy expiry has to land before the insert that depends on it.

Spec §5 constrains the second kind directly:

- "A locking query must return the current database row, not an entity already cached in the persistence context: refresh it, or run the query through JDBC."
- Lazy expiry must be an immediate SQL `UPDATE`. Hibernate flushes inserts before updates, so an expiry left as a dirty entity would run *after* the new hold's insert. It would then trip `uq_claimed_seat` on a seat that is actually free.

How should the application talk to Postgres?

## Decision Drivers

* **Lock semantics must be exactly what spec §5 says**, on the pinned versions: Hibernate 7.4.5 and pgjdbc 42.7.13. They must not depend on how a Hibernate dialect translates a lock mode. A version-specific assumption about that translation already cost the spec a review round (rev 2.5, corrected in 2.6).
* **One global lock order must be checkable.** Deadlock-freedom is argued from the order, so it must be possible to see every lock in one place.
* **Locking reads must see the committed row**, not the persistence context.
* **The project exists partly to evidence JPA** (spec §1), which is a common requirement in Java roles. A JDBC-only codebase would not show it.
* **Keep the persistence code small.** This is a portfolio service, not a framework.

## Considered Options

* **A. JPA only.** Spring Data repositories, with `@Lock(PESSIMISTIC_WRITE)`, plus `refresh()` or `PESSIMISTIC_*` lock modes for the locking reads.
* **B. JDBC only.** `JdbcClient` everywhere, with no ORM.
* **C. JPA for ordinary reads and writes, plus explicit `JdbcClient` SQL for every locking read, the advisory lock, lazy expiry and `SET LOCAL lock_timeout`.** All of the explicit SQL lives in one class.

## Decision Outcome

**Chosen: C.** It is the only option that meets every driver.

- **Why not A.** It makes lock semantics depend on dialect translation, the driver this project has already been burned by. It also leaves locking reads one forgotten `refresh()` away from returning a cached entity. And JPA has no native advisory-lock vocabulary, so `pg_advisory_xact_lock` would be raw SQL anyway.
- **Why not B.** It removes the flush-order trap entirely, and it is the simplest. But it throws away the JPA evidence the project exists to produce. It also hand-maps every ordinary read and write that JPA does well.
- **What C costs.** Two access styles in one codebase. C makes that cost explicit and contains it:
  - **One gateway.** Every lock goes through `LockingGateway`. `CLAUDE.md` makes lock SQL anywhere else in `src/main` a review BLOCKER, and gives a grep to check it.
  - **Detached records.** The gateway returns records (`EventRow`, `HoldRow`), never entities. A locked read can't be handed back to Hibernate as a managed object by accident.
  - **Mandatory transaction.** The gateway is `@Transactional(propagation = MANDATORY)`. Outside a transaction its locks would release at statement end, so it fails loudly instead of silently doing nothing.

### Consequences

* ✅ Every lock is visible in one file, so "one global lock order" is a property a reviewer can check by reading it.
* ✅ Locking reads always see the committed row, and lazy expiry is a guarded `UPDATE ... AND expires_at <= :now` that reaches the database at once.
* ✅ The JPA entities stay simple: getters, no setters, and `ddl-auto: validate` against the Flyway schema.
* ⚠️ Two styles to keep apart. A Plan 3 service must not load `SeatHold` entities inside a locking transaction. With no `@DynamicUpdate`, a dirty entity's flush rewrites every column, and that can overwrite a status the gateway's `UPDATE` just changed. PR #10's description carries this forward.
* ⚠️ Raw JDBC sees pgjdbc's type support directly. pgjdbc 42.7.13 has no `Instant` conversion, so every JDBC boundary converts through `OffsetDateTime`. Entities keep `Instant`, because Hibernate maps it itself.
* ⚠️ `SET LOCAL lock_timeout` cannot take a bind parameter, so its value is a literal. It is formatted from a `long` and comes from configuration bounded to 1 ms–1 min (plan 2 decision 4, as corrected in PR #12). No request data reaches it.
* ⚠️ Not yet proven: the seat-hold row locks block a concurrent writer. In a throwaway mutation test, PR #10's posting review deleted both seat-hold `FOR UPDATE` clauses, and every test still passed. The clauses are still in the shipped code. Plan 3 owns that test.
