# FrontRow

> **Status: design only. No code exists yet.** This folder currently contains a
> spec and nothing else. The repository is bootstrapped but no code has been scaffolded.

An event ticketing and seat-reservation service in Spring Boot, exposed through
two inbound adapters over one domain core: a REST API for humans, and an MCP
server for agents.

Two things it is built to demonstrate:

1. **Correctness under concurrency** — double-booking is prevented by a
   Postgres partial unique index rather than by application logic, and that is
   proven by a test where N threads race for the same seat and exactly one
   wins. Run against real Postgres via Testcontainers.
2. **Agent-facing API design** — thin, single-responsibility MCP tools with
   typed schemas, structured error contracts, and a stated threat model. The
   central decision: **an agent can hold seats; only a human can buy them.**
   There is no `confirm_order` tool.

## Where things are

| | |
|---|---|
| Design spec | [`docs/superpowers/specs/2026-09-17-frontrow-design.md`](docs/superpowers/specs/2026-09-17-frontrow-design.md) |

## Next steps

1. User reviews the design spec.
2. Run the `new-project` skill to bootstrap the repository.
3. Run `superpowers:writing-plans` to turn Phase 1 into an implementation plan.
