# FrontRow

Event ticketing / seat-reservation service in Spring Boot. One domain core, two
inbound adapters: REST (humans) and MCP (agents). Global workflow rules live in
the user-level CLAUDE.md and apply here.

## Current state

**Scope (ADR-006, spec revision 2.7, 2026-10-27): FrontRow is a finished, local-only portfolio project.**
Phase 1 is the whole project; the former Phases 2–4 are "Possible next steps" in the README, not commitments.
Plans 4B and 5 are written against the spec §7 demo story. `GET /api/orders/{id}` and the `ADMIN` role are
cut. Plan 4B removes their rule, the role, the `adam` user and the `ADMIN` test assertions (`/api/orders/x`, and
the admin-refused checks) from `main`, and repoints the tests that use `adam` but are not about `ADMIN` at another
user (spec §6). It also fixes the app listening on every interface, a defect on `main` (spec §3): compose publishes
the app's port on `127.0.0.1` only and leaves Postgres's unpublished, compose's Postgres password comes from a
gitignored `.env`, and the README's and this file's run commands gain
`-Dspring-boot.run.arguments=--server.address=127.0.0.1`. It also fixes the post-start reopen, a defect on `main`
(spec §5, §6): `POST /api/events`, and a `PATCH` that sets a window bound, reject a resulting window that closes
after `starts_at` (`invalid_request`, `parameter: sales_window`); a status-only `PATCH` is not checked, and there is no
schema `CHECK`, so seed data must comply, and a plan 4B test asserts it for every seeded event. `POST /api/events` stays. From here on, findings follow the **portfolio quality bar** (spec §9),
three kinds:
- a defect (behaviour on `main` or in the change under review that contradicts the spec, an ADR or the README) is
  always fixed or removed, before merge when it is in a change under review;
- a security finding is a defect unless it is on spec §9's approved known-limitations list. Only Markus adds to that
  list; a reviewer or author never does. The approved design decisions are a closed set of two: the agent sharing
  its human's owner, and confirm tied only to the hold id. Any new one needs Markus's approval and is named in spec §9;
  a spec or ADR revision that records a new accepted security consequence makes it a list candidate, not design.
  Only Markus adds to, widens or rewords an entry in either list; an unapproved change to either list's text is a
  defect in the change under review. The approved scope items are a closed set of four, under the same gate: static
  agent tokens that never expire, in-memory Basic users, plain HTTP on loopback, no OAuth2. A finding about one is
  not a finding; an unstated consequence of one (no lockout, so online guessing) is. Neither spec §9's demo-grade
  definition nor the §2 non-goals is an exemption source;
- non-security hardening is fixed only if it is part of the core guarantee, visible in the demo, or an obvious flaw
  to someone reading the code. Other hardening goes into the README's "Known limitations", never GitHub issues.

**Phase 1 plans 1 (foundation), 2 (domain foundations), 3A (hold creation), 3B (confirm, release,
cancellation, the sweeper) and 4A (the security edge and the REST write endpoints) are complete.
Together they provide: a Maven build, CI, the V1 schema
migration, a principal-propagation spike, a pure `domain` package (money, statuses, sales window, hold
rules, the request fingerprint), the shared error contract (`error`), JPA entities and repositories over
all eight V1 tables (`persistence`), the locking gateway (`persistence/LockingGateway.java`), and every
write path on holds, orders and an existing event's status and window as a service in `service/`:
hold creation with idempotent replay (`HoldService`), confirmation, the only purchase path (`ConfirmService`),
release (`ReleaseService`), the event PATCH and cancellation (`EventPatchService`), and the expiry sweeper
and 24-hour key purge (`ExpirySweepService`, scheduled by `config/SweepSchedule`). Plan 4A adds the
three security chains (`security/SecurityConfig`), hashed demo credentials, Problem Details errors and a
16 KiB request-body bound (`rest/`), and four REST endpoints over those services: hold, release,
confirm and the event PATCH. No read endpoint, no event creation and no MCP tool exists yet.**
264 test runs pass, including all seven spec §5 concurrency tests and a proof that test 1 fails without
`uq_claimed_seat`. Plans 4B (the read services and GET endpoints, event creation, OpenAPI) and 5 (MCP)
remain.
The migration itself is `src/main/resources/db/migration/V1__core_schema.sql`. The design spec is
`docs/superpowers/specs/2026-09-17-frontrow-design.md` (revision 2.7, the portfolio-scope change; 2.6
reviewed and merged 2026-09-18, 2.6.1 a wording fix, 2.6.2 a post-implementation
sync, 2.6.3 a status sync plus two decisions — when `now` is read, and `DRAFT` as
`not_found` — which plan 3B implements; 2.6.4 and 2.6.5 post-implementation syncs after plans 3B and 4A).
Plan 3A's text carries dated errata at its top. Six ADRs are in `docs/adr/`: the three from spec §13; ADR-004, recorded
by plan 1's spike; ADR-005, the JPA-plus-JDBC persistence split behind the
lock rule below; and ADR-006, the portfolio scope. Plan 1 was executed on `feat/phase-1-foundation`:
`docs/superpowers/plans/2026-09-18-phase-1-plan-1-foundation.md`. Plan 2 was
executed on `feat/phase-1-domain-foundations` and merged as PR #10 (`e432c3c`,
2026-10-05): `docs/superpowers/plans/2026-10-02-phase-1-plan-2-domain-foundations.md`.
PR #10's description carries the plan deviations and the list Plan 3 must address.
Plan 3A was merged as PR #16 (`ed1566a`) and executed on `feat/phase-1-hold-creation`, merged as PR #18 (`4de410e`, 2026-10-16):
`docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`. Plan 3B:
`docs/superpowers/plans/2026-10-17-phase-1-plan-3b-confirm-release-cancel.md`. Plan 4A:
`docs/superpowers/plans/2026-10-20-phase-1-plan-4a-security-edge.md`.

## Deferred bootstrap items — owed by the Phase 1 scaffold task

These `new-project` steps need a `pom.xml` and were deliberately not done at
bootstrap. The scaffold task must land all of them, not just the build:

- ~~Maven wrapper; Java/Spring versions pinned from Maven Central (not from the spec)~~
- ~~Test framework: JUnit 5 + AssertJ + Testcontainers; `./mvnw verify` as the test command~~
- ~~Formatter (Spotless)~~
- ~~Per-file `PostToolUse` Spotless hook in `.claude/settings.json`~~
- ~~Linter / static analysis (SpotBugs), and compile + tests as the type-check gate (`-Werror`)~~
- ~~Linter / static analysis enforced in CI~~
- ~~CI workflow, CodeQL (`java-kotlin`), dependency vulnerability scan, Dependabot
  (`maven` + `github-actions`) — invoke `cicd-standards` first~~
- ~~Branch-protection required status checks~~ — `main` requires
  `Build and test` and `Analyze (java-kotlin)`, strict mode on

## Architecture rules

- The domain core has **no Spring Web and no MCP types**. If changing an MCP tool
  requires changing the domain core, the boundary has leaked — that is a review BLOCKER.
- Double-booking is prevented by Postgres constraints, not application logic
  (ADR-001). Integration and concurrency tests run against real Postgres
  (Testcontainers) — never H2, never a mocked repository.
- All lock SQL lives in `persistence/LockingGateway.java` (why: ADR-005) — `FOR UPDATE`, `FOR SHARE`
  and `pg_advisory_xact_lock` appear nowhere else in `src/main`. A service that writes
  its own lock SQL breaks the single global lock order (spec §5) — that is a review
  BLOCKER. Check with:
  `grep -rlniE "for (no key )?update|for (key )?share|pg_[a-z_]*advisory|@Lock\b|LockModeType|PESSIMISTIC|skip locked|nowait|lock table" src/main/java/`
  A match inside a comment counts too, so keep lock vocabulary out of comments elsewhere.
- The `domain` package imports only `java.*`. Check with:
  `grep -rn "^import" src/main/java/io/github/markusluisflores/frontrow/domain/ | grep -v "import java\."`
- MCP tools return structured errors (`{"code": ..., "hint": ...}`, same codes as REST). An
  unstructured string error is a review BLOCKER.
- No MCP tool may complete a purchase. Agents hold; humans buy. `/api/**` and
  `/mcp` are disjoint credential chains (ADR-002).
- MCP is served over Streamable HTTP only — no stdio, no SSE (ADR-003).
- Every credential and role rule lives in `security/SecurityConfig.java`: the `/api/**`
  chain (Basic only, with the spec §6 role table), the `/mcp` chain (agent bearer tokens only, `AGENT`
  authority), and a last catch-all chain that denies everything but four anonymous paths. An `/api/**`
  path the table doesn't list is denied, so a new endpoint needs its rule there. Owner scoping is not a role rule: the services enforce
  it (release and confirm answer `not_found` for another owner's hold via
  `LockingGateway#lockGroupRows(group, owner)`), so a read scoped by owner, such as 4B's `GET /api/me/holds` and `GET /api/me/orders`, must filter by
  the caller in the service: its table row alone doesn't do it. The 16 KiB body bound
  is not in a chain: `rest/RestConfig` registers `RequestBodyLimitFilter` ahead of all of them, and it
  holds only while Boot's FormContentFilter and multipart resolver stay off and Tomcat's form-post cap stays at 16 KB
  (`application.yml`).
- Every service entry point is `@Transactional(isolation = READ_COMMITTED)` and calls
  `TransactionBoundary.requireOwnTransaction()` as its first statement; no adapter is
  `@Transactional`. `Propagation.NEVER` is not the answer: it runs the method with no transaction
  at all (plan 4A decision 4). The check is a chain: `TransactionIsolationTest` scans `service/`
  for `@Service` classes and fails unless they equal its `SERVICES` list, and
  `ServiceTransactionBoundaryTest` fails on a public method of a listed service it doesn't cover.
  So a new service fails the build until it is listed and its entry points are covered.
- REST errors are RFC 9457 Problem Details from `rest/RestExceptionHandler`: a `DomainException`
  carries its `code`, and anything else is a generic 500 that echoes nothing. Errors a filter
  answers before MVC (401, 403, 413, 415) are written by `rest/Problems.write`, in the same shape.

## Conventions

- Commit hooks live in `.githooks/`; activate with `git config core.hooksPath .githooks`
  (commit-message format, device-path block, gitleaks secret scan).
- Diagrams in committed docs use Mermaid.
- ADRs go in `docs/adr/`. Session journal: `docs/journal/2026.md`.
  Interview guide: `docs/project-reviewer.md` — design-stage entries must not
  claim shipped work.
- Tests: JUnit Jupiter 6 (Boot 4.1.1-managed; same API as the "JUnit 5" named in
  the spec) + AssertJ + Testcontainers. Run everything with `./mvnw verify`.
- No usable credential is committed. `scripts/demo-credentials.sh` writes bcrypt and SHA-256 hashes
  to the gitignored `config/application-demo.yml` and prints the raw values once; run the app with
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo`. The app also needs a Postgres datasource:
  `application.yml` sets none, so supply `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
  `SPRING_DATASOURCE_PASSWORD` in the environment. Without a datasource startup fails at DataSource
  auto-configuration, and without the demo profile it fails because there are no users. Tests generate their own credentials per run (`support/TestCredentials`).

## CI Runbook

| Workflow | Runs on | Manual trigger |
|---|---|---|
| `ci.yml` — Build and test | push to `main`, PRs | `gh workflow run ci.yml --ref <branch>` |
| `codeql.yml` — Analyze (java-kotlin) | push to `main`, PRs, Mondays | `gh workflow run codeql.yml --ref <branch>` |
| `dependency-review.yml` | PRs only | Re-run from the PR's Checks tab |

No workflow reads a secret. Never push an empty commit to trigger CI.
Manual triggers work only once the workflow file is on `main` —
`workflow_dispatch` isn't available from a branch that hasn't merged yet.
