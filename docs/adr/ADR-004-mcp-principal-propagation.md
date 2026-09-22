# ADR-004: MCP tools get the caller from McpTransportContext

**Date:** 2026-09-22
**Status:** Accepted

Decided by the principal-propagation spike (spec §3), run against Spring Boot
4.1.1, Spring AI 2.0.1 and MCP Java SDK 2.0.0. Evidence:
`src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java`.

## Context and Problem Statement

Every owner-scoped guardrail (ADR-002) needs the authenticated principal inside
an `@McpTool` method. Streamable HTTP may run tool methods on a thread where
`SecurityContextHolder` is empty. How does a tool method learn its caller?

## Decision Drivers

* The owner must come from the authenticated principal, never from a tool
  parameter (ADR-002).
* The mechanism must be proven over real HTTP with a real bearer token, not
  assumed.
* It should not depend on an implementation detail a library upgrade could
  change silently.

## Considered Options

* **A. `SecurityContextHolder`** inside the tool method.
* **B. `McpTransportContext`**, filled by a `contextExtractor` on
  `WebMvcStreamableServerTransportProvider` from `ServerRequest.principal()`.

## Decision Outcome

**Chosen: B**, because both controls and both mechanisms passed (decision
table row 1: A = PASS, B = PASS). With both mechanisms working, the rule picks
B rather than treating the result as a tie: A = PASS (`mechanismA_securityContextHolderSeesThePrincipal`
returned `"alice"`), B = PASS (`mechanismB_transportContextCarriesThePrincipal`
returned `"alice"`).

Why A behaved as it did: Spring AI 2.0.1's `McpServerAutoConfiguration`
registers a `servletMcpSyncServerCustomizer` bean that sets
`immediateExecution(true)` for servlet SYNC servers, so tool methods run on the
request thread, and `SecurityContextHolder` is populated there by the spike's
bearer filter through `RequestAttributeSecurityContextRepository`. A passing
did not change the choice: `McpTransportContext` is the transport's own
carrier for request data and does not depend on which thread runs the tool,
where `SecurityContextHolder` working today is a threading detail a future
Spring AI change could alter silently.

### Consequences

* ✅ Plan 3's adapter reads the owner via `McpTransportContext` and passes it
  to the application service as a plain `String`, so the domain core stays
  free of MCP types.
* ⚠️ The app defines its own `WebMvcStreamableServerTransportProvider` bean,
  replacing the auto-configured one, so it must track the auto-config's
  settings (endpoint, keep-alive, disallow-delete) on Spring AI upgrades.
* ⚠️ The spike test stays in the suite as a tripwire: a Spring AI upgrade that
  changes tool threading fails it.
