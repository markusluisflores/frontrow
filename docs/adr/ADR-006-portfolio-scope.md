# ADR-006: FrontRow is a finished, local-only portfolio project

**Date:** 2026-10-27
**Status:** Accepted

Decided by Markus on 2026-10-27 in a brainstorming session, choosing from the options below at each step. Recorded in spec revision 2.7 (§1, §2, §3, §5, §6, §7, §8, §9, §10, §11, §12 and §13; see its §14 row).

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

For security findings under the portfolio bar (added 2026-10-28, during PR #33's review):
* **An approved, closed list** of exemptions, each approved by Markus, or **an "exploitable" test** that a reviewer applies per finding.

The remaining REST surface:
* **Keep it all**, **trim to the demo story**, or keep only the minimum the demo needs.

## Decision Outcome

**Chosen:**
- **C**, the walkthrough, with the live demo as a possible next step;
- **drop Phases 2–4** into "Possible next steps";
- **the portfolio bar**, with an approved closed list for security findings (2026-10-28);
- **trim the REST surface to the demo story**, whose organiser arc keeps `POST /api/events` (spec §7).

Each choice serves the walkthrough audience and keeps the differentiators whole.

- **Scope.** Phase 1 is the whole project. Still to build (spec §12 is the checklist):
  - the read endpoints, event creation, Swagger UI, seed data and `docker compose` (plan 4B);
  - the five MCP tools and structured transport errors (plan 5);
  - the evidence: checked-in tool schemas with a CI drift test, a real logged failure-and-recovery run, a demo recording, and a README with an architecture diagram, a short threat model, four headline metrics, "Known limitations" and "Possible next steps".
- **Cut:**
  - `GET /api/orders/{id}` and the `ADMIN` role, which nothing else used. `GET /api/me/orders` shows the purchase, and release and confirm already demonstrate owner scoping in the service.
  - The separate `docs/mcp/architecture.md`, now a README section.
  - Most README metrics.
- **Kept, against the earlier trim order:** `POST /api/events`. Without it, the event `PATCH` that is already built only ever acts on seeded events. With it, the organiser side is a complete story: create a draft, put it on sale, sell it, cancel it.
- **The portfolio quality bar** (spec §9). Every finding is a defect, a security finding or a hardening finding. Defects are always fixed; a security finding is a defect unless spec §9 lists it as an approved exemption, and those lists change only with Markus's approval; hardening is fixed only if it meets one of three criteria, otherwise it goes into the README's "Known limitations". No limitation is filed as a GitHub issue, because open issues on a portfolio repo read as unfinished work. Spec §9 is the only statement of the bar and its lists; this ADR does not copy them.

- **Why not B.** It needs real authentication and a deploy before anything can be clicked. That is most of the old Phase 2, for an audience that mostly reads.
- **Why not keep the phases.** A committed phase that will never start reads as an unfinished project. A "Possible next steps" list reads as a deliberate stopping point, and any item can still be revived.
- **Why not fix everything.** In this project, review findings have turned into hardening work: framework-level edge cases an interviewer is unlikely to probe. A visible list of deliberate trade-offs shows the same judgment for a fraction of the cost.
- **Why an approved list, not an "exploitable" test.** "Exploitable" needs a definition, and the spec's own limitations sorted either way under any short one, so two reviewers could apply it in opposite ways. A closed list, each entry with its reason and Markus's approval, gives one reading: a security finding not on it is a defect.
- **Why not keep one small follow-on (MCP tool metrics).** One more talking point is not worth reopening the phase list. The measured success rate and p95 latency stay a possible next step, so spec goal 3 names the parts of the MCP bar FrontRow meets.
- **Why not the core guarantee only.** Too narrow. Flaws a reviewer sees in the demo, or spots when reading the code, are what a reviewer judges the project by.
- **Why not keep all of REST.** `GET /api/orders/{id}` adds review weight for nothing the demo needs: `GET /api/me/orders` already shows the purchase.
- **Why not the minimum the demo needs.** It would lose `GET /api/me/orders`, the visible result of the purchase.

### Consequences

* ✅ The project has a definite end: plans 4B and 5 against spec §12.
* ✅ The differentiators are untouched. On `main`, plan 4B removes only what this decision cut: the `GET /api/orders/*` rule, the `ADMIN` role and the `adam` user, with their tests.
* ✅ What plans 4B and 5 owe, including the defects on `main` that review of this decision surfaced, is one checklist: spec §12.
* ✅ "Local only" holds by construction for compose, on Docker Engine 28.0 or later; by the documented command otherwise (spec §3).
* ✅ ADR-002's consequence "Real authentication (OAuth2 / OIDC) is Phase 2" becomes a possible next step; ADR-002 carries a dated note.
* ✅ "Known limitations" turns accepted gaps into a talking point instead of a backlog.
* ⚠️ No live demo. Reviewers have to run `docker compose` or watch the recording.
* ⚠️ No observability, Kafka or OAuth2 evidence from this project. Those signals would have to come from another project, or from reviving a next step.
* ⚠️ The hardening part of the bar is a judgment call per finding. The three criteria keep it checkable, but "obvious to someone reading the code" still needs a reviewer to apply it honestly. Security findings carry no such judgment: they are defects unless spec §9 lists them as approved.
