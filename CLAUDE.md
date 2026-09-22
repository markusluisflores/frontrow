# FrontRow

Event ticketing / seat-reservation service in Spring Boot. One domain core, two
inbound adapters: REST (humans) and MCP (agents). Global workflow rules live in
the user-level CLAUDE.md and apply here.

## Current state

**Scaffold only — a Maven build and a context-load test exist; no domain
code yet.** The design spec is
`docs/superpowers/specs/2026-09-17-frontrow-design.md` (revision 2.6.1; 2.6
reviewed and merged 2026-09-18, 2.6.1 a wording fix). The three spec §13 ADRs
are in `docs/adr/`. The Phase 1 plan 1 (foundation) is under execution on
`feat/phase-1-foundation`:
`docs/superpowers/plans/2026-09-18-phase-1-plan-1-foundation.md`.

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
- Branch-protection required status checks, once the CI job names exist

## Architecture rules

- The domain core has **no Spring Web and no MCP types**. If changing an MCP tool
  requires changing the domain core, the boundary has leaked — that is a review BLOCKER.
- Double-booking is prevented by Postgres constraints, not application logic
  (ADR-001). Integration and concurrency tests run against real Postgres
  (Testcontainers) — never H2, never a mocked repository.
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
