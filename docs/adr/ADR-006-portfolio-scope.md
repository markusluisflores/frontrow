# ADR-006: FrontRow is a finished, local-only portfolio project

**Date:** 2026-10-27
**Status:** Accepted

Decided by Markus on 2026-10-27 in a brainstorming session, choosing from the options below at each step. Recorded in spec revision 2.7 (§1, §2, §3, §6, §7, §9, §10, §11, §12 and §13; see its §14 row).

## Context and Problem Statement

The spec budgeted Phase 1 at "roughly one focused week" and committed to three more phases: observability, real authentication and a hosted deploy (Phase 2), an outbox with Kafka (Phase 3), and a thin UI (Phase 4).

On 2026-10-27, over five weeks in (the spec is dated 2026-09-17), five of Phase 1's seven plans were built. The measured state on 2026-10-23:
- 5 code PRs against 21 docs PRs;
- about 3,300 lines of application code and 5,700 lines of tests, against about 17,000 lines of plans, spec, journal, ADRs and the interview guide (about 16,000 without the guide).

Most of the time went to process and to hardening edge cases, not to the problem itself.

FrontRow will never be used for real. It exists to demonstrate Java and Spring Boot, and the provider side of an agent-facing API (spec §1). How much of the remaining scope does that purpose need?

## Decision Drivers

* **The audience reads; it doesn't use.** A recruiter or interviewer judges the repo, the README and a recorded demo. Nobody runs a production workload.
* **The differentiators are already built or scoped:** the schema-level no-double-booking guarantee and its concurrency tests (ADR-001), agents hold and humans buy (ADR-002), and the MCP evidence artifacts (spec §7).
* **The research in spec §1:** a small complete thing beats a half-finished everything.
* **A public deploy on demo-grade credentials would overstate the security story** (spec §2), so a live demo means real authentication first. That is weeks of work for something few reviewers would click.
* **Finishing matters more than breadth.** Each extra phase or plan carries a fixed cost in plan, review and docs PRs.

## Considered Options

How it is presented:
* **A. A repo walkthrough, local only.**
* **B. A live hosted demo,** which needs Phase 2's real authentication and deploy.
* **C. A walkthrough now, with a live demo as an uncommitted possible next step.**

The later phases:
* **Drop them into "Possible next steps"**, keep one small follow-on (MCP tool metrics), or keep them as deferred phases.

The quality bar:
* **Fix everything found**, a **portfolio bar** with a "Known limitations" list, or the **core guarantee only**.

The remaining REST surface:
* **Keep it all**, **trim to the demo story**, or keep only the minimum the demo needs.

## Decision Outcome

**Chosen:**
- **C**, the walkthrough, with the live demo as a possible next step;
- **drop Phases 2–4** into "Possible next steps";
- **the portfolio bar**;
- **trim the REST surface to the demo story**, whose organiser arc keeps `POST /api/events` (spec §7).

Each choice serves the walkthrough audience and keeps the differentiators whole.

- **Scope.** Phase 1 is the whole project. Still to build:
  - the read endpoints, event creation, Swagger UI, seed data and `docker compose` (plan 4B);
  - the five MCP tools and structured transport errors (plan 5);
  - the evidence: checked-in tool schemas with a CI drift test, a real logged failure-and-recovery run, a demo recording, and a README with an architecture diagram, a short threat model, four headline metrics, "Known limitations" and "Possible next steps".
- **Cut:**
  - `GET /api/orders/{id}` and the `ADMIN` role, which nothing else used. `GET /api/me/orders` shows the purchase, and release and confirm already demonstrate owner scoping in the service.
  - The separate `docs/mcp/architecture.md`, now a README section.
  - Most README metrics.
- **Kept, against the earlier trim order:** `POST /api/events`. Without it, the event `PATCH` that is already built only ever acts on seeded events. With it, the organiser side is a complete story: create a draft, put it on sale, sell it, cancel it.
- **The portfolio quality bar** (spec §9) sorts every finding into one of two kinds:
  - **A defect is always fixed, or the behaviour is removed.** A defect is shipped behaviour (code on `main`) that contradicts a claim in the spec, an ADR or the README. That includes every spec §6 role-table row and owner-scoping rule. The criteria below don't apply, and a defect is never listed as a limitation.
  - **The three criteria gate only hardening:** findings where the behaviour matches every documented claim but could be stronger. A hardening finding is fixed and tested only if it is:
    - part of the core guarantee: no double-booking, no purchase over MCP, the disjoint chains, the §6 role table, and owner scoping (no buyer acts on another buyer's hold or order);
    - visible in the demo story;
    - an obvious flaw to someone reading the code.

  Hardening that meets none of the three goes into the README's "Known limitations", with a line on why. Limitations are not filed as GitHub issues, because open issues on a portfolio repo read as unfinished work.

- **Why not B.** It needs real authentication and a deploy before anything can be clicked. That is most of the old Phase 2, for an audience that mostly reads.
- **Why not keep the phases.** A committed phase that will never start reads as an unfinished project. A "Possible next steps" list reads as a deliberate stopping point, and any item can still be revived.
- **Why not fix everything.** In this project, review findings have turned into hardening work: framework-level edge cases an interviewer is unlikely to probe. A visible list of deliberate trade-offs shows the same judgment for a fraction of the cost.
- **Why not keep one small follow-on (MCP tool metrics).** One more talking point is not worth reopening the phase list. The measured success rate and p95 latency stay a possible next step, so spec goal 3 names the parts of the MCP bar FrontRow meets.
- **Why not the core guarantee only.** Too narrow. Flaws a reviewer sees in the demo, or spots when reading the code, are what a reviewer judges the project by.
- **Why not keep all of REST.** `GET /api/orders/{id}` adds review weight for nothing the demo needs: `GET /api/me/orders` already shows the purchase.
- **Why not the minimum the demo needs.** It would lose `GET /api/me/orders`, the visible result of the purchase.

### Consequences

* ✅ The project has a definite end: plans 4B and 5 against spec §12.
* ✅ The differentiators are untouched. Nothing already on `main` is removed except, in plan 4B: the `/api/orders/*` rule, the `ADMIN` role, the `adam` user (`DemoCredentials.java` and `TestCredentials.java`) and the `/api/orders/x` test assertions. Tests that use `adam` but are not about `ADMIN`, such as `SecurityChainsTest#anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser`, are repointed at another user, not deleted.
* ✅ "Local only" is made true by construction: plan 4B publishes the compose port on `127.0.0.1` only and sets `server.address: 127.0.0.1` in the demo profile (spec §3).
* ✅ ADR-002's consequence "Real authentication (OAuth2 / OIDC) is Phase 2" becomes a possible next step; ADR-002 carries a dated note.
* ✅ "Known limitations" turns accepted gaps into a talking point instead of a backlog.
* ⚠️ No live demo. Reviewers have to run `docker compose` or watch the recording.
* ⚠️ No observability, Kafka or OAuth2 evidence from this project. Those signals would have to come from another project, or from reviving a next step.
* ⚠️ The portfolio bar is a judgment call per finding. The three criteria keep it checkable, but "obvious to someone reading the code" still needs a reviewer to apply it honestly.
