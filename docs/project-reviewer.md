# FrontRow — Project Reviewer & Interview Guide

> **Living document.** Updated as new concepts are added or lessons are learned.
> Last updated: 2026-10-05

> ⚠️ **Phase 1 plans 1 and 2 of 5 have landed. Nothing can reserve a seat yet.**
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
>     transactions.
>
> **Not built:** application services (no hold, confirm, release or cancel path),
> the expiry sweeper, the seven §5 concurrency tests, the security chains, REST
> endpoints, MCP tools, seed data, and a Dockerfile or compose file. Nothing is
> deployed. Entries below that describe
> unbuilt work are still *design decisions* supported by
> `docs/superpowers/specs/2026-09-17-frontrow-design.md` — do not claim those
> as shipped work in an interview; the honest framing is "here's a design I
> reasoned through and the tradeoffs I weighed," which is still a real
> answer. Entries get rewritten with implementation evidence as later plans
> land.

---

## What We Built

**What exists, as of 2026-10-05:**

**Plan 1, the foundation:**
- A Maven build on Java 25. The wrapper is pinned to Maven 3.10.0 with its
  distribution checksum. The build is gated by Spotless, SpotBugs and
  `-Xlint:all -Werror`.
- CI running build-and-test, CodeQL, dependency review and Dependabot, with two
  checks required on `main`.
- The `V1__core_schema.sql` migration, which carries the whole domain model and
  the seat-claim invariant.
- 21 constraint tests, each inserting a violating row.
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

There are **95 test runs** in total. Still missing: any code path that writes a
hold, an order or a release.

The *designed* system, most of which is not built: an event ticketing and
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
catches the resulting constraint violation and translates it into a structured
`seat_taken` error rather than leaking a 500. The alternatives considered were
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

**Interview talking point:** "Hold expiry runs two ways on purpose. There's a
scheduled sweeper, but correctness doesn't depend on it — when someone tries to
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
- **Not proven, and worth admitting:** the seat_hold row locks. A reviewer
  deleted both `FOR UPDATE` clauses and all 14 gateway tests still passed.
  Plan 3 owns that test.
- **Still design (plan 3):** hold creation's lock-expire-insert-flush per seat,
  cancellation's ordering, and every service that calls the gateway.

**Interview talking point:** "The index guarantees no seat is ever claimed
twice. The lock order keeps the other state transitions correct and
deadlock-free: confirm, release, cancel and expiry all decide on current
state. I wrote down one lock order that every transaction follows, so
deadlock-freedom is a property I can argue from the order rather than hope for.
The subtle part was that hold creation interleaves locks and inserts. Taking
all the locks first and then inserting sounds safe, but it breaks the ordering
across the two phases, so the design claims seats one at a time. What's built
today is the gateway itself. Every lock lives in one file, so 'one global order'
is something you can check by reading that file. The event and per-owner locks
are proven with concurrent transactions. A reviewer mutation-tested the seat-row
locks and showed nothing yet proves they block, so that's the first test the
next plan adds."

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

**The longer version:** Both were found by the final whole-branch review of
plan 2, and both are built and tested.
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
doesn't apply to a `Duration`, which I only knew after checking the validator
jar we actually ship."

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
dodge.** The spec estimated "a full focused week" for Phase 1. Eighteen days in,
the foundation and the domain foundations are built. No write path exists yet,
so nothing can hold or buy a seat. The reason is not drift: Phase 1 was
re-planned from 3 documents into 5 after two proved too large to review, and
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

These are design findings; none of it has been implemented or tested yet.

### Reviewing Until the Rule Is Met, and a Claim I Got Wrong

Revision 2 went through seven review rounds before merging. After every fix
pass, the reviewer that raised the findings resumed to verify them, and a fresh
reviewer read cold. Blockers per round were 2, 1, 2, 1, 0, 1 and 0.

The round-6 blocker was mine. In round 5, I wrote a reviewer's suggestion into
the spec: that Hibernate's PostgreSQL dialect ignores a positive lock-timeout
hint. That's true of Hibernate 6. Spring Boot 4.1.1 ships Hibernate 7.4.5, and
its source (checked afterwards) applies the timeout with `SET LOCAL` around the
one locking query. The design choice survived, for a better reason: one explicit
`SET LOCAL` covers every lock in the transaction. The claim did not.

**Interview talking point:** "The review loop found real protocol bugs, each
one smaller than the last. The one I'm most careful to mention is a false claim
I introduced myself: I took a reviewer's statement about Hibernate at face
value, and it was only true for the previous major version. The lesson I took:
check version-specific library behaviour against the source of the exact
version we'll pin, before it goes into a spec."

---

## Bugs Worth Remembering

*No runtime defect is known on `main`.* That covers plans 1 and 2: the scaffold,
CI, the V1 schema, the spike, the domain foundations and the locking gateway.
Two things did reach `main`, and they should be named rather than hidden:
- **A known test gap:** nothing proves the seat_hold row locks block. Plan 3 owns
  it.
- **One wrong commit message:** `a648936` claims a javadoc reflow it never did.
  PR #10 discloses it.

Several real defects were caught **before** merge, and those are the ones worth
discussing, because catching them is the claim:

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
3. **No cancellation.** The sold-once constraint is permanent in Phase 1; the
   spec states how it would be relaxed (§5), and that is the likely follow-up.

---

## Review as the Deliverable (2026-10-02)

*Written when plans 2-5 were still documents. As of 2026-10-05, plan 2 is built
(PR #10), and the plan-2 items below carry "now built" notes. Plans 3-5 are still
documents or unwritten.* The review of those documents is where most defects
were caught before code existed. Unless an item says "now built", treat it as a
**review or design outcome, not shipped code**, and say so if asked.

**The mutation test, which *is* shipped.** Deleting `uq_claimed_seat` from the
migration fails exactly three tests, and a reviewer independently checked every
other test for hidden dependence on the index and found none.

*Follow-up to expect:* "Three tests prove the index exists, not that it holds
under concurrency." Correct. Spec §5's seven concurrency tests (racing holds,
confirms and cancels) are plan 3 and are not built. Plan 2's lock tests prove the
locks behave; they never race two writers for one seat.
The schema tests prove the constraint rejects the state; they do not prove the
service translates the violation into the right error.

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

**A security finding, now in ADR-002 (design; not built).** Declaring any
`SecurityFilterChain` bean removes Spring Boot's default chain, and
`FilterChainProxy` passes a request matching **no** chain straight through with no
security applied — it does not deny it. So two `securityMatcher`-scoped chains
would leave every other path unauthenticated, silently: no test fails and nothing
logs above TRACE.

*Follow-up:* "Why not default to permit and secure each chain?" Because that is
fail-open and the failure is invisible. The required fix is a catch-all chain
evaluated last with `anyRequest().denyAll()`, plus a test that hits an unmatched
path and expects 401 or 403.

*Follow-up:* "Is it built?" No — plan 4 owns it.

**The persistence split (built in PR #10; its ADR is still owed).** JPA for ordinary reads and
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

**What went wrong.** PR #10 opened with no review on it at all. Every one of the
12 review dispatches carried an instruction, written by the controlling session
itself, not to post to GitHub. That broke a standing rule: posting the review is
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
- All 12 reports were still in the subagent transcripts. They were posted
  verbatim, with dispositions.
- A fresh reviewer then posted a proper inline review itself.
- That review found what all 12 earlier ones missed: it mutation-tested the
  seat-row locks and showed no test proves they block.

**Interview talking point:** "The most useful failure in this project wasn't in
the code. My review process said every review must be posted to the PR, and for
a whole plan it wasn't. The orchestrating session told each reviewer not to
post, because the PR didn't exist yet. What I took from it is that a rule which
has failed four times as prose isn't going to start working with stronger prose.
The fixes I'm weighing are structural: open a draft PR before the first review
so there's always somewhere to post, and a hook that flags a PR with zero review
comments. And the review that finally got posted found a real test gap, which is
a decent argument for the rule in the first place."

*Be accurate if pressed:* the structural fixes are **candidates in the project's
process queue, not built**.

---

## Revision Notes

| Date | Change | Accuracy-drift check |
|---|---|---|
| 2026-09-17 | Created at design stage from the spec and journal. No code, ADRs, retros, or PRs existed to read. | **DRIFT FOUND** — 2 real defects, 3 minor. Fixed inline; see below. |
| 2026-09-18 | Synced to spec revisions 2 through 2.4: claim-index predicate, transport and identity model, trim order, open questions, and the rev-1 self-review talking point (its "caps get dropped" claim no longer matches the spec). Still design-stage — no shipped claims added. | Cold reviewer flagged the stale talking point and version note; both fixed |
| 2026-09-22 | Added the Phase 1 banner when plan 1 merged — the first update where shipped work existed. | No separate accuracy check was recorded at the time; this row corrects that omission |
| 2026-10-02 (wrap-up) | Plan 1 executed and merged, plan 2 written and merged, Dependabot's first two bumps merged. Added *Review as the Deliverable*, corrected the stack table, and took the scoping talking point out of the past tense. | **DRIFT FOUND** — 12 items, the sharpest being the inverse of this guide's usual risk: "What We Built — *(Nothing yet.)*" **denied** work that exists. Also: the banner read as "Phase 1 landed" when plan 1 of 5 landed; the not-built list omitted entities, repositories, security chains, seed data and Docker; "JUnit 5" contradicted `CLAUDE.md`; springdoc and Docker were listed as if present; "pin versions at scaffold time" was stale; and the scoping point described a finished week in the past tense on day 15 with no domain code |
| 2026-10-05 (wrap-up) | Plan 2 executed and merged (PR #10). Rewrote the banner and *What We Built* to cover the domain foundations, added a persistence row to the stack table, added "built" evidence to concepts 4 and 7 (with the unproven seat-row locks stated), added concept 8 (silent failures made loud), two bug stories, a *now built* note on the plan-2 review findings, and the subagent-execution / posting-failure section. | *pending — see the row below once the check runs* |
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
