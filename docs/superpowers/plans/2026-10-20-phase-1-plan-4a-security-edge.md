# FrontRow Phase 1, Plan 4A (The Security Edge and the REST Write Endpoints) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the services plans 3A and 3B built behind a real HTTP edge. That means three Spring Security chains, hashed demo credentials, Problem Details errors, request bounds, and the four REST write endpoints: hold, release, confirm (the only purchase path) and the event `PATCH`. Prove the chains are disjoint and the edge holds, against real Postgres.

**Architecture:** Two new packages sit beside the services.
- **`security`** holds the chains, the filters and the credentials.
- **`rest`** holds the controllers, the error renderer and the body-size filter.

The domain core gets no Spring Web or Security type. Two service-side changes are deliberate (decisions 4 and 8):
- every service entry point refuses to join a caller's transaction;
- the event `PATCH` bounds the instants it accepts.

Phase 1 is now seven plans:
- 1, 2, 3A and 3B are built;
- **4A (this plan):** the security edge and the REST write endpoints;
- **4B:** the read services and the six `GET` endpoints, event creation (`POST /api/events`), and springdoc / Swagger UI;
- **5:** the MCP tools and the §7 evidence artifacts.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Framework 7.0.9, Spring Security 7.1.1, Jackson 3.1.5, Tomcat 11.0.24), Postgres 18, JUnit Jupiter 6, AssertJ, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.6.4). Read:
- §3, *Caller identity*: the two credential types, the explicit bearer-reject filter, `AGENT`, and the anonymous paths;
- §6: *The threat model*, *Guardrails on the write path*, *Error contracts* and *REST surface*;
- §8, the *Security tests* row;
- §9, *Demo-grade security*;
- §12, the security and no-raw-token items.

ADR-002 (`docs/adr/ADR-002-agents-hold-humans-buy.md`) is the decision record for the chains, and ADR-004 is the principal-propagation answer that the `/mcp` chain keeps working.

**Other inputs.** Each one is closed by a task named in the coverage tables at the end. They are the Plan 4 items of the journal's 2026-10-18 / 10-19 Pending list:
- event creation has no service;
- REST `Idempotency-Key` naming (PR #18 thread 4296541034);
- owner validation (4296749409);
- the iteration order of `details()` (4296749419);
- `Propagation.NEVER`, or adapters that are never `@Transactional` (4296540451);
- out-of-range `PATCH` instants (PR #22 thread 4305094277);
- who may PATCH;
- an organiser can't GET their own `DRAFT`;
- a generic 500 for anything that is not a `DomainException`;
- human and agent principals never mapping to the same owner string;
- bounding request size at the adapter.

## Global Constraints

- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). The `domain` package imports only `java.*`; check with the grep in `CLAUDE.md`. No task here touches `domain`.
- **All lock SQL lives in `persistence/LockingGateway.java`** (ADR-005), comments included. Check with the grep in `CLAUDE.md`. No task here adds lock SQL.
- **The owner always comes from the authenticated principal,** never from a parameter or a header (spec §3, §6). A controller passes `Principal#getName()` and nothing else.
- **No usable credential is ever committed** (spec §12): no password, no raw token, not even a test one. Tests generate theirs per run (decision 5).
- **`/api/**` accepts HTTP Basic only. `/mcp` accepts an agent bearer token only.** A bearer header on `/api/**` is refused with 401, on anonymous endpoints too (spec §3, ADR-002).
- **No MCP tool may complete a purchase** (ADR-002). This plan adds no tool; `/mcp` authenticates and serves nothing new.
- **REST errors are RFC 9457 Problem Details** (spec §6). A `DomainException` carries `code`, `hint` and its details. Anything else is a generic 500 that echoes nothing.
- Real Postgres only (`postgres:18-alpine` via Testcontainers). Never H2, never a mocked repository.
- Java 25, `-Xlint:all,-processing -Werror` (tests included), Spotless (palantir), SpotBugs. `./mvnw verify` is the test command, and it must be green at every commit.
- **The formatter decides layout.** Every code block below is the formatter's output, taken from a build of this plan. The Spotless check runs at `validate`, so every command below starts with `spotless:apply`. A file written with LF line endings fails the check on this Windows checkout until it is applied, before any test runs. Palantir does **not** reflow javadoc; keep new javadoc within 120 columns by hand.
- **Write every file as UTF-8.** A Windows tool that defaults to cp1252 turns `§` into a byte that is not UTF-8. Plan 3B's Task 2 and this plan's own authoring each hit this once.
- Commit messages: `<type>(<scope>): <subject>`, 72 characters max, no trailing period (`.githooks/commit-msg`). No local device path in a committed file.

## Plan-level decisions

Decisions 1, 2, 3 and 5 are Markus's (brainstorm, 2026-10-20). Decision 4 replaces one of his, for the reason it gives. The rest are this plan's. Each is listed so a reviewer can reject it individually.

1. **Plan 4 is split into 4A and 4B** (Markus). 4A is the security edge and the four write endpoints whose services exist. It is **Mandatory** tier. 4B is Standard tier:
   - the read services, which plan 5's three read tools reuse, so they belong in `service/`;
   - the six `GET` endpoints;
   - event creation: **plan 4B builds the `POST /api/events` service**, which closes the "names the plan that does" input;
   - springdoc / Swagger UI.

   4A's `/api/**` chain already carries the whole spec §6 role table, 4B's rows included (decision 10), so 4B adds controllers and no security rule.
2. **Any `ORGANISER` may PATCH any event** (Markus). This is spec §6 as written: a role gate, with no per-event ownership, because the schema records no event owner. The limit is stated plainly here and in `EventController`'s javadoc. The README (plan 5) must say it too. Per-event ownership goes to Phase 2, with real authentication.
   - **Consequence for 4B:** an organiser still can't `GET` their own `DRAFT`. Spec 2.6.3 makes a draft `not_found` to every caller, and "own" has no data behind it. The organiser sees the draft in the `POST` and `PATCH` responses. This closes the "can't GET their own draft" input as a stated limit, not a feature.
   - The `BUYER`, `ADMIN` and agent-token cases are all refused, and Task 4 tests each one.
3. **An owner is the Basic username, for both credentials** (Markus). The agent's principal name is its user's username, so a user and their own agent share the owner, the hold cap and the idempotency-key namespace. Spec §6's handover needs that: X sees and confirms what X's agent held. It also gives a compromised agent more reach than spec §6's one-line threat model states; the follow-ups below carry that to plan 5's README.
   - **Read this way, PR #18's `/security-review` note bans accidental aliasing between different principals.** A user deliberately sharing an owner with their own agent is not aliasing. A human and their agent reusing one key replay each other's hold, which is the same owner's hold either way.
   - **No two principals can alias.** Every username must match `^[a-z0-9][a-z0-9._-]{0,63}$`, checked at startup by `@Validated` binding. So there are no case or Unicode look-alikes, no U+0000 and no unpaired surrogate. That closes thread 4296749409 where users are created, without changing the service.
   - **Each token's config entry names a username.** Startup fails if that user is missing or lacks `BUYER` (spec §3: tokens are issued only to `BUYER` users).
4. **Every service entry point calls `TransactionBoundary.requireOwnTransaction()` as its first statement**, and keeps `REQUIRED` propagation with `READ_COMMITTED` (Markus chose this after the correction below). It throws `IllegalTransactionStateException` if the transaction it runs in was joined rather than started, so an outer transaction fails loudly as a 500, never as a silent loss of isolation (thread 4296540451).
   - **`Propagation.NEVER` is rejected, with evidence.** It was the thread's suggestion and Markus's first choice, and it is wrong about Spring: `NEVER` runs the method *with no transaction at all*. The authoring build tried it: all 11 `EventPatchServiceTest` tests broke. `LockingGateway` is `MANDATORY`, so its calls threw before any lock was taken; the lock-timeout test's PATCH never blocked, and no `READ_COMMITTED` was ever applied. Spring has no propagation for "start one, and fail if one exists".
   - **What proves it.** The check is a chain: scan, list, boundary test. `TransactionIsolationTest#theListIsEveryServiceInThePackage` scans `service/` for `@Service` classes, with no application context, and fails unless they equal its hand-written `SERVICES` list. `ServiceTransactionBoundaryTest` calls all six entry points of the listed services inside an outer transaction, and its reflection check fails the build if a listed service's public method isn't covered. So a new service fails the build until it is listed and its entry points are covered. With the guard on one service only, the other services' calls joined the outer transaction silently, which is the thread's bug shown live.
   - **Rejected: a rule plus an architecture test that no adapter is `@Transactional`.** A `TransactionTemplate`, or a service calling a service, slips past it.
   - **At merge,** the reply on thread 4296540451 corrects its `NEVER` suggestion, so the next reader doesn't repeat it.
5. **Demo credentials are hashes only** (Markus). The committed `application.yml` defines no user and no token. Startup fails without them, by design: a Basic chain with no users is a misconfiguration.
   - **`scripts/demo-credentials.sh`** runs `security/DemoCredentials`. It writes bcrypt password hashes and SHA-256 token hashes to `config/application-demo.yml`, which is gitignored, and prints the raw values once. The app runs with the `demo` profile. Spring Boot reads `./config/application-{profile}.yml` from the working directory, so the file is not on the classpath and is not in the jar.
   - **Tests generate their own credentials once per JVM run.** `support/TestCredentials` holds the random values. Its nested `EnvironmentPostProcessor`, registered in `src/test/resources/META-INF/spring.factories`, adds their hashes to every `SpringApplication` the tests start, so every test context has users without any committed value. This replaces plan 1's spike approach (a per-run UUID in one test) with one used everywhere.
   - **The test users:** `alice` and `bob` (`BUYER`), `carol` (`BUYER`, `ORGANISER`), `olivia` (`ORGANISER`) and `adam` (`ADMIN`). `alice` and `carol` hold tokens. `carol` exists so a test can show a token for a user who is also an organiser carries no organiser power.
6. **CSRF: Spring's CSRF token is off on every chain,** which is right for a stateless API with no session and no cookie. But Basic credentials become ambient once a browser has cached them after a `WWW-Authenticate: Basic` challenge. A hostile page could then make the browser send an unsafe request to `/api/holds/{id}/confirm`, which needs no body, with the credentials attached. A cross-site form `POST` does that, and so do a `fetch` with `mode: 'no-cors'` and a `navigator.sendBeacon`, which can send a bodyless `POST` with no `Content-Type` at all. Three layers stand against it:
   - **No 401 carries a Basic challenge,** so a browser never prompts and never caches the credentials. Swagger UI and `curl` send the header explicitly. RFC 9110 expects a challenge on a 401; omitting it is the documented trade.
   - **Fetch Metadata.** An unsafe method on `/api/**` (anything but `GET`, `HEAD`, `OPTIONS` and `TRACE`) whose `Sec-Fetch-Site` is `cross-site` or `same-site`, in any case, is refused with 403. This is the layer that catches the bodyless request. `same-origin`, `none` (a user-initiated navigation) and an absent header pass, because `curl` and other non-browser clients send none.
   - **Form types.** Unsafe methods on `/api/**` refuse form-submittable content types (`application/x-www-form-urlencoded`, `multipart/form-data`, `text/plain`) with 415. Those are the only types a cross-site form can send without a CORS preflight, and no CORS is configured, so a preflight fails. An unparseable `Content-Type` is refused too.

   Both refusals live in one filter, `security/CrossSiteWriteFilter`, ahead of Basic authentication. **A browser that sends no Fetch Metadata relies on the first layer alone** against a request with no form type, such as a bodyless `no-cors` `fetch` or a `sendBeacon`. That includes any browser reaching the app over plain HTTP on a host other than `localhost`: browsers send Fetch Metadata only to potentially trustworthy origins (HTTPS, or `localhost`). Phase 1 runs on `localhost` and Phase 2 is meant for HTTPS, so both get the second layer. If such a browser has cached the credentials by some other route, that request gets through.

   **CodeQL.** The plan expects CodeQL to raise alert #1's class of finding (`java/spring-disabled-csrf-protection`) again on the new chains. These layers are an argument to present to Markus for dismissing it, not a certainty that the finding is false: the gap just named is real. Dismissing it stays his call.
7. **Errors** (`rest/Problems`, `rest/RestExceptionHandler`):
   - **Statuses:**

     | Code | Status |
     |---|---|
     | `invalid_request`, `too_many_seats` | 400 |
     | `not_found` | 404 |
     | `seat_taken`, `hold_limit_exceeded`, `hold_expired`, `hold_not_active`, `sales_not_open`, `sales_closed` | 409 |
     | `idempotency_key_reused` | 422, the IETF Idempotency-Key draft's status for a key reused with a different request |
     | `contention_retry` | 503, with `Retry-After: 1` |

     The mapping is a switch expression with no default, so a new `ErrorCode` is a compile error.
   - **Members:** `code`, `hint` and every detail are extension members, in key order (thread 4296749419). Every test compares bodies as parsed JSON trees, never strings. Spring 7 leaves `type` unset, which RFC 9457 reads as `about:blank`. The filter-side writer, `Problems.write` (the body filter, the security filters, and every chain's 401 and 403), does the same, and sets `instance` to the request URI as the MVC converter does. So for the same problem, a filter's body and a handler's body have the same members: `ProblemsTest#aFilterWritesTheSameShapeAsTheConverter` renders one problem through both and compares the parsed trees.
   - **Parameter names:** the service names MCP's parameter, so the renderer renames `idempotency_key` to `Idempotency-Key` (thread 4296541034). A missing header is named the same way.
   - **Framework failures become `invalid_request` with a `parameter`:**
     - unreadable JSON → `body` (a literal `null` body too: Spring refuses a required body that is null);
     - the first JSON property on Jackson's error path, already snake_case → that property, for example `status` for an unknown enum constant;
     - a mistyped path variable → its snake_case name (`hold_group_id`, `event_id`).

     `HoldRequest` marks its list `@JsonSetter(contentNulls = Nulls.FAIL)`, so a null seat id is named `event_seat_ids`. Without it, `List.copyOf` would throw inside Jackson's constructor call, and only `body` would be named.
   - **Everything else is a generic 500** with no detail, logged server-side. That covers any `RuntimeException`, `IllegalStateException` (they can carry internal ids) and `DataIntegrityViolationException`, which is never guessed into a conflict code.
   - **401, 403, 413 and 415 are Problem Details bodies with no `code`.** Spec §6's codes are domain codes.
8. **Bounds.**
   - **Bodies:** `rest/RequestBodyLimitFilter` is registered at `Ordered.HIGHEST_PRECEDENCE`, ahead of every security chain, on every path. It refuses a declared `Content-Length` over **16 KiB** with 413 before reading. A chunked body is counted as it is read, through `getInputStream()` or `getReader()`, and the read that crosses the limit fails. The count lives on the request, so asking for the stream again can't reset it. That bounds what `HoldCommand` copies (the handoff's "bound request size" input).
   - **Boot's `FormContentFilter` is off** (`spring.mvc.formcontent.filter.enabled: false`, security review of PR #28). It reads a form body on `PUT`, `PATCH` and `DELETE` before Spring Security runs, so a chunked form body over the bound became a 500 with an ERROR stack trace, for anonymous callers, on any path. No endpoint takes a form body. With it off, Task 6 shows that request gets the security answer instead: 415 on `/api/**`, 401 elsewhere.
   - **Headers:** Tomcat's default 8 KiB header limit bounds `Idempotency-Key` before the service's 200-character check. Task 6 proves both bounds over a real socket.
   - **PATCH instants:** `EventPatchService` refuses an instant outside `0001-01-01T00:00:00Z` … `9999-12-31T23:59:59.999999Z` as `invalid_request` with `parameter: sales_window`, before the instant reaches any SQL (PR #22 thread 4305094277). That is what both `timestamptz` and a four-digit ISO 8601 year hold. It lives in the service, not the adapter, because the PATCH is the one write path that takes caller-supplied instants, whichever adapter calls. A test stores the widest window and reads it back exactly.
9. **The wire is snake_case.** `spring.jackson.property-naming-strategy: SNAKE_CASE` matches MCP's parameter names and the error details. `HoldService.STORED_FORM` is deliberately independent of the application mapper (plan 3A decision 6), so replay of stored rows is unaffected. The MCP server renders its successful responses with its own mapper, and the spike test still passes. **A transport error on `/mcp` is not the MCP server's, though** (security review of PR #28): malformed JSON, or a chunked body over 16 KiB that the body filter refuses as it is read, is rendered by the application's mapper (a declared length over 16 KiB is refused by the body filter itself, with a clean 413), and that body includes a `stack_trace`. 4A serves no tool and leaves this to plan 5, as a requirement in the follow-ups below.
10. **The chains** (`security/SecurityConfig`):

    | Order | Matcher | Takes | Notes |
    |---|---|---|---|
    | 1 | `/api/**` | Basic | `BearerRejectFilter` and `CrossSiteWriteFilter` run before Basic authentication. The whole spec §6 table; any unlisted `/api/**` path is denied |
    | 2 | `/mcp`, `/mcp/**` | Agent bearer token | `AgentTokenFilter` authenticates a known token's user with `AGENT` only. The chain installs no Basic filter, so a Basic header authenticates nothing |
    | 3 | none, `LOWEST_PRECEDENCE` | — | `/error`, `/actuator/health`, `/swagger-ui/**` and `/v3/api-docs/**` anonymous (spec §3); everything else denied (ADR-002) |

    - **Stateless:** every chain is stateless, with no request cache and no logout endpoint, and answers with the Problem Details 401 and 403. There is no session to end, and Spring's default `/logout` would otherwise answer anonymously on every chain.
    - **Users:** a `UserDetailsService` bean built from the credentials replaces Boot's generated `user`. Without it, that user would sit in the parent authentication manager and authenticate on `/api/**` as a fallback.
    - **Actuator:** `spring-boot-starter-actuator` is added, with only `health` exposed, so the catch-all's one actuator path is real and tested.
    - **ADR-002:** its stale "Plan 4 must add a catch-all chain" line becomes a statement of what was built, and its header says what is implemented. Its "at worst tie up seats" consequence gains decision 3's qualification, and its plan-numbering note names the seven-plan Phase 1.
11. **Plan 1's spike test moves onto the real `/mcp` chain.** Its own chain would collide with the new one. Its tools and its transport provider stay, its token becomes `TestCredentials.token("alice")`, and ADR-004's two mechanisms are re-proved through the production chain.
12. **SpotBugs: no new exclusion.** Every record that holds a collection copies it with `List.copyOf` or `Set.copyOf`. SpotBugs recognises those copies and not `Collections.unmodifiableList`, as the authoring build found.

**Review and execution tier: Mandatory** (spec header; decision 1). This is auth work behind a published contract. Execution gets the full layer set:
- per-task two-stage reviews;
- the `reviewer` agent and the `security-reviewer` agent;
- `/security-review`.

**Follow-ups this plan leaves open, each named so it isn't lost:**
- the post-4A spec sync: decisions 3 to 10 fill gaps rev 2.6.4 leaves open, on its own docs branch;
- ADR-002's "⚠️ … unproven: the scaffold spike must show how the principal propagates" bullet, which ADR-004 has since answered;
- the README statements decisions 2 and 6 require (plan 5);
- **a plan 5 requirement: MCP error bodies carry no stack trace** (decision 9). Plan 5 adds a test that sends malformed JSON and an oversized **chunked** body (a declared length gets the body filter's clean 413) to `/mcp` with a valid token, and checks that neither response carries a `stack_trace`;
- **two risks in Markus's decisions, for plan 5's README and threat model to state** (security review of PR #28; reported, not re-decided):
  - decision 3's shared owner means a compromised agent can also release the human's own holds, fill the human's hold-group cap and replay the human's idempotency keys. That is wider than spec §6's one-line threat model. Confirm is tied only to a hold id, so the human confirms whatever that hold contains;
  - decision 2 means one compromised organiser account can cancel every event.

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `src/main/java/.../service/TransactionBoundary.java`, and the five services' entry points | Each entry point starts its own transaction (decision 4) | 1 |
| `src/main/java/.../service/EventPatchService.java` | The PATCH's instant bounds (decision 8) | 1 |
| `src/main/java/.../security/Role.java`, `CredentialProperties.java`, `Credentials.java`, `CredentialsConfig.java` | Validated, hashed credentials and the startup checks (decisions 3, 5) | 2 |
| `src/main/java/.../security/DemoCredentials.java`, `scripts/demo-credentials.sh`, `.gitignore` | The local demo's generated credentials | 2 |
| `src/test/java/.../support/TestCredentials.java`, `src/test/resources/META-INF/spring.factories` | Every test context's per-run credentials | 2 |
| `src/main/java/.../rest/Problems.java`, `RestExceptionHandler.java` | Problem Details, statuses and parameter names (decision 7) | 3 |
| `src/main/java/.../rest/RequestBodyLimitFilter.java`, `RestConfig.java` | The 16 KiB body bound (decision 8) | 3 |
| `src/main/java/.../security/SecurityConfig.java`, `BearerRejectFilter.java`, `CrossSiteWriteFilter.java`, `AgentTokenFilter.java` | The three chains and their filters (decisions 6, 10) | 4 |
| `pom.xml`, `src/main/resources/application.yml` | Actuator and the test starters; snake_case, actuator exposure | 2, 3, 4 |
| `src/test/java/.../mcp/PrincipalPropagationSpikeTest.java` | Re-proved on the real `/mcp` chain (decision 11) | 4 |
| `docs/adr/ADR-002-agents-hold-humans-buy.md` | What was built | 4 |
| `src/main/java/.../rest/HoldController.java`, `EventController.java` | Hold, release, confirm and the event PATCH | 5 |
| `src/test/java/.../rest/RequestBoundsOverHttpTest.java` | The bounds over a real socket | 6 |
| `CLAUDE.md` | Current state and three new architecture rules | 7 |

`...` is `io/github/markusluisflores/frontrow`. Each task's code blocks are exact.

**How the code blocks below work.** A new file is given whole, and so is a modified file of 25 lines or fewer. A larger file is changed by **find and replace**: each "find" block occurs exactly once in the file as the previous blocks left it, and the "replace" block includes the lines around the change. Apply them in order. `docs/` and `CLAUDE.md` blocks use four-backtick fences.

**How this plan was checked.** The code was built first, as seven commits on a scratch branch off `main` (`683a308`), and the blocks below were generated from them. A replay harness then applied this document's own blocks and commands, step by step, to a fresh worktree of `main`. It checked each command's stated outcome, and at each commit that the tree equals the scratch commit's tree. The results are at the end of this plan.

---

### Task 1: Every service entry point starts its own transaction, and the PATCH bounds its instants

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/TransactionBoundary.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java` (test)
- Create: `src/test/java/io/github/markusluisflores/frontrow/service/ServiceTransactionBoundaryTest.java` (test)
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java` (test)

**Interfaces:**
- Consumes: the five services plans 3A and 3B built, and `TransactionIsolationTest.SERVICES`.
- Produces: `TransactionBoundary.requireOwnTransaction()`, package-private in `service`. `EventPatchService.EARLIEST` and `LATEST` are package-private `Instant`s. `TransactionIsolationTest.SERVICES` becomes package-private, so `ServiceTransactionBoundaryTest` can read it, and a new test checks it against a scan of the package. No public signature changes.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java`, find:

```java
import java.time.Instant;
import java.util.List;
```

Replace it with:

```java
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java`, find:

```java

    private static DomainException rejection(ThrowingCallable call) {
```

Replace it with:

```java

    @Test
    void anInstantPostgresCannotStoreIsInvalidRequestNotA500() {
        // Year 300000 is past timestamptz's upper bound: unbounded, Postgres raises 22008, which surfaces as a 500
        // (PR #22 thread 4305094277). Instant.MIN is past the lower bound.
        for (EventPatch outOfRange : List.of(
                new EventPatch(eventId, null, null, Instant.parse("+300000-01-01T00:00:00Z")),
                new EventPatch(eventId, null, Instant.MIN, null),
                new EventPatch(eventId, null, EventPatchService.EARLIEST.minusNanos(1000), null),
                new EventPatch(eventId, null, EventPatchService.LATEST, EventPatchService.LATEST.plusNanos(1000)))) {
            DomainException rejected = rejection(() -> patches.patch(outOfRange));
            assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(rejected.details()).containsEntry("parameter", "sales_window");
        }
    }

    @Test
    void theWidestAllowedWindowIsStoredExactly() {
        EventPatchResult result =
                patches.patch(new EventPatch(eventId, null, EventPatchService.EARLIEST, EventPatchService.LATEST));

        assertThat(result.salesOpenAt()).isEqualTo(EventPatchService.EARLIEST);
        assertThat(result.salesCloseAt()).isEqualTo(EventPatchService.LATEST);
        assertThat(jdbc.sql("SELECT sales_open_at, sales_close_at FROM event WHERE id = :id")
                        .param("id", eventId)
                        .query((rs, row) -> List.of(
                                rs.getObject(1, OffsetDateTime.class).toInstant(),
                                rs.getObject(2, OffsetDateTime.class).toInstant()))
                        .single())
                .containsExactly(EventPatchService.EARLIEST, EventPatchService.LATEST);
    }

    private static DomainException rejection(ThrowingCallable call) {
```

Create `src/test/java/io/github/markusluisflores/frontrow/service/ServiceTransactionBoundaryTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every service entry point refuses to join a caller's transaction (plan 4A decision 4). The guard is the method's
 * first statement, so no call here needs real data. A new public service method fails everyEntryPointIsCovered until
 * it is added to calls().
 */
@SpringBootTest
@Import(ServiceTestConfiguration.class)
class ServiceTransactionBoundaryTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    ReleaseService releases;

    @Autowired
    EventPatchService patches;

    @Autowired
    ExpirySweepService sweeper;

    private Map<String, Runnable> calls() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("HoldService#hold", () -> holds.hold(new HoldCommand("alice", 1, List.of(1L), "k1")));
        calls.put("ConfirmService#confirm", () -> confirms.confirm(new HoldGroupCommand("alice", UUID.randomUUID())));
        calls.put("ReleaseService#release", () -> releases.release(new HoldGroupCommand("alice", UUID.randomUUID())));
        calls.put("EventPatchService#patch", () -> patches.patch(new EventPatch(1, null, null, null)));
        calls.put("ExpirySweepService#expireLapsedHolds", sweeper::expireLapsedHolds);
        calls.put("ExpirySweepService#purgeHoldRequests", sweeper::purgeHoldRequests);
        return calls;
    }

    @Test
    void everyEntryPointRefusesAnOuterTransaction() {
        calls().forEach((name, call) -> assertThatThrownBy(() -> tx.executeWithoutResult(status -> call.run()))
                .as(name)
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessage("a service entry point must start its own transaction"));
    }

    @Test
    void everyEntryPointIsCovered() {
        assertThat(calls().keySet())
                .containsExactlyInAnyOrderElementsOf(TransactionIsolationTest.SERVICES.stream()
                        .flatMap(service -> Arrays.stream(service.getDeclaredMethods()))
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(ServiceTransactionBoundaryTest::name)
                        .collect(Collectors.toSet()));
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
```

Replace it with:

```java
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
 * Postgres defaults to READ COMMITTED, so a forgotten setting fails no behavioural test — which is why spec §5's
 * requirement is checked here instead. Plan 3B appends its services to the list.
 */
class TransactionIsolationTest {

    private static final List<Class<?>> SERVICES = List.of(
            HoldService.class,
```

Replace it with:

```java
 * Postgres defaults to READ COMMITTED, so a forgotten setting fails no behavioural test — which is why spec §5's
 * requirement is checked here instead. Plan 3B appends its services to the list. Joining a caller's transaction
 * would defeat the setting, so ServiceTransactionBoundaryTest proves each listed method refuses one (plan 4A). The list
 * itself is checked against a scan of this package, so a new service that nobody lists fails the build.
 */
class TransactionIsolationTest {

    static final List<Class<?>> SERVICES = List.of(
            HoldService.class,
```

In `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`, find:

```java
            ExpirySweepService.class);
```

Replace it with:

```java
            ExpirySweepService.class);

    @Test
    void theListIsEveryServiceInThePackage() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        Set<String> scanned = scanner.findCandidateComponents(TransactionIsolationTest.class.getPackageName()).stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        assertThat(scanned)
                .isNotEmpty()
                .isEqualTo(SERVICES.stream().map(Class::getName).collect(Collectors.toSet()));
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./mvnw spotless:apply test -Dtest='TransactionIsolationTest,ServiceTransactionBoundaryTest,EventPatchServiceTest'
```

Expected: FAIL. Compilation stops in `testCompile` with `cannot find symbol` for `variable EARLIEST` and `variable LATEST` in `EventPatchService`.

- [ ] **Step 3: Add the guard and the bounds**

In `src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java`, find:

```java
    public OrderResult confirm(HoldGroupCommand command) {
        UUID group = command.holdGroupId();
```

Replace it with:

```java
    public OrderResult confirm(HoldGroupCommand command) {
        TransactionBoundary.requireOwnTransaction();
        UUID group = command.holdGroupId();
```

In `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`, find:

```java
 * confirm can commit against a window it has just closed, or an event it has just cancelled. A lock timeout bounds
 * every wait, and a timeout asks the organiser to retry (spec §5, Liveness). Who may call this is Plan 4's concern.
 */
@Service
public class EventPatchService {
```

Replace it with:

```java
 * confirm can commit against a window it has just closed, or an event it has just cancelled. A lock timeout bounds
 * every wait, and a timeout asks the organiser to retry (spec §5, Liveness). The REST adapter lets any ORGANISER call
 * it, for any event: Phase 1 has no per-event ownership (plan 4A decision 2).
 */
@Service
public class EventPatchService {

    /**
     * The widest sales window a PATCH may set: what both timestamptz and a four-digit ISO 8601 year can hold. An
     * instant outside it would otherwise reach Postgres and fail there as a 500 (PR #22 thread 4305094277). This is the
     * one write path that takes caller-supplied instants, so the bound lives here, whichever adapter calls.
     */
    static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

    static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");
```

In `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`, find:

```java
    public EventPatchResult patch(EventPatch patch) {
        try {
```

Replace it with:

```java
    public EventPatchResult patch(EventPatch patch) {
        TransactionBoundary.requireOwnTransaction();
        try {
```

In `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`, find:

```java
        }
        boolean windowChanged = !openAt.equals(event.salesOpenAt()) || !closeAt.equals(event.salesCloseAt());
```

Replace it with:

```java
        }
        if (openAt.isBefore(EARLIEST) || closeAt.isAfter(LATEST)) {
            throw invalid("sales_window");
        }
        boolean windowChanged = !openAt.equals(event.salesOpenAt()) || !closeAt.equals(event.salesCloseAt());
```

In `src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java`, find:

```java
    public int expireLapsedHolds() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
```

Replace it with:

```java
    public int expireLapsedHolds() {
        TransactionBoundary.requireOwnTransaction();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
```

In `src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java`, find:

```java
    public int purgeHoldRequests() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
```

Replace it with:

```java
    public int purgeHoldRequests() {
        TransactionBoundary.requireOwnTransaction();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
```

In `src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java`, find:

```java
    public HoldResult hold(HoldCommand command) {
        requireWellFormed(command);
```

Replace it with:

```java
    public HoldResult hold(HoldCommand command) {
        TransactionBoundary.requireOwnTransaction();
        requireWellFormed(command);
```

In `src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java`, find:

```java
    public ReleaseResult release(HoldGroupCommand command) {
        try {
```

Replace it with:

```java
    public ReleaseResult release(HoldGroupCommand command) {
        TransactionBoundary.requireOwnTransaction();
        try {
```

Create `src/main/java/io/github/markusluisflores/frontrow/service/TransactionBoundary.java`:

```java
package io.github.markusluisflores.frontrow.service;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Every service entry point runs in a transaction it started itself (PR #18 thread 4296540451). Joined to a caller's
 * transaction, Spring would neither apply nor check READ COMMITTED, and the caller's earlier locks would come before
 * step 1 of the lock order (spec §5). Spring has no propagation for "start one, and fail if one exists": NEVER runs the
 * method with no transaction at all (plan 4A decision 4). So each entry point keeps REQUIRED and calls this first.
 */
final class TransactionBoundary {

    private TransactionBoundary() {}

    /** Fails loudly — a 500 at the adapter — if the transaction this method runs in was joined, not started here. */
    static void requireOwnTransaction() {
        if (!TransactionAspectSupport.currentTransactionStatus().isNewTransaction()) {
            throw new IllegalTransactionStateException("a service entry point must start its own transaction");
        }
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

```bash
./mvnw spotless:apply test -Dtest='TransactionIsolationTest,ServiceTransactionBoundaryTest,EventPatchServiceTest'
```

Expected: PASS, `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Mutation checks, each restored afterwards.** First, in `TransactionIsolationTest.SERVICES`, replace `ExpirySweepService.class` with a second `ConfirmService.class` and re-run Step 4's command. Expected: `theListIsEveryServiceInThePackage` fails, because the scan finds a service the list no longer names. Then delete the `TransactionBoundary.requireOwnTransaction();` line from `ReleaseService#release` and re-run Step 4's command. Expected: `everyEntryPointRefusesAnOuterTransaction` fails at `[ReleaseService#release]`, with `DomainException: not_found` where `IllegalTransactionStateException` was expected. Without the guard, release joined the outer transaction and ran, and answered for the made-up group instead of refusing. Restore the line. The authoring build ran both mutations and got these results.

- [ ] **Step 6: Run the whole build**

```bash
./mvnw spotless:apply verify
```

Expected: `Tests run: 202, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java src/main/java/io/github/markusluisflores/frontrow/service/ExpirySweepService.java src/main/java/io/github/markusluisflores/frontrow/service/HoldService.java src/main/java/io/github/markusluisflores/frontrow/service/ReleaseService.java src/main/java/io/github/markusluisflores/frontrow/service/TransactionBoundary.java src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java src/test/java/io/github/markusluisflores/frontrow/service/ServiceTransactionBoundaryTest.java src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java
git commit -m "feat(service): guard transaction boundaries and bound patch instants"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

### Task 2: Hashed credentials, validated at startup

**Files:**
- Modify: `.gitignore`
- Create: `scripts/demo-credentials.sh`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/CredentialProperties.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/Credentials.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/CredentialsConfig.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/DemoCredentials.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/Role.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/io/github/markusluisflores/frontrow/security/CredentialPropertiesTest.java` (test)
- Create: `src/test/java/io/github/markusluisflores/frontrow/security/DemoCredentialsTest.java` (test)
- Create: `src/test/java/io/github/markusluisflores/frontrow/support/TestCredentials.java` (test)
- Create: `src/test/resources/META-INF/spring.factories` (test)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `security.Role` (`BUYER`, `ORGANISER`, `ADMIN`); `security.CredentialProperties` (`frontrow.security.users[]` and `.agent-tokens[]`); and `security.Credentials`, with `userDetailsService()`, `ownerOfToken(String rawToken) : Optional<String>` and `static sha256Hex(String)`. A `Credentials` bean comes from `CredentialsConfig`. In tests, `support.TestCredentials.password(user)` and `.token(user)` return the per-run raw values. Every later task's tests authenticate with them.

**Until Task 4,** Spring Boot's default security chain still guards every path. These credentials are bound and checked, and nothing uses them yet.

- [ ] **Step 1: Write the failing tests and the test credentials**

Create `src/test/java/io/github/markusluisflores/frontrow/security/CredentialPropertiesTest.java`:

```java
package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Runs the real @Validated binding and the cross-field checks, with no database and no full application context. */
class CredentialPropertiesTest {

    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("secret");
    private static final String TOKEN_HASH = Credentials.sha256Hex("token");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CredentialsConfig.class)
            .withPropertyValues(
                    "frontrow.security.users[0].username=alice",
                    "frontrow.security.users[0].password-hash=" + HASH,
                    "frontrow.security.users[0].roles=BUYER",
                    "frontrow.security.users[1].username=olivia",
                    "frontrow.security.users[1].password-hash=" + HASH,
                    "frontrow.security.users[1].roles=ORGANISER",
                    "frontrow.security.agent-tokens[0].sha256=" + TOKEN_HASH,
                    "frontrow.security.agent-tokens[0].username=alice");

    @Test
    void aValidSetBindsAndAuthenticates() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            Credentials credentials = context.getBean(Credentials.class);
            assertThat(credentials.ownerOfToken("token")).contains("alice");
            assertThat(credentials.ownerOfToken("Token")).isEmpty();
            UserDetails alice = credentials.userDetailsService().loadUserByUsername("alice");
            assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                            .matches("secret", alice.getPassword()))
                    .isTrue();
            assertThat(alice.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_BUYER");
        });
    }

    @Test
    void noUsersAtAllIsRejected() {
        new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfig.class)
                .run(context -> assertRejected(context, "users"));
    }

    @Test
    void aUsernameThatCouldAliasAnotherIsRejected() {
        // Uppercase, a Cyrillic look-alike, U+0000 and an unpaired surrogate (PR #18 thread 4296749409).
        for (String username : new String[] {"Alice", "аlice", "al\u0000ice", "al\uD800ice", "-alice"}) {
            runner.withPropertyValues("frontrow.security.users[0].username=" + username)
                    .run(context -> assertRejected(context, "users[0].username"));
        }
    }

    @Test
    void aPasswordThatIsNotABcryptHashIsRejected() {
        for (String hash : new String[] {"{noop}secret", "secret", HASH.substring("{bcrypt}".length())}) {
            runner.withPropertyValues("frontrow.security.users[0].password-hash=" + hash)
                    .run(context -> assertRejected(context, "users[0].passwordHash"));
        }
    }

    @Test
    void aUserWithNoRoleIsRejected() {
        runner.withPropertyValues("frontrow.security.users[1].roles=")
                .run(context -> assertRejected(context, "users[1].roles"));
    }

    @Test
    void aMalformedTokenHashIsRejected() {
        runner.withPropertyValues("frontrow.security.agent-tokens[0].sha256=" + TOKEN_HASH.toUpperCase())
                .run(context -> assertRejected(context, "agentTokens[0].sha256"));
    }

    @Test
    void aTokenForAMissingUserOrANonBuyerIsRejected() {
        for (String username : new String[] {"mallory", "olivia"}) {
            runner.withPropertyValues("frontrow.security.agent-tokens[0].username=" + username)
                    .run(context -> assertStartupFails(context, "tokens are issued only to BUYER users"));
        }
    }

    @Test
    void aDuplicateUsernameOrTokenIsRejected() {
        runner.withPropertyValues("frontrow.security.users[1].username=alice")
                .run(context -> assertStartupFails(context, "duplicate username"));
        runner.withPropertyValues(
                        "frontrow.security.agent-tokens[1].sha256=" + TOKEN_HASH,
                        "frontrow.security.agent-tokens[1].username=alice")
                .run(context -> assertStartupFails(context, "duplicate token hash"));
    }

    private static void assertRejected(AssertableApplicationContext context, String field) {
        assertThat(context).hasFailed();
        Optional<BindValidationException> validation = bindValidationCause(context.getStartupFailure());
        assertThat(validation).isPresent();
        assertThat(validation.get().getValidationErrors().getAllErrors())
                .anySatisfy(error -> assertThat(error.toString()).contains(field));
    }

    private static void assertStartupFails(AssertableApplicationContext context, String message) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(message);
    }

    private static Optional<BindValidationException> bindValidationCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindValidationException validation) {
                return Optional.of(validation);
            }
        }
        return Optional.empty();
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/security/DemoCredentialsTest.java`:

```java
package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The generated file is valid configuration, and the raw values shown once are the ones its hashes accept. */
class DemoCredentialsTest {

    private static final Pattern PASSWORD_LINE = Pattern.compile("  (\\S+) \\(\\w+\\) password: (\\S+)");
    private static final Pattern TOKEN_LINE = Pattern.compile("  (\\S+)'s agent token: (\\S+)");

    @Test
    void theGeneratedYamlBindsAndAcceptsTheRawValuesShownOnce() throws IOException {
        DemoCredentials.Generated generated = DemoCredentials.generate(new SecureRandom());
        var source = new YamlPropertySourceLoader()
                .load("demo", new ByteArrayResource(generated.yaml().getBytes(StandardCharsets.UTF_8)))
                .getFirst();

        new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfig.class)
                .withInitializer(
                        context -> context.getEnvironment().getPropertySources().addFirst(source))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Credentials credentials = context.getBean(Credentials.class);
                    PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
                    Map<String, String> passwords = matches(PASSWORD_LINE, generated.rawValues());
                    assertThat(passwords).containsOnlyKeys(DemoCredentials.USERS.keySet());
                    passwords.forEach((user, password) -> assertThat(encoder.matches(
                                    password,
                                    credentials
                                            .userDetailsService()
                                            .loadUserByUsername(user)
                                            .getPassword()))
                            .isTrue());
                    Map<String, String> tokens = matches(TOKEN_LINE, generated.rawValues());
                    assertThat(tokens).containsOnlyKeys(DemoCredentials.TOKEN_HOLDER);
                    assertThat(credentials.ownerOfToken(tokens.get(DemoCredentials.TOKEN_HOLDER)))
                            .contains(DemoCredentials.TOKEN_HOLDER);
                });
        assertThat(generated.yaml())
                .doesNotContain(matches(PASSWORD_LINE, generated.rawValues()).values());
    }

    private static Map<String, String> matches(Pattern pattern, String text) {
        Map<String, String> found = new HashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }
        return found;
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/support/TestCredentials.java`:

```java
package io.github.markusluisflores.frontrow.support;

import io.github.markusluisflores.frontrow.security.Credentials;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Every test context's users and agent tokens, generated once per JVM, so no raw password or token is committed (spec
 * §12; plan 4A decision 5). The nested EnvironmentPostProcessor, registered in META-INF/spring.factories, adds their
 * hashes to every SpringApplication the tests start.
 */
public final class TestCredentials {

    /** Each user's roles. alice and carol hold agent tokens; carol is also an ORGANISER. */
    public static final Map<String, List<String>> USERS = Map.of(
            "alice", List.of("BUYER"),
            "bob", List.of("BUYER"),
            "carol", List.of("BUYER", "ORGANISER"),
            "olivia", List.of("ORGANISER"),
            "adam", List.of("ADMIN"));

    public static final List<String> TOKEN_HOLDERS = List.of("alice", "carol");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> PASSWORDS = new HashMap<>();
    private static final Map<String, String> TOKENS = new HashMap<>();

    static {
        USERS.keySet().forEach(user -> PASSWORDS.put(user, randomValue()));
        TOKEN_HOLDERS.forEach(user -> TOKENS.put(user, randomValue()));
    }

    private TestCredentials() {}

    public static String password(String user) {
        return PASSWORDS.get(user);
    }

    public static String token(String user) {
        return TOKENS.get(user);
    }

    private static String randomValue() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static final class Environment implements EnvironmentPostProcessor {

        @Override
        public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
            // Cost 4, the minimum: these hashes protect nothing, and every test context computes them.
            BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
            Map<String, Object> properties = new HashMap<>();
            List<String> users = USERS.keySet().stream().sorted().toList();
            for (int i = 0; i < users.size(); i++) {
                String user = users.get(i);
                String prefix = "frontrow.security.users[" + i + "].";
                properties.put(prefix + "username", user);
                properties.put(prefix + "password-hash", "{bcrypt}" + bcrypt.encode(password(user)));
                properties.put(prefix + "roles", String.join(",", USERS.get(user)));
            }
            for (int i = 0; i < TOKEN_HOLDERS.size(); i++) {
                String user = TOKEN_HOLDERS.get(i);
                properties.put("frontrow.security.agent-tokens[" + i + "].sha256", Credentials.sha256Hex(token(user)));
                properties.put("frontrow.security.agent-tokens[" + i + "].username", user);
            }
            environment.getPropertySources().addLast(new MapPropertySource("testCredentials", properties));
        }
    }
}
```

Create `src/test/resources/META-INF/spring.factories`:

```properties
org.springframework.boot.EnvironmentPostProcessor=\
io.github.markusluisflores.frontrow.support.TestCredentials.Environment
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./mvnw spotless:apply test -Dtest='CredentialPropertiesTest,DemoCredentialsTest'
```

Expected: FAIL. Compilation stops in `testCompile` with `cannot find symbol` for `class Credentials`.

- [ ] **Step 3: Add the credentials, the generator and the script**

In `.gitignore`, find:

```text
# Local environment and secrets
.env
```

Replace it with:

```text
# Local environment and secrets
# config/ holds scripts/demo-credentials.sh's generated application-demo.yml (plan 4A decision 5)
/config/
.env
```

Create `scripts/demo-credentials.sh`:

```bash
#!/usr/bin/env bash
# Generates the local demo's credentials (spec §9): bcrypt and SHA-256 hashes go to config/application-demo.yml,
# which is gitignored, and the raw passwords and agent token are printed once. Start the app with the demo profile:
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
set -euo pipefail
cd "$(dirname "$0")/.."

target=config/application-demo.yml
if [ -e "$target" ]; then
  echo "$target already exists; delete it to generate new credentials" >&2
  exit 1
fi

./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=runtime
separator=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) separator=";" ;; esac

mkdir -p config
umask 077
# Write to a temporary file and move it into place only on success, so a failed run leaves nothing behind.
partial="$(mktemp config/.application-demo.yml.XXXXXX)"
trap 'rm -f "$partial"' EXIT
java -cp "target/classes${separator}$(cat target/demo-classpath.txt)"   io.github.markusluisflores.frontrow.security.DemoCredentials > "$partial"
mv "$partial" "$target"
trap - EXIT
echo "Wrote $target (hashes only)." >&2
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/CredentialProperties.java`:

```java
package io.github.markusluisflores.frontrow.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Demo-grade credentials (spec §9): in-memory Basic users and static agent tokens, stored only as hashes. Nothing
 * usable is committed: scripts/demo-credentials.sh writes them to a gitignored config/application-demo.yml, and tests
 * generate their own (plan 4A decision 5). Cross-field rules are checked by {@link Credentials}.
 */
@Validated
@ConfigurationProperties(prefix = "frontrow.security")
public record CredentialProperties(@NotEmpty List<@Valid User> users, List<@Valid AgentToken> agentTokens) {

    /**
     * A username is the owner string for both credentials (plan 4A decision 3). Lowercase ASCII only, so no two
     * principals can alias: no case or Unicode look-alikes, no U+0000 and no unpaired surrogate (PR #18 thread
     * 4296749409).
     */
    public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";

    /** Spring's delegating form of a bcrypt hash; nothing else is accepted, so a {noop} password cannot slip in. */
    public static final String BCRYPT_PATTERN = "^\\{bcrypt\\}\\$2[aby]?\\$\\d\\d\\$[./0-9A-Za-z]{53}$";

    public static final String SHA256_HEX_PATTERN = "^[0-9a-f]{64}$";

    /** Immutable copies. An absent list binds as empty, so a missing users list fails @NotEmpty. */
    public CredentialProperties {
        users = users == null ? List.of() : List.copyOf(users);
        agentTokens = agentTokens == null ? List.of() : List.copyOf(agentTokens);
    }

    public record User(
            @NotNull @Pattern(regexp = USERNAME_PATTERN) String username,
            @NotNull @Pattern(regexp = BCRYPT_PATTERN) String passwordHash,
            @NotEmpty Set<Role> roles) {

        public User {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    /** An agent token, by the SHA-256 of its raw value, and the user it acts for. */
    public record AgentToken(
            @NotNull @Pattern(regexp = SHA256_HEX_PATTERN) String sha256,
            @NotNull @Pattern(regexp = USERNAME_PATTERN) String username) {}
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/Credentials.java`:

```java
package io.github.markusluisflores.frontrow.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * The validated credential set. Startup fails here on any rule the per-field constraints cannot express: a duplicate
 * username or token, or a token for a user who is missing or lacks BUYER — tokens are issued only to BUYER users (spec
 * §3).
 */
public final class Credentials {

    private final Map<String, CredentialProperties.User> users;
    private final Map<String, String> tokenOwners;

    private Credentials(Map<String, CredentialProperties.User> users, Map<String, String> tokenOwners) {
        this.users = users;
        this.tokenOwners = tokenOwners;
    }

    public static Credentials from(CredentialProperties properties) {
        Map<String, CredentialProperties.User> users = new HashMap<>();
        for (CredentialProperties.User user : properties.users()) {
            if (users.putIfAbsent(user.username(), user) != null) {
                throw new IllegalStateException("frontrow.security.users: duplicate username " + user.username());
            }
        }
        Map<String, String> tokenOwners = new HashMap<>();
        for (CredentialProperties.AgentToken token : properties.agentTokens()) {
            CredentialProperties.User user = users.get(token.username());
            if (user == null || !user.roles().contains(Role.BUYER)) {
                throw new IllegalStateException(
                        "frontrow.security.agent-tokens: tokens are issued only to BUYER users, not "
                                + token.username());
            }
            if (tokenOwners.putIfAbsent(token.sha256(), token.username()) != null) {
                throw new IllegalStateException("frontrow.security.agent-tokens: duplicate token hash");
            }
        }
        return new Credentials(Map.copyOf(users), Map.copyOf(tokenOwners));
    }

    /** The Basic users, with Spring's ROLE_ prefix on each role. */
    public UserDetailsService userDetailsService() {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        users.values()
                .forEach(user -> manager.createUser(User.withUsername(user.username())
                        .password(user.passwordHash())
                        .roles(user.roles().stream().map(Role::name).toArray(String[]::new))
                        .build()));
        return manager;
    }

    /** The user a raw agent token acts for, if any. Only the token's SHA-256 is ever compared or stored. */
    public Optional<String> ownerOfToken(String rawToken) {
        return Optional.ofNullable(tokenOwners.get(sha256Hex(rawToken)));
    }

    public static String sha256Hex(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JDK provides SHA-256", impossible);
        }
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/CredentialsConfig.java`:

```java
package io.github.markusluisflores.frontrow.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CredentialProperties.class)
public class CredentialsConfig {

    @Bean
    Credentials credentials(CredentialProperties properties) {
        return Credentials.from(properties);
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/DemoCredentials.java`:

```java
package io.github.markusluisflores.frontrow.security;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Generates the local demo's users and agent token (spec §9, §11). scripts/demo-credentials.sh runs it: the hashes go
 * to stdout, which the script writes to the gitignored config/application-demo.yml, and the raw values go to stderr,
 * shown once. Nothing usable is committed (plan 4A decision 5).
 */
public final class DemoCredentials {

    /** The demo cast: two buyers (alice holds the agent token), an organiser and an admin. */
    static final Map<String, Role> USERS =
            Map.of("alice", Role.BUYER, "bob", Role.BUYER, "olivia", Role.ORGANISER, "adam", Role.ADMIN);

    static final String TOKEN_HOLDER = "alice";

    private DemoCredentials() {}

    public static void main(String[] args) {
        Generated generated = generate(new SecureRandom());
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.print(generated.yaml());
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.print(generated.rawValues());
    }

    static Generated generate(SecureRandom random) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        StringBuilder yaml = new StringBuilder(
                        "# Generated by scripts/demo-credentials.sh. Hashes only; never commit.\n")
                .append("frontrow:\n  security:\n    users:\n");
        StringBuilder raw = new StringBuilder("Demo credentials, shown once:\n");
        for (Map.Entry<String, Role> user : new TreeMap<>(USERS).entrySet()) {
            String password = randomValue(random);
            yaml.append("      - username: ")
                    .append(user.getKey())
                    .append("\n        password-hash: \"{bcrypt}")
                    .append(bcrypt.encode(password))
                    .append("\"\n        roles: [")
                    .append(user.getValue())
                    .append("]\n");
            raw.append("  ")
                    .append(user.getKey())
                    .append(" (")
                    .append(user.getValue())
                    .append(") password: ")
                    .append(password)
                    .append('\n');
        }
        String token = randomValue(random);
        yaml.append("    agent-tokens:\n      - sha256: ")
                .append(Credentials.sha256Hex(token))
                .append("\n        username: ")
                .append(TOKEN_HOLDER)
                .append('\n');
        raw.append("  ")
                .append(TOKEN_HOLDER)
                .append("'s agent token: ")
                .append(token)
                .append('\n');
        return new Generated(yaml.toString(), raw.toString());
    }

    private static String randomValue(SecureRandom random) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record Generated(String yaml, String rawValues) {}
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/Role.java`:

```java
package io.github.markusluisflores.frontrow.security;

/** The three REST roles (spec §9). An agent token never carries one: the MCP chain grants only AGENT (spec §3). */
public enum Role {
    BUYER,
    ORGANISER,
    ADMIN
}
```

In `src/main/resources/application.yml`, find:

```yaml
  sweeper-enabled: true
```

Replace it with:

```yaml
  sweeper-enabled: true
  # frontrow.security (users and agent tokens) is deliberately absent: nothing usable is committed, and startup fails
  # without it. scripts/demo-credentials.sh writes it to config/application-demo.yml; start with the demo profile.
```

Then make the script executable in git, because the Windows checkout records no mode:

```bash
git add scripts/demo-credentials.sh && git update-index --chmod=+x scripts/demo-credentials.sh
```

Expected: no output.

- [ ] **Step 4: Run them to verify they pass, with one full context to show the test credentials bind**

```bash
./mvnw spotless:apply test -Dtest='CredentialPropertiesTest,DemoCredentialsTest,EventPatchServiceTest'
```

Expected: PASS, `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Run the script once, and check the file it writes is ignored**

```bash
scripts/demo-credentials.sh 2>/dev/null && git status --short --ignored config
```

Expected: `!! config/`, ignored, so it can never be staged.

It printed the raw passwords and token to stderr; this command discards them. Run it without `2>/dev/null` to use the demo. To regenerate, delete `config/application-demo.yml` first: the script refuses to overwrite it. It writes to a temporary file and moves it into place only on success, so a failed run leaves nothing behind.

- [ ] **Step 6: Run the whole build**

```bash
./mvnw spotless:apply verify
```

Expected: `Tests run: 211, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add .gitignore scripts/demo-credentials.sh src/main/java/io/github/markusluisflores/frontrow/security/CredentialProperties.java src/main/java/io/github/markusluisflores/frontrow/security/Credentials.java src/main/java/io/github/markusluisflores/frontrow/security/CredentialsConfig.java src/main/java/io/github/markusluisflores/frontrow/security/DemoCredentials.java src/main/java/io/github/markusluisflores/frontrow/security/Role.java src/main/resources/application.yml src/test/java/io/github/markusluisflores/frontrow/security/CredentialPropertiesTest.java src/test/java/io/github/markusluisflores/frontrow/security/DemoCredentialsTest.java src/test/java/io/github/markusluisflores/frontrow/support/TestCredentials.java src/test/resources/META-INF/spring.factories
git commit -m "feat(security): add hashed demo credentials and startup checks"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

### Task 3: Problem Details, and the request-body bound

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/Problems.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/RequestBodyLimitFilter.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/RestConfig.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/RestExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/io/github/markusluisflores/frontrow/rest/ProblemsTest.java` (test)
- Create: `src/test/java/io/github/markusluisflores/frontrow/rest/RestExceptionHandlerTest.java` (test)

**Interfaces:**
- Consumes: `DomainException`, `ErrorCode`.
- Produces:
  - `rest.Problems`: `status(ErrorCode) : HttpStatus`, `of(DomainException) : ProblemDetail`, `invalidParameter(String)`, `plain(HttpStatus)`, and `write(HttpServletRequest, HttpServletResponse, ProblemDetail)` for filters, which sets `instance` to the request URI and which Task 4's filters and entry points use;
  - `rest.RestExceptionHandler`, a `@RestControllerAdvice` that applies to Task 5's controllers;
  - `rest.RequestBodyLimitFilter.MAX_BODY_BYTES` (16 384), and its `BodyTooLargeException`.

**These tests need no application context:** `RestExceptionHandlerTest` drives a test controller through a standalone MockMvc, with the handler, a snake_case converter and the filter.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/io/github/markusluisflores/frontrow/rest/ProblemsTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyCodeHasItsStatus() {
        Map<ErrorCode, HttpStatus> expected = new EnumMap<>(ErrorCode.class);
        expected.put(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST);
        expected.put(ErrorCode.TOO_MANY_SEATS, HttpStatus.BAD_REQUEST);
        expected.put(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND);
        expected.put(ErrorCode.SEAT_TAKEN, HttpStatus.CONFLICT);
        expected.put(ErrorCode.HOLD_LIMIT_EXCEEDED, HttpStatus.CONFLICT);
        expected.put(ErrorCode.HOLD_EXPIRED, HttpStatus.CONFLICT);
        expected.put(ErrorCode.HOLD_NOT_ACTIVE, HttpStatus.CONFLICT);
        expected.put(ErrorCode.SALES_NOT_OPEN, HttpStatus.CONFLICT);
        expected.put(ErrorCode.SALES_CLOSED, HttpStatus.CONFLICT);
        expected.put(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_CONTENT);
        expected.put(ErrorCode.CONTENTION_RETRY, HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(expected).containsOnlyKeys(ErrorCode.values());
        expected.forEach((code, status) ->
                assertThat(Problems.status(code)).as(code.code()).isEqualTo(status));
    }

    @Test
    void extensionMembersAreInKeyOrderWhateverOrderDetailsIteratesIn() {
        DomainException failure = new DomainException(
                ErrorCode.INVALID_REQUEST, Map.of("seat_ids", List.of(3L, 1L), "parameter", "event_seat_ids"));

        ProblemDetail problem = Problems.of(failure);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).isNotNull();
        assertThat(problem.getProperties().keySet()).containsExactly("code", "hint", "parameter", "seat_ids");
        assertThat(problem.getProperties())
                .containsEntry("code", "invalid_request")
                .containsEntry("hint", ErrorCode.INVALID_REQUEST.hint());
    }

    @Test
    void theIdempotencyKeyIsNamedAsRestCarriesIt() {
        ProblemDetail problem = Problems.invalidParameter("idempotency_key");

        assertThat(problem.getProperties()).containsEntry("parameter", "Idempotency-Key");
        assertThat(Problems.invalidParameter("event_seat_ids").getProperties())
                .containsEntry("parameter", "event_seat_ids");
    }

    @Test
    void aPlainProblemHasNoCodeAndNoDetail() {
        ProblemDetail problem = Problems.plain(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(problem.getProperties()).isNull();
        assertThat(problem.getDetail()).isNull();
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
    }

    @Test
    void aFilterWritesTheSameShapeAsTheConverter() throws Exception {
        DomainException failure = new DomainException(
                ErrorCode.SALES_NOT_OPEN, Map.of("sales_open_at", Instant.parse("2030-01-01T00:00:00Z")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        Problems.write(new MockHttpServletRequest("POST", "/api/holds/x/confirm"), response, Problems.of(failure));

        ProblemDetail rendered = Problems.of(failure);
        rendered.setInstance(URI.create("/api/holds/x/confirm"));
        MockHttpOutputMessage converted = new MockHttpOutputMessage();
        new JacksonJsonHttpMessageConverter(JsonMapper.builder())
                .write(rendered, MediaType.APPLICATION_PROBLEM_JSON, converted);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body).isEqualTo(JSON.readTree(converted.getBodyAsString()));
        assertThat(body).isEqualTo(JSON.readTree("""
                        {"title": "Conflict", "status": 409, "instance": "/api/holds/x/confirm",
                         "code": "sales_not_open", "hint": "sales have not opened for this event yet",
                         "sales_open_at": "2030-01-01T00:00:00Z"}
                        """));
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/rest/RestExceptionHandlerTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The handler and the body limit, with no application context: a test controller raises each kind of failure.
 * Responses are compared as parsed JSON trees, never as strings (PR #18 thread 4296749419).
 */
class RestExceptionHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new RestExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(
                    JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
            .addFilters(new RequestBodyLimitFilter())
            .build();

    @RestController
    static class FailingController {

        record Body(EventStatus status, List<Long> eventSeatIds) {}

        @GetMapping("/t/domain")
        void domain() {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST, Map.of("parameter", "idempotency_key", "seat_ids", List.of(7L)));
        }

        @GetMapping("/t/contention")
        void contention() {
            throw DomainException.of(ErrorCode.CONTENTION_RETRY);
        }

        @GetMapping("/t/bug")
        void bug() {
            throw new IllegalStateException("hold 42 belongs to bob");
        }

        @GetMapping("/t/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint uq_claimed_seat");
        }

        @PostMapping("/t/body")
        Body body(@RequestBody Body body) {
            return body;
        }

        @GetMapping("/t/groups/{holdGroupId}")
        UUID group(@PathVariable UUID holdGroupId) {
            return holdGroupId;
        }

        @GetMapping("/t/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }
    }

    @Test
    void aDomainFailureCarriesItsCodeHintAndRestNamedDetails() throws Exception {
        MockHttpServletResponse response = perform(get("/t/domain"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(body(response)).isEqualTo(JSON.readTree("""
                        {"title": "Bad Request", "status": 400, "instance": "/t/domain",
                         "code": "invalid_request", "hint": "check the named parameter and try again",
                         "parameter": "Idempotency-Key", "seat_ids": [7]}
                        """));
    }

    @Test
    void contentionAsksForARetry() throws Exception {
        MockHttpServletResponse response = perform(get("/t/contention"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(body(response).get("code").asString()).isEqualTo("contention_retry");
    }

    @Test
    void anythingElseIsAGeneric500ThatEchoesNothing() throws Exception {
        for (String path : List.of("/t/bug", "/t/integrity")) {
            MockHttpServletResponse response = perform(get(path));

            assertThat(response.getStatus()).as(path).isEqualTo(500);
            assertThat(body(response)).as(path).isEqualTo(JSON.readTree("""
                            {"title": "Internal Server Error", "status": 500, "instance": "%s"}
                            """.formatted(path)));
        }
    }

    @Test
    void malformedJsonNamesTheBody() throws Exception {
        assertInvalid(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{"), "body");
    }

    @Test
    void aBadFieldIsNamedAsTheWireNamesIt() throws Exception {
        assertInvalid(
                post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"SOLD_OUT\"}"),
                "status");
        assertInvalid(
                post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{\"event_seat_ids\": [\"x\"]}"),
                "event_seat_ids");
    }

    @Test
    void aBadPathVariableIsNamedAsTheWireNamesIt() throws Exception {
        assertInvalid(get("/t/groups/not-a-uuid"), "hold_group_id");
    }

    @Test
    void aMissingIdempotencyKeyNamesTheHeader() throws Exception {
        assertInvalid(get("/t/header"), "Idempotency-Key");
    }

    @Test
    void aDeclaredBodyOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        MockHttpServletResponse response = perform(post("/t/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[RequestBodyLimitFilter.MAX_BODY_BYTES + 1]));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(body(response)).isEqualTo(JSON.readTree("""
                        {"title": "Content Too Large", "status": 413, "instance": "/t/body"}
                        """));
    }

    @Test
    void aBodyAtTheLimitIsRead() throws Exception {
        String padded = "{\"status\": \"ON_SALE\"" + " ".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES - 21) + "}";
        assertThat(padded).hasSize(RequestBodyLimitFilter.MAX_BODY_BYTES);

        MockHttpServletResponse response =
                perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content(padded));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aBodyReadThroughTheReaderIsBoundedToo() throws Exception {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter();
        long[] read = new long[1];

        filter.doFilter(
                undeclaredLength(RequestBodyLimitFilter.MAX_BODY_BYTES),
                new MockHttpServletResponse(),
                (request, response) ->
                        read[0] = ((HttpServletRequest) request).getReader().transferTo(Writer.nullWriter()));
        assertThat(read[0]).isEqualTo(RequestBodyLimitFilter.MAX_BODY_BYTES);

        assertThatThrownBy(() -> filter.doFilter(
                        undeclaredLength(RequestBodyLimitFilter.MAX_BODY_BYTES + 1),
                        new MockHttpServletResponse(),
                        (request, response) ->
                                ((HttpServletRequest) request).getReader().transferTo(Writer.nullWriter())))
                .isInstanceOf(RequestBodyLimitFilter.BodyTooLargeException.class);
    }

    @Test
    void anUnknownCharsetIsTheServletContractsCheckedException() {
        MockHttpServletRequest unknown = new MockHttpServletRequest("POST", "/t/body");
        unknown.setContent(new byte[1]);
        unknown.setCharacterEncoding("no-such-charset");

        assertThatThrownBy(() -> new RequestBodyLimitFilter()
                        .doFilter(
                                unknown,
                                new MockHttpServletResponse(),
                                (request, response) -> ((HttpServletRequest) request).getReader()))
                .isInstanceOf(UnsupportedEncodingException.class)
                .hasMessage("no-such-charset");
    }

    @Test
    void wireNamesAreSnakeCase() {
        assertThat(RestExceptionHandler.snakeCase("holdGroupId")).isEqualTo("hold_group_id");
        assertThat(RestExceptionHandler.snakeCase("eventId")).isEqualTo("event_id");
    }

    private void assertInvalid(RequestBuilder request, String parameter) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = body(response);
        assertThat(body.get("code").asString()).isEqualTo("invalid_request");
        assertThat(body.get("parameter").asString()).isEqualTo(parameter);
    }

    /** A body that declares no Content-Length, as a chunked one does, so only the counting wrapper can refuse it. */
    private static HttpServletRequest undeclaredLength(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/t/body");
        request.setContent(new byte[bytes]);
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./mvnw spotless:apply test -Dtest='ProblemsTest,RestExceptionHandlerTest'
```

Expected: FAIL. Compilation stops in `testCompile` with `cannot find symbol` for `variable Problems`.

- [ ] **Step 3: Add the renderer, the handler, the filter and snake_case**

Create `src/main/java/io/github/markusluisflores/frontrow/rest/Problems.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every REST error body is an RFC 9457 Problem Details object (spec §6). A domain failure adds its code, its hint and
 * its details as extension members, in key order: details() iterates in a per-JVM order (PR #18 thread 4296749419),
 * and a rendered body must not. A failure with no domain code — a 401, a 403, a 500 — has no code member.
 */
public final class Problems {

    /** The service names MCP's parameter; REST carries the key in a header (spec §6; PR #18 thread 4296541034). */
    static final Map<String, String> REST_PARAMETER_NAMES = Map.of("idempotency_key", "Idempotency-Key");

    /** For filters, which answer before Spring MVC's converters run; independent of the app's Jackson settings. */
    private static final JsonMapper FILTER_FORM = JsonMapper.builder().build();

    private Problems() {}

    /** Switch expression, no default: a new ErrorCode is a compile error here, not a silent 500. */
    public static HttpStatus status(ErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, TOO_MANY_SEATS -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SEAT_TAKEN, HOLD_LIMIT_EXCEEDED, HOLD_EXPIRED, HOLD_NOT_ACTIVE, SALES_NOT_OPEN, SALES_CLOSED ->
                HttpStatus.CONFLICT;
            // The IETF Idempotency-Key draft's status for a key reused with a different request.
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CONTENTION_RETRY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    public static ProblemDetail of(DomainException failure) {
        ErrorCode code = failure.errorCode();
        Map<String, Object> members = new TreeMap<>();
        failure.details().forEach((key, value) -> members.put(key, restName(key, value)));
        members.put("code", code.code());
        members.put("hint", code.hint());
        ProblemDetail problem = ProblemDetail.forStatus(status(code));
        members.forEach(problem::setProperty);
        return problem;
    }

    public static ProblemDetail invalidParameter(String parameter) {
        return of(new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter)));
    }

    /** A bare status: the reason phrase as title, and nothing else — no detail, no echo. */
    public static ProblemDetail plain(HttpStatus status) {
        return ProblemDetail.forStatus(status);
    }

    /**
     * Writes a problem from a filter, in the same RFC 9457 shape the MVC converter produces. The converter sets the
     * instance to the request URI, and so does this writer.
     */
    public static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        // Spring leaves type unset, which RFC 9457 reads as about:blank; this writer does the same.
        URI type = problem.getType();
        if (type != null) {
            body.put("type", type.toString());
        }
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("instance", request.getRequestURI());
        Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            body.putAll(properties);
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        FILTER_FORM.writeValue(response.getOutputStream(), body);
    }

    private static Object restName(String key, Object value) {
        return "parameter".equals(key) && value instanceof String parameter
                ? REST_PARAMETER_NAMES.getOrDefault(parameter, parameter)
                : value;
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/rest/RequestBodyLimitFilter.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds every request body, ahead of every security chain (plan 4A decision 8). The services copy what they receive
 * — HoldCommand copies its whole seat list — so the bound belongs at the edge. A declared Content-Length over the limit
 * is refused before anything is read. A chunked body is counted as it is read, through the stream or the reader, and
 * crossing the limit fails the read.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 16 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            Problems.write(request, response, Problems.plain(HttpStatus.CONTENT_TOO_LARGE));
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** Thrown by a read that crosses the limit; RestExceptionHandler answers it with 413. */
    public static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        /** Kept on the request, so a caller that asks for the stream again cannot reset it. */
        private long count;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream());
        }

        /** The reader decodes the limited stream, so a body read as characters is bounded the same way. */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset;
            try {
                charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
                // The servlet contract: an encoding the JVM can't decode is a checked UnsupportedEncodingException.
                throw new UnsupportedEncodingException(encoding);
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        private void counted(int n) throws BodyTooLargeException {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
        }

        private final class LimitedInputStream extends ServletInputStream {

            private final ServletInputStream delegate;

            LimitedInputStream(ServletInputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public int read() throws IOException {
                int b = delegate.read();
                if (b >= 0) {
                    counted(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = delegate.read(buffer, offset, length);
                if (n > 0) {
                    counted(n);
                }
                return n;
            }

            @Override
            public boolean isFinished() {
                return delegate.isFinished();
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
                delegate.setReadListener(listener);
            }
        }
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/rest/RestConfig.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RestConfig {

    /** First of all filters, ahead of Spring Security's chains, on every path. */
    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter() {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/rest/RestExceptionHandler.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Renders every failure a REST controller raises as Problem Details (spec §6). A DomainException carries its code.
 * Malformed input becomes invalid_request naming the parameter. Anything else is a bug and gets a generic 500: no
 * message is echoed (an IllegalStateException can carry internal ids), and no database failure is guessed into a
 * conflict code (plan 4A decision 7).
 */
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domainFailure(DomainException failure) {
        ProblemDetail problem = Problems.of(failure);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        if (failure.errorCode() == ErrorCode.CONTENTION_RETRY) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpectedFailure(Exception failure) {
        LOG.error("Unhandled failure in a REST request", failure);
        return ResponseEntity.internalServerError().body(Problems.plain(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (causedBy(failure, RequestBodyLimitFilter.BodyTooLargeException.class)) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(Problems.plain(HttpStatus.CONTENT_TOO_LARGE));
        }
        return invalid(Problems.invalidParameter(jsonParameter(failure)));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = failure.getPropertyName();
        return invalid(Problems.invalidParameter(name == null ? "request" : snakeCase(name)));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (failure instanceof MissingRequestHeaderException missing) {
            return invalid(Problems.invalidParameter(missing.getHeaderName()));
        }
        return super.handleServletRequestBindingException(failure, headers, status, request);
    }

    private static ResponseEntity<Object> invalid(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** The first JSON property on the failing path — already snake_case — or "body" if Jackson names none. */
    private static String jsonParameter(HttpMessageNotReadableException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson) {
                for (JacksonException.Reference reference : jackson.getPath()) {
                    if (reference.getPropertyName() != null) {
                        return reference.getPropertyName();
                    }
                }
            }
        }
        return "body";
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** A path variable's Java name (holdGroupId) as the wire names it (hold_group_id). */
    static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
```

In `src/main/resources/application.yml`, find:

```yaml
        type: SYNC
  jpa:
```

Replace it with:

```yaml
        type: SYNC
  jackson:
    # The wire form matches MCP's parameter names and the error details (plan 4A decision 9). HoldService's stored
    # replay form uses its own mapper, so this does not reach rows stored before it.
    property-naming-strategy: SNAKE_CASE
  mvc:
    formcontent:
      filter:
        # Boot's FormContentFilter reads a form body on PUT, PATCH and DELETE before Spring Security runs, so a chunked
        # form body over the 16 KiB bound became an anonymous 500 on any path (plan 4A decision 8). No endpoint takes a
        # form body, so the filter is off.
        enabled: false
  jpa:
```

- [ ] **Step 4: Run them to verify they pass**

```bash
./mvnw spotless:apply test -Dtest='ProblemsTest,RestExceptionHandlerTest'
```

Expected: PASS, `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Run the whole build.** This is the first build with snake_case on the wire, and it must not break the MCP spike.

```bash
./mvnw spotless:apply verify
```

Expected: `Tests run: 228, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/rest/Problems.java src/main/java/io/github/markusluisflores/frontrow/rest/RequestBodyLimitFilter.java src/main/java/io/github/markusluisflores/frontrow/rest/RestConfig.java src/main/java/io/github/markusluisflores/frontrow/rest/RestExceptionHandler.java src/main/resources/application.yml src/test/java/io/github/markusluisflores/frontrow/rest/ProblemsTest.java src/test/java/io/github/markusluisflores/frontrow/rest/RestExceptionHandlerTest.java
git commit -m "feat(rest): render errors as problem details and bound request bodies"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

### Task 4: The three security chains

**Files:**
- Modify: `docs/adr/ADR-002-agents-hold-humans-buy.md`
- Modify: `pom.xml`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/AgentTokenFilter.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/BearerRejectFilter.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/CrossSiteWriteFilter.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/security/SecurityConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java` (test)
- Create: `src/test/java/io/github/markusluisflores/frontrow/security/SecurityChainsTest.java` (test)
- Create: `src/test/java/io/github/markusluisflores/frontrow/security/SecurityFiltersTest.java` (test)

**Interfaces:**
- Consumes: `Credentials` (Task 2); `Problems.write` and `Problems.plain` (Task 3); `TestCredentials` (Task 2).
- Produces:
  - `security.SecurityConfig`, with beans `apiChain` (order 1), `mcpChain` (order 2), `catchAllChain` (lowest precedence) and `userDetailsService`;
  - package-private `BearerRejectFilter.isBearer(String)`, `CrossSiteWriteFilter.isCrossSite(String)` and `.isFormSubmittable(String)`, and `AgentTokenFilter.AGENT`.

**No write endpoint exists until Task 5.** So in `SecurityChainsTest`, a request that passes security reaches no handler and gets 404. That 404 is the positive control that the chain let it through.

- [ ] **Step 1: Add the actuator and the two test starters, write the failing tests, and move the spike onto the real chain**

In `pom.xml`, find:

```xml
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>

        <dependency>
```

Replace it with:

```xml
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <dependency>
```

In `pom.xml`, find:

```xml
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
```

Replace it with:

```xml
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
```

Replace the whole of `src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java`:

```java
package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike (spec §3): how does an authenticated principal reach an {@code @McpTool} method over Streamable HTTP?
 * Real bearer token, real HTTP, real MCP client. Mechanism A is SecurityContextHolder, mechanism B is
 * McpTransportContext populated by a contextExtractor. Since plan 4A the token passes the application's own /mcp chain
 * (security/SecurityConfig), not a spike-only one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, PrincipalPropagationSpikeTest.SpikeConfig.class})
class PrincipalPropagationSpikeTest {

    /** alice's agent token, generated per run: spec §12 allows no raw token in the repository, even a test one. */
    static final String SPIKE_TOKEN = TestCredentials.token("alice");

    static final String SPIKE_USER = "alice";
    static final String PRINCIPAL_KEY = "frontrow.principal";
    static final String ANONYMOUS = "<anonymous>";

    @LocalServerPort
    int port;

    /**
     * Tripwire, not a functional requirement: ADR-004 chose mechanism B (McpTransportContext). This test proves
     * mechanism A also happens to work today, given current Spring AI tool-execution threading. If a future Spring
     * AI upgrade changes that threading and this test starts failing, re-read ADR-004 before touching this test —
     * the fix is not to restore mechanism A.
     */
    @Test
    void mechanismA_securityContextHolderSeesThePrincipal() {
        assertThat(callTool("whoami_security_context"))
                .as("tripwire for ADR-004's assumption that tool methods run on the request thread; "
                        + "mechanism B (McpTransportContext) is the chosen path regardless of this result; "
                        + "a failure here means re-reading ADR-004, not fixing a bug")
                .isEqualTo(SPIKE_USER);
    }

    @Test
    void mechanismB_transportContextCarriesThePrincipal() {
        assertThat(callTool("whoami_transport_context")).isEqualTo(SPIKE_USER);
    }

    @Test
    void unauthenticatedMcpRequestIsRejectedWith401() throws Exception {
        assertThat(postPing(null).statusCode()).isEqualTo(401);
    }

    /** Positive control: the /mcp chain admits the token, so A/B results are about mechanism. */
    @Test
    void authenticatedMcpRequestPassesTheSecurityChain() throws Exception {
        assertThat(postPing("Bearer " + SPIKE_TOKEN).statusCode()).isNotIn(401, 403);
    }

    private HttpResponse<String> postPing(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String callTool(String toolName) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        "http://localhost:" + port)
                .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + SPIKE_TOKEN))
                .build();
        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            CallToolResult result = client.callTool(
                    CallToolRequest.builder(toolName).arguments(Map.of()).build());
            assertThat(result.isError()).as("tool error: %s", result.content()).isNotEqualTo(Boolean.TRUE);
            return ((TextContent) result.content().getFirst()).text();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SpikeConfig {

        @Bean
        WhoAmITools whoAmITools() {
            return new WhoAmITools();
        }

        /** Replaces the auto-configured provider (it is @ConditionalOnMissingBean) to add mechanism B. */
        @Bean
        WebMvcStreamableServerTransportProvider spikeTransportProvider(
                @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
            return WebMvcStreamableServerTransportProvider.builder()
                    .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                    .mcpEndpoint(properties.getMcpEndpoint())
                    .keepAliveInterval(properties.getKeepAliveInterval())
                    .disallowDelete(properties.isDisallowDelete())
                    .contextExtractor(request -> McpTransportContext.create(Map.of(
                            PRINCIPAL_KEY,
                            request.principal().map(Principal::getName).orElse(ANONYMOUS))))
                    .build();
        }
    }

    static class WhoAmITools {

        @McpTool(name = "whoami_security_context", description = "Spike: principal via SecurityContextHolder")
        public String whoAmIFromSecurityContext() {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication == null ? ANONYMOUS : authentication.getName();
        }

        @McpTool(name = "whoami_transport_context", description = "Spike: principal via McpTransportContext")
        public String whoAmIFromTransportContext(McpTransportContext context) {
            Object principal = context.get(PRINCIPAL_KEY);
            return principal == null ? ANONYMOUS : principal.toString();
        }
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/security/SecurityChainsTest.java`:

```java
package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The three chains (spec §3, §8; ADR-002), through the application's real filter chain. These run before any write
 * endpoint exists, so a request that passes security reaches no handler and gets 404: that 404 is the positive control
 * that the chain let it through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityChainsTest {

    private static final String CONFIRM = "/api/holds/" + UUID.randomUUID() + "/confirm";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserDetailsService users;

    @Test
    void aBearerTokenIsRefusedOnAnonymousAndAuthenticatedApiEndpointsAlike() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get("/api/events"), post(CONFIRM))) {
            MockHttpServletResponse response =
                    perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice")));
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void anAgentTokenCannotPatchEvenForAUserWhoIsAlsoAnOrganiser() throws Exception {
        assertThat(perform(patch("/api/events/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("carol")))
                        .getStatus())
                .isEqualTo(401);
        // The same user, with their password: the role works on /api/**, so only the credential type was refused.
        assertThat(perform(patch("/api/events/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .with(as("carol")))
                        .getStatus())
                .isNotIn(401, 403);
    }

    @Test
    void noUnauthorizedResponseCarriesABasicChallenge() throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(post(CONFIRM), post(CONFIRM).with(httpBasic("alice", "wrong")), get("/nowhere"))) {
            MockHttpServletResponse response = perform(request);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(response.getContentType()).isEqualTo("application/problem+json");
        }
    }

    @Test
    void eachWriteEndpointAdmitsOnlyItsRole() throws Exception {
        String group = "/api/holds/" + UUID.randomUUID();
        // A fresh builder per request: with() on a reused builder would stack a second Authorization header.
        List<Supplier<MockHttpServletRequestBuilder>> buyerOnly = List.of(
                () -> post("/api/events/1/holds"),
                () -> post(CONFIRM),
                () -> delete(group),
                () -> get("/api/me/holds"));
        for (Supplier<MockHttpServletRequestBuilder> request : buyerOnly) {
            assertThat(perform(request.get().with(as("olivia"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("adam"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("alice"))).getStatus()).isNotIn(401, 403);
        }
        assertThat(perform(patch("/api/events/1").with(as("alice"))).getStatus())
                .isEqualTo(403);
        assertThat(perform(patch("/api/events/1").with(as("olivia"))).getStatus())
                .isNotIn(401, 403);
    }

    @Test
    void anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser() throws Exception {
        assertThat(perform(get("/api/admin").with(as("adam"))).getStatus()).isEqualTo(403);
    }

    @Test
    void aFormSubmittableContentTypeIsRefusedOnUnsafeMethods() throws Exception {
        for (String type : List.of("application/x-www-form-urlencoded", "multipart/form-data", "text/plain")) {
            assertThat(perform(post(CONFIRM).contentType(type).content("x").with(as("alice")))
                            .getStatus())
                    .as(type)
                    .isEqualTo(415);
        }
        assertThat(perform(get("/api/events").contentType("text/plain")).getStatus())
                .isNotEqualTo(415);
    }

    @Test
    void aCrossSiteWriteIsRefusedEvenWithNoBody() throws Exception {
        // A no-cors fetch or a sendBeacon from another site: a bodyless POST, with credentials a browser attached.
        MockHttpServletResponse crossSite =
                perform(post(CONFIRM).header("Sec-Fetch-Site", "cross-site").with(as("alice")));
        assertThat(crossSite.getStatus()).isEqualTo(403);
        assertThat(crossSite.getContentType()).isEqualTo("application/problem+json");
        assertThat(perform(post(CONFIRM).header("Sec-Fetch-Site", "same-origin").with(as("alice")))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void mcpTakesOnlyAKnownBearerToken() throws Exception {
        assertThat(perform(mcpPing()).getStatus()).isEqualTo(401);
        assertThat(perform(mcpPing().with(as("alice"))).getStatus()).isEqualTo(401);
        assertThat(perform(mcpPing().header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                        .getStatus())
                .isEqualTo(401);
        assertThat(perform(mcpPing().header(HttpHeaders.AUTHORIZATION, "Bearer " + TestCredentials.token("alice")))
                        .getStatus())
                .isNotIn(401, 403);
    }

    @Test
    void theCatchAllOpensOnlyItsAnonymousPaths() throws Exception {
        assertThat(perform(get("/actuator/health")).getStatus()).isEqualTo(200);
        for (MockHttpServletRequestBuilder request : List.of(
                get("/actuator/env"),
                get("/nowhere"),
                post("/nowhere"),
                get("/apix"),
                get("/mcpx"),
                // No chain keeps Spring's logout endpoint: there is no session to end.
                post("/logout"),
                get("/logout"))) {
            assertThat(perform(request).getStatus()).isEqualTo(401);
        }
        assertThat(perform(get("/actuator/env").with(as("adam"))).getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/logout")).getStatus()).isEqualTo(401);
        assertThat(perform(post("/api/logout").with(as("alice"))).getStatus()).isEqualTo(403);
    }

    @Test
    void bootsGeneratedUserDoesNotExist() {
        assertThatThrownBy(() -> users.loadUserByUsername("user")).isInstanceOf(UsernameNotFoundException.class);
    }

    private static MockHttpServletRequestBuilder mcpPing() {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
    }

    private static RequestPostProcessor as(String user) {
        return httpBasic(user, TestCredentials.password(user));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }
}
```

Create `src/test/java/io/github/markusluisflores/frontrow/security/SecurityFiltersTest.java`:

```java
package io.github.markusluisflores.frontrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityFiltersTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anyBearerSchemeIsRecognisedWhateverItsCase() {
        for (String header : List.of("Bearer abc", "bearer abc", "BEARER abc", "  Bearer\tabc", "Bearer")) {
            assertThat(BearerRejectFilter.isBearer(header)).as(header).isTrue();
        }
        for (String header : List.of("Basic YWxpY2U6eA==", "Bearerabc", "Token abc", "")) {
            assertThat(BearerRejectFilter.isBearer(header)).as(header).isFalse();
        }
        assertThat(BearerRejectFilter.isBearer(null)).isFalse();
    }

    @Test
    void onlyACrossSiteOrSameSiteFetchIsRecognised() {
        for (String value : List.of("cross-site", "same-site", "CROSS-SITE", " Same-Site ")) {
            assertThat(CrossSiteWriteFilter.isCrossSite(value)).as(value).isTrue();
        }
        for (String value : List.of("same-origin", "none", "")) {
            assertThat(CrossSiteWriteFilter.isCrossSite(value)).as(value).isFalse();
        }
        assertThat(CrossSiteWriteFilter.isCrossSite(null)).isFalse();
    }

    @Test
    void anUnsafeCrossSiteRequestIsRefusedWith403WhateverItsContentType() throws Exception {
        // A no-cors fetch or a sendBeacon: no Content-Type at all, so only Fetch Metadata shows where it came from.
        assertThat(crossSiteFilterStatus("POST", "cross-site", null)).isEqualTo(403);
        assertThat(crossSiteFilterStatus("DELETE", "same-site", null)).isEqualTo(403);
        assertThat(crossSiteFilterStatus("PATCH", "cross-site", "application/json"))
                .isEqualTo(403);
        assertThat(crossSiteFilterStatus("POST", "cross-site", "text/plain")).isEqualTo(403);
        // A same-origin or user-initiated request, or a client that sends no Fetch Metadata, passes.
        assertThat(crossSiteFilterStatus("POST", "same-origin", null)).isEqualTo(200);
        assertThat(crossSiteFilterStatus("POST", "none", null)).isEqualTo(200);
        assertThat(crossSiteFilterStatus("POST", null, null)).isEqualTo(200);
        // A form type is still refused when Fetch Metadata passes, and a safe method is never refused.
        assertThat(crossSiteFilterStatus("POST", "same-origin", "text/plain")).isEqualTo(415);
        assertThat(crossSiteFilterStatus("GET", "cross-site", "text/plain")).isEqualTo(200);
    }

    @Test
    void onlyFormSubmittableContentTypesAreRefused() {
        for (String type : List.of(
                "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x",
                "text/plain;charset=UTF-8",
                "TEXT/PLAIN",
                "not a media type")) {
            assertThat(CrossSiteWriteFilter.isFormSubmittable(type)).as(type).isTrue();
        }
        for (String type : List.of("application/json", "application/merge-patch+json")) {
            assertThat(CrossSiteWriteFilter.isFormSubmittable(type)).as(type).isFalse();
        }
        assertThat(CrossSiteWriteFilter.isFormSubmittable(null)).isFalse();
    }

    @Test
    void aKnownTokenAuthenticatesItsUserWithAgentAndNothingElse() throws Exception {
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("pw");
        new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfig.class)
                .withPropertyValues(
                        "frontrow.security.users[0].username=carol",
                        "frontrow.security.users[0].password-hash=" + hash,
                        "frontrow.security.users[0].roles=BUYER,ORGANISER",
                        "frontrow.security.agent-tokens[0].sha256=" + Credentials.sha256Hex("carols-token"),
                        "frontrow.security.agent-tokens[0].username=carol")
                .run(context -> {
                    AgentTokenFilter filter = new AgentTokenFilter(context.getBean(Credentials.class));

                    assertThat(authenticationFor(filter, "Bearer carols-token")).hasValueSatisfying(authentication -> {
                        assertThat(authentication.getName()).isEqualTo("carol");
                        // carol is also an ORGANISER, but a token carries no role (spec §3).
                        assertThat(authentication.getAuthorities())
                                .extracting(Object::toString)
                                .containsExactly("AGENT");
                    });
                    for (String header :
                            new String[] {"Bearer wrong", "Basic Y2Fyb2w6cHc=", "Bearer", "carols-token"}) {
                        assertThat(authenticationFor(filter, header)).as(header).isEmpty();
                    }
                });
    }

    /** The filter's status for one request: 200 means it passed the request on. */
    private static int crossSiteFilterStatus(String method, String secFetchSite, String contentType) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/holds/x/confirm");
        if (secFetchSite != null) {
            request.addHeader("Sec-Fetch-Site", secFetchSite);
        }
        request.setContentType(contentType);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new CrossSiteWriteFilter().doFilter(request, response, chain);
        assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }

    private static Optional<Authentication> authenticationFor(AgentTokenFilter filter, String authorization)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", authorization);
        List<Authentication> seen = new ArrayList<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.add(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return Optional.ofNullable(seen.getFirst());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./mvnw spotless:apply test -Dtest='SecurityChainsTest,SecurityFiltersTest,PrincipalPropagationSpikeTest'
```

Expected: FAIL. Compilation stops in `testCompile` with `cannot find symbol` for `class AgentTokenFilter` and `variable BearerRejectFilter`.

- [ ] **Step 3: Add the chains, the filters and the actuator**

Create `src/main/java/io/github/markusluisflores/frontrow/security/AgentTokenFilter.java`:

```java
package io.github.markusluisflores.frontrow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On /mcp, a known bearer token authenticates the user it was issued to, with the single AGENT authority and no role
 * (spec §3). An unknown token, or a Basic header, authenticates nothing, so the chain answers 401. The context is
 * also saved as a request attribute, as plan 1's spike did, so the MCP transport sees it (ADR-004).
 */
final class AgentTokenFilter extends OncePerRequestFilter {

    static final String AGENT = "AGENT";

    private final Credentials credentials;
    private final RequestAttributeSecurityContextRepository repository =
            new RequestAttributeSecurityContextRepository();

    AgentTokenFilter(Credentials credentials) {
        this.credentials = credentials;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> owner =
                rawToken(request.getHeader(HttpHeaders.AUTHORIZATION)).flatMap(credentials::ownerOfToken);
        if (owner.isPresent()) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    owner.get(), null, List.of(new SimpleGrantedAuthority(AGENT))));
            SecurityContextHolder.setContext(context);
            repository.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }

    static Optional<String> rawToken(String authorization) {
        if (!BearerRejectFilter.isBearer(authorization)) {
            return Optional.empty();
        }
        String token = authorization.strip().substring(6).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/BearerRejectFilter.java`:

```java
package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On /api/**, any bearer credential is refused with 401, on anonymous endpoints too (spec §3, ADR-002). Spring's Basic
 * filter silently ignores a Bearer header, so without this an agent token would pass as anonymous: the chains would
 * not be disjoint. The scheme is matched case-insensitively, as RFC 9110 says schemes are.
 */
final class BearerRejectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isBearer(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            Problems.write(request, response, Problems.plain(HttpStatus.UNAUTHORIZED));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isBearer(String authorization) {
        if (authorization == null) {
            return false;
        }
        String trimmed = authorization.strip();
        return trimmed.regionMatches(true, 0, "Bearer", 0, 6)
                && (trimmed.length() == 6 || Character.isWhitespace(trimmed.charAt(6)));
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/CrossSiteWriteFilter.java`:

```java
package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The second and third CSRF layers on /api/** (plan 4A decision 6). The first is that no 401 carries a Basic
 * challenge, so a browser never prompts for the credentials and never caches them. On an unsafe method, this filter
 * then refuses:
 *
 * <ul>
 *   <li>with 403, a Sec-Fetch-Site of cross-site or same-site. That catches what carries no form type at all: a
 *       no-cors fetch or a sendBeacon sends a bodyless POST with no Content-Type, and the browser attaches any cached
 *       credentials. same-origin, none and an absent header pass; curl and other non-browser clients send none;
 *   <li>with 415, the three content types a cross-site HTML form can send without a CORS preflight, and an unparseable
 *       one. No CORS is configured, so a preflight fails.
 * </ul>
 *
 * <p>A browser that sends no Fetch Metadata relies on the first layer alone against a request with no form type. That
 * includes every browser reaching the app over plain HTTP on a host other than localhost: browsers send Fetch Metadata
 * only to potentially trustworthy origins (HTTPS, or localhost).
 */
final class CrossSiteWriteFilter extends OncePerRequestFilter {

    private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final Set<MediaType> FORM_TYPES =
            Set.of(MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA, MediaType.TEXT_PLAIN);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod())) {
            if (isCrossSite(request.getHeader(SEC_FETCH_SITE))) {
                Problems.write(request, response, Problems.plain(HttpStatus.FORBIDDEN));
                return;
            }
            if (isFormSubmittable(request.getContentType())) {
                Problems.write(request, response, Problems.plain(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The two Fetch Metadata values that mean another site started the request; matched case-insensitively. */
    static boolean isCrossSite(String secFetchSite) {
        if (secFetchSite == null) {
            return false;
        }
        String value = secFetchSite.strip();
        return value.equalsIgnoreCase("cross-site") || value.equalsIgnoreCase("same-site");
    }

    static boolean isFormSubmittable(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return FORM_TYPES.stream().anyMatch(form -> form.equalsTypeAndSubtype(type));
        } catch (InvalidMediaTypeException unparseable) {
            // Spring MVC could not read it either; refuse it here rather than guess.
            return true;
        }
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/security/SecurityConfig.java`:

```java
package io.github.markusluisflores.frontrow.security;

import io.github.markusluisflores.frontrow.rest.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Three chains, tried in order (spec §3, §9; ADR-002). /api/** takes HTTP Basic only; /mcp takes an agent bearer token
 * only; everything else falls to a last chain with no matcher, which opens four anonymous paths and denies the rest.
 * Without that last chain, a path matching neither of the first two would be served with no security at all.
 *
 * <p>All three are stateless and keep Spring's CSRF token off: nothing here uses a session or a cookie. Plan 4A
 * decision 6 is what stands in for it on /api/**: no 401 carries a Basic challenge, so a browser never caches the
 * credentials; and CrossSiteWriteFilter refuses an unsafe method that a browser's Fetch Metadata marks as sent from
 * another site, and any form-submittable content type. A browser that sends no Fetch Metadata, which includes any
 * browser on plain HTTP to a host other than localhost, relies on the first layer alone against a request with no form
 * type. No chain has a logout endpoint: there is no session to end.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** Ours replaces Boot's generated user, which would otherwise authenticate on /api/** as a fallback. */
    @Bean
    UserDetailsService userDetailsService(Credentials credentials) {
        return credentials.userDetailsService();
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                // The spec §6 REST table, whole, including the endpoints plan 4B adds; anything unlisted is denied.
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HttpMethod.GET, "/api/events", "/api/events/*", "/api/events/*/availability")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events/*/holds", "/api/holds/*/confirm")
                        .hasRole("BUYER")
                        .requestMatchers(HttpMethod.DELETE, "/api/holds/*")
                        .hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/me/holds", "/api/me/orders")
                        .hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/*")
                        .hasAnyRole("BUYER", "ADMIN")
                        // Any ORGANISER, for any event: Phase 1 has no per-event ownership (plan 4A decision 2).
                        .requestMatchers(HttpMethod.POST, "/api/events")
                        .hasRole("ORGANISER")
                        .requestMatchers(HttpMethod.PATCH, "/api/events/*")
                        .hasRole("ORGANISER")
                        .anyRequest()
                        .denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized()))
                .addFilterBefore(new BearerRejectFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new CrossSiteWriteFilter(), BasicAuthenticationFilter.class);
        return stateless(http).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain mcpChain(HttpSecurity http, Credentials credentials) throws Exception {
        http.securityMatcher("/mcp", "/mcp/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(AgentTokenFilter.AGENT))
                .addFilterBefore(new AgentTokenFilter(credentials), AnonymousAuthenticationFilter.class);
        return stateless(http).build();
    }

    /** No securityMatcher, and evaluated last (ADR-002): the four anonymous paths of spec §3, and nothing else. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain catchAllChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                auth -> auth.requestMatchers("/error", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return stateless(http).build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(unauthorized()).accessDeniedHandler(forbidden()));
    }

    /** 401 with no WWW-Authenticate challenge (plan 4A decision 6), and a Problem Details body. */
    static AuthenticationEntryPoint unauthorized() {
        return (request, response, failure) ->
                Problems.write(request, response, Problems.plain(HttpStatus.UNAUTHORIZED));
    }

    static AccessDeniedHandler forbidden() {
        return (request, response, failure) -> Problems.write(request, response, Problems.plain(HttpStatus.FORBIDDEN));
    }
}
```

In `src/main/resources/application.yml`, find:

```yaml

frontrow:
```

Replace it with:

```yaml

management:
  endpoints:
    web:
      exposure:
        # Boot's default, stated: health is the one actuator endpoint, and the only one the catch-all chain opens.
        include: health

frontrow:
```

- [ ] **Step 4: Run them to verify they pass**

```bash
./mvnw spotless:apply test -Dtest='SecurityChainsTest,SecurityFiltersTest,PrincipalPropagationSpikeTest'
```

Expected: PASS, `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Mutation checks, each restored afterwards.** Each claim below is Mandatory-tier, so check that its test can fail. Make one change, re-run `SecurityChainsTest`, check the named tests fail, and restore. The authoring build ran all six and got these results:

  | File | Change | Fails |
  |---|---|---|
  | `SecurityConfig.java` | Delete `.addFilterBefore(new BearerRejectFilter(), BasicAuthenticationFilter.class)` | `aBearerTokenIsRefusedOnAnonymousAndAuthenticatedApiEndpointsAlike`. Only its anonymous endpoint shows the gap: on an authenticated endpoint, an ignored bearer falls to 401 anyway, which is why spec §8 asks for both |
  | `SecurityConfig.java` | Replace the catch-all's four paths with `"/**"` | `theCatchAllOpensOnlyItsAnonymousPaths`, and `noUnauthorizedResponseCarriesABasicChallenge`, whose `/nowhere` request now passes security |
  | `SecurityConfig.java` | Replace `.httpBasic(basic -> basic.authenticationEntryPoint(unauthorized()))` with `.httpBasic(basic -> {})` | `noUnauthorizedResponseCarriesABasicChallenge` |
  | `SecurityConfig.java` | Delete `.addFilterBefore(new CrossSiteWriteFilter(), BasicAuthenticationFilter.class)` | `aFormSubmittableContentTypeIsRefusedOnUnsafeMethods` and `aCrossSiteWriteIsRefusedEvenWithNoBody` |
  | `SecurityConfig.java` | Delete `.logout(logout -> logout.disable())` | `theCatchAllOpensOnlyItsAnonymousPaths`: `/logout` no longer gets 401 |
  | `CrossSiteWriteFilter.java` | Replace `isCrossSite(request.getHeader(SEC_FETCH_SITE))` with `isCrossSite(null)` | `aCrossSiteWriteIsRefusedEvenWithNoBody`, and only that: its bodyless request carries no form type, so nothing else refuses it |

- [ ] **Step 6: Record what was built in ADR-002**

In `docs/adr/ADR-002-agents-hold-humans-buy.md`, find:

````markdown

Not yet implemented. The REST and MCP adapters and the two credential chains this
governs arrive in Phase 1 plans 4 and 5. Plans 1 and 2 built only what sits beneath
them (plan 1's MCP spike aside), and plan 3A (hold creation) adds more beneath them. The full
design is in the spec, `docs/superpowers/specs/2026-09-17-frontrow-design.md`
§3 (*Caller identity*) and §6.
````

Replace it with:

````markdown

Partly implemented. Phase 1 plan 4A built the two credential chains, the catch-all chain
and the REST write endpoints, confirm included, in `security/SecurityConfig.java` and
`rest/`. The MCP tools arrive in plan 5, so `/mcp` authenticates agents but serves no tool
yet. The full design is in the spec, `docs/superpowers/specs/2026-09-17-frontrow-design.md`
§3 (*Caller identity*) and §6.
````

In `docs/adr/ADR-002-agents-hold-humans-buy.md`, find:

````markdown
* ✅ A fully compromised agent can at worst tie up seats until its holds expire,
  within the per-owner caps. It cannot spend money.
* ✅ The boundary is testable in both directions. Security tests must show a
````

Replace it with:

````markdown
* ✅ A fully compromised agent can at worst tie up seats until its holds expire,
  within the per-owner caps. It cannot spend money. Because an agent shares its
  human's owner (plan 4A decision 3), it can also release that human's holds and
  fill their hold-group cap; it still cannot buy.
* ✅ The boundary is testable in both directions. Security tests must show a
````

In `docs/adr/ADR-002-agents-hold-humans-buy.md`, find:

````markdown
  matcher — is served with no security applied, silently: no test fails.
  Plan 4 must add a catch-all chain with no `securityMatcher` that is
  evaluated *last* — Spring tries chains in ascending order and stops at
  the first match, so this chain needs the largest order value,
  `@Order(Ordered.LOWEST_PRECEDENCE)` — with `anyRequest().denyAll()`, plus
  a test that hits an unmatched path and expects 401/403.

*Plan numbering updated 2026-10-15 (PR #17) to the current six-plan Phase 1, after the 2026-10-02 re-plan and the 2026-10-14 split of Plan 3 into 3A and 3B; no decision changed.*
````

Replace it with:

````markdown
  matcher — is served with no security applied, silently: no test fails.
  Plan 4A added the catch-all chain this called for:
  `SecurityConfig#catchAllChain` has no `securityMatcher` and is evaluated
  *last*, with `@Order(Ordered.LOWEST_PRECEDENCE)`, since Spring tries chains in
  ascending order and stops at the first match. It opens only spec §3's
  anonymous paths (`/error`, `/actuator/health`, `/swagger-ui/**`,
  `/v3/api-docs/**`) and denies everything else.
  `SecurityChainsTest#theCatchAllOpensOnlyItsAnonymousPaths` hits unmatched
  paths, `/actuator/env` among them, and expects 401. Widening the chain to
  permit everything fails that test.

*Plan numbering updated 2026-10-15 (PR #17) after the 2026-10-02 re-plan and the 2026-10-14 split of Plan 3 into 3A and 3B, and again by plan 4A's split of Plan 4 into 4A and 4B, to the current seven-plan Phase 1 (1, 2, 3A, 3B, 4A, 4B, 5); no decision changed.*
````

- [ ] **Step 7: Run the whole build**

```bash
./mvnw spotless:apply verify
```

Expected: `Tests run: 243, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add docs/adr/ADR-002-agents-hold-humans-buy.md pom.xml src/main/java/io/github/markusluisflores/frontrow/security/AgentTokenFilter.java src/main/java/io/github/markusluisflores/frontrow/security/BearerRejectFilter.java src/main/java/io/github/markusluisflores/frontrow/security/CrossSiteWriteFilter.java src/main/java/io/github/markusluisflores/frontrow/security/SecurityConfig.java src/main/resources/application.yml src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java src/test/java/io/github/markusluisflores/frontrow/security/SecurityChainsTest.java src/test/java/io/github/markusluisflores/frontrow/security/SecurityFiltersTest.java
git commit -m "feat(security): add the api, mcp and catch-all security chains"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

### Task 5: The four write endpoints

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/EventController.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/HoldController.java`
- Create: `src/test/java/io/github/markusluisflores/frontrow/rest/WriteEndpointsTest.java` (test)

**Interfaces:**
- Consumes: `HoldService#hold`, `ReleaseService#release`, `ConfirmService#confirm` and `EventPatchService#patch`, with their commands and results; the chains (Task 4); the renderer (Task 3).
- Produces:
  - `POST /api/events/{eventId}/holds` → 201 `HoldResult`, including on a replay;
  - `DELETE /api/holds/{holdGroupId}` → 200 `ReleaseResult`;
  - `POST /api/holds/{holdGroupId}/confirm` → 200 `OrderResult`;
  - `PATCH /api/events/{eventId}` → 200 `EventPatchResult`.

  Request records `HoldController.HoldRequest(eventSeatIds)` and `EventController.EventPatchRequest(status, salesOpenAt, salesCloseAt)`, snake_case on the wire. Plan 4B adds its `GET`s and `POST /api/events` to `EventController`, and new controllers beside it.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/io/github/markusluisflores/frontrow/rest/WriteEndpointsTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four write endpoints end to end: the real filter chain, the controllers, the services and Postgres. Bodies are
 * compared as parsed JSON, never as strings (PR #18 thread 4296749419).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTestConfiguration.class)
class WriteEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;
    long eventId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 6);
    }

    @Test
    void holdReplayConfirmAndConfirmAgain() throws Exception {
        MockHttpServletResponse held = perform(hold("alice", "k1", seats.get(1), seats.get(0)));
        assertThat(held.getStatus()).isEqualTo(201);
        JsonNode hold = body(held);
        assertThat(hold.get("event_id").asLong()).isEqualTo(eventId);
        assertThat(hold.get("seats").findValuesAsString("event_seat_id"))
                .containsExactly(seats.get(0).toString(), seats.get(1).toString());
        assertThat(hold.get("total_cents").asLong()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
        assertThat(hold.get("expires_at").asString()).isEqualTo("2026-10-01T12:10:00Z");

        MockHttpServletResponse replayed = perform(hold("alice", "k1", seats.get(0), seats.get(1)));
        assertThat(replayed.getStatus()).isEqualTo(201);
        assertThat(body(replayed)).isEqualTo(hold);

        String group = hold.get("hold_group_id").asString();
        MockHttpServletResponse confirmed =
                perform(post("/api/holds/" + group + "/confirm").with(as("alice")));
        assertThat(confirmed.getStatus()).isEqualTo(200);
        JsonNode order = body(confirmed);
        assertThat(order.get("hold_group_id").asString()).isEqualTo(group);
        assertThat(order.get("lines")).hasSize(2);

        MockHttpServletResponse again =
                perform(post("/api/holds/" + group + "/confirm").with(as("alice")));
        assertThat(body(again)).isEqualTo(order);
        assertThat(fixtures.ordersForGroup(UUID.fromString(group))).isEqualTo(1);
    }

    @Test
    void releaseFreesTheSeatsForSomeoneElse() throws Exception {
        String group = body(perform(hold("alice", "k1", seats.get(0))))
                .get("hold_group_id")
                .asString();

        MockHttpServletResponse released = perform(delete("/api/holds/" + group).with(as("alice")));

        assertThat(released.getStatus()).isEqualTo(200);
        assertThat(body(released)).isEqualTo(JSON.readTree("""
                        {"hold_group_id": "%s", "released": 1, "expired": 0}""".formatted(group)));
        assertThat(perform(hold("bob", "k1", seats.get(0))).getStatus()).isEqualTo(201);
    }

    @Test
    void anotherOwnersHoldIsNotFoundToConfirmAndToRelease() throws Exception {
        String group = body(perform(hold("alice", "k1", seats.get(0))))
                .get("hold_group_id")
                .asString();

        for (MockHttpServletRequestBuilder request :
                List.of(post("/api/holds/" + group + "/confirm"), delete("/api/holds/" + group))) {
            MockHttpServletResponse response = perform(request.with(as("bob")));
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(body(response).get("code").asString()).isEqualTo("not_found");
        }
        assertThat(fixtures.ordersForGroup(UUID.fromString(group))).isZero();
    }

    @Test
    void aTakenSeatIsAConflictNamingTheSeat() throws Exception {
        perform(hold("alice", "k1", seats.get(0)));

        MockHttpServletResponse taken = perform(hold("bob", "k1", seats.get(0)));

        assertThat(taken.getStatus()).isEqualTo(409);
        JsonNode problem = body(taken);
        assertThat(problem.get("code").asString()).isEqualTo("seat_taken");
        assertThat(problem.get("seat_ids").get(0).asLong()).isEqualTo(seats.get(0));
    }

    @Test
    void theIdempotencyKeyIsNamedAsTheHeaderWhetherMissingOrMalformed() throws Exception {
        MockHttpServletResponse missing = perform(post("/api/events/" + eventId + "/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_seat_ids\": [%d]}".formatted(seats.get(0)))
                .with(as("alice")));
        MockHttpServletResponse blank = perform(hold("alice", " ", seats.get(0)));

        for (MockHttpServletResponse response : List.of(missing, blank)) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(body(response).get("parameter").asString()).isEqualTo("Idempotency-Key");
        }
    }

    @Test
    void aKeyReusedForADifferentRequestIsUnprocessable() throws Exception {
        perform(hold("alice", "k1", seats.get(0)));

        MockHttpServletResponse reused = perform(hold("alice", "k1", seats.get(1)));

        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(body(reused).get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void thePerOwnerGroupCapHoldsThroughRest() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(perform(hold("alice", "k" + i, seats.get(i))).getStatus())
                    .isEqualTo(201);
        }

        MockHttpServletResponse capped = perform(hold("alice", "k3", seats.get(3)));

        assertThat(capped.getStatus()).isEqualTo(409);
        assertThat(body(capped).get("code").asString()).isEqualTo("hold_limit_exceeded");
        assertThat(perform(hold("bob", "k3", seats.get(3))).getStatus()).isEqualTo(201);
    }

    @Test
    void malformedRequestsNameTheParameter() throws Exception {
        assertInvalid(post("/api/holds/not-a-uuid/confirm").with(as("alice")), "hold_group_id");
        assertInvalid(
                post("/api/events/x/holds")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_seat_ids\": [1]}")
                        .with(as("alice")),
                "event_id");
        assertInvalid(
                post("/api/events/" + eventId + "/holds")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null")
                        .with(as("alice")),
                "body");
        assertInvalid(hold("alice", "k1"), "event_seat_ids");
        assertInvalid(
                post("/api/events/" + eventId + "/holds")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_seat_ids\": [1, null]}")
                        .with(as("alice")),
                "event_seat_ids");
    }

    @Test
    void anOrganiserCancelsAndTheHoldsAreReleased() throws Exception {
        perform(hold("alice", "k1", seats.get(0), seats.get(1)));

        MockHttpServletResponse cancelled = perform(patchEvent("olivia", "{\"status\": \"CANCELLED\"}"));

        assertThat(cancelled.getStatus()).isEqualTo(200);
        JsonNode result = body(cancelled);
        assertThat(result.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(result.get("released_holds").asInt()).isEqualTo(2);
        assertThat(fixtures.eventStatus(eventId)).isEqualTo("CANCELLED");
    }

    @Test
    void aPatchOutsideWhatPostgresCanStoreIsInvalidNotA500() throws Exception {
        MockHttpServletResponse response =
                perform(patchEvent("olivia", "{\"sales_close_at\": \"+300000-01-01T00:00:00Z\"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response).get("parameter").asString()).isEqualTo("sales_window");
    }

    @Test
    void anUnknownStatusNamesTheField() throws Exception {
        assertInvalid(
                patch("/api/events/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SOLD_OUT\"}")
                        .with(as("olivia")),
                "status");
    }

    private MockHttpServletRequestBuilder hold(String user, String key, Long... seatIds) {
        return post("/api/events/" + eventId + "/holds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("event_seat_ids", List.of(seatIds))))
                .with(as(user));
    }

    private MockHttpServletRequestBuilder patchEvent(String user, String json) {
        return patch("/api/events/" + eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(as(user));
    }

    private void assertInvalid(MockHttpServletRequestBuilder request, String parameter) throws Exception {
        MockHttpServletResponse response = perform(request);
        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode problem = body(response);
        assertThat(problem.get("code").asString()).isEqualTo("invalid_request");
        assertThat(problem.get("parameter").asString()).isEqualTo(parameter);
    }

    private static RequestPostProcessor as(String user) {
        return httpBasic(user, TestCredentials.password(user));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./mvnw spotless:apply test -Dtest='WriteEndpointsTest'
```

Expected: FAIL. It compiles, then `Tests run: 11, Failures: 9, Errors: 2`: with no controller, every request that passes security gets 404.

- [ ] **Step 3: Add the controllers**

Create `src/main/java/io/github/markusluisflores/frontrow/rest/EventController.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.service.EventPatch;
import io.github.markusluisflores.frontrow.service.EventPatchResult;
import io.github.markusluisflores.frontrow.service.EventPatchService;
import java.time.Instant;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * An organiser's changes to an event (spec §6): its status, cancellation included, and its sales window. Any
 * ORGANISER may change any event, because Phase 1 records no event owner (plan 4A decision 2). Plan 4B adds the reads
 * and event creation here.
 */
@RestController
public class EventController {

    private final EventPatchService patches;

    public EventController(EventPatchService patches) {
        this.patches = patches;
    }

    /** The JSON body of a PATCH. An absent field keeps its current value. */
    public record EventPatchRequest(EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {}

    @PatchMapping("/api/events/{eventId}")
    public EventPatchResult patch(@PathVariable long eventId, @RequestBody EventPatchRequest request) {
        return patches.patch(new EventPatch(eventId, request.status(), request.salesOpenAt(), request.salesCloseAt()));
    }
}
```

Create `src/main/java/io/github/markusluisflores/frontrow/rest/HoldController.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.markusluisflores.frontrow.service.ConfirmService;
import io.github.markusluisflores.frontrow.service.HoldCommand;
import io.github.markusluisflores.frontrow.service.HoldGroupCommand;
import io.github.markusluisflores.frontrow.service.HoldResult;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.service.OrderResult;
import io.github.markusluisflores.frontrow.service.ReleaseResult;
import io.github.markusluisflores.frontrow.service.ReleaseService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A buyer's holds (spec §6, REST surface): hold, release, and confirm, the only purchase path. Each method maps the
 * request to one service call; the owner is always the authenticated user's name, never a parameter (spec §3). No
 * method here is transactional: each service starts its own transaction, and refuses to join one (plan 4A decision 4).
 */
@RestController
public class HoldController {

    private final HoldService holds;
    private final ReleaseService releases;
    private final ConfirmService confirms;

    public HoldController(HoldService holds, ReleaseService releases, ConfirmService confirms) {
        this.holds = holds;
        this.releases = releases;
        this.confirms = confirms;
    }

    /**
     * The JSON body of a hold: the specific seats wanted (spec §6; the service never chooses seats). Jackson refuses a
     * null element itself, so the error names event_seat_ids; List.copyOf would throw on it, and only "body" would be
     * named.
     */
    public record HoldRequest(
            @JsonSetter(contentNulls = Nulls.FAIL) List<Long> eventSeatIds) {

        public HoldRequest {
            eventSeatIds = eventSeatIds == null ? null : List.copyOf(eventSeatIds);
        }
    }

    /** 201 on a new hold and on a replay alike: a replay returns the original result (spec §5). */
    @PostMapping("/api/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResult hold(
            @PathVariable long eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HoldRequest request,
            Principal principal) {
        // A missing body, or a literal null, never gets here: Spring refuses it, and it is invalid_request "body".
        return holds.hold(new HoldCommand(principal.getName(), eventId, request.eventSeatIds(), idempotencyKey));
    }

    @DeleteMapping("/api/holds/{holdGroupId}")
    public ReleaseResult release(@PathVariable UUID holdGroupId, Principal principal) {
        return releases.release(new HoldGroupCommand(principal.getName(), holdGroupId));
    }

    /** Idempotent: a repeated confirm returns the order the first one made (spec §5). */
    @PostMapping("/api/holds/{holdGroupId}/confirm")
    public OrderResult confirm(@PathVariable UUID holdGroupId, Principal principal) {
        return confirms.confirm(new HoldGroupCommand(principal.getName(), holdGroupId));
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

```bash
./mvnw spotless:apply test -Dtest='WriteEndpointsTest'
```

Expected: PASS, `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Run the whole build**

```bash
./mvnw spotless:apply verify
```

Expected: `Tests run: 254, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/rest/EventController.java src/main/java/io/github/markusluisflores/frontrow/rest/HoldController.java src/test/java/io/github/markusluisflores/frontrow/rest/WriteEndpointsTest.java
git commit -m "feat(rest): add the hold, release, confirm and event patch endpoints"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

### Task 6: The request bounds over a real socket

**Files:**
- Create: `src/test/java/io/github/markusluisflores/frontrow/rest/RequestBoundsOverHttpTest.java` (test)

**Interfaces:**
- Consumes: `RequestBodyLimitFilter` (Task 3), the hold endpoint (Task 5), `TestCredentials` (Task 2).
- Produces: nothing new.

MockMvc never opens a socket. So it can't send a chunked body that declares no length, and it never applies Tomcat's header limit. This test starts the app on a random port and uses `java.net.http` over HTTP/1.1. **It passes on its first run,** because Task 3 built the filter and turned Boot's `FormContentFilter` off. Step 3's mutations are what show it can fail.

- [ ] **Step 1: Write the test**

Create `src/test/java/io/github/markusluisflores/frontrow/rest/RequestBoundsOverHttpTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request bounds over a real socket (plan 4A decision 8), where MockMvc cannot reach: a chunked body declares no
 * length, so only the counting stream can stop it; and Tomcat's own header limit bounds Idempotency-Key. Boot's
 * FormContentFilter is off, so nothing reads a form body before security answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceTestConfiguration.class)
class RequestBoundsOverHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long eventId;
    long seatId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seatId = fixtures.eventSeats(eventId, venueId, 1).getFirst();
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedAsItIsRead() throws Exception {
        byte[] body = paddedHold(RequestBodyLimitFilter.MAX_BODY_BYTES + 1);
        HttpRequest request = holdRequest("k1")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).get("title").asString()).isEqualTo("Content Too Large");
        assertThat(fixtures.groupsOwnedBy("alice")).isZero();
    }

    @Test
    void aChunkedBodyAtTheLimitIsServed() throws Exception {
        byte[] body = paddedHold(RequestBodyLimitFilter.MAX_BODY_BYTES);
        HttpRequest request = holdRequest("k1")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(201);
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefused() throws Exception {
        HttpRequest request = holdRequest("k1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(paddedHold(RequestBodyLimitFilter.MAX_BODY_BYTES + 1)))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(413);
    }

    @Test
    void anAnonymousChunkedFormBodyOverTheLimitIsRefusedNotA500() throws Exception {
        // Boot's FormContentFilter would read this PUT body before Spring Security, hit the bound and answer 500.
        byte[] form = ("a=" + "x".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES)).getBytes(StandardCharsets.UTF_8);
        for (String path : new String[] {"/api/events/1", "/nowhere"}) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(form)))
                    .build();

            int expected = path.startsWith("/api/") ? 415 : 401;
            assertThat(send(request).statusCode()).as(path).isEqualTo(expected);
        }
    }

    @Test
    void anOversizedIdempotencyKeyNeverReachesTheService() throws Exception {
        HttpRequest request = holdRequest("k".repeat(9 * 1024))
                .POST(HttpRequest.BodyPublishers.ofByteArray(paddedHold(0)))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(400);
        assertThat(fixtures.holdRequestsOwnedBy("alice")).isZero();
    }

    /** A valid hold for the one seat, padded with whitespace to exactly the given size (or unpadded, for 0). */
    private byte[] paddedHold(int size) {
        String json = "{\"event_seat_ids\": [" + seatId + "]}";
        String padding = " ".repeat(Math.max(0, size - json.length()));
        return (json.substring(0, json.length() - 1) + padding + "}").getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder holdRequest(String idempotencyKey) {
        String basic = Base64.getEncoder()
                .encodeToString(("alice:" + TestCredentials.password("alice")).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + eventId + "/holds"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + basic)
                .header("Idempotency-Key", idempotencyKey);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        // HTTP/1.1, so a body with no declared length goes out chunked.
        try (HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
```

- [ ] **Step 2: Run it**

```bash
./mvnw spotless:apply test -Dtest='RequestBoundsOverHttpTest'
```

Expected: PASS, `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 3: Mutation checks, each restored afterwards.** First, in `RequestBodyLimitFilter`, replace `chain.doFilter(new LimitedRequest(request), response);` with `chain.doFilter(request, response);` and re-run Step 2. Expected: `aChunkedBodyOverTheLimitIsRefusedAsItIsRead` fails, and only that test: a declared length is still refused before the wrapper. Restore the line. Then, in `application.yml`, set `spring.mvc.formcontent.filter.enabled` to `true` and re-run Step 2. Expected: `anAnonymousChunkedFormBodyOverTheLimitIsRefusedNotA500` fails, with 500 where 415 was expected on `/api/events/1`. Restore it. These are the authoring build's results.

- [ ] **Step 4: Run the whole build**

```bash
./mvnw spotless:apply verify
```

Expected: `Tests run: 259, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/github/markusluisflores/frontrow/rest/RequestBoundsOverHttpTest.java
git commit -m "test(rest): prove the request bounds over a real socket"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

### Task 7: `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Record the current state and the three new rules**

In `CLAUDE.md`, find:

````markdown

**Phase 1 plans 1 (foundation), 2 (domain foundations), 3A (hold creation) and 3B (confirm, release,
cancellation, the sweeper) are complete. Together they provide: a Maven build, CI, the V1 schema
migration, a principal-propagation spike, a pure `domain` package (money, statuses, sales window, hold
````

Replace it with:

````markdown

**Phase 1 plans 1 (foundation), 2 (domain foundations), 3A (hold creation), 3B (confirm, release,
cancellation, the sweeper) and 4A (the security edge and the REST write endpoints) are complete.
Together they provide: a Maven build, CI, the V1 schema
migration, a principal-propagation spike, a pure `domain` package (money, statuses, sales window, hold
````

In `CLAUDE.md`, find:

````markdown
release (`ReleaseService`), the event PATCH and cancellation (`EventPatchService`), and the expiry sweeper
and 24-hour key purge (`ExpirySweepService`, scheduled by `config/SweepSchedule`). Creating an event has no service yet, and no REST endpoint or MCP tool exists.**
197 test runs pass, including all seven spec §5 concurrency tests and a proof that test 1 fails without
`uq_claimed_seat`. Plans 4 (REST + security chains) and 5 (MCP) remain.
The migration itself is `src/main/resources/db/migration/V1__core_schema.sql`. The design spec is
````

Replace it with:

````markdown
release (`ReleaseService`), the event PATCH and cancellation (`EventPatchService`), and the expiry sweeper
and 24-hour key purge (`ExpirySweepService`, scheduled by `config/SweepSchedule`). Plan 4A adds the
three security chains (`security/SecurityConfig`), hashed demo credentials, Problem Details errors and a
16 KiB request-body bound (`rest/`), and four REST endpoints over those services: hold, release,
confirm and the event PATCH. No read endpoint, no event creation and no MCP tool exists yet.**
259 test runs pass, including all seven spec §5 concurrency tests and a proof that test 1 fails without
`uq_claimed_seat`. Plans 4B (the read services and GET endpoints, event creation, OpenAPI) and 5 (MCP)
remain.
The migration itself is `src/main/resources/db/migration/V1__core_schema.sql`. The design spec is
````

In `CLAUDE.md`, find:

````markdown
`docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`. Plan 3B:
`docs/superpowers/plans/2026-10-17-phase-1-plan-3b-confirm-release-cancel.md`.
````

Replace it with:

````markdown
`docs/superpowers/plans/2026-10-14-phase-1-plan-3a-hold-creation.md`. Plan 3B:
`docs/superpowers/plans/2026-10-17-phase-1-plan-3b-confirm-release-cancel.md`. Plan 4A:
`docs/superpowers/plans/2026-10-20-phase-1-plan-4a-security-edge.md`.
````

In `CLAUDE.md`, find:

````markdown
- MCP is served over Streamable HTTP only — no stdio, no SSE (ADR-003).
````

Replace it with:

````markdown
- MCP is served over Streamable HTTP only — no stdio, no SSE (ADR-003).
- Every security rule lives in `security/SecurityConfig.java`: the `/api/**` chain (Basic only, with
  the spec §6 role table), the `/mcp` chain (agent bearer tokens only, `AGENT` authority), and a last
  catch-all chain that denies everything but four anonymous paths. An `/api/**` path the table
  doesn't list is denied, so a new endpoint needs its rule there.
- Every service entry point is `@Transactional(isolation = READ_COMMITTED)` and calls
  `TransactionBoundary.requireOwnTransaction()` as its first statement; no adapter is
  `@Transactional`. `Propagation.NEVER` is not the answer: it runs the method with no transaction
  at all (plan 4A decision 4). The check is a chain: `TransactionIsolationTest` scans `service/`
  for `@Service` classes and fails unless they equal its `SERVICES` list, and
  `ServiceTransactionBoundaryTest` fails on a public method of a listed service it doesn't cover.
  So a new service fails the build until it is listed and its entry points are covered.
- REST errors are RFC 9457 Problem Details from `rest/RestExceptionHandler`: a `DomainException`
  carries its `code`, and anything else is a generic 500 that echoes nothing.
````

In `CLAUDE.md`, find:

````markdown
  the spec) + AssertJ + Testcontainers. Run everything with `./mvnw verify`.
````

Replace it with:

````markdown
  the spec) + AssertJ + Testcontainers. Run everything with `./mvnw verify`.
- No usable credential is committed. `scripts/demo-credentials.sh` writes bcrypt and SHA-256 hashes
  to the gitignored `config/application-demo.yml` and prints the raw values once; run the app with
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo`. Without that profile, startup fails:
  there are no users. Tests generate their own credentials per run (`support/TestCredentials`).
````

- [ ] **Step 2: Run the clean build and the architecture greps**

```bash
./mvnw clean verify
```

Expected: `Tests run: 259, Failures: 0, Errors: 0, Skipped: 0`, `BugInstance size is 0` and `BUILD SUCCESS`.

```bash
grep -rlniE "for (no key )?update|for (key )?share|pg_[a-z_]*advisory|@Lock\b|LockModeType|PESSIMISTIC|skip locked|nowait|lock table" src/main/java/
```

Expected: exactly one line, `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`.

```bash
grep -rn "^import" src/main/java/io/github/markusluisflores/frontrow/domain/ | grep -v "import java\." || echo "domain imports only java.*"
```

Expected: `domain imports only java.*`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): record plan 4A in the current state and the rules"
```

Expected: the commit-msg hook accepts the subject, and gitleaks reports `no leaks found`.

---

## Coverage

**Spec requirements this plan owns:**

| Requirement | Task |
|---|---|
| §3: `/api/**` Basic only, with an explicit 401 filter for bearer headers, on anonymous endpoints too | 4 |
| §3: `/mcp` bearer only; a token carries `AGENT` and no role; tokens issued only to `BUYER` users | 2, 4 |
| §3: everything outside the two chains closed, except `/swagger-ui/**`, `/v3/api-docs/**`, `/error` and `/actuator/health`; no other actuator endpoint | 4 |
| §3: no tool parameter carries identity; the owner comes from the principal | 5 |
| §6: an agent token cannot reach the purchase endpoint at all | 4 |
| §6, the threat model (only a human can buy): a hostile page can't buy with a browser's credentials, within decision 6's stated limit for a browser that sends no Fetch Metadata | 4 |
| §6: hold, release, confirm and `PATCH` endpoints, with their roles | 4, 5 |
| §6: `Idempotency-Key` header required on the REST hold | 5 |
| §6: another owner's hold answers `not_found` to release and confirm | 5 |
| §6: Problem Details with `code` as an extension member; a hint on every code | 3 |
| §6: the per-owner caps hold through REST | 5 |
| §8: bearer rejected on `/api/**`, anonymous and authenticated; Basic rejected on `/mcp`; unauthenticated `/mcp` 401; owner-only release and confirm; per-owner caps; an agent token can't use `ORGANISER` or `ADMIN` powers | 4, 5 |
| §9: in-memory Basic users with `BUYER`, `ORGANISER` and `ADMIN`; static bearer tokens stored only as SHA-256 hashes; demo tokens generated at setup | 2 |
| §12: no raw token in the repository or committed config | 2 (and every test, by decision 5) |
| §12: the chains disjoint in both directions | 4 |
| ADR-002: the catch-all chain and its test, and the stale line updated | 4 |

**The Plan 4 inputs from the journal's Pending list:**

| Input | Closed by |
|---|---|
| Event creation has no service | Decision 1: plan 4B builds it |
| REST `Idempotency-Key` naming (4296541034) | Decision 7, Task 3 (`theIdempotencyKeyIsNamedAsRestCarriesIt`), Task 5 |
| Owner validation (4296749409) | Decision 3, Task 2 (`aUsernameThatCouldAliasAnotherIsRejected`) |
| The iteration order of `details()` (4296749419) | Decision 7, Task 3 (`extensionMembersAreInKeyOrderWhateverOrderDetailsIteratesIn`); every body compared as a tree |
| `Propagation.NEVER`, or no `@Transactional` adapters (4296540451) | Decision 4, Task 1 |
| Out-of-range `PATCH` instants (4305094277) | Decision 8, Task 1, Task 5 |
| Who may PATCH | Decision 2, Task 4 |
| The organiser can't GET their own draft | Decision 2, as a stated limit carried to 4B |
| A generic 500 for anything not a `DomainException` | Decision 7, Task 3 (`anythingElseIsAGeneric500ThatEchoesNothing`) |
| Human and agent principals never map to the same owner string | Decision 3, Task 2 |
| Bound request size at the adapter | Decision 8, Tasks 3 and 6 |

**Verification-matrix crossing.** The security tests have two independent dimensions: the credential type (none, Basic, bearer) and the endpoint's access rule (anonymous, role-gated, unlisted). The two most likely to interact are **a bearer credential** and **an anonymous endpoint**: a bearer header that Spring ignores becomes anonymous, and anonymous is exactly what an anonymous endpoint admits. `aBearerTokenIsRefusedOnAnonymousAndAuthenticatedApiEndpointsAlike` crosses them, and Task 4's first mutation shows it is the only test that catches that gap.

## How this plan was checked: the replay

**The scratch chain.** This plan's code was written and built first, as seven commits off `main` (`683a308`), rebuilt after the round 1 and round 2 reviews:
- `31ee273`: transaction guard and PATCH bounds;
- `f8c6596`: credentials;
- `d9a517f`: Problem Details and the body bound;
- `c7615dd`: chains;
- `c10944e`: endpoints;
- `4a09cd8`: bounds over HTTP;
- `b06cee5`: `CLAUDE.md`.

Every code block above was generated from those commits.

**The replay** (2026-10-20, rerun after the round 2 review). A harness read *this document*, not the commits. On a fresh worktree of `main`, it applied every code block and ran every `bash` command, in order.
- **Exit codes:** a command whose "Expected:" line says FAIL had to exit non-zero, and every other command had to exit zero.
- **Output:** every backticked fragment in an "Expected:" line had to appear in the command's output.
- **Commits:** after each commit command, the commit's tree had to be identical to its scratch commit's tree.

**Result: 29 commands checked, 0 failures; all seven commit trees identical to the scratch chain.** The red steps of Tasks 1–5 failed as stated, and every green step, verify and commit passed as stated. The test-run counts at each task's verify were 202, 211, 228, 243, 254, 259 and 259 (clean).

**What the replay does not check:**
- **The mutation steps** (Task 1 Step 5, Task 4 Step 5, Task 6 Step 3) are prose. The authoring build ran each one, and the plan states its result.
- **Task 7's "exactly one line".** The harness checked only that `LockingGateway.java` appears.
- **CI.** It runs on the PR that executes this plan.
