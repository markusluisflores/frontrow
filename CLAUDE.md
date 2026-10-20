# FrontRow

Event ticketing / seat-reservation service in Spring Boot. One domain core, two
inbound adapters: REST (humans) and MCP (agents). Global workflow rules live in
the user-level CLAUDE.md and apply here.

## Current state

**Phase 1 plans 1 (foundation), 2 (domain foundations), 3A (hold creation) and 3B (confirm, release,
cancellation, the sweeper) are complete. Together they provide: a Maven build, CI, the V1 schema
migration, a principal-propagation spike, a pure `domain` package (money, statuses, sales window, hold
rules, the request fingerprint), the shared error contract (`error`), JPA entities and repositories over
all eight V1 tables (`persistence`), the locking gateway (`persistence/LockingGateway.java`), and every
write path on holds, orders and an existing event's status and window as a service in `service/`:
hold creation with idempotent replay (`HoldService`), confirmation, the only purchase path (`ConfirmService`),
release (`ReleaseService`), the event PATCH and cancellation (`EventPatchService`), and the expiry sweeper
and 24-hour key purge (`ExpirySweepService`, scheduled by `config/SweepSchedule`). Creating an event has no service yet, and no REST endpoint or MCP tool exists.**
197 test runs pass, including all seven spec §5 concurrency tests and a proof that test 1 fails without
`uq_claimed_seat`. Plans 4 (REST + security chains) and 5 (MCP) remain.
The migration itself is `src/main/resources/db/migration/V1__core_schema.sql`. The design spec is
`docs/superpowers/specs/2026-09-17-frontrow-design.md` (revision 2.6.4; 2.6
reviewed and merged 2026-09-18, 2.6.1 a wording fix, 2.6.2 a post-implementation
sync, 2.6.3 a status sync plus two decisions — when `now` is read, and `DRAFT` as
`not_found` — which plan 3B implements, 2.6.4 a post-implementation sync after plan 3B).
Plan 3A's text carries dated errata at its top. Five ADRs are in `docs/adr/`: the three from spec §13; ADR-004, recorded
by plan 1's spike; and ADR-005, the JPA-plus-JDBC persistence split behind the
lock rule below. Plan 1 was executed on `feat/phase-1-foundation`:
`docs/superpowers/plans/2026-09-18-phase-1-plan-1-foundation.md`. Plan 2 was
executed on `feat/phase-1-domain-foundations` and merged as PR #10 (`e432c3c`,
2026-10-05): `docs/superpowers/plans/2026-10-02-phase-1-plan-2-domain-foundations.md`.
PR #10's description carries the plan deviations and the list Plan 3 must address.
Plan 3A was merged as PR #16 (`ed1566a`) and executed on `feat/phase-1-hold-creation`, merged as PR #18 (`4de410e`, 2026-10-16):
`docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`. Plan 3B:
`docs/superpowers/plans/2026-10-17-phase-1-plan-3b-confirm-release-cancel.md`.

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

## Conventions

- Commit hooks live in `.githooks/`; activate with `git config core.hooksPath .githooks`
  (commit-message format, device-path block, gitleaks secret scan).
- Diagrams in committed docs use Mermaid.
- ADRs go in `docs/adr/`. Session journal: `docs/journal/2026.md`.
  Interview guide: `docs/project-reviewer.md` — design-stage entries must not
  claim shipped work.
- Tests: JUnit Jupiter 6 (Boot 4.1.1-managed; same API as the "JUnit 5" named in
  the spec) + AssertJ + Testcontainers. Run everything with `./mvnw verify`.

## CI Runbook

| Workflow | Runs on | Manual trigger |
|---|---|---|
| `ci.yml` — Build and test | push to `main`, PRs | `gh workflow run ci.yml --ref <branch>` |
| `codeql.yml` — Analyze (java-kotlin) | push to `main`, PRs, Mondays | `gh workflow run codeql.yml --ref <branch>` |
| `dependency-review.yml` | PRs only | Re-run from the PR's Checks tab |

No workflow reads a secret. Never push an empty commit to trigger CI.
Manual triggers work only once the workflow file is on `main` —
`workflow_dispatch` isn't available from a branch that hasn't merged yet.
