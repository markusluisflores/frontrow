# FrontRow Phase 1, Plan 5 (The MCP Tools and the Evidence) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Plan form: task-level, no code (Markus, 2026-11-04). This instruction outranks `writing-plans`' "No Placeholders" rule for code steps.** This plan holds decisions, files, exact interfaces, the tests each task must have, and acceptance criteria. It holds no code bodies. The executor writes each test first, watches it fail, then writes the code, and the code is reviewed once, as real code (queue item 10, cause 3). A named signature or wire shape in this plan is a contract. Anything else is the executor's to choose, following the patterns already on `main`.

**Goal:** Finish FrontRow. That means:
- the five MCP tools over Streamable HTTP, as a thin adapter over the existing services;
- the `/mcp` hardening that plan 4A's review deferred;
- Phase 1 tool logging;
- the §7 evidence: checked-in tool schemas that CI holds to the code, a real reproduced failure-and-recovery run log, and a recorded demo;
- the README sections plan 5 owes (architecture, threat model, metrics, known limitations, next steps).

**Architecture:**
- **The adapter** is a new `mcp/` package: `@McpTool` methods that each make one service call and translate the result or a `DomainException` into a tool result. It reads the owner from `McpTransportContext` (ADR-004) and passes it to the service as a plain `String`. The domain core and the services learn nothing of MCP.
- **The reads reuse** `EventReadService` (`search`, `event`, `availability`). **The writes reuse** `HoldService#hold` and `ReleaseService#release`. No new service, no new lock SQL.
- **`/mcp` hardening** stays in the `security/` and `rest/` filters that already guard it.
- **The evidence** is a test-scope scripted MCP client, two shell scripts, committed text artifacts and a human-recorded demo.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1 (`@McpTool`, Streamable HTTP, MCP Java SDK 2.0.0), JUnit Jupiter 6, AssertJ, Testcontainers. No new dependency is expected; a task that needs one says so in its report.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.7.1). Read:
- **§12's "Plan 5" and "The whole project" boxes:** the whole scope of this plan;
- §3 *Caller identity* (the disjoint chains, the `/mcp` header rule) and *Scaffold spike*;
- §6 in full: the tool table and bullets, *The threat model*, *Guardrails* (tool logging), *Error contracts*, *Request bounds*;
- §7: the artifacts, the demo story, the logged failure run, the credential rule;
- §9: the portfolio quality bar and its two closed lists (the README reproduces them).

Also read ADR-002, ADR-003, ADR-004 (owner from `McpTransportContext`), ADR-005 and ADR-006, and `PrincipalPropagationSpikeTest`, which already proves the principal reaches a tool method through the production `/mcp` chain.

## Global Constraints

- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). `domain` imports only `java.*`; the services import no MCP type. Check: `grep -rn "^import" src/main/java/io/github/markusluisflores/frontrow/domain/ | grep -v "import java\."` prints nothing, and `grep -rln "modelcontextprotocol\|springframework.ai" src/main/java/` prints only files under `mcp/` and any `config/` wiring the plan names. A change to a service to suit a tool is a boundary leak and a review BLOCKER.
- **All lock SQL lives in `persistence/LockingGateway.java`** (ADR-005), comments included. No task adds lock SQL or lock vocabulary elsewhere (`CLAUDE.md` has the grep).
- **No MCP tool may complete a purchase** (ADR-002). There is no `confirm_order`; Task 3 tests it.
- **No tool parameter carries identity.** The owner comes only from the authenticated principal (§3).
- **MCP errors are structured, never strings:** `{"code": ..., "hint": ..., details}` with the REST codes (§6). An unstructured string error is a review BLOCKER.
- **No raw credential in the repository, a log line, a committed artifact or a recording** (§7, §12). Tests generate their own credentials (`support/TestCredentials`). Raw tokens and passwords never go on a command line, in a test name or in an assertion message.
- **Every service entry point** keeps `@Transactional(isolation = READ_COMMITTED)` and `TransactionBoundary.requireOwnTransaction()`. This plan adds no service. If one is added after all, it joins `TransactionIsolationTest.SERVICES` and `ServiceTransactionBoundaryTest#calls()`.
- **The wire is snake_case.** Compare JSON as parsed trees, never as strings.
- Real Postgres only (Testcontainers). Never H2, never a mocked repository.
- Java 25, `-Xlint:all,-processing -Werror` (tests included), Spotless (palantir), SpotBugs. `./mvnw verify` is the test command and must be green at every commit. A new file in this checkout needs `./mvnw spotless:apply` before a test run (the checkout is CRLF).
- Windows traps (memory `windows-build-traps`): run shell from Git Bash, never WSL `bash`; mutate files in binary mode; delete stale `target/` copies of test resources; a mutation check asserts the expected failure message, not just a red build.
- Commit messages: `<type>(<scope>): <subject>`, 72 characters max, no trailing period (`.githooks/commit-msg`). No local device path in a committed file.

## Plan-level decisions

Decisions 1 and 2 are Markus's (brainstorm, 2026-11-04). The rest are this plan's; each is listed so a reviewer can reject it on its own.

1. **Task-level plan, no code** (Markus). See the header. A reviewer checks that each task's interface, tests and acceptance criteria are enough to write the code from, and that nothing contradicts the code on `main`. The need for a standing override of `writing-plans`' rule is recorded in `workflow-audits\frontrow\queue.md` (item 14), not applied to global config.
2. **The agent in `logs/example_run.txt` is a scripted MCP client** (Markus). A test-scope Java client using the MCP SDK drives the real server over real HTTP. The §7 scenario is scripted in order: `check_availability`; `scripts/take-seat.sh` takes one of those seats as a second user; `hold_seats` returns `seat_taken`; the client calls `check_availability` again and holds different seats. The log header says the conflicting hold was scripted. The conflict, the error and the recovery are the server's real behaviour, unedited. The recording of the demo is a real Claude Code session, recorded by Markus (Task 8).
3. **Tool names, parameters and access are §6's table, exactly.** Wire parameter names are the snake_case names in the table (`event_id`, `event_seat_ids`, `idempotency_key`, `hold_group_id`, `from_date`, `to_date`). The schemas are generated from the tool methods and checked in (decision 7); a test asserts the property names, which are not left to Java parameter naming.
4. **The tool result shape.** A success returns the service's result as structured content, in the same snake_case form as the REST body of the matching endpoint, plus the same JSON as text content for clients that read only text. A `DomainException` returns a tool result with `isError: true` and structured content `{"code", "hint", <details>}`, built from `ErrorCode#code()`, `ErrorCode#hint()` and the exception's details, the same members `rest/Problems` puts in a Problem Details body (for `seat_taken`, `seat_ids`). The tool method does not throw for a domain failure.
5. **Failures that are not domain codes stay structured too.**
   - A parameter the MCP framework cannot bind (a malformed `from_date`, a non-numeric `event_id`) is `invalid_request` naming the parameter, as REST does for a mistyped path variable. The executor finds out where this happens (inside the tool call, or before it) and covers both.
   - Any other exception is a tool error with the generic content `{"code": "internal_error", "hint": "unexpected failure; retry, or tell the operator"}`, no detail, and the exception logged server-side. REST's equivalent is a generic 500 with no `code` (§6). **`internal_error` is a twelfth wire code that §6 does not list.** This is the one spec-adjacent call in the plan, so Markus decides it: if he rejects it, the generic failure is a JSON-RPC error with no tool result, which is not a `{"code","hint"}` body. The spec sentence is then added in the post-plan-5 spec sync, as its own docs PR. `ErrorCode` (the eleven domain codes) is not changed.
6. **Execution tiers.** Tasks 1, 2 and 3 are **Mandatory** (caller authentication and the agent-to-human hold handover are Mandatory-tier slices in the spec header): per-task two-stage review, the `security-reviewer` agent, and `/security-review` on the diff before pushing. Tasks 4 and 7 get the `security-reviewer` too (a token must not reach a log or an artifact). Tasks 5, 6, 9 and 10 are Standard. Task 8 is a human task. **Proposed models, for Markus to accept at the execution session's start:** implementers and task reviews Sonnet; `security-reviewer`, the whole-branch review and the PR rounds Opus; `model` set explicitly on every dispatch.
7. **Schema drift is a test, not a workflow.** `ToolSchemasTest` builds the tool list the server really serves (`tools/list` through the MCP client, over the production wiring) and compares each tool's `inputSchema` with `docs/mcp/tools/<tool>.schema.json`. The files are stable: sorted keys, two-space indent, a trailing newline. `./mvnw verify` already runs in CI, so CI fails on drift with no change to a workflow file (no `cicd-standards` work). The failure message names the regenerate command: the same test run with `-Dfrontrow.schemas.write=true` rewrites the files. A tool added, removed or renamed is drift too.
8. **Tool logging is one `INFO` line per call from the adapter itself,** through one helper that every tool method goes through, so a new tool cannot skip it. Fields: tool, outcome (`ok` or the error code), duration in milliseconds, owner (the username). Arguments and results are not logged: a token never enters the method, but the idempotency key and seat ids have no reason to be in a log either. The logger name is fixed, so a test can capture it.
9. **`/mcp` header rule.** The check lives in the `/mcp` chain, before token authentication. Exactly one `Authorization` header must be present and carry a known bearer token. Zero, two or more headers, a non-bearer scheme, or an unknown token → 401 in the existing filter-error shape (no `WWW-Authenticate`). One header holding a comma-joined list is one header; its value is not a known token, so it is 401. The `/api/**` bearer-reject filter is unchanged.
10. **The `/mcp` transport-error fix is reproduce-first.** Spec §6 says a transport error serialises with `stack_trace` and is "rendered by the application's mapper, not the MCP server's". Task 1 reproduces each case against the running chain, finds the mechanism, then fixes it. The acceptance rule is the one in §12: no `/mcp` transport error body carries `stack_trace`, and none is an unstructured string. Whether the fix is an error-rendering change, a servlet error-attribute setting or a handler is the executor's finding, stated in the task report and in a short note in the code.
11. **Over-cap form `POST` to `/mcp`.** Tomcat's `server.tomcat.max-http-form-post-size: 16KB` makes the parameter-map read throw, and the transport answers 500. The fix makes the answer 413 through `rest/Problems.write` (`Problems.plain(HttpStatus.CONTENT_TOO_LARGE)`), in the filter-error shape with no `code` and no `stack_trace`. An under-cap form `POST` to `/mcp` behaves as it does today; a test pins whatever that is, so the fix widens nothing.
12. **Schema-constraint coverage is a guard test** (Task 6): it reads the live catalog and fails when a constraint or unique index in the V1 schema has no violating-row test. The existing `SchemaConstraintsTest` covers most of §5 already; the task finds the gaps by evidence rather than by assumption.
13. **Run-log and recording hygiene is tested where text can be tested** (Task 7): a scan test over `logs/` and `docs/demo/` text files for `Authorization` header values, bearer-shaped tokens and password-shaped strings, plus the repo's gitleaks pre-commit hook. A video or GIF cannot be scanned; its rule is a human check in Task 8's runbook.
14. **README claims that mirror a list are tested:** the four approved known limitations (§9) must appear in the README's "Known limitations" section by their lead sentences, so the README cannot drift from the spec's list.
15. **Not in this plan:**
    - the post-plan-5 spec sync (tick the §12 boxes, the `internal_error` sentence if accepted, the status text) and the journal entry: their own docs PRs, as after every plan;
    - the interview guide: it waits for the project's end (Markus, 2026-11-03);
    - a hosted demo, observability, any "possible next step" (§9);
    - changing any `ErrorCode` or any service.

## File Structure

| File | Task | Responsibility |
|---|---|---|
| `security/AgentTokenFilter.java` (or a filter beside it), `security/SecurityConfig.java` | 1 | Exactly-one-`Authorization`-header rule on `/mcp` |
| `rest/RequestBodyLimitFilter.java` or a new filter in `rest/`, `rest/Problems.java` | 1 | Over-cap form `POST` to `/mcp` → 413 |
| `/mcp` error rendering (location found by the Task 1 reproduction) | 1 | No `stack_trace` in a transport error |
| `mcp/McpConfig.java` | 2 | Production Streamable HTTP provider with the owner extractor (ADR-004) |
| `mcp/ToolResults.java` | 2 | Success and error result building, the `internal_error` fallback |
| `mcp/ToolCall.java` | 2, 4 | The one wrapper every tool goes through: errors, timing, the log line |
| `mcp/EventTools.java` | 2 | `search_events`, `get_event`, `check_availability` |
| `mcp/HoldTools.java` | 3 | `hold_seats`, `release_hold` |
| `docs/mcp/tools/*.schema.json`, `mcp/ToolSchemasTest` | 5 | Checked-in schemas and the drift test |
| `schema/SchemaConstraintsTest.java`, `schema/ConstraintCoverageTest.java` | 6 | Every constraint has a violating-row test |
| `scripts/take-seat.sh`, `scripts/example-run.sh`, `src/test/.../evidence/ExampleRun.java`, `logs/example_run.txt` | 7 | The scripted failure-and-recovery run |
| `docs/demo/` | 8 | Storyboard, runbook and the recording |
| `README.md`, `CLAUDE.md` | 9, 10 | Architecture, threat model, metrics, limitations, next steps; current state |

Paths under `security/`, `rest/`, `mcp/`, `persistence/` and `service/` are relative to `src/main/java/io/github/markusluisflores/frontrow/`. Tests mirror them under `src/test/java/…`.

---

### Task 1: Harden `/mcp` (Mandatory)

**Files:** `security/AgentTokenFilter.java` or a filter beside it; `security/SecurityConfig.java`; `rest/RequestBodyLimitFilter.java` or a new `rest/` filter; the error-rendering site the reproduction finds. Tests: `security/SecurityChainsTest`, `rest/RequestBoundsOverHttpTest`, and a new `mcp/McpTransportErrorsTest`.

**Interfaces:** consumes `Credentials#ownerOfToken`, `Problems.write`, `Problems.plain`. Produces no new public API: only behaviour on `/mcp`.

**Behaviour to build (each one is a failing test first):**
- 401 (filter-error Problem Details, no `WWW-Authenticate`) on `/mcp` unless exactly one `Authorization` header is present and it carries a known bearer. Tests, all with a valid agent token for the bearer: Basic then Bearer; Bearer then Basic; Bearer then Bearer (two known tokens, and the same token twice); no header; an unknown bearer; a lone Basic header; a comma-joined single header. A positive control: one known bearer is admitted. The tests send raw headers over a real socket or the servlet test client with repeated headers, not a client that merges them.
- Reproduce, then fix, the `stack_trace` on `/mcp` transport errors. Three cases, each with a valid agent token: malformed JSON; a chunked body over 16 KiB; an over-cap form `POST`. For each: record in the task report what the body looked like before. After the fix, none contains `stack_trace` (or any exception class name or message), and none is an unstructured string.
- The over-cap form `POST` answers 413, in the shape `Problems.write` writes: parsed JSON equal to the plain 413 body, with no `code` key. Cover the chunked variant too, since a declared `Content-Length` over the bound is already refused by the existing filter.
- Pin the under-cap form `POST` behaviour (decision 11).

**Acceptance:** `./mvnw verify` green; the new tests fail on `main` for the reason stated, not for a setup error (the failing run is in the task report); the §12 boxes for `/mcp` 401, `stack_trace` and the 413 are each backed by a named test. **Mandatory-tier review:** the `security-reviewer` is asked specifically about header-parsing differentials between Tomcat and the filter (case, repeated headers, folding) and about any path on which a body is read before the chain authenticates.

### Task 2: The MCP wiring and the three read tools (Mandatory)

**Files:** `mcp/McpConfig.java`, `mcp/ToolResults.java`, `mcp/ToolCall.java`, `mcp/EventTools.java`. Tests: `mcp/EventToolsTest` (full stack over real HTTP with the MCP client, as `PrincipalPropagationSpikeTest` does), `mcp/ToolResultsTest` (unit).

**Interfaces:**
- `McpConfig` provides the Streamable HTTP transport provider bean with a context extractor that puts the authenticated principal's name under a fixed key (the spike's `frontrow.principal` is the model; ADR-004). With no principal the extractor puts nothing and a tool refuses (the `/mcp` chain makes this unreachable; the refusal is defence in depth and has a test).
- `ToolCall` runs a tool body, returns a `CallToolResult`, and is the only place that maps `DomainException` and any other `Throwable` (decisions 4, 5). Task 4 adds the log line to it.
- Tools, per §6 and decision 3. Each calls exactly one service method:
  - `search_events(query?, from_date?, to_date?, limit?)` → `EventReadService#search(EventQuery)`; `ON_SALE` only, `limit` default 10, max 50 (outside 1–50 is `invalid_request`, `parameter: limit`, as REST).
  - `get_event(event_id)` → `EventReadService#event`.
  - `check_availability(event_id, section?)` → `EventReadService#availability`.
- Delete nothing from the spike test unless it becomes redundant; if the production provider bean makes the spike's replacement bean conflict, the spike test follows (report the change).

**Tests (first):** each read tool returns the REST read's data for the same input (compare parsed trees against the service result); a `DRAFT` and a `CANCELLED` event answer `not_found` to `get_event` and `check_availability` and are absent from `search_events`; `check_availability` and every read tool return no `hold_group_id`, owner or `expires_at` (§6, §12); `search_events` matches case-insensitively and literally (`%`, `_`); a bad `limit`, a malformed date and a non-numeric `event_id` are structured `invalid_request` errors naming the parameter; an unexpected exception (a test-only failing collaborator) gives the `internal_error` body with no detail; the success result carries both structured and text content.

**Acceptance:** all green; the three tools callable over real HTTP with a generated token; no service or `domain` file changed (the greps in Global Constraints). The report says how a binding failure reaches the tool layer (decision 5) and what was done.

### Task 3: The two write tools and the handover (Mandatory)

**Files:** `mcp/HoldTools.java`. Tests: `mcp/HoldToolsTest`, `mcp/NoPurchaseToolTest`.

**Interfaces:** `hold_seats(event_id, event_seat_ids, idempotency_key)` → `HoldService#hold(new HoldCommand(owner, …))`; `release_hold(hold_group_id)` → `ReleaseService#release(new HoldGroupCommand(owner, …))`. The owner is the principal name from the transport context and nothing else.

**Tests (first):**
- `hold_seats` success returns `hold_group_id`, the held seats, the total and `expires_at` (the `HoldResult` contract). A replay with the same key and request returns the original result; the same key with a different request is `idempotency_key_reused`.
- Every §6 hold failure comes back as `{"code","hint",…}` with the same code the REST endpoint gives for the same input: `seat_taken` (with `seat_ids`), `sales_not_open`, `sales_closed`, `hold_limit_exceeded`, `too_many_seats`, `not_found`, `invalid_request`, `contention_retry` where it can be provoked, and `release_hold` on an expired, released or another owner's hold (`not_found` for another owner's: it does not reveal the hold exists).
- **The handover, end to end:** alice's agent token holds seats over `/mcp`; alice's Basic credentials see that hold in `GET /api/me/holds` and confirm it over REST, then see the order in `GET /api/me/orders`; bob sees none of it and cannot release or confirm it. The same token cannot reach `/api/holds/{id}/confirm` (401).
- **No purchase tool:** the served tool list is exactly the five names; none is named or described as confirming, buying or paying; no tool schema has a property named `owner`, `user`, `buyer` or `username`. The five-name assertion fails when a sixth tool is added without this test being touched.
- Concurrency still belongs to the service tests; one test races two tokens' `hold_seats` for one seat over HTTP and asserts one winner and one `seat_taken`.

**Acceptance:** all green; §12's "five MCP tools" box has a test per tool; the `security-reviewer` is asked about owner spoofing (any parameter, header or transport-context path that could set the owner other than the token) and about the handover's cross-owner cases.

### Task 4: Phase 1 tool logging (Standard, with the `security-reviewer`)

**Files:** `mcp/ToolCall.java`; test `mcp/ToolLoggingTest`.

**Behaviour:** one structured `INFO` line per tool call on a fixed logger name, with tool, outcome (`ok` or the error code, `internal_error` included), duration in milliseconds and owner (decision 8). Every tool passes through it, for success and every failure kind.

**Tests (first):** for each of the five tools, a success call and a failing call produce exactly one line each, with the right tool, outcome and owner and a numeric duration; a call that fails binding still logs one line; no line contains the agent token, its SHA-256 hash, the `Authorization` value, an idempotency key or a seat id; a request with an unknown token produces no tool line and no token text anywhere in the captured output (capture everything the test run logs, not just the tool logger).

**Acceptance:** the §12 logging box is backed by those tests; the line format is documented in a javadoc on `ToolCall`, one example line.

### Task 5: Tool schemas checked in, CI fails on drift (Standard)

**Files:** `docs/mcp/tools/{search_events,get_event,check_availability,hold_seats,release_hold}.schema.json`; `mcp/ToolSchemasTest`.

**Behaviour:** decision 7. The committed files are generated by the test's write mode, then committed. Each file holds the tool's name, description and `inputSchema` (the part a client sees), in stable form.

**Tests:** the drift test; a mutation check run once by the executor and recorded in the report (change a parameter name or add a tool → the test fails with the message that names the regenerate command; revert); a test that the property names match decision 3 and that required/optional match §6's table (`?` = optional).

**Acceptance:** `./mvnw verify` fails on drift; the schemas are in version control; the §12 box "Tool schemas checked in, and CI fails on schema drift" names `ToolSchemasTest`.

### Task 6: Every schema constraint has a violating-row test (Standard)

**Files:** `schema/SchemaConstraintsTest.java`, new `schema/ConstraintCoverageTest.java`.

**Behaviour:** first, by evidence: list every constraint and unique index of the V1 schema from the catalog (`pg_constraint`, `pg_indexes`) and map each to the existing test that inserts a violating row. Record the mapping in the task report. Add a violating-row test for each gap (the §5 table is the minimum: `uq_claimed_seat`, `uq_sold_once`, the composite foreign keys on `event_seat`, `uq_order_hold_group`, the `hold_request` primary key). Then `ConstraintCoverageTest` reads the catalog and fails when a named constraint or index has neither a test method referencing it nor an entry in a short, commented allowlist of constraints that are deliberately not row-tested (plain primary keys of surrogate ids, for example). The allowlist's reasons are reviewable.

**Acceptance:** the guard fails if a constraint is added to a migration without a test (the executor shows this once with a temporary throwaway migration or a catalog-level fake, and removes it); the §12 box "Every schema constraint in §5 has an integration test inserting a violating row" cites the mapping.

### Task 7: The scripted failure-and-recovery run (Mandatory-adjacent: `security-reviewer`)

**Files:** `scripts/take-seat.sh`; `scripts/example-run.sh`; `src/test/java/io/github/markusluisflores/frontrow/evidence/ExampleRun.java`; `src/test/java/…/evidence/ExampleRunTest.java`; `src/test/java/…/evidence/EvidenceHygieneTest.java`; `logs/example_run.txt` (written at the end, against the live compose app).

**Behaviour:**
- `take-seat.sh <base-url> <event-id> <seat-id>`: a REST hold as a different demo user (`bob`) with a fresh idempotency key. The password comes from an environment variable, never an argument, and curl is given the credentials without them appearing in `ps` (stdin config). It prints the HTTP status and the hold group id only.
- `ExampleRun` (test scope, a `main` the script launches): reads the base URL from an argument and alice's token from an environment variable; connects with the MCP client; runs the §7 scenario in order; calls `take-seat.sh` between `check_availability` and `hold_seats` through a callback the test replaces; prints a transcript of tool name, arguments (seat ids, event id; never the token or the `Authorization` header), and the structured result. The header states the conflicting hold was scripted.
- `scripts/example-run.sh` builds the test classpath the way `scripts/demo-credentials.sh` does and runs `ExampleRun`.
- `ExampleRunTest` runs the same scenario in-process against the Testcontainers app with the callback calling `HoldService` as bob, and asserts the sequence: first `hold_seats` is `seat_taken` naming the seat, the second `check_availability` no longer lists it, the retry succeeds on other seats. This keeps the run reproducible in CI.
- `EvidenceHygieneTest` scans the text artifacts under `logs/` and `docs/demo/` and the output of `ExampleRun` from `ExampleRunTest` for `Authorization:` header values, bearer-shaped tokens, `Basic ` credentials and password-shaped strings (patterns documented in the test; a seeded fake credential in a temp file proves each pattern fires).

**Human-free completion step (the executor does it, as plan 4B's Task 8 Step 7 did):** start Docker (check the daemon, re-check before the run), `bash scripts/demo-credentials.sh`, `docker compose up --build`, run `scripts/example-run.sh` against it, save the transcript as `logs/example_run.txt`, run the hygiene test on it, tear down, delete `config/application-demo.yml` and `.env`, and re-run `scripts/demo-credentials.sh` so the credentials that produced the log are gone (§7, §12). The raw values the script prints are never pasted into a report, a commit or a PR.

**Acceptance:** the log contains a real `seat_taken` and a recovery, with the scripted-conflict header; the §12 boxes on the log and its hygiene are backed by `ExampleRunTest` and `EvidenceHygieneTest`.

### Task 8: The demo recording (a human task: Markus)

The recording is Markus's. The executor prepares everything else and then reports **BLOCKED until a human performs the check** (global rule: a task's verification step that cannot be completed is a blocked task, not a footnote). The executor does not merge the PR with this task open unless Markus waives it in writing.

**Files:** `docs/demo/README.md` (storyboard and runbook), `docs/demo/<recording>` (GIF or short video, Markus's choice of format, size kept small enough for the repository), and the README link.

**The executor writes the runbook** so that Markus can record without improvising:
- storyboard = the §7 buyer and agent story: Claude Code (connected with `claude mcp add --transport http … --header …`) finds an event, checks seats and holds them; the human sees the hold in `GET /api/me/holds` (Swagger UI or curl), confirms over REST, sees the order in `GET /api/me/orders`; optionally the two-buyers-cannot-share-a-seat moment and the organiser arc;
- before recording: how to add the MCP server without the token appearing on screen (configure it first, clear the terminal, never `echo` it; Swagger UI's Authorize dialog and a `curl -u` line stay off screen too), and a checklist of what must not appear (§7);
- after recording: regenerate the credentials (delete `config/application-demo.yml`, re-run `scripts/demo-credentials.sh`), scrub-check the file by scrubbing through it frame by frame, then commit it;
- the check for §12's "All 5 MCP tools callable from Claude Code over Streamable HTTP with a bearer token": Markus calls each of the five from Claude Code during the recording or just before it, and says so in the PR.

**Acceptance:** the recording is in `docs/demo/` and linked from the README; Markus confirms in the PR that no credential is visible.

### Task 9: The README (Standard)

**Files:** `README.md`; test `ReadmeTest` (location beside `ComposeFileTest`).

**Content (the §12 README boxes; wording is the executor's, claims are the spec's and the code's):**
- **Architecture and threat model:** the §3 Mermaid diagram adapted to the README (never ASCII art), trust boundaries (two disjoint chains; where each credential is checked; what an agent token can and cannot reach), why there is no `confirm_order` stated prominently, and the demo-grade security caveat in plain words (the definition in §9; overstating it is worse than omitting it).
- **What the two approved design decisions cost**, as §6 states them: an agent can release its human's holds, fill their hold-group cap and replay their idempotency keys; a prompt-injected agent can steer which hold the human confirms (confirm is tied only to the hold id). Also the §6 organiser blast radius.
- **"Local only" assumptions:** Docker Engine 28.3.3 or later on Linux for the loopback binding (CVE-2025-54388 is why), and DNS rebinding (no `Host` check; approved limitation 4).
- **Headline metrics, measured, none estimated** (§7): the hold-race parameters and outcome (racers × repetitions, winners per run, from `HoldConcurrencyTest`'s constants and assertion), test counts per layer (from the surefire reports: unit, service/integration, schema, REST, MCP), the integration suite's runtime, and the number of tools with checked-in schemas (five). Each number is read from a run in the task and the report says which command produced it. A tiny script that prints them may be added under `scripts/` if it keeps the numbers re-derivable.
- **Known limitations:** reproduce §9's four approved limitations by their lead sentences; add the plan 4B and 5 hardening items, each with a line on why it is not fixed: fractional JSON numbers are truncated to integers; `GET /api/me/orders` is an N+1 and unpaginated; floating image tags in `Dockerfile` and `compose.yaml`; `SeedDataTest` is not hermetic; `ComposeFileTest` has gaps; availability costs three queries; the README's run path needs JDK 25 on the host even for compose (because `scripts/demo-credentials.sh` runs on the host); `scripts/demo-credentials.sh` leaves `config/` with the caller's umask, which breaks the read-only bind mount on Linux; the OpenAPI document marks anonymous reads as needing Basic; plus any new item Task 1–7 reports. **The executor verifies each claim against the code before writing it;** one that has since been fixed is dropped, and one that is a defect (§9 (a)) is reported, not listed. A new security limitation is never added to the list by the executor (Markus approves additions, §9).
- **"Possible next steps":** §9's list.
- **Links:** the schemas, the run log, the recording (once Task 8 lands), ADRs, spec.

**Tests (first):** `ReadmeTest` asserts the README has the Mermaid block, the `confirm_order` explanation, each approved limitation's lead sentence from §9 (decision 14: read from the spec file, not retyped), the 28.3.3 assumption, "DNS rebinding", and the four metrics' labels; links to files that must exist (`docs/mcp/tools/*.schema.json`, `logs/example_run.txt`) resolve.

**Acceptance:** the §12 README boxes each cite a `ReadmeTest` assertion or a manually checked line, and the PR description says which.

### Task 10: The whole-project verification (Standard)

**Files:** `CLAUDE.md` (current state, as plan 4B did), the PR description. No spec change (decision 15).

**Behaviour (the executor runs and records):**
- A clean-checkout build: clone the branch to an empty directory and run `./mvnw verify` (this is the local twin of "CI green on a clean checkout"); the real box is CI on the PR, checked once, after Markus pings, not polled.
- A live compose run, as in Task 7: `docker compose up` starts the service and Postgres with seed data; every row of the §6 REST table is exercised once with `curl` (the evidence goes in the PR description, no credentials in it); Swagger UI loads; the five MCP tools answer over `/mcp`. The credentials are regenerated afterwards.
- A ticked-box walk: each §12 Plan 5 and whole-project box with the test or artifact that backs it, in the PR description.
- `CLAUDE.md`'s "Current state" updated: plan 5 built, test count, the MCP rules as built (the `mcp/` package, the adapter boundary and the structured-error rule in Architecture rules).

**Acceptance:** all of the above recorded; nothing in the PR is a claim not backed by a run in this task.

---

## Coverage: §12 boxes to tasks and tests

| §12 box | Task | Backing test or artifact |
|---|---|---|
| The five MCP tools and the §7 evidence artifacts | 2, 3, 5, 7, 8, 9 | `EventToolsTest`, `HoldToolsTest`; schemas; run log; recording; README |
| Tool logging: one structured line, owner by username; no token logged | 4 | `ToolLoggingTest` |
| Neither the log nor the recording shows a credential; credentials regenerated after | 7, 8 | `EvidenceHygieneTest`; the Task 7 completion step; Task 8's runbook and Markus's confirmation |
| `/mcp` 401 unless exactly one header with a known bearer | 1 | `SecurityChainsTest` header cases (both Basic/Bearer orders, Bearer+Bearer) |
| No `/mcp` transport error carries `stack_trace` | 1 | `McpTransportErrorsTest` (malformed JSON, chunked over the bound, over-cap form `POST`) |
| Over-cap form `POST` to `/mcp` → 413, filter-error shape | 1 | `RequestBoundsOverHttpTest` / `McpTransportErrorsTest` |
| `docker compose up` starts service and Postgres; seeded | 10 | live compose run (also plan 4B Task 8) |
| Swagger UI browsable; every REST row exercised end to end | 10 | the Task 10 `curl` walk; existing `ReadEndpointsTest`, `WriteEndpointsTest`, `MeEndpointsTest` |
| All 5 tools callable from Claude Code over Streamable HTTP with a bearer token | 3, 8 | `HoldToolsTest`, `EventToolsTest` over real HTTP; Markus's check in Task 8 |
| Every §5 schema constraint has a violating-row test | 6 | `SchemaConstraintsTest`, `ConstraintCoverageTest` |
| `logs/example_run.txt` is a real reproduced run | 7 | `ExampleRunTest` (reproducibility), the committed log |
| Tool schemas checked in; CI fails on drift | 5 | `ToolSchemasTest` |
| README: Mermaid, trust boundaries, no `confirm_order` | 9 | `ReadmeTest` |
| Recording in `docs/demo/`, linked from README | 8, 9 | the file; `ReadmeTest` link check |
| README: caveat, metrics, limitations (§9 list), next steps | 9 | `ReadmeTest` |
| README: what the two design decisions cost | 9 | `ReadmeTest` |
| README: Docker 28.3.3 assumption; DNS rebinding | 9 | `ReadmeTest` |
| CI green on a clean checkout | 10 | clean-checkout `verify`; CI on the PR |
| Already ticked (concurrency tests, disjoint chains, no raw token in the repo, ADRs) | all | kept true: Global Constraints; Task 3 and Task 7 tests |

## Execution and review notes (for the execution session)

- Each task: a failing test first, shown failing for the reason stated; then the code; `./mvnw verify` green before the commit. One commit per task unless a task says otherwise.
- Task order is the dependency order: 1 → 2 → 3 → 4 → 5; 6 is independent; 7 needs 2 and 3; 8 needs 7; 9 needs 5 and 7 (8 may land after it); 10 last.
- The `security-reviewer` runs on Tasks 1, 3, 4 and 7. A whole-branch review (Opus) follows the last task, then `/security-review`, then the PR on the full lane with sequential rounds (a resumed check finishes before any fresh round).
- Record every dispatch's role and model in a scratchpad file as you go, and write them into `workflow-audits\frontrow\observation-log.md` under "Plan 5 execution trace" before or at the merge. A deviation from this plan is recorded in the task report and copied into the PR description; a change of behaviour goes back to the controller.
- Docker's engine stopped twice during plan 4B's execution on this machine: start it, and re-check before every compose run.
