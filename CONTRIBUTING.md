# Contributing to FrontRow

> The Maven build does not exist yet — the commands below are the target setup,
> landing with the Phase 1 scaffold. Until then the repository is docs-only.

## Development Setup

Requires JDK 25 and Docker (Testcontainers and `docker compose` both need it).

```bash
git clone https://github.com/markusluisflores/frontrow.git
cd frontrow
git config core.hooksPath .githooks
./mvnw verify
docker compose up
```

## Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feat/<description>` | `feat/hold-seats-tool` |
| Bug fix | `fix/<description>` | `fix/hold-expiry-race` |
| Docs | `docs/<description>` | `docs/spec-revision` |
| Chore | `chore/<description>` | `chore/pin-spring-versions` |

## Workflow

1. Branch from `main` — never commit directly to `main`
2. Write or update tests for any logic changes
3. Run `./mvnw verify` — all tests must pass before opening a PR
4. Open a PR using the provided template — fill in all sections
5. CI must be green before merge

## Commit Messages

`<type>(<scope>): <imperative subject>` — enforced by `.githooks/commit-msg`.

```
feat(holds): add hold_seats MCP tool with structured seat_taken error
fix(db): include CONVERTED in the active-hold partial unique index
docs(spec): resolve MCP caller identity for stdio transport
```

## Bug Reports

See [SECURITY.md](SECURITY.md) for security vulnerabilities.
For all other bugs, use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.yml) issue template.
Only file a bug if the defect was found after merge to main or a release — catch-during-development issues are fixed inline.

## Feature Requests

Use the [Feature Request](.github/ISSUE_TEMPLATE/feature_request.yml) issue template.
