# FrontRow — Project Reviewer & Interview Guide

> **Living document.** Updated as new concepts are added or lessons are learned.
> Last updated: 2026-09-18

> ⚠️ **Design stage only. Nothing in this project has been built yet.**
> Every entry below is a *design decision* supported by
> `docs/superpowers/specs/2026-09-17-frontrow-design.md`. No code, no tests, no
> measured results exist. Do not claim any of this as shipped work in an
> interview — the honest framing is "here's a design I reasoned through and the
> tradeoffs I weighed," which is still a real answer. Entries get rewritten with
> implementation evidence as Phase 1 lands.

---

## What We Built

*(Nothing yet.)* The designed system: an event ticketing and seat-reservation
service in Spring Boot, exposed through two inbound adapters over one domain
core — a REST API for humans and an MCP server for agents.

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
| Build | Maven |
| Testing | JUnit 5, AssertJ, Testcontainers |
| API docs | springdoc-openapi |
| Container / CI | Docker, GitHub Actions |
| Deploy | Railway (Phase 2, after real auth) |

*Spring Boot 4.1.1 and Spring AI 2.0.1 were confirmed as the latest GA releases on Maven Central on 2026-09-18. Pin exact versions at scaffold time.*

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
optimistic locking with a version column and pessimistic `SELECT ... FOR UPDATE`.
Both work, but both place the guarantee in application logic, which means a bug
in that logic silently becomes a double-booking. The index means that even if the
service layer is wrong, the database cannot record the bad state.

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
trace logging with caller identifiers redacted. Identity is enforced by two
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

## Engineering Process

### Scoping Under a Real Time Constraint

The research consulted recommended Kafka, a full Prometheus/Grafana observability
stack, and OAuth2 — all genuinely in demand. All three were cut from Phase 1
because the available time was days-to-a-week and including them produces a
half-finished everything, which the same research says is worse than a small
complete thing. They're scheduled as Phases 2–3.

**Interview talking point:** "I had about a week, and the checklist I was working
from had more on it than fits in a week. So I scoped Phase 1 to be genuinely
*done* — domain, REST, real integration tests, the MCP tools and their evidence
artifacts — and pushed Kafka and the observability stack to later phases. The
thing I protected was the concurrency tests, the credential separation and the
MCP failure log, because those are the parts that are actually differentiating.
The cuttable parts were the organiser endpoints and the breadth of seed data."

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
record. These are design findings; none of it has been implemented or tested yet.

---

## Bugs Worth Remembering

*Nothing yet — no code has been written.* This section fills in from
`docs/retros/` and debugging sessions as Phase 1 is implemented.

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

## Revision Notes

| Date | Change | Accuracy-drift check |
|---|---|---|
| 2026-09-17 | Created at design stage from the spec and journal. No code, ADRs, retros, or PRs existed to read. | **DRIFT FOUND** — 2 real defects, 3 minor. Fixed inline; see below. |
| 2026-09-18 | Synced to spec revision 2 and 2.1: claim-index predicate, transport and identity model, trim order, open questions, and the rev-1 self-review talking point (its "caps get dropped" claim no longer matches the spec). Still design-stage — no shipped claims added. | Cold reviewer flagged the stale talking point and version note; both fixed |

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
