# FrontRow

Event ticketing / seat-reservation service in Spring Boot. One domain core, two
inbound adapters: REST (humans) and MCP (agents). Global workflow rules live in
the user-level CLAUDE.md and apply here.

## Current state

**Docs only — no build exists yet.** The design spec is
`docs/superpowers/specs/2026-09-17-frontrow-design.md`. Code scaffolding waits
for (1) the spec revision to merge and (2) a Phase 1 implementation plan.

## Deferred bootstrap items — owed by the Phase 1 scaffold task

These `new-project` steps need a `pom.xml` and were deliberately not done at
bootstrap. The scaffold task must land all of them, not just the build:

- Maven wrapper; Java/Spring versions pinned from Maven Central (not from the spec)
- Test framework: JUnit 5 + AssertJ + Testcontainers; `./mvnw verify` as the test command
- Formatter (Spotless) plus a per-file `PostToolUse` hook in `.claude/settings.json`
- Linter / static analysis enforced in CI; compile + tests as the type-check gate
- CI workflow, CodeQL (`java-kotlin`), dependency vulnerability scan, Dependabot
  (`maven` + `github-actions`) — invoke `cicd-standards` first
- Branch-protection required status checks, once the CI job names exist

## Architecture rules

- The domain core has **no Spring Web and no MCP types**. If changing an MCP tool
  requires changing the domain core, the boundary has leaked — that is a review BLOCKER.
- Double-booking is prevented by Postgres constraints, not application logic.
  Integration and concurrency tests run against real Postgres (Testcontainers) —
  never H2, never a mocked repository.
- MCP tools return structured errors (`{"error": ..., "hint": ...}`). An
  unstructured string error is a review BLOCKER.
- No MCP tool may complete a purchase. Agents hold; humans buy.

## Conventions

- Commit hooks live in `.githooks/`; activate with `git config core.hooksPath .githooks`
  (commit-message format, device-path block, gitleaks secret scan).
- Diagrams in committed docs use Mermaid.
- ADRs go in `docs/adr/`. Session journal: `docs/journal/2026.md`.
  Interview guide: `docs/project-reviewer.md` — design-stage entries must not
  claim shipped work.
