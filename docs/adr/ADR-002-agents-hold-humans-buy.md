# ADR-002: Agents hold, humans buy: no `confirm_order` tool, disjoint credential chains

**Date:** 2026-09-18
**Status:** Accepted

Not yet implemented. No code exists; this records a design decision. The full
design is in the spec, `docs/superpowers/specs/2026-09-17-frontrow-design.md`
§3 (*Caller identity*) and §6.

## Context and Problem Statement

FrontRow exposes write operations to AI agents over MCP. An agent may be buggy,
compromised or prompt-injected. Which writes should an agent be able to make,
and how is that limit enforced rather than just stated?

The no-`confirm_order` decision was made on 2026-09-17. How it is enforced (the
disjoint chains, and trusted identity in place of a caller-supplied parameter)
was designed in spec revisions 2 to 2.5 on 2026-09-18. Revision 2 introduced
the disjoint chains, 2.2 limited tokens to `BUYER` users with an `AGENT`
authority, and 2.5 added the explicit 401 filter for bearer tokens on
`/api/**`.

## Decision Drivers

* Holds are reversible and expire on their own. Purchases are neither.
* A misbehaving agent's worst case should be bounded and self-healing.
* The boundary must be enforced by authentication, not by tool design alone.
  A rule an agent could get around by calling a different endpoint is
  aspirational.
* Per-owner guardrails (caps, owner-only release) need an identity the caller
  cannot choose.
* An agent's hold must still be buyable by its human, so the handover has to
  be designed, not assumed.

## Considered Options

* **A `confirm_order` MCP tool**, letting an agent complete a purchase.
* **No `confirm_order` tool, with identity supplied as a tool parameter**
  (`buyer_ref`). This was the revision 1 design, where MCP authentication
  over stdio was still an open question.
* **No `confirm_order` tool, with identity from the authenticated principal on
  two disjoint filter chains.**

## Decision Outcome

**Chosen: no `confirm_order` tool, with identity from the authenticated
principal on two disjoint filter chains**, because it is the only option where
"an agent cannot buy" is enforced by authentication, and where the per-owner
guardrails rest on an identity the caller cannot forge.

* Confirmation exists only as `POST /api/holds/{holdGroupId}/confirm`.
* `/api/**` accepts HTTP Basic only. `/mcp` accepts bearer tokens only.
* Spring's Basic filter silently ignores an `Authorization: Bearer` header, so
  `/api/**` needs an explicit filter that returns 401 for any bearer request,
  on anonymous endpoints too.
* An MCP token carries no role of its own. Tokens are issued only to `BUYER`
  users, and the MCP chain grants a single `AGENT` authority scoped to `/mcp`.
* No tool takes an owner parameter.
* The handover: agent and human share one owner identity. The human sees the
  agent's holds under `GET /api/me/holds` and confirms there.

The rejected options:

* **A `confirm_order` tool** turns a compromised or prompt-injected agent into
  an irreversible purchase.
* **A caller-supplied `buyer_ref`** makes per-owner caps and owner-only release
  security theatre, because the caller picks whose identity to use. The
  revision 1 self-review said the caps should be dropped rather than enforced
  against it. Revision 2 replaced it with an authenticated identity.

### Consequences

* ✅ A fully compromised agent can at worst tie up seats until its holds expire,
  within the per-owner caps. It cannot spend money.
* ✅ The boundary is testable in both directions. Security tests must show a
  bearer token rejected on `/api/**`, on both an anonymous and an authenticated
  endpoint, and Basic rejected on `/mcp`.
* ⚠️ Every purchase needs a human step, even when the user wants their agent to
  buy. This is deliberate.
* ⚠️ Owner-scoped guardrails depend on the principal reaching `@McpTool`
  methods. That is unproven: the scaffold spike (spec §3) must show how the
  principal propagates before any guardrail is planned.
* ⚠️ Phase 1 security is demo-grade: in-memory Basic users, and static bearer
  tokens stored only as SHA-256 hashes. The README must say so. Real
  authentication (OAuth2 / OIDC) is Phase 2.
* ⚠️ Declaring any `SecurityFilterChain` bean disables Spring Boot's default
  one (`defaultSecurityFilterChain` is `@ConditionalOnDefaultWebSecurity`),
  and `FilterChainProxy` passes through, unauthenticated, any request that
  matches no chain — it does not deny it. The spike is safe only because
  `/mcp` is its one endpoint. Once `/api/**` and `/mcp` are both written as
  `securityMatcher`-scoped chains, everything outside both matchers —
  actuator, error dispatch, a later endpoint, a path-normalisation variant
  that slips a matcher — is served with no security applied, silently: no
  test fails, nothing logs. Plan 2 must add a lowest-ordered catch-all chain
  with no `securityMatcher` and `anyRequest().denyAll()`, plus a test that
  hits an unmatched path and expects 401/403.
