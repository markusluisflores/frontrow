# ADR-007: MCP tools are hand-registered specifications, not `@McpTool` methods

**Date:** 2026-11-05
**Status:** Accepted

Accepted by Markus on 2026-11-05, in the plan 5 planning session (decisions 3 to 5 of
`docs/superpowers/plans/2026-11-04-phase-1-plan-5-mcp-tools-and-evidence.md`). Recorded on 2026-11-09 with spec
revision 2.7.2 (§3, §6 and §10; §14 row). Evidence: `src/main/java/io/github/markusluisflores/frontrow/mcp/` and its
tests, merged in PR #40.

## Context and Problem Statement

Spec revisions up to 2.7.1 said the five MCP tools are Spring AI `@McpTool` methods, with their schemas generated from
the method signatures. Every tool must answer a bad argument (a malformed `from_date`, a non-numeric `event_id`, a
missing required argument, a scalar where `event_seat_ids` wants a list) as a structured
`{"code":"invalid_request","hint":...,"parameter":<name>}` result, as REST does (spec §6). Three layers sit in front of
a tool's own logic, and the plan read the first two from the jars:

1. MCP Java SDK 2.0.0 validates the arguments against the tool's input schema before any handler runs
   (`McpServer.SyncSpecification#validateToolInputs`, default `true`) and answers a plain-text error with no structured
   content.
2. For an `@McpTool` method, Spring AI 2.0.1 converts each argument itself before the method runs
   (`SyncMcpToolMethodCallback#apply`) and answers a failure with a plain-text error. The tool method never runs, so
   nothing in the method, including a wrapper that every tool goes through, can see or shape that failure.
3. Only what the adapter's own handler receives is under the adapter's control.

Can the tools keep `@McpTool` and still return a structured error that names the offending parameter?

## Decision Drivers

* An unstructured string error is a review BLOCKER (spec §6): every failure the caller can cause must be a
  machine-parseable result with a recovery hint.
* The error must name the parameter, and the schemas must keep their real types (`integer`, `string`, `array`), not be
  retyped to `String` to dodge conversion.
* The schema must stay tied to the method signature, and a drift in it must show up in a test.
* The owner comes from the authenticated principal, never from an argument (ADR-002, ADR-004).

## Considered Options

* **A. Keep `@McpTool` and wrap the call handler.** Intercept the handler Spring AI builds for the method.
* **B. Hand-register one `SyncToolSpecification` per tool**, with the adapter's own argument binding, SDK input
  validation switched off, and the schema generated from the same annotated method.

## Decision Outcome

**Chosen: B.**

* `McpConfig` exposes **one `List<SyncToolSpecification>` bean**. `McpServerAutoConfiguration#mcpSyncServer` collects the
  list from an `ObjectProvider<List<SyncToolSpecification>>`, so five separate specification beans would silently not be
  served.
* Each tool is a typed method with `@McpToolParam` on its parameters and **no `@McpTool`**, so Spring AI's scanner does
  not register it a second time. Its `inputSchema` comes from `McpJsonSchemaGenerator#generateForMethodInput(Method)`,
  the generator the `@McpTool` path uses, so names, types, descriptions and `required` still come from the declaration.
  The checked-in files in `docs/mcp/tools/` and `ToolSchemasTest` guard that output: a Spring AI upgrade that changes the
  generator shows up as drift.
* The handler passes the raw `CallToolRequest#arguments()` map into the adapter's `ToolCall`, whose **own strict
  binding** converts each argument to the declared parameter type, in declared order, and names the parameter on any
  failure. A JSON number for a `String` or `LocalDate` parameter is `invalid_request`; Jackson's lenient coercion would
  have accepted it. Parameters are boxed types, so a missing argument reaches the check instead of failing as a null into
  a primitive; whether one is required comes from `@McpToolParam(required)`.
* SDK input validation is switched off by a **`@Primary` `McpSyncServerCustomizer`** in `McpConfig` that sets
  `validateToolInputs(false)` and keeps `immediateExecution(true)`. Spring AI's built-in servlet customizer already sets
  `immediateExecution(true)`, and the auto-configuration takes one `Optional<McpSyncServerCustomizer>`, so the primary
  bean replaces it rather than adding to it.
* **`internal_error` fallback.** Any exception that is not a domain failure is a tool error with the generic content
  `{"code":"internal_error","hint":"unexpected failure; retry, or tell the operator"}`, no detail, and the cause logged
  server-side. It is a twelfth MCP wire code, defined only in the MCP adapter; REST's equivalent stays a generic 500
  with no `code`, and `ErrorCode` keeps its eleven domain codes.
* **Structured content is always a JSON object.** `search_events` returns `{"events": [...]}`, because
  `GET /api/events` returns a top-level array and MCP types `structuredContent` as an object. No tool declares an
  `outputSchema` (it would wrap each handler in a second failure mode), and the text content equals the structured
  content.

## Pros and Cons of the Options

### A. Keep `@McpTool` and wrap the call handler

* ✅ The annotation stays on the methods, as the spec said.
* ❌ Layer 2 runs before the method, so a wrapper around the method never sees a conversion failure.
* ❌ A wrapper around the handler Spring AI builds can catch the failure, but the plain-text error it receives never names
  the parameter, so the result could not be `invalid_request` with a `parameter`. Rejected for that reason.
* ❌ SDK validation (layer 1) would still answer first with plain text unless it is switched off anyway.

### B. Hand-registered specifications

* ✅ The adapter owns the binding, so every argument failure is structured and names its parameter.
* ✅ Schemas still come from the method declarations and are guarded by checked-in files.
* ⚠️ The adapter carries its own binding code, and the tools are not discovered by annotation.

## Consequences

* ✅ Every argument failure is exactly one structured `invalid_request` result and exactly one tool log line.
* ✅ The structured content shape is enforced by the result builder's type (a record in, a map out), not by a test alone.
* ⚠️ The `@McpTool` annotation is absent, so spec sections 3, 6 and 10 changed (revision 2.7.2).
* ⚠️ The server must be assembled from one list bean and a `@Primary` customizer. A new tool must be added to that list,
  or it is silently not served; `ToolSchemasTest` fails on a tool added, removed or renamed without its checked-in file.
* ⚠️ Spring AI or MCP SDK upgrades can change the generator, the customizer composition or the validation default. The
  schema drift test and the tests that the served server has `immediateExecution` and no input validation are the
  tripwires.
* ⚠️ Layers 1 and 2 were read from the jars (`javap`) at planning time; the behaviour is now covered by the tools' tests
  in PR #40.
