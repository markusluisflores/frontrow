# ADR-001: Enforce the seat-claim invariant in the schema

**Date:** 2026-09-18
**Status:** Accepted

Not yet implemented. No code exists; this records a design decision. The full
design is in the spec, `docs/superpowers/specs/2026-09-17-frontrow-design.md`
§5.

## Context and Problem Statement

FrontRow's central guarantee is that a seat is never double-booked: never held
by two buyers at once, and never sold twice. Holds and confirmations arrive
concurrently from two adapters (REST and MCP). Where does that guarantee live:
in application code that checks before it writes, or in the database, so that a
violating row cannot be written at all?

The decision was first made on 2026-09-17. The predicate was widened in spec
revision 2 on 2026-09-18.

## Decision Drivers

* The guarantee must hold even if application logic is wrong, and under any
  amount of concurrency.
* It must be provable with a test that fails when the guarantee is removed. The
  spec's Definition of Done requires that the hold-race test genuinely fails if
  the index is dropped.
* It must cover the whole seat lifecycle, not just the pending-hold phase.

## Considered Options

* **Optimistic locking** (a version column), with the check-then-write done in
  the application.
* **Pessimistic `SELECT ... FOR UPDATE`** as the primary guarantee, with the
  availability check done in the application.
* **A partial unique index over `ACTIVE` holds only**, plus
  `UNIQUE (event_seat_id)` on `order_line`. This was the revision 1 design.
* **A partial unique index over `ACTIVE` and `CONVERTED` holds**, plus
  `UNIQUE (event_seat_id)` on `order_line`.

## Decision Outcome

**Chosen: a partial unique index over `ACTIVE` and `CONVERTED`, plus
`uq_sold_once` on `order_line`**, because it is the only option where the
database itself refuses a double claim at every stage of a seat's life:

```sql
CREATE UNIQUE INDEX uq_claimed_seat
    ON seat_hold (event_seat_id) WHERE status IN ('ACTIVE', 'CONVERTED');

ALTER TABLE order_line
    ADD CONSTRAINT uq_sold_once UNIQUE (event_seat_id);
```

The rejected options:

* **Optimistic and pessimistic locking** both leave the guarantee in the
  application. A missed lock or a wrong check on one code path is a
  double-booking. Row locks are still used, but to order state transitions and
  keep them deadlock-free (spec §5, *Locking discipline*), not as the source of
  the invariant.
* **The `ACTIVE`-only index** guaranteed "never sold twice" but not "never held
  once sold". A sold seat has no active hold, so a second buyer could hold it
  and would fail only at confirmation. Spec revision 2 caught this. Adding
  `CONVERTED` to the predicate lets one index cover both pending and sold
  seats.

The application's job is to translate a violation of the constraint into the
structured `seat_taken` error, not to prevent it.

The index alone does not decide when a seat becomes free again. An `ACTIVE`
hold past its `expires_at` still matches the predicate, so it keeps blocking
the seat until something marks it `EXPIRED`. Hold creation therefore expires a
lapsed hold on each seat itself, as an immediate SQL `UPDATE` before inserting
its own row for that seat. Hibernate flushes inserts before updates, so an
expiry left as a dirty entity would run after the insert and trip
`uq_claimed_seat` on a seat that is actually free (spec §5, *Locking
discipline* and *Hold expiry*).

### Consequences

* ✅ Double-booking fails at the database, whatever the application does. The
  hold-race test can prove it by failing when `uq_claimed_seat` is removed.
* ✅ The claim is narrow enough to state honestly. Spec §5 tables what the schema
  guarantees and what the application still guarantees. Per-owner caps,
  sales-window checks and lock-then-decide confirmation stay application
  guarantees, each with its own test.
* ⚠️ Postgres is required, because the design depends on partial unique
  indexes (spec §10). Integration and concurrency tests must run against real
  Postgres through Testcontainers, never H2 or a mocked repository.
* ⚠️ The application must map constraint violations reliably. It reads the
  constraint name from the driver's structured error, following the cause chain
  and `BatchUpdateException`, not message text.
* ⚠️ Correctness depends on lazy expiry running as an immediate `UPDATE` inside
  the hold transaction, not on the sweeper. A dedicated concurrency test with
  the sweeper off (spec §5, test 7) must guard this.
* ⚠️ `CONVERTED` is permanent while no order can be cancelled. Adding
  cancellation later means a new terminal `REFUNDED` hold status that the index
  excludes, and `uq_sold_once` becoming a partial index (spec §5). Order
  cancellation is an explicit non-goal until then.
