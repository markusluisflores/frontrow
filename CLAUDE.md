# FrontRow

Event ticketing / seat-reservation service in Spring Boot. One domain core, two
inbound adapters: REST (humans) and MCP (agents). Global workflow rules live in
the user-level CLAUDE.md and apply here.

## Current state

**Phase 1 plans 1 (foundation) and 2 (domain foundations) are complete.
Together they provide: a
Maven build, CI, the V1 schema migration, a principal-propagation spike, a pure
`domain` package (money, statuses, sales window, hold rules), the shared error
contract (`error`), JPA entities and repositories over all eight V1 tables
(`persistence`), and the locking gateway (`persistence/LockingGateway.java`).
No write path, REST endpoint or MCP tool exists yet.**
95 test runs pass: 22 schema constraint tests, 14 locking-gateway tests (4 of
them concurrent-transaction lock proofs), 4 configuration-bounds tests, and the
domain, error, mapping, advisory-key and spike tests. The migration itself
is `src/main/resources/db/migration/V1__core_schema.sql`. The design spec is
`docs/superpowers/specs/2026-09-17-frontrow-design.md` (revision 2.6.1; 2.6
reviewed and merged 2026-09-18, 2.6.1 a wording fix). Four ADRs are in
`docs/adr/` — the three from spec §13 plus ADR-004, recorded by
plan 1's spike. Plan 1 was executed on `feat/phase-1-foundation`:
`docs/superpowers/plans/2026-09-18-phase-1-plan-1-foundation.md`. Plan 2 was
executed on `feat/phase-1-domain-foundations` and merged as PR #10 (`e432c3c`,
2026-10-05): `docs/superpowers/plans/2026-10-02-phase-1-plan-2-domain-foundations.md`.
PR #10's description carries the plan deviations and the list Plan 3 must address.

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
- All lock SQL lives in `persistence/LockingGateway.java` — `FOR UPDATE`, `FOR SHARE`
  and `pg_advisory_xact_lock` appear nowhere else in `src/main`. A service that writes
  its own lock SQL breaks the single global lock order (spec §5) — that is a review
  BLOCKER. Check with:
  `grep -rlniE "for (no key )?update|for (key )?share|pg_[a-z_]*advisory|@Lock\b|LockModeType|PESSIMISTIC|skip locked|nowait" src/main/java/`
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
