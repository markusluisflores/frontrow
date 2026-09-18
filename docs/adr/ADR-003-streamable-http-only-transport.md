# ADR-003: Serve MCP over Streamable HTTP only

**Date:** 2026-09-18
**Status:** Accepted

Not yet implemented. No code exists; this records a design decision. The full
design is in the spec, `docs/superpowers/specs/2026-09-17-frontrow-design.md`
§10.

## Context and Problem Statement

Revision 1 of the spec served MCP over stdio. That left an open question: with
no HTTP layer, how would a caller be authenticated? Per-caller guardrails and
`release_hold` depended on the answer. Which MCP transport or transports should
FrontRow support?

Decided in spec revision 2 on 2026-09-18. The user explicitly accepted dropping
stdio.

## Decision Drivers

* Every MCP caller must be authenticated. ADR-002's per-owner guardrails and
  its disjoint credential chains depend on it.
* One running service: no second JVM launched beside the `docker compose`
  instance, and nothing that can corrupt the protocol through stdout.
* No deprecated transports.
* No loss of client reach: Claude Code must still be able to connect.

## Considered Options

* **stdio**, as in revision 1.
* **HTTP with SSE.**
* **Streamable HTTP**, via `spring-ai-starter-mcp-server-webmvc` with
  `spring.ai.mcp.server.protocol=STREAMABLE`.

## Decision Outcome

**Chosen: Streamable HTTP only**, because it is the only option that satisfies
all four drivers.

The rejected options:

* **stdio:**
  * It has no HTTP layer, so there is nowhere to put an authentication filter.
  * Claude Code launches a stdio server as its own subprocess. That means a
    second JVM beside the `docker compose` instance, with its own connection
    pool and its own sweeper.
  * Anything written to stdout, such as the Spring banner or console logging,
    corrupts the protocol.
* **SSE** is deprecated in the MCP specification (replaced by Streamable HTTP
  in the 2025-03-26 revision) and in Spring AI since 2.0.0.

Dropping stdio costs no client reach. Claude Code connects to HTTP servers
directly, passing the bearer token as a header.

### Consequences

* ✅ MCP runs in the same process as REST, behind its own security filter chain,
  so bearer-token identity is enforced before any tool runs. An unauthenticated
  call gets a 401 and never reaches a tool.
* ✅ There is one service process, with one connection pool. A second sweeper
  would only have been redundant, not wrong (spec §5). The real cost of stdio
  was the second JVM and the stdout corruption risk.
* ⚠️ The service must be running for an agent to use it (`docker compose up`).
  An MCP client cannot launch it on demand.
* ⚠️ Tool methods may run on a thread where `SecurityContextHolder` is empty.
  The scaffold spike (spec §3) must prove how the principal reaches a tool
  method before any owner-scoped guardrail is planned.
* ⚠️ The server depends on the Spring AI MCP starter's Streamable HTTP support.
  Versions are re-checked and pinned at scaffold time.
