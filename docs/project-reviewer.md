# FrontRow — Project Reviewer & Interview Guide

> **Living document.** Updated as new concepts are added or lessons are learned.
> Last updated: 2026-10-22

> ⚠️ **Phase 1 plans 1, 2, 3A, 3B and 4A of 7 have landed. Every write path on holds, orders and an existing event's
> status and window exists as a service, and four of them have REST endpoints behind the security chains: hold,
> release, confirm (the only purchase path) and the event `PATCH`. Creating an event, reading anything, and every
> MCP tool do not exist yet, and the app has no datasource configuration, so it runs only in the test suite.**
> Plans 3A (hold creation, PR #18) and 3B (confirm, release, cancellation and the sweeper, PR #24) are Java
> services; plan 4A (PR #29) put them behind HTTP. All of it is exercised by tests against real Postgres: the REST
> golden path through the full filter chain with MockMvc, and the request bounds over a real socket.
>
> **Built**, and proven by `./mvnw verify`:
> - the Maven build and CI (build, CodeQL, dependency review, Dependabot);
> - the V1 schema migration and its constraint tests;
> - the principal-propagation spike (ADR-004);
> - the domain foundations (plan 2, PR #10):
>   - a framework-free `domain` package;
>   - the shared error contract;
>   - JPA entities and repositories over V1;
>   - a `LockingGateway` whose event and advisory locks are proven with concurrent
>     transactions;
> - hold creation with idempotent replay (plan 3A, PR #18), and lock-wait proofs that the
>   seat-row locks block;
> - confirm (the only purchase path), release, the event PATCH and cancellation, the expiry
>   sweeper and the 24-hour idempotency-key purge (plan 3B, PR #24), plus spec 2.6.3's two
>   code changes (hold creation reads the clock after the event lock; a `DRAFT` event answers
>   `not_found`);
> - all seven of spec §5's concurrency tests (1, 3, 4, 5 and 7 in plan 3A; 2 and 6 in plan 3B);
> - the security edge (plan 4A, PR #29): three disjoint security chains, hashed demo credentials, Problem Details
>   errors, a 16 KiB request-body bound, a guard that every service starts its own transaction, and the four REST
>   write endpoints.
>
> **Not built:** event creation, the read services and the six `GET` endpoints, and Swagger UI (plan 4B); the MCP
> tools and the evidence artifacts (plan 5); seed data, a datasource configuration, and a Dockerfile or compose
> file. Nothing is deployed, and no MCP tool exists, so "an agent can hold" is still design. Entries below that describe
> unbuilt work are still *design decisions* supported by
> `docs/superpowers/specs/2026-09-17-frontrow-design.md` — do not claim those
> as shipped work in an interview; the honest framing is "here's a design I
> reasoned through and the tradeoffs I weighed," which is still a real
> answer. Entries get rewritten with implementation evidence as later plans
> land.

---

## What We Built

**What exists, as of 2026-10-19:**

**Plan 1, the foundation:**
- A Maven build on Java 25. The wrapper is pinned to Maven 3.10.0 with its
  distribution checksum. The build is gated by Spotless, SpotBugs and
  `-Xlint:all -Werror`.
- CI running build-and-test, CodeQL, dependency review and Dependabot, with two
  checks required on `main`.
- The `V1__core_schema.sql` migration, which carries the whole domain model and
  the seat-claim invariant.
- 22 constraint test runs (21 methods). 19 insert a violating row and expect a
  rejection, and 3 check that a legal row is still accepted.
- A principal-propagation spike test.

**Plan 2, the domain foundations** (PR #10, merged 2026-10-05):
- A `domain` package that imports only `java.*`: `Money`, the status enums, the
  half-open `SalesWindow`, and `HoldRules` (the live-hold predicate and the
  confirm precedence).
- An `error` package: the 11 spec error codes with recovery hints, and a lookup
  that reads SQLState and constraint name from the Postgres driver's structured
  error.
- JPA entities and repositories over all eight V1 tables. `ddl-auto: validate`
  makes the app refuse to start on a schema mismatch.
- Bounded configuration and an injected `Clock`.
- The `LockingGateway`, which holds every lock SQL statement in the codebase.

**Plan 3A, hold creation** (PR #18, merged 2026-10-16):
- `HoldService#hold`: one all-or-nothing READ COMMITTED transaction that claims
  the idempotency key, takes the per-owner and event locks, then claims each seat
  in ascending order (lazy expiry, then insert). Only successes are recorded; a
  retry with the same key and request replays the stored result.
- The gateway statements it needs: the key claim, the stored-response read and
  write, and the seat insert. `seat_taken` is decided only by `uq_claimed_seat`.
- Spec §5 concurrency tests 1, 3, 4, 5 and 7, against real Postgres, and a test
  that drops `uq_claimed_seat` and shows the same race then has several winners.

**Plan 3B, confirm, release, cancellation and the sweeper** (PR #24, merged 2026-10-19):
- `ConfirmService`, the only code that writes an order: an owner-scoped, unlocked
  read finds the group's event; it then locks the event row `FOR SHARE` and the
  group's seat rows in ascending order, reads `now`, and only then decides on
  current state: an existing order first, then the event's status and sales
  window, then the rows' state.
- `ReleaseService`, which refuses a group already converted to an order and never
  takes the event lock.
- `EventPatchService`, the event PATCH and cancellation. Cancellation is refused once
  anything is sold, and a lock timeout answers `contention_retry` on this path only.
- `ExpirySweepService`: bulk expiry with `SKIP LOCKED` and a 24-hour purge of
  idempotency keys, scheduled by `config/SweepSchedule`. The test properties
  (`frontrow.sweeper-enabled=false`) turn the schedule off in Spring Boot test contexts, so
  nothing runs the sweeper on a timer during tests; test 7 asserts its own context has no
  `SweepSchedule` bean. `ExpirySweepServiceTest` calls the sweep and purge directly against
  Postgres, and `SweepScheduleTest` checks the schedule's wiring with a stub sweeper and no
  database.
- Spec 2.6.3 in hold creation: `now` is read after the event lock, and a `DRAFT`
  event answers `not_found`.
- Spec §5 concurrency tests 2 and 6, a release-versus-confirm race, and a test that
  ends one real hold among 70,001 ids in one statement.

**Plan 4A, the security edge and the REST write endpoints** (PR #29, merged 2026-10-21):
- Three Spring Security chains in `security/SecurityConfig`: `/api/**` takes HTTP Basic only and refuses a bearer
  token in any `Authorization` header; `/mcp` takes an agent bearer token only and grants a single `AGENT`
  authority; a last chain with no matcher opens four anonymous paths and denies everything else.
- Hashed demo credentials: bcrypt passwords and SHA-256 tokens, checked at startup. Nothing usable is committed;
  a script generates the demo's, and the tests generate their own on every run.
- RFC 9457 Problem Details for every error the application answers on `/api/**`, with an exhaustive code-to-status
  mapping and a generic 500 for anything else (not for a direct `POST /error`, Tomcat's HTML 400 for an oversized
  header, or `/mcp` transport errors); a 16 KiB request-body bound ahead of every chain; a cross-site write filter
  for the CSRF risk of cached Basic credentials.
- `TransactionBoundary`: every service entry point fails loudly if it joins a caller's transaction.
- Four REST endpoints: hold, release, confirm and the event `PATCH`. The owner always comes from the principal.

There are **264 test runs** in total. Still missing: event creation (no service),
every read path (none of spec §6's GETs has a service or query behind it), every
MCP tool, and a datasource configuration, so the app runs only in the test suite.

The *designed* system, whose REST adapter is partly built and whose MCP adapter is not: an event ticketing and
seat-reservation service in Spring Boot, exposed through two inbound adapters
over one domain core — a REST API for humans and an MCP server for agents.

**Why this project exists** (worth being able to say out loud): `Java` was on
the resume with no supporting project or work bullet behind it. Infor was LPL,
RBC was SDET work, the capstone was Next.js, Carpool was PHP. This project turns
a claimed skill into a demonstrated one. The secondary goal is showing the AI
*provider* side — designing an agent-facing API — where The Fourth Official
already covers the consumer side.

**Planned stack:**

| Layer | Choice |
|---|---|
| Language | Java 25 (LTS) |
| Framework | Spring Boot 4.1 |
| MCP | Spring AI 2.0 (`@McpTool`), Streamable HTTP transport with bearer-token auth |
| Database | Postgres + Flyway |
| Persistence | Spring Data JPA (Hibernate 7.4.5) for ordinary reads/writes; explicit `JdbcClient` SQL for every lock and for lazy expiry *(built in plan 2)* |
| Build | Maven |
| Testing | JUnit Jupiter 6 (Boot-managed; the spec says "JUnit 5", same API), AssertJ, Testcontainers |
| API docs | springdoc-openapi *(planned; not in `pom.xml` yet)* |
| Container / CI | GitHub Actions (built); Docker *(planned — only Testcontainers uses Docker today)* |
| Deploy | Railway (Phase 2, after real auth) |

*Spring Boot 4.1.1, Spring AI 2.0.1 and Java 25 are **pinned** in `pom.xml`. Maven moved to 3.10.0 and Spotless to 3.10.3 via Dependabot on 2026-10-02.*

---

## Technical Concepts

### 1. Putting an Invariant in the Schema Instead of the Service Layer

**Simple version:** Imagine a cinema where two people can click the same seat at
the same instant. You could have a staff member check a list before writing each
booking down — but if that person blinks, you've sold one seat twice. Instead,
the booking sheet itself is printed with one slot per seat. There is physically
nowhere to write a second name. The rule is enforced by the paper, not by the
person.

**The longer version:** Double-booking is prevented by a Postgres partial unique
index — `CREATE UNIQUE INDEX ON seat_hold (event_seat_id) WHERE status IN ('ACTIVE', 'CONVERTED')`
— plus a unique constraint on `order_line(event_seat_id)`. The predicate covers
`CONVERTED` (sold) as well as `ACTIVE`: with `ACTIVE` alone, a sold seat has no
active hold, so the schema would stop a double *sale* but not a new hold on an
already-sold seat. That gap was caught in the spec's revision-2 review. Application code
is designed to catch the resulting constraint violation and translate it into a structured
`seat_taken` error rather than leaking a 500. Plan 2 built both halves of that: the
lookup that reads the constraint name from the driver (`PostgresErrors`) and the
`SEAT_TAKEN` code. Plan 3A (PR #18) built the translation: the hold service lets the insert fail on the index rather
than checking first, and maps only `uq_claimed_seat` to `seat_taken`. A test drops the index and shows a race for one
seat then has several winners. The alternatives considered were
optimistic locking with a version column and pessimistic `SELECT ... FOR UPDATE`
as the *primary* guarantee. Both work, but both place the guarantee in
application logic, which means a bug in that logic silently becomes a
double-booking. The index means that even if the service layer is wrong, the
database cannot record the bad state. Row locks are still used in the design,
for a different job: ordering concurrent transactions so they can't deadlock,
and so that confirm, release and expiry decide on current state. The index is
the guarantee; the locks keep the protocol well-behaved.

**Interview talking point:** "I put the no-double-booking rule in the schema as a
partial unique index rather than in a service method. Optimistic and pessimistic
locking both would have worked, but they make correctness depend on application
code being right. The index covers both live holds and converted-to-sold ones,
so one constraint guards the whole lifecycle. With it, if my logic has a bug the worst case is a
constraint violation I have to translate into a clean error — not a seat sold
twice. I'd revisit that if the contention pattern meant constraint violations
became the common path rather than the exceptional one, because then I'm paying
for failed transactions instead of avoiding them."

---

### 2. Deliberately Solving Hold Expiry Twice

**Simple version:** When you're holding tickets, you get a few minutes before
they go back on sale. Two things make that happen: a cleaner who sweeps the
building every so often collecting expired holds, and a rule that says the moment
anyone reaches for a seat, any expired hold on *that* seat is torn up on the
spot. If the cleaner is off sick, the system is still correct — it's just
untidy.

**The longer version:** Expiry is handled both lazily and by a scheduler. The
lazy path transitions stale `ACTIVE` holds to `EXPIRED` inside the same
transaction as a new hold attempt on those seats. The scheduled sweeper does the
same in bulk on a timer. The lazy path carries correctness; the sweeper only
keeps the table tidy and keeps availability counts honest for read queries. This
means correctness never depends on the scheduler having run — a scheduler that
is down, paused, or lagging degrades tidiness, not safety.

**Built (plan 3A):** lazy expiry in hold creation, proven by §5 test 7 with no
sweeper. *(Updated 2026-10-19.)* The sweeper is built too (plan 3B, PR #24): a bulk
expiry using `SKIP LOCKED`, so it skips rows another transaction has locked instead
of waiting, plus a 24-hour purge of idempotency keys. Its schedule is off in the Spring
Boot test contexts, so no test races a timed sweep. Test 7's context has no schedule and
never calls the sweeper, so the correctness proof runs without it. `ExpirySweepServiceTest`
calls the sweeper directly.

**Interview talking point:** "Hold expiry runs two ways on purpose. There's a
sweeper, but correctness doesn't depend on it — when someone tries to
take a seat, any expired hold on that specific seat is cleaned up in the same
transaction. That way a scheduler outage makes availability counts stale, which
is a display problem, rather than blocking seats forever, which is a correctness
problem. It also means I can reason about the sweeper as pure hygiene and not
worry about its timing guarantees."

---

### 3. The Threat Model: An Agent Can Hold, Only a Human Can Buy

**Simple version:** The AI assistant is allowed to put tickets on hold for you.
It is not allowed to buy them. A hold undoes itself after a few minutes if
nobody acts on it; a purchase doesn't undo itself at all. So the reversible
action is automated and the irreversible one needs a person.

**The longer version:** The MCP server exposes five thin tools —
`search_events`, `get_event`, `check_availability` (read), `hold_seats` and
`release_hold` (write). There is deliberately no `confirm_order` tool; order
confirmation exists only on the REST adapter, behind a human. The dividing line
is reversibility, not sensitivity: `hold_seats` mutates state and is still safe
to automate because the mutation expires on its own, while a purchase is
terminal. Write-path guardrails include per-request seat caps, an idempotency key
so a retried call doesn't create a second hold, sales-window enforcement, and
a structured log line per tool call with the owner hashed (full tracing is
Phase 2). Identity is enforced by two
disjoint credential chains: the MCP endpoint accepts only bearer tokens issued
to a user's agent, the REST API accepts only HTTP Basic, and the confirm
endpoint lives on REST — so an agent token cannot reach the purchase path at all.

**Built (plan 3A):** the hold-side guardrails, in `HoldService`: `too_many_seats`,
idempotent replay (`idempotency_key_reused` on a mismatch), the sales gate, and the
active-group cap, raced in test 4. *(Updated 2026-10-19.)* Plan 3B (PR #24) built
confirmation as `ConfirmService`, the only code that writes an order, which the
pre-PR security review checked. *(Updated 2026-10-22.)* Plan 4A (PR #29) built the
two credential chains and the confirm endpoint behind Basic, and its tests show an
agent token refused on `/api/**`, the confirm endpoint included, and a token for a
user who is also an organiser carrying no organiser power. The MCP tools and their
logging are not built, so the agent side is still design. A structural check that
no MCP adapter depends on `ConfirmService` is an input to plan 5.

**Interview talking point:** "I drew the line at reversibility rather than at
read-versus-write. The agent can hold seats, which does mutate state — but a hold
expires on its own, so the worst case of a confused agent is some seats being
briefly unavailable. Buying is terminal, so there's no MCP tool for it at all;
confirmation lives on the REST side behind a human. I'd rather the tool surface
be obviously safe than rely on prompt instructions telling a model to be
careful — and the agent's credential is rejected by the REST side outright, so
even a prompt-injected agent can at worst tie up seats until its holds expire."

---

### 4. Money as Integer Cents

**Simple version:** Store `1050` and remember it means 10.50 in whatever currency
the row carries, instead of storing `10.50` as a decimal number the computer has
to approximate.

**The longer version:** Amounts are stored as `long` cents plus an ISO currency
code, wrapped in a `Money` value object. `double` is wrong because binary
floating point can't represent most decimal fractions exactly and errors
accumulate across arithmetic. `BigDecimal` is correct but drags in a rounding-mode
decision at every operation, and at ticket-price scale that complexity buys
nothing. Integer cents sidesteps both.

**Built (plan 2):** `domain/Money.java` is a record of `long cents` and a currency
that must be three uppercase letters, the same rule as V1's `CHECK` constraints.
Arithmetic uses `Math.addExact` and `Math.multiplyExact`, so an overflow throws
instead of wrapping silently. Adding CAD to USD is rejected.

**Interview talking point:** "Money is `long` cents with a currency code in a
value object. Not `double` — binary floating point can't hold most decimal
fractions exactly. I could have used `BigDecimal`, and I would in a system doing
percentage or interest math where rounding mode genuinely matters, but for ticket
prices integer cents gives exactness without a rounding decision on every
operation."

---

### 5. MCP Servers Have a Credibility Bar, and It's a Testing Checklist

**Simple version:** Anyone can demo an AI tool doing the thing it's supposed to
do. What's rare — and what people actually look for — is showing what happens
when it goes wrong, and proving you handled that.

**The longer version:** Published 2026 hiring guidance for MCP work names
specific signals: typed JSON schemas checked into version control, thin
single-responsibility tools rather than "mega-tools" hiding business logic,
structured error contracts with recovery hints (`{"code":"seat_taken","hint":"call check_availability for current seats"}`),
an architecture diagram with a threat model, measured tool success rate and p95
latency, and **at least one logged tool failure showing error-and-recovery**.
Named weak signals: happy-path-only demos, unstructured error strings, and MCP on
a resume with no server repo. FrontRow's `logs/example_run.txt` is *specified* to
capture a real race — the intended scenario is an agent's seat being taken
between `check_availability` and `hold_seats`, receiving `seat_taken` with a
hint, re-checking, and holding different seats. The spec requires that run be
real, not fabricated. **It does not exist yet.**

**Interview talking point:** "The artifact I'm treating as non-negotiable is a
logged failure run, not just a working demo — an agent losing a race for a seat,
getting a structured error with a hint, and recovering. That's what's driving me
to design the error contracts as machine-parseable objects with recovery hints
rather than strings: the error is part of the tool's interface, because the
consumer is a model that has to decide what to do next from it."

---

### 6. Why MCP and REST Are the Same Project

**Simple version:** The business rules live in one place. The REST API and the AI
tool interface are two different doors into the same room — neither of them owns
the furniture.

**The longer version:** A domain core with no Spring Web types and no MCP types
in it, with both adapters as thin translation layers over shared application
services. The checkable consequence: if changing an MCP tool requires changing
anything in the domain core, the boundary has leaked. That's a concrete review
criterion rather than an aspiration.

**Interview talking point:** "MCP is just another inbound adapter — the domain
doesn't know it exists. That's why this is one service rather than a backend plus
a separate MCP project. The test I set for myself is that adding or changing a
tool shouldn't require touching the domain core; if it does, I've let protocol
concerns leak into business logic."

---

### 7. One Global Lock Order Instead of Case-by-Case Locking

**Simple version:** Picture several people who each need keys from the same
rack. If everyone always takes keys left to right, nobody ends up holding a key
someone else is waiting for while waiting for one of theirs. Once the order is
fixed, gridlock can't happen.

**The longer version:** The unique index guarantees correctness, but concurrent
transactions still need locks, for two reasons: so confirm, release and expiry
decide on current state, and so nothing deadlocks. The spec (§5 *Locking
discipline*) fixes one order that every path follows, skipping the steps it
doesn't need:

1. the idempotency key;
2. a per-owner advisory lock;
3. the event row (`FOR SHARE` to hold or confirm, `FOR UPDATE` to change the
   event);
4. seat rows in ascending id.

Two consequences took review rounds to find:
- A cancellation has to lock the event row *before* any seat row. Otherwise a
  cancel racing a confirm either deadlocks or leaves a cancelled event with a
  sold order.
- Hold creation must finish each seat (lock, lazily expire, insert, flush)
  before starting the next. "Lock everything, then insert everything" breaks
  the order across the two phases.

Lazy expiry must also be an immediate SQL `UPDATE`, because Hibernate flushes
inserts before updates.

**What plan 2 built, and what it proved:**
- **One file holds the lock vocabulary.** `persistence/LockingGateway.java`
  contains:
  - the per-owner advisory lock (step 2);
  - `FOR SHARE` / `FOR UPDATE` on the event (step 3);
  - seat_hold row locks, ascending by `event_seat_id` (step 4);
  - lazy expiry as an immediate guarded `UPDATE`;
  - `SET LOCAL lock_timeout`.

  A `CLAUDE.md` rule, with a grep to check it, forbids lock SQL anywhere else in
  `src/main`.
- **Proven with real concurrent transactions:**
  - two share locks on an event don't block each other;
  - an exclusive lock makes a share lock wait;
  - a second advisory lock for the same owner waits, while a different owner's
    doesn't;
  - `lock_timeout` surfaces as SQLState `55P03`.

  Waits are observed in `pg_stat_activity`, not inferred from a timeout.
- **The seat_hold row locks, now proven (plan 3A).** After plan 2, a reviewer
  deleted both `FOR UPDATE` clauses and all 14 gateway tests still passed. Plan
  3A added a wait proof for each clause, scoped to one Postgres backend; with
  either clause deleted, its test fails with `did not block on a lock within 10s`.
- **Built in plan 3A:** hold creation's claim-each-seat-in-order loop, raced in
  §5 tests 1, 3, 4 and 5.
- **Built in plan 3B** *(updated 2026-10-19)*: confirm, release and cancellation
  follow the same order: the event before any seat row, seat rows ascending, and
  release never takes the event. The sweeper takes no part in it: its `FOR UPDATE
  SKIP LOCKED` never waits, skipping any row another transaction holds, so it
  cannot deadlock (`LockingGateway.expireLapsedHolds`). Tests 2 and 6 race confirm against
  lazy expiry and against cancellation, and a third test races release against
  confirm. Reviewers traced every pair of paths for opposite lock orders and found
  none.

**Interview talking point:** "The index guarantees no seat is ever claimed
twice. The lock order keeps the other state transitions correct and
deadlock-free: confirm, release, cancel and expiry all decide on current
state. The spec fixes one lock order that every waiting transaction follows, so
deadlock-freedom is a property I can argue from the order rather than hope for.
The subtle part was that hold creation interleaves locks and inserts. Taking
all the locks first and then inserting sounds safe, but it breaks the ordering
across the two phases, so the design claims seats one at a time. Every lock
lives in one file, so 'one global order' is something you can check by reading
that file. Every write path built so far that waits on a lock follows it; the
sweeper never waits, because `SKIP LOCKED` skips rows it can't take. Racing overlapping multi-seat holds in
different orders produces no deadlock and no partial hold, and confirm is raced
against expiry, cancellation and release. A reviewer once
showed that nothing proved the seat-row locks block; there's now a test for
each, and deleting either lock clause fails it."

---

### 8. Making Silent Failures Loud: a Zero Timeout and a Lock With No Transaction

**Simple version:** Two settings in the box office could fail without anyone
noticing.
- **The waiting rule.** The rule is "wait at most 5 seconds for a lock". If
  someone typed 0, Postgres reads that as "wait forever", not "don't wait".
- **The bouncer.** The lock helper is like a bouncer who only works inside the
  building. Call him from the street and he waves everyone through and says
  nothing.

Both now complain loudly the moment they're misused.

**The longer version:** The Task 4 review first raised the unbounded timeout as a
Minor finding. The final whole-branch review of plan 2 escalated it to Important,
and separately found the missing transaction guard. Both fixes are built and
tested.
- **The timeout.** `SET LOCAL lock_timeout` is written as a literal, because
  Postgres won't take a bind parameter there. The value comes from
  configuration.
  - The plan promised "validated configuration" but only had `@NotNull`. So
    `frontrow.lock-timeout: 0s` became `'0ms'`, which Postgres treats as
    disabled. That silently removes the spec's guarantee that an event change
    can't be starved by a stream of seat holds.
  - The plan's `@Min`/`@Max` couldn't have worked anyway: Hibernate Validator
    9.1.3 has no `@Min` validator for `Duration`, confirmed against the jar.
  - The fix is `@DurationMin`/`@DurationMax`: lock timeout 1 ms–1 min, hold TTL
    1 s–1 h. A test proves `0s` fails startup.
- **The transaction guard.** Outside a transaction, an advisory lock and a
  `FOR UPDATE` both release when the statement ends, and `SET LOCAL` does
  nothing.
  - A future service that forgot `@Transactional` would lose every lock with no
    error, and single-threaded tests can't see that.
  - The gateway is now `@Transactional(propagation = MANDATORY)`, and a test
    proves a bare call throws `IllegalTransactionStateException`.

**Interview talking point:** "Two of the bugs I care most about in this project
never threw an error. A zero lock timeout reads to Postgres as 'no timeout', and
a lock taken outside a transaction is released the instant it's acquired. Both
would have passed every test I had. So the fixes aren't extra checks so much as
turning a silent wrong answer into a loud failure. The config has bounds that
fail at startup, and the lock gateway refuses to run unless a transaction
already exists. The plan's own validation idea was wrong too: plain `@Min`
doesn't apply to a `Duration`, which only came out when the session checked the
validator jar we actually ship."

---

## Engineering Process

### Scoping Under a Real Time Constraint

The research consulted recommended Kafka, a full Prometheus/Grafana observability
stack, and OAuth2 — all genuinely in demand. All three were cut from Phase 1
because the available time was days-to-a-week and including them produces a
half-finished everything, which the same research says is worse than a small
complete thing. They're scheduled as Phases 2–3.

**Interview talking point:** "The checklist I was working
from had more on it than fits in the time available, so I scoped Phase 1 to be
genuinely *done* rather than broad — domain, REST, real integration tests, the
MCP tools and their evidence artifacts — and pushed Kafka and the observability
stack to later phases. What I decided to protect, if time ran short, was the
concurrency tests, the credential separation and the MCP failure log, because
those are the differentiating parts.
The cuttable parts were the organiser endpoints and the breadth of seed data."

**Be ready for the timeline question, because the honest answer is better than a
dodge.** The spec estimated "a full focused week" for Phase 1. *(Updated
2026-10-22.)* Thirty-five days in (2026-09-17 to 2026-10-22), the foundation, the
domain foundations and every write path on holds, orders and an existing event's status and window (plans 3A and
3B) are built as services, and plan 4A put four of them behind REST and the security chains; creating an event is
not built, nor is any read endpoint or MCP tool. A human can hold and buy seats through the REST endpoints in the
test suite (with MockMvc, not over a socket), but the app has no datasource configuration or compose file yet, so it
does not run on its own. The reason
is not drift: Phase 1 was re-planned from 3 documents into 5 when Plan 2 proved
too large to review, into 6 when Plan 3 did too (split into 3A and 3B on
2026-10-14), and into 7 when Plan 4 was split into 4A and 4B (2026-10-20), and
every plan has gone through multi-round review before any code was written —
which caught five defects in plan 2 alone that would each have failed at runtime
or build time. That is a deliberate trade of calendar time against defect cost,
and it is worth saying out loud rather than implying the estimate held.

### Design Decisions Recorded Before Code

The spec (`docs/superpowers/specs/2026-09-17-frontrow-design.md`) carries goals
and non-goals, the domain model, the concurrency design, the MCP threat model,
phasing, and one remaining open question (token issuance). Revision 1 had four;
revision 2 resolved them. A self-review pass of revision 1 caught two real defects: a hidden dependency (per-caller
guardrails silently needed a trusted caller identity that the same spec lists as
unresolved for stdio transport) and an ambiguous requirement ("basic role-based
security", now defined and explicitly labelled demo-grade).

**Interview talking point:** "I review my own specs before anyone else sees them,
and on this one it caught a dependency I'd hidden from myself — I'd written
per-caller rate limits into the tool design while listing 'how do we authenticate
an MCP caller over stdio' as an open question elsewhere in the same document. My
first fix was to drop those caps rather than enforce them against a
caller-supplied ID, because that would be security theatre. The later fix was
better: move the MCP server to HTTP, so there's a real authenticated identity and
the caps can stay."

A second review (revision 2, 2026-09-18) found the index predicate left sold
seats holdable, that the agent-to-human handover was undesigned, and that stdio
transport meant a second process with nowhere to authenticate. The design moved
to Streamable HTTP with bearer tokens, which resolved the identity question
rather than dropping the caps. A cold review of that revision then found that a
double-clicked confirm would have reported `hold_expired` for a successful
purchase, and that concurrent idempotent retries were undefined. Both were fixed
in revision 2.1 with a lock-then-decide confirm and an insert-first idempotency
record. Three more rounds (2.2–2.4) found smaller protocol bugs:
- a cancel racing a confirm, fixed with a global lock order;
- a Hibernate flush-order trap in lazy expiry;
- a release path whose outcome depended on the sweeper.

These were design findings. Plan 2 (PR #10) built the lock-order primitives and
lazy expiry as an immediate guarded `UPDATE`, both with tests. *(Updated
2026-10-19.)* Plan 3B (PR #24) built the cancel, confirm and release paths that use
them, with §5 tests 2 and 6 racing confirm against expiry and against cancellation.
*(Updated 2026-10-22.)* Plan 4A (PR #29) made all three reachable over REST: confirm,
release, and cancellation through the event `PATCH`.

### Seven Review Rounds, and a Claim We Got Wrong

Revision 2 went through seven review rounds before merging. After every fix
pass, the reviewer that raised the findings resumed to verify them, and a fresh
reviewer read cold. Blockers per round were 2, 1, 2, 1, 0, 1 and 0.
Under the review standard as written then, that stopped one clean round early:
round 7 was the only BLOCKER-free fresh round after round 6's BLOCKER, and the
standard's one exception, for an escalated round on a stronger model, did not
apply, because every reviewer ran on the session's own model. That is the same
shortfall as the PR #16 miscount in the 2026-10-15 section below: one clean
round, not two.

The round-6 blocker was self-inflicted: in round 5 the authoring session wrote a
reviewer's suggestion into the spec unchecked, that Hibernate's PostgreSQL dialect ignores a positive lock-timeout
hint. That's true of Hibernate 6. Spring Boot 4.1.1 ships Hibernate 7.4.5, and
its source (checked afterwards) applies the timeout with `SET LOCAL` around the
one locking query. The design choice survived, for a better reason: one explicit
`SET LOCAL` covers every lock in the transaction. The claim did not.

**Interview talking point:** "The review loop found real protocol bugs, each
one smaller than the last. The one I'm most careful to mention is a false claim
our own process introduced: a reviewer's statement about Hibernate was written
into the spec unchecked, and it was only true for the previous major version. The lesson I took:
check version-specific library behaviour against the source of the exact
version we'll pin, before it goes into a spec."

---

## Bugs Worth Remembering

*No runtime defect is known to be reachable on `main`, beyond the gaps plan 4A states and defers.* That covers plans
1, 2, 3A, 3B and 4A: the scaffold, CI, the V1 schema, the spike, the domain foundations, the locking
gateway, hold creation, confirm, release, cancellation, the sweeper, the security chains and the four REST write
endpoints. *(Added 2026-10-22.)* The deferred gaps: with a valid agent token, malformed JSON or an oversized chunked
body sent to `/mcp` gets an error body that includes a `stack_trace`, which plan 5 must remove; a direct anonymous `POST /error`
gets Spring Boot's own 500 body, not Problem Details, which leaks nothing; and a browser that sends no Fetch
Metadata relies on one CSRF layer alone (plan 4A decision 6, a stated trade). (Seven review findings on hold creation were parked
with rulings rather than fixed, one of them a request that waits across
`sales_close_at` and can still get a hold. The clock is read once, at arrival, which was plan decision 5's design,
when the spec did not yet fix the sampling point. The same cause has two more effects: a hold that lapsed during the
wait can give a false `seat_taken`, and `expires_at` is short by the length of the wait. Spec revision 2.6.3 then
decided the sampling point: after the locks that precede each transaction's first time-dependent decision (for hold
creation, the event-row lock). The same revision made a `DRAFT` event answer `not_found`, not `sales_not_open`
with the planned opening time. *(Updated 2026-10-19.)* Plan 3B (PR #24) closed both gaps: hold creation now reads the
clock after the event lock, confirm and release read it after their row locks, the sweeper and purge read it at
their start, and cancellation and the event PATCH read no clock at all (plan 3B decision 3). `ClockReadPointTest`
covers hold creation, release and confirm only. Hold creation's seat-row waits still come after its read, so a
smaller residual of the same three effects stays by design. Spec revision 2.6.4 removed the header block that
listed both gaps. Confirm re-checks the sales window, so a hold granted after `sales_close_at` still cannot be
bought. See *Building the First Write Path*.)
Two things did reach `main`, and they should be named
rather than hidden:
- **A test gap, now closed:** after plan 2, nothing proved the seat_hold row locks
  block. Plan 3A added the tests (concept 7).
- **One wrong commit message:** `a648936` claims a javadoc reflow it never did.
  PR #10 discloses it, and plan 3A did the reflow.

Several real defects were caught **before** merge, and those are the ones worth
discussing, because catching them is the claim:

- **Plan 3B, a scaling defect and three evidence gaps before merge:** a cancellation that could never succeed
  with 65,535 or more active holds, a concurrency test whose losing branch almost never ran, a false test comment, and a
  spec guarantee no test backed. The stories are in *Executing Plan 3B* below.
- **Plan 3A, two defects and a test gap before merge:** the sales gate's switch failing open, two idempotency keys
  aliasing through a surrogate character, and a test gap where no test checked each seat's own price. The stories are in *Building
  the First Write Path* below.
- **Plan 1 review, 3 blockers:** `mvnw` would have been committed without its
  executable bit (`core.filemode=false` locally), failing CI on a file that
  looked correct; a deprecated MCP constructor would have failed the build under
  `-Werror`; and a claim that `protocol: STREAMABLE` was redundant was wrong —
  without that line Spring AI starts an SSE server instead, against ADR-003.
- **Plan 2 review, 5 blockers**, none of which had code yet. See *Review as the
  Deliverable* below.
- **Plan 2 execution, a test that tested nothing.** `PersistenceMappingTest`
  claimed to round-trip every entity through Postgres.
  - **Symptom:** none. It passed.
  - **Root cause:** the class is `@Transactional`, so `findById` straight after
    `save` returned Hibernate's cached instance and issued no SELECT. The
    timestamp-drift test got back the very `Instant` it had passed in.
  - **Fix:** `flush()` then `clear()` the persistence context before every
    read-back.
  - **Proof:** with SQL logging on, SELECTs now appear after the clear. A
    placeholder assertion failed with `{"ok": true}`, the value as Postgres
    normalised it, which shows the read really came from the database.
- **Plan 2 execution, two silent failures:** the zero lock timeout and the
  gateway running outside a transaction. See concept 8.
- **A Dependabot PR stored `mvnw.cmd` with CRLF**, defeating the repository's own
  `.gitattributes` normalization and turning a 2-line version bump into a
  190-line diff. Fixed with `git add --renormalize` before merge.

The one early red check was CI configuration, not code: the first `Dependency
review` run (`35783056327`) failed because the repository's Dependency graph
setting wasn't enabled yet; enabling it fixed the run, with no workflow or code
change. GitHub overwrites a run's conclusion on re-run, so the history now shows
it green — the evidence is that run id and the 2026-10-02 journal entry. This section fills in from `docs/retros/` and debugging sessions as
more of Phase 1 is implemented.

---

## Open Questions I Should Be Able to Discuss

Revision 1 of the spec had four open questions; revision 2 resolved them
(specific seat ids; bearer tokens over Streamable HTTP; a configurable TTL; an
idempotent in-process sweeper). What an interviewer could still press on:

1. **Why not stdio?** It's the default transport for local MCP servers. The
   answer: no HTTP layer to authenticate against, and the client spawns a second
   copy of the service. Be ready to say what would change if a stdio client were
   a hard requirement.
2. **Token issuance** is a setup script in Phase 1 — demo-grade by design, with
   OAuth2 deferred to Phase 2.
3. **No order cancellation.** The sold-once constraint is permanent in Phase 1; the
   spec states how it would be relaxed (§5), and that is the likely follow-up.

---

## Review as the Deliverable (2026-10-02)

*Written when plans 2-5 were still documents. As of 2026-10-05, plan 2 is built
(PR #10), and the plan-2 items below carry "now built" notes. As of 2026-10-22,
plans 2, 3A, 3B and 4A are built (PR #10, PR #18, PR #24, PR #29); 4B and 5 are
unwritten.* The review of those documents is where most defects
were caught before code existed. Unless an item says "now built", treat it as a
**review or design outcome, not shipped code**, and say so if asked.

**The mutation test, which *is* shipped.** When plan 2 shipped, deleting
`uq_claimed_seat` from the migration failed exactly three tests, and a reviewer
independently checked every other test for hidden dependence on the index and
found none. Since plan 3A, spec §5 test 1 and its drop-index companion depend on
the index too, so the current count is higher; it has not been re-measured.

*Follow-up to expect:* "Three tests prove the index exists, not that it holds
under concurrency." It was correct when written. Spec §5's seven concurrency
tests (racing holds, confirms and cancels) are split between plan 3A (tests 1,
3, 4, 5 and 7) and 3B (tests 2 and 6). *(Updated 2026-10-16.)* Plan 3A's five
are built (PR #18): test 1 races eight buyers for one seat, twenty times, and
expects exactly one winner and seven `seat_taken` errors. A companion test drops
the index and shows the same race then has several winners. *(Updated
2026-10-19.)* Tests 2 and 6 are built too (PR #24): test 2 races a confirm against
lazy expiry by a rival hold, and test 6 races a confirm against a cancellation. How
evenly those races split turned out to matter; see *Executing Plan 3B*.

**Five defects caught in plan 2 before a line of its code existed.** Each would
have failed at runtime or build time:

- `rs.getObject(column, Instant.class)` — pgjdbc 42.7.13 has no `Instant` branch,
  so every locking read would have thrown.
- Binding a bare `Instant` fails the same way; the test seed would not insert.
- `currency char(3)` mapped as a plain `String` fails Hibernate's
  `ddl-auto: validate` at startup (`bpchar` versus `varchar`).
- A JPA `@IdClass` without `serialVersionUID` fails the build: `-Xlint:all`
  includes `serial`, and `-Werror` makes it fatal.
- The lock-wait tests could have passed **vacuously** — on a 2-core machine the
  common ForkJoinPool never schedules the waiting task, so the test times out for
  a scheduling reason and concludes that the lock waits.

*Follow-up:* "Why didn't you catch those yourself?" They are version-specific
library behaviours, and the reviewers verified rather than reasoned — one ran a
real `postgres:18-alpine` container, another compiled the class under the
project's own compiler flags.

*Follow-up:* "How is the vacuous pass fixed?" Waiters run on a dedicated
virtual-thread executor rather than the common pool, and the test asserts the
wait by reading `pg_stat_activity.wait_event_type = 'Lock'` — observing the wait
instead of inferring it from a timeout.

**Now built (PR #10):** all five fixes are in the code. That covers the
`OffsetDateTime` conversion at every JDBC boundary,
`@JdbcTypeCode(SqlTypes.CHAR)` on both currency fields, `serialVersionUID` on
`HoldRequestId`, and the virtual-thread executor with the `pg_stat_activity`
wait check. The build and the lock tests pass on CI.

**A security finding, now in ADR-002 (now built, PR #29).** Declaring any
`SecurityFilterChain` bean removes Spring Boot's default chain, and
`FilterChainProxy` passes a request matching **no** chain straight through with no
security applied — it does not deny it. So two `securityMatcher`-scoped chains
would leave every other path unauthenticated, silently: no test fails and nothing
logs above TRACE.

*Follow-up:* "Why not default to permit and secure each chain?" Because that is
fail-open and the failure is invisible. The required fix is a catch-all chain
evaluated last with `anyRequest().denyAll()`, plus a test that hits an unmatched
path and expects 401 or 403.

*Follow-up:* "Is it built?" *(Updated 2026-10-22.)* Yes, by plan 4A (PR #29). The
catch-all chain has no matcher, sits at `LOWEST_PRECEDENCE` and denies everything but
four anonymous paths. `SecurityChainsTest#theCatchAllOpensOnlyItsAnonymousPaths`
covers it, and `anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser` covers the same
fail-closed rule inside `/api/**`.

**The persistence split (built in PR #10; recorded as ADR-005).** JPA for ordinary reads and
writes, explicit `JdbcClient` SQL for locking reads and lazy expiry. Hibernate
flushes inserts before updates, so an expiry left as a dirty entity would run
*after* the new hold's insert and trip the unique index on a seat that is
actually free.

*Follow-up:* "Why not JDBC throughout?" It would remove the trap entirely, but
the project exists partly to evidence JPA, which is a common requirement.

*Follow-up:* "Why not refresh the entity instead?" The spec allows it; the plan
chose JDBC so lock semantics do not depend on how a Hibernate dialect translates
a lock mode — a version-specific assumption that had already cost this project a
review round once.

**The spike (shipped as a test; ADR-004).** Both candidate mechanisms carry the
authenticated principal into an `@McpTool` method. `McpTransportContext` was
chosen anyway, because `SecurityContextHolder` works there only thanks to Spring
AI setting `immediateExecution(true)` for servlet SYNC servers — a threading
detail an upgrade could change silently.

*Follow-up:* "If both work, why does the choice matter?" Be precise: mechanism B
removes the dependency on *which thread* runs the tool, not the dependency on
Spring Security — the principal still resolves through the filter chain at
extraction time. The losing test stays in the suite as a tripwire.

---

## Executing a Plan With Subagents, and a Process Failure Worth Telling (2026-10-05)

**What the process was.** Plan 2 was executed task by task.
- **Per task:** a fresh implementer subagent built each task, and a fresh
  reviewer checked it for spec compliance and quality. Any fix got its own
  scoped re-review.
- **Whole branch:** after all four tasks, the branch got a final review on the
  strongest model, one fix wave, and then a resumed reviewer plus a fresh one.
  A pre-PR `reviewer` agent followed, then `/security-review`.
- **Results:** the per-task reviews found 2 Important issues. The final review
  found 2 more, both silent failures (concept 8). Every round after the fix wave
  had 0 blockers.

**What went wrong.** PR #10 opened with no review on it at all. The controlling
session wrote a "do not post to GitHub" line into 10 review instructions: 9
reviewers, plus one of them resumed for a second round. The eleventh review, the
`/security-review` pass, carried no posting instruction either way, and also
posted nothing. That broke a standing rule: posting the review is
unconditional, because it's the evidence a review happened. It was the fourth
recorded failure of that rule. Three of the four were caught by the user asking
"where are the reviews?", not by any check.

The causes were specific:
- **Process order.** Every review in this process runs before the PR exists, so
  there was nowhere to post.
- **A conflated rule.** "Ask before publishing" was mixed up with "a review must
  post".
- **A stale line.** One line in a memory file survived an earlier fix. That fix
  corrected one note and never searched the others.

**The recovery:**
- All 11 review reports were still in the subagent transcripts. They were posted
  verbatim, in 8 comments, with dispositions.
- A fresh reviewer then posted a proper inline review itself.
- That review found what all 11 earlier ones missed: it mutation-tested the
  seat-row locks and showed no test proves they block (closed in plan 3A; see concept 7).

**Interview talking point:** "The most useful failure in this project wasn't in
the code. My review process said every review must be posted to the PR, and for
a whole plan it wasn't. The orchestrating session told each reviewer not to
post, because the PR didn't exist yet. What I took from it is that a rule which
has failed four times as prose isn't going to start working with stronger prose.
So the fix was structural: a hook that asks before merging a PR with no human
review on it, plus a defined place for the reviews that run before a PR exists.
And the review that finally got posted found a real test gap, which is a decent
argument for the rule in the first place."

*Be accurate if pressed:* the first fixes proposed, an early draft PR and a post-to-PR
patch to the execution skill, were **not adopted**. They were drafted, then
reviewed, and that review found two blockers:
- the branch was pushed only once, so later reviews couldn't post inline;
- the early PR clashed with the global rule that security and pre-PR checks run
  before a PR opens.

They were withdrawn and parked for a workflow audit. *(Updated 2026-10-15.)* That audit
rejected the early draft PR outright. Reviews that run before the PR exists are now held,
or summarized in one development-review comment, and posted once the PR opens. The
merge-time hook **is** live: the workflow audit registered it globally on 2026-10-11, and it
was live-fired. The hook and its registration live in private repos; this repo's record of
the change is the 2026-10-15 journal entry. It
asks before `gh pr merge` or `gh pr ready` on a PR with no human review record. Its limit,
if pressed: any one human record satisfies it, even an unrelated comment, and it doesn't
see merges made outside Claude Code.

*A second lesson from the same day, worth having ready:* the setup repo's
working copy *is* the running configuration. Drafting the patches on a feature
branch there made them live before anyone had reviewed them, because "unmerged"
doesn't mean "inactive" for a config repo. Process changes now happen only during
deliberate workflow audits, and a project session just writes them down.

---

## Checking a Plan Before Anyone Builds It, and a Miscounted Review Round (2026-10-15)

**What happened.** Plan 3 (the first write path) was estimated at ~3,400 lines, 1.7× the plans already reviewed.
Since the plan is what gets approved, I split it in two: 3A, hold creation, and 3B, everything that acts on an
existing hold. 3A was written, I read it, and it was reviewed on PR #16 over three fresh rounds before merging.
*(Updated 2026-10-16: 3A has since been executed, as PR #18; see the next section.)* This section is about how the
plan was checked before any of its code existed. The orchestrating session and its
subagents did the checking below.

**Executing a plan's code before approving it.**
- The plan's code blocks were applied by script to a scratch copy of `main` and built. That first check found three
  defects in the draft:
  - a JSON assertion comparing an `IntNode` with a `LongNode` (Jackson 3.1.5);
  - SpotBugs findings that now have a narrow, documented exclusion;
  - mutation checks that "failed" on a formatter error before any test ran, which would have been a false pass.
- It still missed round 1's BLOCKER. A test written in step 1 referred to a class that only exists in step 7, and
  Maven compiles every test file, so the plan broke mid-task. Building only the **end state** can't see that.
- The replacement replays the plan **step by step** on a fresh copy and asserts what each step says will happen:
  compile failures, test counts, mutation messages. It held 24 of 24, and caught a regression in the round-1 fixes.

**Interview talking point:** "For a plan that subagents will execute task by task, the risk isn't the finished
code; it's the steps in between. So in my workflow, before I approve a plan, it gets replayed in order against a
clean checkout, and every step's stated outcome is asserted, including the steps that are meant to fail. The AI
session builds and runs that replay; I read what it finds. The first version of the check only built the final
state, and a reviewer found a step that couldn't compile. That's why every step is checked now."

**A miscount I caught.**
- The review rule is: stop after **two consecutive** BLOCKER-free rounds, counting **fresh** reviewers only. A
  reviewer resumed to verify its own findings doesn't count.
- After fresh round 1 found a BLOCKER and fresh round 2 found none, the orchestrating session told me the PR was
  ready to merge: "two review rounds, no BLOCKER left open". The error was treating round 1 as clean because its
  BLOCKER had been fixed. A fixed BLOCKER doesn't make a clean round, so there was one clean round, not two.
- I asked whether the rule wasn't two clean rounds. It is. A third fresh round ran, found no BLOCKER (three
  SUGGESTIONs: two fixed and verified, one partly fixed, its rest to be fixed when Task 4 is executed), and the PR
  merged after that.
- **Why the gate didn't catch it:** the checklist the session fills in before saying "ready to merge" asks for the
  round count, each round's blocker status, and whether a resumed+fresh pair followed the last fix. All three could
  be stated truthfully under the miscount. The gate checks proxies for the rule, not the rule itself.

**Interview talking point:** "The orchestrating session told me a PR was ready to merge when, by our own rule, it
wasn't. The rule needs two consecutive clean rounds from fresh reviewers. It reported 'two rounds, no BLOCKER left
open', but the first round had a BLOCKER, and fixing it doesn't make it a clean round. I caught it by asking. What
interests me is why its checklist didn't stop it: it asks for three facts that could all be stated truthfully, and
none of them is the actual condition. It's a pattern I've seen before in this project, a prose gate that can be
satisfied without its substance. I had the session log it for my next process audit; one candidate fix is making
the gate state the condition itself."

*Be accurate if pressed:* that fix is **proposed, not adopted** (frontrow queue item 8). The same review cycle also
exposed two concurrent reviewers sharing one scratch folder, where one deleted the other's working copy (queue item
7). Both wait for the next workflow audit.

---

## Building the First Write Path: What Review Found in Code That Had Already Been Reviewed (2026-10-16)

**What happened.** Plan 3A was executed the same way as plan 2: subagents implemented it task by task, with a fresh
reviewer per task, then a final whole-branch review, `/security-review` and a pre-PR `reviewer`. All of that happened
before the PR existed. PR #18 then had two fresh review rounds, and both were BLOCKER-free. It merged on 2026-10-16
with 142 test runs. The orchestrating session and its subagents did the building and the reviewing; I read the
results and made the merge call.

**Three findings worth telling,** because each was in code that had already passed review once. In each, a reviewer
reading the code found the problem, and an experiment proved it:
- **A guard that failed open, written into the plan itself.** The sales-window gate used Java `switch` *statements*
  over enums. A switch statement over an enum doesn't have to cover every constant, so adding a new event status
  would have let holds through without any compiler warning, even under `-Werror`. The plan's three review rounds on
  PR #16 missed it. The task reviewer flagged it as a fail-open minor for final-review triage. The final whole-branch
  review raised it to a SUGGESTION and proved it by compiling a probe with the project's own JDK. The fix uses switch
  *expressions*, where a missing case is a compile error.
- **Two idempotency keys that the driver turned into one.** The Postgres driver encodes strings with the JDK's UTF-8
  encoder, which silently turns an unpaired UTF-16 surrogate into `?`. So `"k\uD800"` and `"k?"` from the same
  owner were stored as the same key, and the second request got the first one's replay. Review round 1 reproduced
  it against real Postgres. Such keys are now rejected as `invalid_request`.
- **A test gap, not a defect.** `HoldService` already attached the right price to each seat. But every fixture seat
  had the same price, so no test checked that each held seat carries its *own* price. Round 2 proved it with a mutation: charging every seat the first seat's
  price passed all 24 service tests. A test with two different prices now fails on that mutation. The fix changed only tests, comments and the test count in `CLAUDE.md`.

**Interview talking point:** "The findings I find most instructive in this project are the ones that got past a review.
One was in the plan itself: a guard written as a Java switch statement, which compiles fine when a new enum value
is added and then silently does nothing for it. The reviewer didn't argue it; they compiled a probe that showed it.
Another was an encoding edge case: the JDBC driver quietly rewrites a malformed string, so two different idempotency
keys collapsed into one. And one was a test-data blind spot, not a defect, that a single mutation exposed. What they have in common
is that each one had already passed one careful read. The next reviewer suspected it, then turned the suspicion into
a compiler probe, a reproduction or a mutation, so it was proven rather than argued."

*Be accurate if pressed:*
- The review-round count is two fresh rounds, both BLOCKER-free. Each round's fixes were verified by that round's
  reviewer, resumed. The last fix pass, which added one test and changed two comments, got that resumed
  verification rather than a third fresh round, because the stopping rule doesn't send a SUGGESTION to another
  fresh round. That was stated before the merge. My own pre-merge checklist also asks for a fresh review after the
  last fix; that could not be met here, the rule and the checklist disagreed, and I had the session log it on the
  process queue (item 8) for the next process audit.
- Seven review findings were deliberately not fixed. They were carried to plan 3B and plan 4, with a reply on each
  PR thread. Examples: when the clock is read relative to lock waits (since decided by spec revision 2.6.3, and
  changed in the code by plan 3B, PR #24), and what REST should call the idempotency-key parameter.

---

## Executing Plan 3B: What Review Found in Code That Matched Its Reference Build (2026-10-19)

**What happened.** Plan 3B (confirm, release, cancellation and the sweeper) was executed like 3A: subagents
implemented its seven tasks one by one, each with a fresh reviewer, then a final whole-branch review, a pre-PR
`reviewer`, a `security-reviewer` agent and `/security-review`, all before the PR existed. None of those found a
BLOCKER. PR #24 then had three fresh review rounds, all BLOCKER-free: one SUGGESTION and one NIT in round 1, one
SUGGESTION in round 2, one NIT in round 3. Each round's fixes were verified by its reviewer, resumed, and a fresh round followed every fix pass.
It merged on 2026-10-19 as `61f0a65`, with 197 test runs. I chose the Mandatory tier and the subagent-driven approach,
and I made the merge call; the orchestrating session and its subagents did the building, measuring and reviewing.

**A plan whose code had already been built.** Plan 3B's code came from a reference build that had been compiled and
tested before the plan was approved. The orchestrating session compared each task's final source, ADR and
`CLAUDE.md` tree with that reference (Task 2's after its review fix `deadad2`, which restored seven double-encoded
comment lines), and all seven matched. That is the controller's own check: PR #24's fresh reviewers marked it not
verified. So nothing below is code drifting from the plan, as far as that check shows. Each finding is something
the plan's own build could not see:
- **A cancellation that could never succeed at scale** (round 2). `endHolds` used `IN (:ids)`, one bind parameter
  per hold id, and the Postgres driver caps a statement at 65,535 parameters. Cancelling an event with that many
  `ACTIVE` holds would fail as a 500 every time. The reviewer reproduced it. The fix is `id = ANY(:ids)`, one array
  parameter, and a new test ends one real hold among 70,001 ids; before the fix it failed with
  `PreparedStatement can have at most 65,535 parameters`.
- **A concurrency test whose losing branch almost never ran** (round 1). Test 2 accepts either winner of a confirm
  racing lazy expiry. Started together, the confirm won 79 of 80 repetitions over the reviewer's four runs, so the
  `hold_expired` branch was barely tested. The confirm now starts 0-10 ms late, varied by repetition. In the session's
  own three runs it won 25 of 60, with both branches in every run (PR #24 deviation 1), and round 1's resumed reviewer
  measured 20 of 60. Every assertion is kept, and the index-predicate mutation still
  fails the test.
- **A test comment that was false** (final whole-branch review, NIT). A comment in `EventPatchServiceTest`, and plan
  decision 6, said two instants 500 ns apart are stored as equal and break `ck_event_sales_window`. The driver rounds
  to microseconds half-up, so they are stored 1 µs apart, a valid window. The test now uses 400 ns, where the claim
  holds: a mutation removing the service's truncation fails with that constraint violation.
- **A guarantee no test backed** (final whole-branch review, SUGGESTION). Spec §5's guarantee table says release is
  tested under concurrency, and nothing raced release. A release-versus-confirm race was added. It needed a start
  lag too (0-5 ms): started together, release won 60 of 60.

**Interview talking point:** "The code for this part of the project was built and run before anyone executed the
plan, and once one encoding slip in a comment was fixed, the orchestrating session's own comparison found what the
subagents produced matched that reference. Review still found four things, and none
of them was the code disagreeing with the plan. One was a scaling limit: cancellation sent one SQL parameter per hold,
so with 65,535 or more active holds the driver would refuse the statement and the cancel could never succeed. The other three
were about evidence. A test that accepts either outcome of a race only tests both if both actually happen, and a
reviewer counted: one side won 79 times out of 80. A test comment claimed a rounding behaviour the driver doesn't
have. And the spec promised a release race no test ran. The AI session and its reviewer subagents did the building,
measuring and reviewing; I chose the tier and the approach and made the merge call. What I take
from it is that 'it matches the plan' and 'the tests prove what they claim' are different questions, and only
measuring answers the second."

*Be accurate if pressed:*
- Test 6 is still skewed: cancellation won 77 of 80 repetitions in round 1's four runs, with no confirm win at all in
  one run, and 59 of 60 in the session's three runs. Its re-check is still guarded: round 1's reviewer moved
  cancellation's "anything sold?" check before the event lock as a mutation, and test 6 failed in 3 of 3 runs.
- At 3B's merge nothing here was reachable from outside the test suite, because no API existed. Deferred to plan 4,
  and built by plan 4A (PR #29): the error handler never echoes `IllegalStateException` messages, which carry
  internal ids, and answers driver errors such as the old parameter-cap failure with a generic 500, never a conflict
  code. Deferred to plan 5: a structural check that no MCP adapter depends on `ConfirmService` or
  `EventPatchService`.
- A task first committed seven comment lines with `—` and `§` double-encoded by a cp1252 read; its task review
  caught it, and it was restored before merge. No multi-model review ran: this repo has no cross-model reviewer
  workflow.
- The spec header's "Where `main` differs" block went stale, and so did three smaller spec details the final review
  noted. Spec revision 2.6.4, on its own docs branch, synced all four.

---

## Revision Notes

| Date | Change | Accuracy-drift check |
|---|---|---|
| 2026-09-17 | Created at design stage from the spec and journal. No code, ADRs, retros, or PRs existed to read. | **DRIFT FOUND** — 2 real defects, 3 minor. Fixed inline; see below. |
| 2026-09-18 | Synced to spec revisions 2 through 2.4: claim-index predicate, transport and identity model, trim order, open questions, and the rev-1 self-review talking point (its "caps get dropped" claim no longer matches the spec). Still design-stage — no shipped claims added. | Cold reviewer flagged the stale talking point and version note; both fixed |
| 2026-09-22 | Added the Phase 1 banner when plan 1 merged — the first update where shipped work existed. | No separate accuracy check was recorded at the time; this row corrects that omission |
| 2026-10-02 (wrap-up) | Plan 1 executed and merged, plan 2 written and merged, Dependabot's first two bumps merged. Added *Review as the Deliverable*, corrected the stack table, and took the scoping talking point out of the past tense. | **DRIFT FOUND** — 12 items, the sharpest being the inverse of this guide's usual risk: "What We Built — *(Nothing yet.)*" **denied** work that exists. Also: the banner read as "Phase 1 landed" when plan 1 of 5 landed; the not-built list omitted entities, repositories, security chains, seed data and Docker; "JUnit 5" contradicted `CLAUDE.md`; springdoc and Docker were listed as if present; "pin versions at scaffold time" was stale; and the scoping point described a finished week in the past tense on day 15 with no domain code |
| 2026-10-05 (second wrap-up) | ADR-005 and spec rev 2.6.2 merged (PR #13). The persistence-split entry now cites ADR-005 instead of "still owed". The posting-failure section now says the structural fixes were drafted, reviewed (2 BLOCKERs), withdrawn and parked, not merely "candidates", and adds the config-repo-checkout-is-live lesson. | Clean on accuracy: a fresh check posted to PR #14 (review 5422689734) found 0 drift and 1 nit, a journal citation pointing at the wrong memory file, which is now fixed. The staleness pass found nothing still calling ADR-005 owed or the posting fixes live |
| 2026-10-05 (wrap-up) | Plan 2 executed and merged (PR #10). Rewrote the banner and *What We Built* to cover the domain foundations, added a persistence row to the stack table, added "built" evidence to concepts 4 and 7 (with the unproven seat-row locks stated), added concept 8 (silent failures made loud), two bug stories, a *now built* note on the plan-2 review findings, and the subagent-execution / posting-failure section. | **DRIFT FOUND** by a fresh check posted to PR #11 (review 5420599658): 3 drift, 5 nits, all fixed. The sharpest drift was the author's own count: "12 review dispatches" told not to post was really 10 instructions to 9 agents, with the 11th review carrying no posting line at all. The other two drifts were stale: "21 constraint tests, each inserting a violating row" (really 22 runs, 3 of them acceptance checks), and "none of it implemented" in the review-history section after plan 2 built part of it |
| 2026-10-15 (wrap-up) | Plan 3A written, reviewed over 3 fresh rounds and merged as a plan (PR #16); no code. Banner: 6 plans, 3A not executed. Added *Checking a Plan Before Anyone Builds It*. Corrected the posting-failure section: the early draft PR was rejected, and the merge-time hook is live (since 2026-10-11). Also rewrote the timeline answer, and retitled the spec-review section to *Seven Review Rounds, and a Claim I Got Wrong* (retitled 2026-10-16), with its admission that PR #1 stopped one clean round early *(these two changes were added to this row on 2026-10-16, closing PR #17's final NIT)*. | **DRIFT FOUND**: 3 drift and 4 nits, all fixed. The sharpest: the miscount section was written with the AI controller as "I", which in Markus's mouth claims a mistake he didn't make and calls him "the user" who caught it. Also: a "not adopted" line contradicting the live hook below it, and a talking point stating a cause the sources don't ("counted a verification pass"). Nits: "plan 3" references not split into 3A/3B, a single "concrete fix" where the queue lists three candidates, "all three were true", and the live hook's limit left unstated. PR #17's round-1 review (5539628551) found the "I"-as-controller drift surviving in the section heading and its bold lead, and the talking points claiming the session's replay and logging as Markus's own work; both fixed, along with "three SUGGESTIONs, fixed and verified" (one was only partly fixed) |
| 2026-10-16 (wrap-up) | Plan 3A executed and merged (PR #18). Banner, *What We Built* (142 runs), concept 1 (the translation is built), concept 7 (seat-row locks proven; talking point no longer says the test is owed), the timeline answer, *Bugs Worth Remembering*, and the concurrency-test follow-up in *Review as the Deliverable* all moved from "not built" to built. Added *Building the First Write Path*. Retitled the spec-review section to *Seven Review Rounds, and a Claim We Got Wrong* and rewrote its narrative so the authoring session, not Markus, owns the mistake. | **DRIFT FOUND** by a fresh check: 3 drift and 7 nits, all fixed. All three drifts were in entries this run hadn't changed: the *Review as the Deliverable* header still called plans 3-5 documents, and two talking points (*Seven Review Rounds* and concept 8) still had the AI session's "I" in Markus's mouth, the same drift fixed on 2026-10-15 in another section. Nits: seat-row proofs credited to plan 2, the task reviewer's fail-open flag understated, concepts 2 and 3 without built notes, a closed test gap read as open, the lock order claimed as Markus's own writing, and "cancellation" ambiguous |
| 2026-10-16 (spec 2.6.3) | Spec revision 2.6.3 and plan 3A's errata. *Bugs Worth Remembering* now says the spec decided the clock's sampling point (after the locks before each transaction's first time-dependent decision; for hold creation, the event-row lock) and made a `DRAFT` event answer `not_found`, and names both as known gaps between `main` and the spec until plan 3B. The `db228bc` line now includes the `CLAUDE.md` test count. Closes PR #19's two open NITs (4299944353, 4299944357). | Checked by PR #20's three fresh rounds (1, 0 and 0 BLOCKERs; the BLOCKER was in the spec). Guide findings, all fixed: the paragraph stated hold creation's read point as the general rule and implied 3B removes every effect (round 1), the lead "No runtime defect is known" now reads "reachable" (round 1), and "Plan 3B moves the read" was present tense about an unwritten plan (round 2). Merged 2026-10-17 as `d93ef7f` |
| 2026-10-17 (PR #20 sync) | Recorded the result of the spec 2.6.3 row's review rounds in that row, which had been written before the rounds ran. No other entry was stale: *Bugs Worth Remembering* already names both gaps between `main` and the spec, and plan 3B is still unwritten. | Staleness pass over every mention of `DRAFT`, the clock's sampling point, plan 3B and spec 2.6.3; found only the forward-looking check note above. PR #21's fresh round 1 (review 5560522924): 0 BLOCKER, 1 SUGGESTION, 2 NIT, all in this row or the journal; all fixed |
| 2026-10-18 (plan 3B written) | Plan 3B is written and merged as a plan (PR #22, `af5b1a4`), not executed. *Bugs Worth Remembering* no longer calls it unwritten. No other entry was stale: the banner already lists 3B's work as not built, and *Review as the Deliverable* already counts 3B among the documents. | Staleness pass over every mention of plan 3B. Checked by PR #23's fresh round 1 (review 5565781561): 0 BLOCKER, 3 SUGGESTION, 3 NIT, all fixed |
| 2026-10-19 (plan 3B executed) | Plan 3B executed and merged (PR #24, `61f0a65`). Banner, *What We Built* (197 runs, a plan 3B block), concepts 2, 3 and 7 (sweeper, confirm and the lock order built; "an agent can't buy" still design), the timeline answer, the review-history note on cancel, confirm and release, *Bugs Worth Remembering* (both gaps between `main` and the spec closed; the spec header's "Where `main` differs" block now stale), and the *Review as the Deliverable* header and concurrency-test follow-up all moved from "not built" to built as services, without adapters. Added *Executing Plan 3B*. | Staleness pass over every mention of plan 3B, "not built", buying, the sweeper, tests 2 and 6, confirm, release and cancel, the clock's sampling point and `DRAFT`. Checked by PR #25's fresh round 1 (review 5576525428): 2 BLOCKER, 4 SUGGESTION, 1 NIT, all fixed. PR #25's fresh round 2 (review 5576660010): 1 BLOCKER, 2 SUGGESTION, 4 NIT, all fixed. PR #25's fresh round 3 (review 5579432416): 0 BLOCKER, 1 SUGGESTION, 2 NIT, all fixed. PR #25's fresh round 4 (review 5579460532): 0 BLOCKER, 1 SUGGESTION, fixed |
| 2026-10-19 (spec 2.6.4) | Spec revision 2.6.4, the post-3B sync. *Bugs Worth Remembering* and *Executing Plan 3B* no longer call the spec header's "Where `main` differs" block owed. | Staleness pass over every mention of that block and of a spec sync being owed. Its review rounds are recorded on its PR |
| 2026-10-22 (plan 4A executed; spec 2.6.5) | Plan 4A written (PR #28) and executed (PR #29, `290b60b`), and spec revision 2.6.5. A sync only, with no new section: the banner (seven plans; four REST write endpoints behind the security chains; no read endpoint, MCP tool or datasource), *What We Built* (264 runs, a plan 4A block), concept 3 (the chains and the confirm endpoint built; the agent side still design), the timeline answer, the review-history note on cancel, confirm and release, *Bugs Worth Remembering* (the gaps plan 4A defers), and in *Review as the Deliverable* its header and the catch-all finding (now built). The banner and the timeline answer say the REST golden path runs with MockMvc, not over a socket, and *What We Built* scopes Problem Details to errors the application answers on `/api/**`. The *Executing Plan 3B* note on the error handler now says plan 4A built it. | Staleness pass over every mention of "no API", "not built", plan 4, the security chains, the catch-all chain, buying, and "reachable". Its review rounds are recorded on its PR |
| 2026-09-18 (wrap-up) | Added concept 7 (global lock order) and the seven-round review entry; synced to rev 2.6.1. Still design-stage. | **DRIFT FOUND** — 5 minor: a Hibernate wording mismatch with the spec (spec corrected in the same PR), round 6–7 counts backed only by the journal (added to the PR #1 log), two talking points that overclaimed, and this missing row. All fixed |

**2026-09-17 drift-check result.** The fresh-context check independently verified
the no-code claim (four documentation files, no `src/`, no `pom.xml`, not a git
repo) and confirmed the guide fabricates no metrics. It then found that the
warning banner had exactly one hole in it: concept 5's talking point said *"the
thing I made sure to include was a logged failure run,"* asserting a produced
artifact and narrating events inside it. Every other talking point describes a
*decision* that genuinely was made, which the banner legitimately reframes — but
a banner cannot make a false factual assertion speakable. Rewritten to a
design-intent claim. The same entry also narrated the unwritten log as fact
(*"captures a real race"*), now hedged to what the spec actually says.

Minor: an invented currency symbol inconsistent with the note's own "cents"
framing; a stack-table row that merged Docker/GitHub Actions under "Deploy"; and
an open question that firmed up the spec's hedge while dropping the spec's own
provisional answer. All corrected.

**The lesson worth keeping:** the risky sentences were in the first-person
talking-point quotes, because that format's past tense makes design intent and
completed work sound identical. Future CAPTURE runs at design stage should treat
those quotes as the highest-risk surface, not the prose around them.
