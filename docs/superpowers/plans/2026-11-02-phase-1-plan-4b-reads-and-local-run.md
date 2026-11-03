# FrontRow Phase 1, Plan 4B (The Reads, Event Creation and the Local Run) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish FrontRow's REST side, and make the local run real. That means:
- the five `GET` endpoints over read services that plan 5's tools will reuse;
- `POST /api/events`;
- springdoc / Swagger UI;
- seed data;
- `docker compose` bound to loopback;
- the defect fixes spec rev 2.7 §12 assigns to this plan.

**Architecture:**
- **Reads:** two read services in `service/`, `EventReadService` and `OwnerReadService`. They run in read-only `READ_COMMITTED` transactions over Spring Data JPA queries and take no lock (ADR-005: JPA for ordinary reads).
- **Creation:** `EventCreateService` inserts the event and its seats through JPA.
- **The window check:** the event `PATCH` gains it, against the `starts_at` that `EventRow` now carries.
- **Controllers:** `EventController` gains the creation and the three event reads, and a new `MeController` serves the two `me/*` reads.
- **The local run:** a multi-stage `Dockerfile`, `compose.yaml` and a Flyway seed location that only the `demo` profile turns on.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Framework 7.0.9, Spring Data JPA, Hibernate 7.4.5, Jackson 3.1.5), springdoc-openapi 3.1.1, Postgres 18, Docker Compose, JUnit Jupiter 6, AssertJ, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.7). Read:
- **§12's "Plan 4B" box list:** the whole scope of this plan. Nothing outside it is built here;
- §3 *Cross-site requests* and its loopback bullets;
- §4 statuses: `DRAFT` is `not_found`, and the definition of live;
- §5 *Event cancellation*, the last three paragraphs: the instant bound and the window check;
- §6: the tool table and its bullets (the reads mirror them), *Error contracts* and *REST surface*;
- §7: the demo story;
- §9: the portfolio quality bar, so findings are classified by its three kinds.

Also read ADR-005 (JPA plus JDBC) and ADR-006 (the portfolio scope).

## Global Constraints

- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). The `domain` package imports only `java.*`. Check: `grep -rn "^import" src/main/java/io/github/markusluisflores/frontrow/domain/ | grep -v "import java\."` prints nothing.
- **All lock SQL lives in `persistence/LockingGateway.java`** (ADR-005), comments included. Check: `grep -rlniE "for (no key )?update|for (key )?share|pg_[a-z_]*advisory|@Lock\b|LockModeType|PESSIMISTIC|skip locked|nowait|lock table" src/main/java/` prints only `LockingGateway.java`. No task adds lock SQL. The reads and creation take no explicit lock.
- **The owner always comes from the authenticated principal** (spec §3). `GET /api/me/*` passes `Principal#getName()` to the service, and the service filters by it. The role table alone does not scope a read (`CLAUDE.md`).
- **A `DRAFT` event is `not_found` to every read by id, and list reads leave it out** (spec §4, §6).
- **Reads show availability, never another owner's hold:** no `hold_group_id`, owner or `expires_at` in the availability read (spec §6).
- **Every service entry point** is `@Transactional(isolation = READ_COMMITTED)` and calls `TransactionBoundary.requireOwnTransaction()` as its first statement. Every new service goes into `TransactionIsolationTest.SERVICES`, and every new public method into `ServiceTransactionBoundaryTest#calls()`, or the build fails.
- **No usable credential is ever committed** (spec §12): no password, no token, no Postgres password. `.env` and `config/` are gitignored; `.env.example` holds an empty value.
- **The wire is snake_case** (`spring.jackson.property-naming-strategy: SNAKE_CASE`). Compare JSON bodies as parsed trees, never as strings.
- Real Postgres only (`postgres:18-alpine` through Testcontainers). Never H2, never a mocked repository.
- Java 25, `-Xlint:all,-processing -Werror` (tests included), Spotless (palantir), SpotBugs. `./mvnw verify` is the test command, and it must be green at every commit.
- **The code below is not formatter output.** This plan was not built before it was written (decision 1). Run `./mvnw spotless:apply` before every test run: the Spotless check runs at `validate`, and an LF file fails it on this Windows checkout until it is applied. Palantir does not reflow javadoc, so keep javadoc within 120 columns by hand.
- **Write every file as UTF-8.** A cp1252 default turns `§` into a byte that isn't UTF-8.
- **Records that hold a collection copy it** with `List.copyOf` or `Map.copyOf`. SpotBugs recognises those copies, not `Collections.unmodifiable*` (plan 4A decision 12).
- Commit messages: `<type>(<scope>): <subject>`, 72 characters max, no trailing period (`.githooks/commit-msg`). No local device path in a committed file.

## Plan-level decisions

Decisions 1 and 2 are Markus's (brainstorm, 2026-11-02). The rest are this plan's; each is listed so a reviewer can reject it on its own.

1. **Full code, not built first** (Markus). Plans 3B and 4A were built in a scratch chain and generated from it, which wrote the code twice (queue item 10, cause 3). Here every step holds complete code, checked by reading it against the code on `main` and the pinned versions, but never compiled.
   - **If a snippet does not compile or a test fails for a reason the plan didn't intend** (an API that differs on a pinned version, a formatter change), the implementer makes the smallest change that keeps the step's stated behaviour. They record it as a deviation in the task report, and the controller copies it into the PR description.
   - A change of behaviour is not a deviation: it goes back to the controller.
2. **Seed data is Flyway SQL in `db/seed`, turned on only by the `demo` profile** (Markus). A profile document in `application.yml` adds `classpath:db/seed` to `spring.flyway.locations`. The file is `V1_1__demo_seed.sql`, version 1.1, so a later schema `V2` still runs after it.
   - **Fixed dates:** every seeded event starts in 2030 or 2031, and its sales opened on 2026-01-01. The demo stays on sale for years, and the seed reads no clock.
   - **The test suite never applies it**, so every test database starts empty, as today.
   - `SeedDataTest` boots with the `demo` profile and asserts the §12 box: `sales_close_at <= starts_at` for every seeded event.
3. **Reads go through JPA, in read-only transactions** (ADR-005 option C: JPA for ordinary reads).
   - Each read method is `@Transactional(isolation = READ_COMMITTED, readOnly = true)` and takes no lock.
   - Loading `SeatHold` entities is safe here. ADR-005's dirty-entity warning is about locking transactions that write through the gateway; a read-only transaction writes nothing, and Hibernate does not flush it.
   - No `JdbcClient` outside the gateway.
   - **One clock read per transaction:** `availability` and `holds` each read `now` once, to decide liveness (spec §4).
4. **Read shapes.** These mirror spec §6's three read tools, so plan 5's tools call the same methods.

   | Endpoint | Service method | Returns |
   |---|---|---|
   | `GET /api/events?query&from_date&to_date&limit` | `EventReadService#search(EventQuery)` | `List<EventDetail>`, `ON_SALE` only, soonest `starts_at` first |
   | `GET /api/events/{eventId}` | `EventReadService#event(long)` | `EventDetail`; a missing, `DRAFT` or `CANCELLED` id is `not_found`: only `ON_SALE` events are readable, because `EventPatchService` allows `DRAFT` to `CANCELLED`, and a cancelled draft must not become readable |
   | `GET /api/events/{eventId}/availability?section` | `EventReadService#availability(long, String)` | `Availability`: `event_id`, `currency`, and per seat `event_seat_id`, `section`, `row_label`, `seat_number`, `price_cents`, `available` |
   | `GET /api/me/holds` | `OwnerReadService#holds(String)` | `List<HeldGroup>`: the caller's groups with at least one live row, soonest expiry first |
   | `GET /api/me/orders` | `OwnerReadService#orders(String)` | `List<OrderResult>`, the confirm response's own shape, newest first |

   - **Search:** `query` matches the event name case-insensitively and literally: `%`, `_` and `!` are escaped, and Postgres's `lower()` lowers both the name and the query, so one engine's case rules apply to both. `from_date` and `to_date` are ISO dates (UTC) and both inclusive. They filter on `starts_at`, clamped to the §5 storable range, so no date can reach SQL out of range. `limit` defaults to 10. Outside 1–50 it is `invalid_request` with `parameter: limit`, rather than silently clamped, because spec §6 says `invalid_request` names the parameter.
   - **A hold or confirm on a cancelled event still answers `sales_closed`** (the services are unchanged). That reveals only that the id exists, which spec §4 already accepts for sequential ids.
   - **`available`** means no claim on the seat now: no `CONVERTED` row, and no `ACTIVE` row with `expires_at > now`. That is the claim index's predicate with liveness applied. It reads only an `ON_SALE` event, so a seat listed is on an event that is on sale. An unknown `section` gives an empty seat list, not an error.
   - **`me/holds` lists a group while it has at least one live row.** That is the same rule that makes a group count against the cap (§4), so a buyer refused with `hold_limit_exceeded` sees every group that counts. Every row of a group shares one `expires_at`, so the seats of a listed group are all live and the seat carries no flag.
   - **No pagination** on `me/*`. Holds are bounded by the cap (3 groups × 8 seats). Orders are not bounded, which is acceptable for a local demo; plan 5's README lists it under "Known limitations" as hardening that meets none of §9's three criteria.
5. **Creation** (`POST /api/events`, `ORGANISER`; spec §6: "a `DRAFT` event on an existing venue with a price per section").
   - **The body:** `venue_id`, `name`, `starts_at`, `sales_open_at`, `sales_close_at`, `currency` and `prices`. `prices` maps each section name to cents; map keys are data, so the snake_case strategy does not touch them.
   - **The checks, in this order.** Each failure is `invalid_request` naming one parameter:
     - `name`: absent or blank;
     - `currency`: not `^[A-Z]{3}$`;
     - `starts_at`: absent, or outside the §5 bound;
     - `sales_window`: a bound absent or outside the §5 bound, a window that does not open before it closes, or a window that closes after `starts_at`;
     - `venue_id`: absent, or no such venue. It is not `not_found`, whose hint points at `search_events`;
     - `prices`: absent, its keys not exactly the venue's sections, or a negative price.
   - **Every seat of the venue becomes an `event_seat`** at its section's price.
   - **No clock and no explicit lock.** Creation makes no time-dependent decision, like the `PATCH` (§5). Its foreign-key checks take `FOR KEY SHARE` on the venue and seat rows. No transaction ever locks those rows, so creation takes no part in the §5 lock order.
   - **The response** is `201` with the new `EventDetail`, whose status is `DRAFT`. That is how an organiser sees their draft (plan 4A decision 2: no read by id discloses a draft).
6. **The window check** (§5, §12). `sales_close_at <= starts_at` is checked on creation, and on a `PATCH` whose body sets `sales_open_at` or `sales_close_at` (the field is present), against the merged window. A status-only `PATCH`, such as a cancel, is not checked, so an event with nothing sold can always be cancelled. Failure is `invalid_request` with `parameter: sales_window`. The rule lives in the domain as `SalesWindow#closesBy(Instant)`. It fixes the post-start reopen (§6): any window that closes by `starts_at` is already closed once the event has started.
7. **One instant bound for both write paths.** `EventPatchService.EARLIEST` and `LATEST` move to a package-private `service/EventInstants`, with `storable(Instant)`, and `EventPatchServiceTest` follows them. Creation truncates to microseconds as the `PATCH` does, so a window is checked as it will be stored.
8. **springdoc-openapi 3.1.1.** It is the newest release built on Boot 4.1.x (its parent is Boot 4.1.0), published 2026-09-06; 3.2.0 is built on Boot 4.1.2 and was nine days old when this plan was written.
   - **Schemas use the wire's names.** springdoc renders schemas with swagger-core's own Jackson 2 mapper, which knows nothing of the application's Jackson 3 `SNAKE_CASE`. Without a fix, Swagger UI would show `eventSeatIds` and its "Try it out" would send a body the API refuses. A `ModelResolver` bean over a snake_case copy of swagger's OpenAPI 3.1 mapper (`Json31`), with `openapi31(true)`, is the documented fix. It must be the 3.1 resolver: springdoc 3.1.1's default document is OpenAPI 3.1, the bean replaces swagger-core's own 3.1 resolver, and a 3.0-mode one would make every component schema lose `"type": "object"`. `OpenApiDocsTest#schemasUseTheWireNames` guards both the names and the type.
   - **The server URL is pinned to `/`.** Without a `servers` entry, springdoc writes each request's `Host` into the shared cached document, so a DNS-rebinding page could point Swagger UI's "Try it out", and the Basic credentials it sends, at its own host. `OpenApiDocsTest#theServerUrlDoesNotComeFromTheHostHeader` guards it.
   - **`springdoc.paths-to-match: /api/**`:** `/mcp` is described by its own tool schemas (spec §7), not by OpenAPI.
   - **One HTTP Basic security scheme**, applied globally. Swagger UI's Authorize button sends the header itself; no 401 carries a challenge (§3).
   - **Swagger UI** is at `/swagger-ui/index.html`, inside the catch-all's `/swagger-ui/**`. springdoc's `/swagger-ui.html` redirect is not one of the four anonymous paths, so it gets 401, and the README names the full path.
9. **Compose** (§3, §12).
   - **Ports:** `app` publishes `127.0.0.1:8080:8080` only, and `postgres` publishes nothing.
   - **Passwords:** both take `${POSTGRES_PASSWORD:?…}`, so compose refuses to start without the generated `.env`.
   - **The image** is multi-stage: the build stage skips tests, Spotless and SpotBugs, because CI runs them and a Windows checkout's CRLF files would fail Spotless inside Linux. The runtime image runs as a non-root `frontrow` user. The build stage installs `unzip` for the Maven wrapper, which otherwise fetches the tar.gz and fails the zip's checksum; the wrapper's `distributionSha256Sum` stays.
   - **`config/` is mounted read-only** at `/app/config`, where Boot finds `config/application-demo.yml`.
   - **`scripts/demo-credentials.sh` writes each file only if it is missing:**
     - `.env`, from `DemoCredentials env`: a `SecureRandom` password, mode `0600`. Postgres keeps its first password in its volume, so regenerating `.env` alone would lock the app out; the header says to run `docker compose down -v` too;
     - `config/application-demo.yml`: as today, but `chmod 644`, so the container's non-root user can read it on a Linux bind mount.

     **The `chmod 644` is a judgment to check.** The file holds bcrypt and SHA-256 hashes of 192-bit random values, which no other local user can reverse. The raw values exist only in the script's one-time output.
   - **`.env.example`** is committed with `POSTGRES_PASSWORD=` (empty): the "placeholder that does not work" (§12), because compose's `:?` refuses an empty value.
   - **`ComposeFileTest`** parses `compose.yaml` and `.env.example` and asserts each of these rules, so a later edit can't silently publish a port.
10. **The rev 2.7 cut** (§12). Removed:
    - the `GET /api/orders/*` rule;
    - `Role.ADMIN`;
    - `adam` in `DemoCredentials` and `TestCredentials`;
    - the `ADMIN` assertions and the `/api/orders/x` assertions.

    `anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser` and `theCatchAllOpensOnlyItsAnonymousPaths` are repointed at `alice`. The unlisted-path test also gains `GET /api/orders/1` as `alice` → 403. That is the proof the rule is gone: before this task it answered 404 from MVC.
11. **The README** gains the run instructions (§12), and its stale "design only, no code exists" status is replaced by one accurate line, because run instructions under that line would contradict it. Everything else in the README (architecture, threat model, metrics, limitations) is plan 5's.
12. **Review and execution tier: Standard** (plan 4A decision 1). That means per-task two-stage reviews, the `reviewer` agent on the branch, and `/security-review` on the diff before pushing. **Proposed, for Markus to accept or reject:** Tasks 1, 5 and 8 also get the `security-reviewer` agent. Task 1 changes the role table, Task 5 is owner scoping, and Task 8 is the network exposure behind approved known limitation 3. The spec header puts REST authorization at Mandatory.
13. **During execution, record each implementer's model** (project memory `plan-5-implementer-model-note`). Record the task, the model as actually run, whether `model` was set or omitted, and that every step held complete code (decision 1). Fix passes count too. Observe only: dispatch exactly as usual. Write the records into `workflow-audits\frontrow\queue.md` item 10 before or at this plan's PR merge.
14. **Not in this plan:**
    - the post-4B spec status sync and the journal entry: their own docs PRs, as after every plan;
    - the Docker Engine floor in the spec and ADR-006: the post-4B spec sync must correct spec §3, the §12 README box and ADR-006's "28.0" to 28.3.3 (CVE-2025-54388). The §12 box is quoted wording, so the change is Markus's to approve;
    - everything in §12's "Plan 5" box list;
    - the whole-project boxes that need plan 5's tools.

## File Structure

| File | Task | Responsibility |
|---|---|---|
| `security/Role.java`, `security/SecurityConfig.java`, `security/DemoCredentials.java` | 1, 8 | `ADMIN` and the orders rule removed; `DemoCredentials` gains `env` |
| `domain/SalesWindow.java` | 2 | `closesBy(Instant)` |
| `persistence/EventRow.java`, `persistence/LockingGateway.java` | 2 | `starts_at` on the locked event row |
| `service/EventInstants.java` | 2 | The §5 storable bound, shared by both event write paths |
| `service/EventPatchService.java` | 2 | The window check on a `PATCH` that sets a bound |
| `service/EventDraft.java`, `service/EventDetail.java`, `service/EventCreateService.java` | 3 | Event creation |
| `persistence/SeatRepository.java` | 3 | A venue's seats |
| `rest/EventController.java` | 3, 4 | `POST /api/events`, then the three event reads |
| `persistence/EventRepository.java`, `persistence/EventSeatRepository.java`, `persistence/SeatHoldRepository.java` | 4, 5 | Read queries |
| `service/EventQuery.java`, `service/Availability.java`, `service/EventReadService.java` | 4 | Event reads |
| `service/HeldGroup.java`, `service/OwnerReadService.java`, `service/OrderResult.java`, `service/ConfirmService.java`, `persistence/TicketOrderRepository.java` | 5 | The caller's holds and orders; `OrderResult.of` shared with confirm |
| `rest/MeController.java` | 5 | `GET /api/me/holds`, `GET /api/me/orders` |
| `pom.xml`, `rest/OpenApiConfig.java`, `application.yml` | 6, 7 | springdoc, the snake_case model resolver, the demo profile's seed location |
| `db/seed/V1_1__demo_seed.sql` | 7 | Two venues, three on-sale events |
| `Dockerfile`, `.dockerignore`, `compose.yaml`, `.env.example`, `scripts/demo-credentials.sh` | 8 | The loopback-only local run |
| `README.md`, `CLAUDE.md` | 9 | Run instructions and the current state |

Paths under `security/`, `domain/`, `persistence/`, `service/` and `rest/` are relative to `src/main/java/io/github/markusluisflores/frontrow/`. Tests mirror them under `src/test/java/…`.

---

### Task 1: Remove what revision 2.7 cut

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/security/Role.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/security/SecurityConfig.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/security/DemoCredentials.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/TestCredentials.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/security/SecurityChainsTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Role` has exactly `BUYER` and `ORGANISER`. The test users are `alice`, `bob` (`BUYER`), `carol` (`BUYER`, `ORGANISER`) and `olivia` (`ORGANISER`); the demo users are `alice`, `bob` and `olivia`.

- [ ] **Step 1: Change the tests first**

In `SecurityChainsTest`:

1. In `aBearerTokenIsRefusedOnAnonymousAndAuthenticatedApiEndpointsAlike`, drop `get("/api/orders/x")` from the list:

```java
        for (MockHttpServletRequestBuilder request : List.of(get("/api/events"), post(CONFIRM), post("/api/events"))) {
```

2. Replace the body of `eachWriteEndpointAdmitsOnlyItsRole` from the `for` loops to the end of the method with:

```java
        for (Supplier<MockHttpServletRequestBuilder> request : buyerOnly) {
            assertThat(perform(request.get().with(as("olivia"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("alice"))).getStatus()).isNotIn(401, 403);
        }
        // ORGANISER only: a buyer is refused.
        for (Supplier<MockHttpServletRequestBuilder> request : List.<Supplier<MockHttpServletRequestBuilder>>of(
                () -> patch("/api/events/1"), () -> post("/api/events"))) {
            assertThat(perform(request.get().with(as("alice"))).getStatus()).isEqualTo(403);
            assertThat(perform(request.get().with(as("olivia"))).getStatus()).isNotIn(401, 403);
        }
    }
```

3. Replace `anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser` with:

```java
    @Test
    void anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser() throws Exception {
        assertThat(perform(get("/api/admin").with(as("alice"))).getStatus()).isEqualTo(403);
        // Revision 2.7 cut GET /api/orders/{id}: with its rule gone, the path is unlisted and denied, not routed.
        assertThat(perform(get("/api/orders/1").with(as("alice"))).getStatus()).isEqualTo(403);
    }
```

4. In `theCatchAllOpensOnlyItsAnonymousPaths`, change the `adam` line to:

```java
        assertThat(perform(get("/actuator/env").with(as("alice"))).getStatus()).isEqualTo(401);
```

- [ ] **Step 2: Run the test to see the new assertion fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=SecurityChainsTest`
Expected: FAIL in `anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser`. `/api/orders/1` as `alice` answers 404, because the `/api/orders/*` rule still admits a `BUYER`.

- [ ] **Step 3: Remove the rule, the role and the users**

In `SecurityConfig#apiChain`, delete these two lines:

```java
                        .requestMatchers(HttpMethod.GET, "/api/orders/*")
                        .hasAnyRole("BUYER", "ADMIN")
```

`Role.java` becomes:

```java
package io.github.markusluisflores.frontrow.security;

/**
 * The two REST roles (spec §9; revision 2.7 cut ADMIN). An agent token never carries one: the MCP chain grants only
 * AGENT (spec §3).
 */
public enum Role {
    BUYER,
    ORGANISER
}
```

In `DemoCredentials`, replace the cast's javadoc and map:

```java
    /** The demo cast: two buyers (alice holds the agent token) and an organiser. */
    static final Map<String, Role> USERS = Map.of("alice", Role.BUYER, "bob", Role.BUYER, "olivia", Role.ORGANISER);
```

In `TestCredentials`, replace `USERS`:

```java
    /** Each user's roles. alice and carol hold agent tokens; carol is also an ORGANISER. */
    public static final Map<String, List<String>> USERS = Map.of(
            "alice", List.of("BUYER"),
            "bob", List.of("BUYER"),
            "carol", List.of("BUYER", "ORGANISER"),
            "olivia", List.of("ORGANISER"));
```

- [ ] **Step 4: Run the whole suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS. Then confirm nothing else names the cut:
`grep -rn "ADMIN\|adam\|api/orders" src/` prints only the new `/api/orders/1` assertion and its comment, and `Role.java`'s own javadoc line ("revision 2.7 cut ADMIN").

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/security src/test/java/io/github/markusluisflores/frontrow/security/SecurityChainsTest.java src/test/java/io/github/markusluisflores/frontrow/support/TestCredentials.java
git commit -m "refactor(security): remove the admin role and the order-by-id rule"
```

---

### Task 2: Sales close by the event's start, on every `PATCH` that sets a bound

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/domain/SalesWindow.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/EventRow.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/LockingGateway.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventInstants.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/EventPatchService.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/domain/SalesWindowTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/service/EventPatchServiceTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/rest/WriteEndpointsTest.java`

**Interfaces:**
- Consumes: Task 1's test users.
- Produces:
  - `SalesWindow#closesBy(Instant startsAt)` → `boolean`;
  - `EventRow(long id, long venueId, EventStatus status, Instant startsAt, Instant salesOpenAt, Instant salesCloseAt, String currency)`;
  - package-private `EventInstants.EARLIEST`, `EventInstants.LATEST` and `EventInstants.storable(Instant)` → `boolean`;
  - `HoldFixtures#setStartsAt(long eventId, Instant)` and `HoldFixtures#setSalesCloseAt(long eventId, Instant)`.

- [ ] **Step 1: Write the failing domain test**

Add to `SalesWindowTest`:

```java
    @Test
    void aWindowClosesByAStartAtOrAfterItsClose() {
        assertThat(WINDOW.closesBy(CLOSE)).isTrue();
        assertThat(WINDOW.closesBy(CLOSE.plusMillis(1))).isTrue();
        assertThat(WINDOW.closesBy(CLOSE.minusMillis(1))).isFalse();
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=SalesWindowTest`
Expected: compilation failure, `cannot find symbol: method closesBy(java.time.Instant)`.

- [ ] **Step 3: Add `closesBy`**

In `SalesWindow`, after `state(Instant)`:

```java
    /**
     * Whether sales close by the event's start (spec §5, Event cancellation): nothing is sold for a performance that has
     * begun, and a window that closes by the start is already closed once the event has started.
     */
    public boolean closesBy(Instant startsAt) {
        return !closeAt.isAfter(startsAt);
    }
```

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=SalesWindowTest`
Expected: PASS.

- [ ] **Step 4: Add the fixtures the service tests need**

Add to `HoldFixtures`, after `setEventStatus`:

```java
    public void setStartsAt(long eventId, Instant startsAt) {
        jdbc.sql("UPDATE event SET starts_at = :starts WHERE id = :id")
                .param("starts", utc(startsAt))
                .param("id", eventId)
                .update();
    }

    /** Writes a window bound past the checks, as a row stored before them would be. */
    public void setSalesCloseAt(long eventId, Instant salesCloseAt) {
        jdbc.sql("UPDATE event SET sales_close_at = :close WHERE id = :id")
                .param("close", utc(salesCloseAt))
                .param("id", eventId)
                .update();
    }
```

- [ ] **Step 5: Write the failing service tests**

In `EventPatchServiceTest`, add `import java.time.temporal.ChronoUnit;`, then add:

```java
    @Test
    void aWindowMayCloseAtTheStartButNotAfterIt() {
        // HoldFixtures events start at NOW + 30 days.
        Instant startsAt = NOW.plus(30, ChronoUnit.DAYS);

        DomainException rejected = rejection(
                () -> patches.patch(new EventPatch(eventId, null, null, startsAt.plusNanos(1000))));
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", "sales_window");

        assertThat(patches.patch(new EventPatch(eventId, null, null, startsAt)).salesCloseAt())
                .isEqualTo(startsAt);
    }

    @Test
    void noPatchCanReopenSalesForAnEventThatHasStarted() {
        // Spec §6: on main an organiser could reopen sales after starts_at. Every reopening sets sales_close_at past
        // the start, so the check refuses it whatever the clock says.
        Instant startsAt = NOW.plus(30, ChronoUnit.DAYS);
        clock.set(startsAt.plus(1, ChronoUnit.DAYS));

        DomainException rejected = rejection(() -> patches.patch(
                new EventPatch(eventId, null, null, startsAt.plus(7, ChronoUnit.DAYS))));

        assertThat(rejected.details()).containsEntry("parameter", "sales_window");
    }

    @Test
    void theCheckUsesTheMergedWindowAndSkipsAStatusOnlyPatch() {
        // A row whose stored window already closes after the start, as one written before the check would.
        fixtures.setSalesCloseAt(eventId, NOW.plus(31, ChronoUnit.DAYS));

        DomainException rejected =
                rejection(() -> patches.patch(new EventPatch(eventId, null, NOW.minusSeconds(60), null)));
        assertThat(rejected.details()).containsEntry("parameter", "sales_window");

        // A cancel sets no bound, so it is not checked: an event with nothing sold can always be cancelled.
        assertThat(patches.patch(new EventPatch(eventId, EventStatus.CANCELLED, null, null))
                        .status())
                .isEqualTo(EventStatus.CANCELLED);
    }
```

Then point the existing bound tests at `EventInstants`, and give the widest-window test an event that starts at `LATEST`, so the window check doesn't refuse it:
- In `anInstantPostgresCannotStoreIsInvalidRequestNotA500`, replace `EventPatchService.EARLIEST` with `EventInstants.EARLIEST`, and `EventPatchService.LATEST` with `EventInstants.LATEST`.
- Replace `theWidestAllowedWindowIsStoredExactly` with:

```java
    @Test
    void theWidestAllowedWindowIsStoredExactly() {
        fixtures.setStartsAt(eventId, EventInstants.LATEST);

        EventPatchResult result =
                patches.patch(new EventPatch(eventId, null, EventInstants.EARLIEST, EventInstants.LATEST));

        assertThat(result.salesOpenAt()).isEqualTo(EventInstants.EARLIEST);
        assertThat(result.salesCloseAt()).isEqualTo(EventInstants.LATEST);
        assertThat(jdbc.sql("SELECT sales_open_at, sales_close_at FROM event WHERE id = :id")
                        .param("id", eventId)
                        .query((rs, row) -> List.of(
                                rs.getObject(1, OffsetDateTime.class).toInstant(),
                                rs.getObject(2, OffsetDateTime.class).toInstant()))
                        .single())
                .containsExactly(EventInstants.EARLIEST, EventInstants.LATEST);
    }
```

And in `WriteEndpointsTest`, add the same rule over HTTP:

```java
    @Test
    void aPatchThatClosesSalesAfterTheStartIsInvalid() throws Exception {
        // The fixture event starts at 2026-10-31T12:00:00Z.
        assertInvalid(patchEvent("olivia", "{\"sales_close_at\": \"2026-10-31T12:00:00.000001Z\"}"), "sales_window");
        assertThat(perform(patchEvent("olivia", "{\"sales_close_at\": \"2026-10-31T12:00:00Z\"}"))
                        .getStatus())
                .isEqualTo(200);
    }
```

- [ ] **Step 6: Run them to see them fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=EventPatchServiceTest+WriteEndpointsTest`
Expected: compilation failure, `cannot find symbol: variable EventInstants`.

- [ ] **Step 7: Carry `starts_at` on the locked event row**

`EventRow.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/** An event row as committed, read under a lock — deliberately not an entity (spec §5). */
public record EventRow(
        long id,
        long venueId,
        EventStatus status,
        Instant startsAt,
        Instant salesOpenAt,
        Instant salesCloseAt,
        String currency) {}
```

In `LockingGateway`, change `EVENT_COLUMNS` and the row mapper in `lockEvent`:

```java
    private static final String EVENT_COLUMNS =
            "id, venue_id, status, starts_at, sales_open_at, sales_close_at, currency";
```

```java
                .query((rs, rowNum) -> new EventRow(
                        rs.getLong("id"),
                        rs.getLong("venue_id"),
                        EventStatus.valueOf(rs.getString("status")),
                        instantAt(rs, "starts_at"),
                        instantAt(rs, "sales_open_at"),
                        instantAt(rs, "sales_close_at"),
                        rs.getString("currency")))
```

- [ ] **Step 8: Share the bound, and check the window on the `PATCH`**

`service/EventInstants.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.time.Instant;

/**
 * The instants both event write paths take from the caller: creation's starts_at and sales window, and a PATCH's sales
 * window (spec §5, Event cancellation). An instant outside this range is invalid_request and never reaches SQL, where
 * it would fail as a 500 (PR #22 thread 4305094277). The range is what both timestamptz and a four-digit ISO 8601 year
 * hold.
 */
final class EventInstants {

    static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

    static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private EventInstants() {}

    static boolean storable(Instant instant) {
        return !instant.isBefore(EARLIEST) && !instant.isAfter(LATEST);
    }
}
```

In `EventPatchService`:
- delete the `EARLIEST` and `LATEST` fields and their javadoc;
- add `import io.github.markusluisflores.frontrow.domain.SalesWindow;`;
- change the `requireAllowed` call to:

```java
            boolean windowSet = patch.salesOpenAt() != null || patch.salesCloseAt() != null;
            requireAllowed(event, status, openAt, closeAt, windowSet);
```

Then replace `requireAllowed` with:

```java
    private static void requireAllowed(
            EventRow event, EventStatus status, Instant openAt, Instant closeAt, boolean windowSet) {
        boolean statusAllowed =
                switch (event.status()) {
                    case DRAFT -> true;
                    // No PATCH moves an event back to DRAFT (spec §5, confirmation step 3).
                    case ON_SALE -> status != EventStatus.DRAFT;
                    case CANCELLED -> status == EventStatus.CANCELLED;
                };
        if (!statusAllowed) {
            throw invalid("status");
        }
        if (!EventInstants.storable(openAt) || !EventInstants.storable(closeAt)) {
            throw invalid("sales_window");
        }
        boolean windowChanged = !openAt.equals(event.salesOpenAt()) || !closeAt.equals(event.salesCloseAt());
        if ((event.status() == EventStatus.CANCELLED && windowChanged) || !openAt.isBefore(closeAt)) {
            throw invalid("sales_window");
        }
        // Only a PATCH that sets a bound is checked, against the merged window, so a status-only PATCH such as a
        // cancel always passes: an event with nothing sold can always be cancelled (spec §5).
        if (windowSet && !SalesWindow.of(openAt, closeAt).closesBy(event.startsAt())) {
            throw invalid("sales_window");
        }
    }
```

Update the class javadoc's last sentence to say the window must close by the event's start:

```java
 * it, for any event: Phase 1 has no per-event ownership (plan 4A decision 2). A PATCH that sets a window bound must
 * leave the window closing by the event's start (spec §5), so no request can reopen a started event's sales.
```

- [ ] **Step 9: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS, with the new tests passing.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/domain/SalesWindow.java src/main/java/io/github/markusluisflores/frontrow/persistence src/main/java/io/github/markusluisflores/frontrow/service src/test/java
git commit -m "fix(service): require event sales to close by the start time"
```

---

### Task 3: Event creation (`POST /api/events`)

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventDraft.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventDetail.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventCreateService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatRepository.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/rest/EventController.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/ServiceTransactionBoundaryTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/service/EventCreateServiceTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/rest/WriteEndpointsTest.java`

**Interfaces:**
- Consumes: `EventInstants` and `SalesWindow#closesBy` (Task 2).
- Produces:
  - `EventDraft(Long venueId, String name, Instant startsAt, Instant salesOpenAt, Instant salesCloseAt, String currency, Map<String, Long> prices)`;
  - `EventDetail(long eventId, long venueId, String venueName, String name, Instant startsAt, Instant salesOpenAt, Instant salesCloseAt, EventStatus status, String currency)`, with package-private `static EventDetail of(Event)`;
  - `EventCreateService#create(EventDraft)` → `EventDetail`;
  - `SeatRepository#findByVenueIdOrderByIdAsc(Long)` → `List<Seat>`;
  - `HoldFixtures#seats(long venueId, String section, int count)` → `List<Long>`.

- [ ] **Step 1: Add the seat fixture**

In `HoldFixtures`, after `eventSeats(long, long, int, long)`:

```java
    /** Fresh seats in one section of a venue, offered by no event yet, in ascending id order. */
    public List<Long> seats(long venueId, String section, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(jdbc.sql("""
                            INSERT INTO seat (venue_id, section, row_label, seat_number)
                            VALUES (:venue, :section, 'A', :number) RETURNING id""")
                    .param("venue", venueId)
                    .param("section", section)
                    .param("number", seatNumbers.incrementAndGet())
                    .query(Long.class)
                    .single());
        }
        return ids;
    }
```

- [ ] **Step 2: Write the failing service test**

`src/test/java/io/github/markusluisflores/frontrow/service/EventCreateServiceTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class EventCreateServiceTest {

    private static final Instant STARTS = Instant.parse("2030-06-14T19:00:00Z");
    private static final Instant OPENS = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-06-14T17:00:00Z");
    private static final Map<String, Long> PRICES = Map.of("Floor", 6500L, "Balcony", 4500L);

    @Autowired
    EventCreateService creations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        fixtures.seats(venueId, "Floor", 3);
        fixtures.seats(venueId, "Balcony", 2);
    }

    @Test
    void createsADraftThatOffersEverySeatAtItsSectionPrice() {
        EventDetail created = creations.create(draft(STARTS, OPENS, CLOSES, PRICES));

        assertThat(created.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(created.venueId()).isEqualTo(venueId);
        assertThat(created.venueName()).isEqualTo("Fixture Hall");
        assertThat(created.startsAt()).isEqualTo(STARTS);
        assertThat(created.salesCloseAt()).isEqualTo(CLOSES);
        assertThat(jdbc.sql("""
                        SELECT s.section, count(*) AS seats, min(es.price_cents) AS low, max(es.price_cents) AS high
                        FROM event_seat es JOIN seat s ON s.id = es.seat_id
                        WHERE es.event_id = :event GROUP BY s.section ORDER BY s.section""")
                        .param("event", created.eventId())
                        .query((rs, row) -> rs.getString("section") + " " + rs.getInt("seats") + " "
                                + rs.getLong("low") + " " + rs.getLong("high"))
                        .list())
                .containsExactly("Balcony 2 4500 4500", "Floor 3 6500 6500");
    }

    @Test
    void theWindowMayCloseAtTheStartButNotAfterIt() {
        assertThat(creations.create(draft(STARTS, OPENS, STARTS, PRICES)).salesCloseAt())
                .isEqualTo(STARTS);
        assertInvalid(() -> creations.create(draft(STARTS, OPENS, STARTS.plusNanos(1000), PRICES)), "sales_window");
        assertInvalid(() -> creations.create(draft(STARTS, CLOSES, CLOSES, PRICES)), "sales_window");
    }

    @Test
    void anInstantPostgresCannotStoreNamesItsParameter() {
        assertInvalid(
                () -> creations.create(draft(EventInstants.EARLIEST.minusNanos(1000), OPENS, CLOSES, PRICES)),
                "starts_at");
        assertInvalid(
                () -> creations.create(draft(Instant.parse("+300000-01-01T00:00:00Z"), OPENS, CLOSES, PRICES)),
                "starts_at");
        assertInvalid(() -> creations.create(draft(STARTS, Instant.MIN, CLOSES, PRICES)), "sales_window");
        assertInvalid(
                () -> creations.create(draft(
                        EventInstants.LATEST, OPENS, EventInstants.LATEST.plusNanos(1000), PRICES)),
                "sales_window");
        assertThat(eventCount()).isZero();
    }

    @Test
    void theWidestStorableEventIsStoredExactly() {
        EventDetail created = creations.create(
                draft(EventInstants.LATEST, EventInstants.EARLIEST, EventInstants.LATEST, PRICES));

        assertThat(jdbc.sql("SELECT starts_at, sales_open_at, sales_close_at FROM event WHERE id = :id")
                        .param("id", created.eventId())
                        .query((rs, row) -> List.of(
                                rs.getObject(1, OffsetDateTime.class).toInstant(),
                                rs.getObject(2, OffsetDateTime.class).toInstant(),
                                rs.getObject(3, OffsetDateTime.class).toInstant()))
                        .single())
                .containsExactly(EventInstants.LATEST, EventInstants.EARLIEST, EventInstants.LATEST);
    }

    @Test
    void thePricesMustCoverExactlyTheVenuesSections() {
        for (Map<String, Long> prices : List.of(
                Map.of("Floor", 6500L),
                Map.of("Floor", 6500L, "Balcony", 4500L, "Box", 9000L),
                Map.of("Floor", 6500L, "Balcony", -1L))) {
            assertInvalid(() -> creations.create(draft(STARTS, OPENS, CLOSES, prices)), "prices");
        }
        assertInvalid(() -> creations.create(draft(STARTS, OPENS, CLOSES, null)), "prices");
        assertThat(eventCount()).isZero();
    }

    @Test
    void eachMissingOrMalformedFieldIsNamed() {
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, " ", STARTS, OPENS, CLOSES, "CAD", PRICES)), "name");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, null, STARTS, OPENS, CLOSES, "CAD", PRICES)), "name");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, "Gig", STARTS, OPENS, CLOSES, "cad", PRICES)),
                "currency");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, "Gig", null, OPENS, CLOSES, "CAD", PRICES)),
                "starts_at");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId, "Gig", STARTS, null, CLOSES, "CAD", PRICES)),
                "sales_window");
        assertInvalid(
                () -> creations.create(new EventDraft(null, "Gig", STARTS, OPENS, CLOSES, "CAD", PRICES)),
                "venue_id");
        assertInvalid(
                () -> creations.create(new EventDraft(venueId + 1000, "Gig", STARTS, OPENS, CLOSES, "CAD", PRICES)),
                "venue_id");
        assertThat(eventCount()).isZero();
    }

    private EventDraft draft(Instant startsAt, Instant openAt, Instant closeAt, Map<String, Long> prices) {
        return new EventDraft(venueId, "Night Owls Live", startsAt, openAt, closeAt, "CAD", prices);
    }

    private int eventCount() {
        return jdbc.sql("SELECT count(*) FROM event").query(Integer.class).single();
    }

    private static void assertInvalid(ThrowingCallable call, String parameter) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        DomainException rejected = (DomainException) thrown;
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", parameter);
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=EventCreateServiceTest`
Expected: compilation failure, `cannot find symbol: class EventCreateService`.

- [ ] **Step 4: Write the command, the result and the service**

`service/EventDraft.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.Map;

/**
 * An organiser's new event (spec §6, POST /api/events): a DRAFT on an existing venue, with a price in cents per section
 * of that venue. Any field may be null; the service names the first one it refuses.
 */
public record EventDraft(
        Long venueId,
        String name,
        Instant startsAt,
        Instant salesOpenAt,
        Instant salesCloseAt,
        String currency,
        Map<String, Long> prices) {

    public EventDraft {
        prices = prices == null ? null : Map.copyOf(prices);
    }
}
```

`service/EventDetail.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.persistence.Event;
import java.time.Instant;

/**
 * An event as creation and the reads return it (spec §6). A read never returns a DRAFT: a draft is not_found there
 * (spec §4). Creation returns the new DRAFT, which is how an organiser sees it.
 */
public record EventDetail(
        long eventId,
        long venueId,
        String venueName,
        String name,
        Instant startsAt,
        Instant salesOpenAt,
        Instant salesCloseAt,
        EventStatus status,
        String currency) {

    static EventDetail of(Event event) {
        return new EventDetail(
                event.getId(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getName(),
                event.getStartsAt(),
                event.getSalesOpenAt(),
                event.getSalesCloseAt(),
                event.getStatus(),
                event.getCurrency());
    }
}
```

`persistence/SeatRepository.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Every seat in a venue, for event creation (spec §6). Seats are never written after seeding. */
    List<Seat> findByVenueIdOrderByIdAsc(Long venueId);
}
```

`service/EventCreateService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.SalesWindow;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.Event;
import io.github.markusluisflores.frontrow.persistence.EventRepository;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.Seat;
import io.github.markusluisflores.frontrow.persistence.SeatRepository;
import io.github.markusluisflores.frontrow.persistence.Venue;
import io.github.markusluisflores.frontrow.persistence.VenueRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event creation (spec §6, POST /api/events): a DRAFT on an existing venue, offering every seat of the venue at its
 * section's price. The instants are bounded and the window must close by the start, as on a PATCH (spec §5). It reads no
 * clock and takes no lock: it makes no time-dependent decision, and nothing else ever locks the venue and seat rows its
 * foreign keys check.
 */
@Service
public class EventCreateService {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final VenueRepository venues;
    private final SeatRepository seats;
    private final EventRepository events;
    private final EventSeatRepository eventSeats;

    public EventCreateService(
            VenueRepository venues, SeatRepository seats, EventRepository events, EventSeatRepository eventSeats) {
        this.venues = venues;
        this.seats = seats;
        this.events = events;
        this.eventSeats = eventSeats;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventDetail create(EventDraft draft) {
        TransactionBoundary.requireOwnTransaction();
        if (draft.name() == null || draft.name().isBlank()) {
            throw invalid("name");
        }
        if (draft.currency() == null || !CURRENCY.matcher(draft.currency()).matches()) {
            throw invalid("currency");
        }
        Instant startsAt = storable(draft.startsAt(), "starts_at");
        Instant openAt = storable(draft.salesOpenAt(), "sales_window");
        Instant closeAt = storable(draft.salesCloseAt(), "sales_window");
        if (!openAt.isBefore(closeAt) || !SalesWindow.of(openAt, closeAt).closesBy(startsAt)) {
            throw invalid("sales_window");
        }
        if (draft.venueId() == null) {
            throw invalid("venue_id");
        }
        try {
            Venue venue = venues.findById(draft.venueId()).orElseThrow(() -> invalid("venue_id"));
            List<Seat> venueSeats = seats.findByVenueIdOrderByIdAsc(venue.getId());
            Map<String, Long> prices = requirePrices(draft.prices(), venueSeats);
            Event event = events.saveAndFlush(
                    new Event(venue, draft.name(), startsAt, openAt, closeAt, EventStatus.DRAFT, draft.currency()));
            eventSeats.saveAllAndFlush(venueSeats.stream()
                    .map(seat -> new EventSeat(event, seat, venue.getId(), prices.get(seat.getSection())))
                    .toList());
            return EventDetail.of(event);
        } catch (DataAccessException failure) {
            throw SqlFailures.translate(failure);
        }
    }

    /** Exactly the venue's sections, each at zero cents or more. */
    private static Map<String, Long> requirePrices(Map<String, Long> prices, List<Seat> venueSeats) {
        Set<String> sections = venueSeats.stream().map(Seat::getSection).collect(Collectors.toSet());
        if (prices == null
                || !prices.keySet().equals(sections)
                || prices.values().stream().anyMatch(price -> price < 0)) {
            throw invalid("prices");
        }
        return prices;
    }

    /** timestamptz keeps microseconds: the instant is checked as it will be stored. */
    private static Instant storable(Instant instant, String parameter) {
        if (instant == null || !EventInstants.storable(instant)) {
            throw invalid(parameter);
        }
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static DomainException invalid(String parameter) {
        return new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }
}
```

- [ ] **Step 5: Register the service with the transaction checks**

In `TransactionIsolationTest`, append `EventCreateService.class` to `SERVICES`:

```java
    static final List<Class<?>> SERVICES = List.of(
            HoldService.class,
            ReleaseService.class,
            ConfirmService.class,
            EventPatchService.class,
            ExpirySweepService.class,
            EventCreateService.class);
```

In `ServiceTransactionBoundaryTest`, add `import java.util.Map;` (if missing), then a field and a call:

```java
    @Autowired
    EventCreateService creations;
```

```java
        calls.put(
                "EventCreateService#create",
                () -> creations.create(new EventDraft(1L, "x", null, null, null, "CAD", Map.of())));
```

- [ ] **Step 6: Run the service tests**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=EventCreateServiceTest+TransactionIsolationTest+ServiceTransactionBoundaryTest`
Expected: PASS.

- [ ] **Step 7: Write the failing endpoint test**

Add to `WriteEndpointsTest`:

```java
    @Test
    void anOrganiserCreatesADraftAndPutsItOnSale() throws Exception {
        long newVenue = fixtures.venue();
        fixtures.seats(newVenue, "Stalls", 2);
        String json = JSON.writeValueAsString(Map.of(
                "venue_id", newVenue,
                "name", "Quiet Strings Quartet",
                "starts_at", "2026-11-20T18:30:00Z",
                "sales_open_at", "2026-09-01T00:00:00Z",
                "sales_close_at", "2026-11-20T16:30:00Z",
                "currency", "CAD",
                "prices", Map.of("Stalls", 3800)));

        MockHttpServletResponse created = perform(post("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(as("olivia")));

        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode event = body(created);
        assertThat(event.get("status").asString()).isEqualTo("DRAFT");
        assertThat(event.get("venue_name").asString()).isEqualTo("Fixture Hall");
        long newEvent = event.get("event_id").asLong();
        assertThat(perform(patch("/api/events/" + newEvent)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\": \"ON_SALE\"}")
                                .with(as("olivia")))
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aMalformedCreationNamesItsParameter() throws Exception {
        assertInvalid(
                post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\": {\"Floor\": null}}")
                        .with(as("olivia")),
                "prices");
        assertInvalid(
                post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Gig\", \"currency\": \"CAD\", \"starts_at\": \"2030-01-01T00:00:00Z\"}")
                        .with(as("olivia")),
                "sales_window");
    }
```

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=WriteEndpointsTest`
Expected: FAIL. `POST /api/events` has no handler, and `olivia`'s request gets 404 instead of 201.

- [ ] **Step 8: Add the endpoint**

`rest/EventController.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.service.EventCreateService;
import io.github.markusluisflores.frontrow.service.EventDetail;
import io.github.markusluisflores.frontrow.service.EventDraft;
import io.github.markusluisflores.frontrow.service.EventPatch;
import io.github.markusluisflores.frontrow.service.EventPatchResult;
import io.github.markusluisflores.frontrow.service.EventPatchService;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Events (spec §6): an organiser creates a DRAFT and changes its status, cancellation included, and its sales window.
 * Any ORGANISER may change any event, because Phase 1 records no event owner (plan 4A decision 2).
 */
@RestController
public class EventController {

    private final EventPatchService patches;
    private final EventCreateService creations;

    public EventController(EventPatchService patches, EventCreateService creations) {
        this.patches = patches;
        this.creations = creations;
    }

    /** The JSON body of a PATCH. An absent field keeps its current value. */
    public record EventPatchRequest(EventStatus status, Instant salesOpenAt, Instant salesCloseAt) {}

    /**
     * The JSON body of a creation. prices maps each section name to cents; map keys are data, so the snake_case wire
     * leaves them alone. Jackson refuses a null price itself, so the error names prices.
     */
    public record EventCreateRequest(
            Long venueId,
            String name,
            Instant startsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            String currency,
            @JsonSetter(contentNulls = Nulls.FAIL) Map<String, Long> prices) {

        public EventCreateRequest {
            prices = prices == null ? null : Map.copyOf(prices);
        }
    }

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.CREATED)
    public EventDetail create(@RequestBody EventCreateRequest request) {
        return creations.create(new EventDraft(
                request.venueId(),
                request.name(),
                request.startsAt(),
                request.salesOpenAt(),
                request.salesCloseAt(),
                request.currency(),
                request.prices()));
    }

    @PatchMapping("/api/events/{eventId}")
    public EventPatchResult patch(@PathVariable long eventId, @RequestBody EventPatchRequest request) {
        return patches.patch(new EventPatch(eventId, request.status(), request.salesOpenAt(), request.salesCloseAt()));
    }
}
```

- [ ] **Step 9: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS. `SecurityChainsTest#eachWriteEndpointAdmitsOnlyItsRole` still passes: `olivia`'s empty `POST /api/events` now answers 400, which is neither 401 nor 403.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow src/test/java
git commit -m "feat(rest): add event creation for organisers"
```

---

### Task 4: The event reads, and the read-exposure test

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/EventRepository.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/EventSeatRepository.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHoldRepository.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventQuery.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/Availability.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/EventReadService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/rest/EventController.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/support/HoldFixtures.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/ServiceTransactionBoundaryTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/service/EventReadServiceTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/rest/ReadEndpointsTest.java`

**Interfaces:**
- Consumes: `EventDetail` and `EventDetail.of(Event)` (Task 3), `EventInstants` (Task 2).
- Produces:
  - `EventQuery(String query, LocalDate fromDate, LocalDate toDate, Integer limit)`;
  - `Availability(long eventId, String currency, List<SeatState> seats)` with `Availability.SeatState(long eventSeatId, String section, String rowLabel, int seatNumber, long priceCents, boolean available)`;
  - `EventReadService#search(EventQuery)` → `List<EventDetail>`, `#event(long)` → `EventDetail`, and `#availability(long eventId, String section)` → `Availability`. Plan 5's three read tools call these.
  - `HoldFixtures#namedEvent(long venueId, String name, String status, Instant startsAt)` → `long`.

- [ ] **Step 1: Add the named-event fixture**

In `HoldFixtures`, after `onSaleEvent`:

```java
    /** An event with its own name and start; its sales opened a day before NOW and close at its start. */
    public long namedEvent(long venueId, String name, String status, Instant startsAt) {
        return jdbc.sql("""
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (:venue, :name, :starts, :open, :starts, :status, 'CAD') RETURNING id""")
                .param("venue", venueId)
                .param("name", name)
                .param("starts", utc(startsAt))
                .param("open", utc(NOW.minus(1, ChronoUnit.DAYS)))
                .param("status", status)
                .query(Long.class)
                .single();
    }
```

- [ ] **Step 2: Write the failing service test**

`src/test/java/io/github/markusluisflores/frontrow/service/EventReadServiceTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class EventReadServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    EventReadService reads;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long venueId;
    long owls;
    long draft;
    long cancelled;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        venueId = fixtures.venue();
        owls = fixtures.namedEvent(venueId, "Night Owls Live", "ON_SALE", Instant.parse("2026-11-10T19:00:00Z"));
        fixtures.namedEvent(venueId, "Night owls 100% Unplugged", "ON_SALE", Instant.parse("2026-11-12T19:00:00Z"));
        fixtures.namedEvent(venueId, "Daybreak", "ON_SALE", Instant.parse("2026-12-01T10:00:00Z"));
        draft = fixtures.namedEvent(venueId, "Secret Draft Owls", "DRAFT", Instant.parse("2026-11-11T19:00:00Z"));
        cancelled = fixtures.namedEvent(venueId, "Cancelled Owls", "CANCELLED", Instant.parse("2026-11-11T20:00:00Z"));
    }

    @Test
    void searchListsOnlyOnSaleEventsSoonestFirst() {
        assertThat(names(new EventQuery(null, null, null, null)))
                .containsExactly("Night Owls Live", "Night owls 100% Unplugged", "Daybreak");
    }

    @Test
    void searchMatchesTheNameCaseInsensitivelyAndLiterally() {
        assertThat(names(new EventQuery("OWLS", null, null, null)))
                .containsExactly("Night Owls Live", "Night owls 100% Unplugged");
        // %, _ and the escape character ! are characters to match, not wildcards. Each empty result below would match
        // a name if that character went to LIKE unescaped.
        assertThat(names(new EventQuery("100%", null, null, null))).containsExactly("Night owls 100% Unplugged");
        assertThat(names(new EventQuery("owls%live", null, null, null))).isEmpty();
        assertThat(names(new EventQuery("_", null, null, null))).isEmpty();
        assertThat(names(new EventQuery("owl!s", null, null, null))).isEmpty();
    }

    @Test
    void searchLowerCasesTheQueryAndTheNameWithOneEngine() {
        // Java lower-cases a word-final Σ to ς; Postgres's lower() gives σ. If the two sides were lowered by different
        // engines, the exact name would not find itself.
        fixtures.namedEvent(venueId, "ΟΔΟΣ", "ON_SALE", Instant.parse("2027-02-01T19:00:00Z"));
        assertThat(names(new EventQuery("ΟΔΟΣ", null, null, null))).containsExactly("ΟΔΟΣ");
    }

    @Test
    void searchFiltersOnTheStartDateWithBothEndsInclusive() {
        LocalDate twelfth = LocalDate.parse("2026-11-12");
        assertThat(names(new EventQuery(null, twelfth, twelfth, null))).containsExactly("Night owls 100% Unplugged");
        assertThat(names(new EventQuery(null, LocalDate.parse("2026-11-11"), null, null)))
                .containsExactly("Night owls 100% Unplugged", "Daybreak");
        // Dates no timestamptz can hold are clamped to the storable range, never sent to SQL.
        assertThat(names(new EventQuery(null, LocalDate.MIN, LocalDate.MAX, null))).hasSize(3);
    }

    @Test
    void searchLimitDefaultsToTenAndIsBoundedToFifty() {
        for (int i = 0; i < 10; i++) {
            fixtures.namedEvent(venueId, "Extra " + i, "ON_SALE", Instant.parse("2027-01-01T00:00:00Z"));
        }
        assertThat(reads.search(new EventQuery(null, null, null, null))).hasSize(10);
        assertThat(reads.search(new EventQuery(null, null, null, 2))).hasSize(2);
        assertThat(reads.search(new EventQuery(null, null, null, 50))).hasSize(13);
        assertInvalid(() -> reads.search(new EventQuery(null, null, null, 0)), "limit");
        assertInvalid(() -> reads.search(new EventQuery(null, null, null, 51)), "limit");
    }

    @Test
    void onlyAnOnSaleEventIsReadableById() {
        assertThat(rejection(() -> reads.event(draft)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.availability(draft, null)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.event(draft + 1000)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.event(cancelled)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.availability(cancelled, null)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rejection(() -> reads.availability(draft + 1000, null)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(reads.event(owls).venueName()).isEqualTo("Fixture Hall");
    }

    @Test
    void availabilitySaysWhetherEachSeatIsClaimedNow() {
        List<Long> seats = fixtures.eventSeats(owls, venueId, 4);
        fixtures.hold(seats.get(0), "bob", "ACTIVE", NOW.plusSeconds(60));
        fixtures.hold(seats.get(1), "alice", "ACTIVE", NOW.minusSeconds(1));
        fixtures.hold(seats.get(2), "alice", "CONVERTED", NOW.minusSeconds(1));

        Availability availability = reads.availability(owls, null);

        assertThat(availability.eventId()).isEqualTo(owls);
        assertThat(availability.currency()).isEqualTo("CAD");
        assertThat(availability.seats())
                .extracting(Availability.SeatState::eventSeatId, Availability.SeatState::available)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(seats.get(0), false),
                        org.assertj.core.groups.Tuple.tuple(seats.get(1), true),
                        org.assertj.core.groups.Tuple.tuple(seats.get(2), false),
                        org.assertj.core.groups.Tuple.tuple(seats.get(3), true));
        assertThat(reads.availability(owls, "Floor").seats()).hasSize(4);
        assertThat(reads.availability(owls, "Balcony").seats()).isEmpty();
    }

    @Test
    void theAvailabilityReadCarriesNoHoldOrOwnerField() {
        // Spec §6 and §12: availability shows whether a seat is free, never whose hold it is.
        assertThat(componentNames(Availability.class)).containsExactly("eventId", "currency", "seats");
        assertThat(componentNames(Availability.SeatState.class))
                .containsExactly("eventSeatId", "section", "rowLabel", "seatNumber", "priceCents", "available");
    }

    private List<String> names(EventQuery query) {
        return reads.search(query).stream().map(EventDetail::name).toList();
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static void assertInvalid(ThrowingCallable call, String parameter) {
        DomainException rejected = rejection(call);
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(rejected.details()).containsEntry("parameter", parameter);
    }

    private static DomainException rejection(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DomainException.class);
        return (DomainException) thrown;
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=EventReadServiceTest`
Expected: compilation failure, `cannot find symbol: class EventReadService`.

- [ ] **Step 4: Add the queries**

`persistence/EventRepository.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query(value = "SELECT status FROM event WHERE id = :id", nativeQuery = true)
    String findStatusTextById(Long id);

    /**
     * search_events and GET /api/events (spec §6): one status, a LIKE pattern escaped with '!', and starts_at in
     * [from, to], soonest first. Postgres's lower() lowers both the name and the pattern, so one engine's case rules
     * apply to both sides; it leaves '!', '%' and '_' alone. Every parameter is non-null, so no typed-null binding
     * reaches Postgres.
     */
    @Query("""
            SELECT e FROM Event e JOIN FETCH e.venue
            WHERE e.status = :status AND lower(e.name) LIKE lower(:pattern) ESCAPE '!'
            AND e.startsAt >= :from AND e.startsAt <= :to
            ORDER BY e.startsAt, e.id""")
    List<Event> search(EventStatus status, String pattern, Instant from, Instant to, Limit limit);

    @Query("SELECT e FROM Event e JOIN FETCH e.venue WHERE e.id = :id")
    Optional<Event> findWithVenue(long id);
}
```

Add to `EventSeatRepository` (with `import java.util.List;` already present):

```java
    /** Every seat the event offers, with its physical seat, in section, row and number order. */
    @Query("""
            SELECT s FROM EventSeat s JOIN FETCH s.seat p
            WHERE s.event.id = :eventId
            ORDER BY p.section, p.rowLabel, p.seatNumber, s.id""")
    List<EventSeat> findByEventWithSeat(long eventId);
```

Add to `SeatHoldRepository` (imports: `io.github.markusluisflores.frontrow.domain.HoldStatus`, `java.time.Instant`, `java.util.List`):

```java
    /**
     * The event's seats claimed now: a CONVERTED row, or an ACTIVE row still live (spec §4). Seat ids only, so the
     * availability read cannot show whose claim it is (spec §6).
     */
    @Query("""
            SELECT h.eventSeat.id FROM SeatHold h
            WHERE h.eventSeat.event.id = :eventId
            AND (h.status = :sold OR (h.status = :active AND h.expiresAt > :now))""")
    List<Long> findClaimedSeatIds(long eventId, Instant now, HoldStatus sold, HoldStatus active);
```

- [ ] **Step 5: Write the read types and the service**

`service/EventQuery.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.time.LocalDate;

/**
 * search_events and GET /api/events (spec §6). Every field is optional: query matches the name, the dates bound
 * starts_at inclusively in UTC, and limit defaults to 10, with 50 the most.
 */
public record EventQuery(String query, LocalDate fromDate, LocalDate toDate, Integer limit) {}
```

`service/Availability.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.util.List;

/**
 * check_availability and GET /api/events/{id}/availability (spec §6). available means nothing claims the seat now: no
 * sale and no live hold. It shows whether a seat is free and never whose hold it is: no hold_group_id, owner or
 * expires_at (spec §6, §12). It does not say the event is on sale; the event read shows its status.
 */
public record Availability(long eventId, String currency, List<SeatState> seats) {

    public Availability {
        seats = List.copyOf(seats);
    }

    public record SeatState(
            long eventSeatId, String section, String rowLabel, int seatNumber, long priceCents, boolean available) {}
}
```

`service/EventReadService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.DomainException;
import io.github.markusluisflores.frontrow.error.ErrorCode;
import io.github.markusluisflores.frontrow.persistence.Event;
import io.github.markusluisflores.frontrow.persistence.EventRepository;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.EventSeatRepository;
import io.github.markusluisflores.frontrow.persistence.SeatHoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event reads (spec §6): search, one event, and its availability. The REST reads and plan 5's three read tools call
 * the same methods. Read-only transactions with no lock (ADR-005): nothing here writes, so no entity is ever flushed. A
 * Only ON_SALE events are readable, by id or in search (spec §4).
 */
@Service
public class EventReadService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final SeatHoldRepository seatHolds;
    private final Clock clock;

    public EventReadService(
            EventRepository events, EventSeatRepository eventSeats, SeatHoldRepository seatHolds, Clock clock) {
        this.events = events;
        this.eventSeats = eventSeats;
        this.seatHolds = seatHolds;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<EventDetail> search(EventQuery query) {
        TransactionBoundary.requireOwnTransaction();
        int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, Map.of("parameter", "limit"));
        }
        return events
                .search(
                        EventStatus.ON_SALE,
                        pattern(query.query()),
                        startOf(query.fromDate()),
                        endOf(query.toDate()),
                        Limit.of(limit))
                .stream()
                .map(EventDetail::of)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public EventDetail event(long eventId) {
        TransactionBoundary.requireOwnTransaction();
        return EventDetail.of(visible(eventId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public Availability availability(long eventId, String section) {
        TransactionBoundary.requireOwnTransaction();
        Event event = visible(eventId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Set<Long> claimed =
                Set.copyOf(seatHolds.findClaimedSeatIds(eventId, now, HoldStatus.CONVERTED, HoldStatus.ACTIVE));
        List<Availability.SeatState> seats = eventSeats.findByEventWithSeat(eventId).stream()
                .filter(seat -> section == null || seat.getSeat().getSection().equals(section))
                .map(seat -> state(seat, claimed))
                .toList();
        return new Availability(event.getId(), event.getCurrency(), seats);
    }

    /**
     * An event that is not ON_SALE answers the same as a missing id. A DRAFT is hidden by spec §4. A CANCELLED event
     * may have been cancelled straight from DRAFT (EventPatchService allows it), so it is hidden too, or a
     * never-published draft's details would become readable by cancelling it.
     */
    private Event visible(long eventId) {
        return events.findWithVenue(eventId)
                .filter(event -> event.getStatus() == EventStatus.ON_SALE)
                .orElseThrow(() -> DomainException.of(ErrorCode.NOT_FOUND));
    }

    private static Availability.SeatState state(EventSeat seat, Set<Long> claimed) {
        return new Availability.SeatState(
                seat.getId(),
                seat.getSeat().getSection(),
                seat.getSeat().getRowLabel(),
                seat.getSeat().getSeatNumber(),
                seat.getPriceCents(),
                !claimed.contains(seat.getId()));
    }

    /**
     * A literal substring match: '!', '%' and '_' in the query are escaped with '!'. The case is left alone:
     * EventRepository#search lowers both sides in Postgres, because Java's lower-casing differs from Postgres's on a
     * word-final Σ and on İ.
     */
    private static String pattern(String query) {
        if (query == null) {
            return "%";
        }
        String escaped = query.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static Instant startOf(LocalDate fromDate) {
        return fromDate == null ? EventInstants.EARLIEST : clamp(fromDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * The last storable instant of to_date (the next day's start less a microsecond), an inclusive bound, so an event
     * starting exactly at LATEST is found. No to_date, or a year past 9999, is LATEST itself.
     */
    private static Instant endOf(LocalDate toDate) {
        if (toDate == null || toDate.getYear() > 9999) {
            return EventInstants.LATEST;
        }
        return clamp(toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minus(1, ChronoUnit.MICROS));
    }

    /** Postgres never sees an instant outside the range timestamptz holds (spec §5). */
    private static Instant clamp(Instant instant) {
        if (instant.isBefore(EventInstants.EARLIEST)) {
            return EventInstants.EARLIEST;
        }
        return instant.isAfter(EventInstants.LATEST) ? EventInstants.LATEST : instant;
    }
}
```

- [ ] **Step 6: Register the service with the transaction checks**

In `TransactionIsolationTest`, append `EventReadService.class` to `SERVICES`. In `ServiceTransactionBoundaryTest`, add the field and three calls:

```java
    @Autowired
    EventReadService eventReads;
```

```java
        calls.put("EventReadService#search", () -> eventReads.search(new EventQuery(null, null, null, null)));
        calls.put("EventReadService#event", () -> eventReads.event(1));
        calls.put("EventReadService#availability", () -> eventReads.availability(1, null));
```

- [ ] **Step 7: Run the service tests**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=EventReadServiceTest+TransactionIsolationTest+ServiceTransactionBoundaryTest`
Expected: PASS.

- [ ] **Step 8: Write the failing endpoint test**

`src/test/java/io/github/markusluisflores/frontrow/rest/ReadEndpointsTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The three anonymous event reads end to end (spec §6). Bodies are compared as parsed JSON, never as strings. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTestConfiguration.class)
class ReadEndpointsTest {

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
    long draftId;
    long cancelledId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        venueId = fixtures.venue();
        eventId = fixtures.namedEvent(venueId, "Night Owls Live", "ON_SALE", Instant.parse("2026-11-10T19:00:00Z"));
        draftId = fixtures.namedEvent(venueId, "Secret Draft", "DRAFT", Instant.parse("2026-11-11T19:00:00Z"));
        cancelledId = fixtures.namedEvent(venueId, "Cancelled Draft", "CANCELLED", Instant.parse("2026-11-12T19:00:00Z"));
        seats = fixtures.eventSeats(eventId, venueId, 2);
    }

    @Test
    void anonymousCallersSearchAndReadOnSaleEvents() throws Exception {
        MockHttpServletResponse listed = perform(get("/api/events").param("query", "owls"));
        assertThat(listed.getStatus()).isEqualTo(200);
        JsonNode events = body(listed);
        assertThat(events).hasSize(1);
        assertThat(names(events.get(0)))
                .containsExactlyInAnyOrder(
                        "event_id",
                        "venue_id",
                        "venue_name",
                        "name",
                        "starts_at",
                        "sales_open_at",
                        "sales_close_at",
                        "status",
                        "currency");

        MockHttpServletResponse one = perform(get("/api/events/" + eventId));
        assertThat(one.getStatus()).isEqualTo(200);
        assertThat(body(one).get("starts_at").asString()).isEqualTo("2026-11-10T19:00:00Z");
    }

    @Test
    void anEventThatIsNotOnSaleIsNotFoundOverHttp() throws Exception {
        for (long id : List.of(draftId, cancelledId)) {
            for (String path : List.of("/api/events/" + id, "/api/events/" + id + "/availability")) {
                MockHttpServletResponse response = perform(get(path));
                assertThat(response.getStatus()).as(path).isEqualTo(404);
                assertThat(body(response).get("code").asString()).isEqualTo("not_found");
            }
        }
        assertThat(body(perform(get("/api/events")))).hasSize(1);
    }

    @Test
    void availabilityShowsWhetherASeatIsFreeAndNeverWhoseHoldItIs() throws Exception {
        UUID bobsGroup = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(0), bobsGroup, "bob", "ACTIVE", HoldFixtures.NOW.plusSeconds(60));

        MockHttpServletResponse response = perform(get("/api/events/" + eventId + "/availability"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode availability = body(response);
        // Spec §6 and §12: no hold_group_id, owner or expires_at, at any level.
        assertThat(names(availability)).containsExactlyInAnyOrder("event_id", "currency", "seats");
        for (JsonNode seat : availability.get("seats")) {
            assertThat(names(seat))
                    .containsExactlyInAnyOrder(
                            "event_seat_id", "section", "row_label", "seat_number", "price_cents", "available");
        }
        assertThat(availability.get("seats").get(0).get("available").asBoolean())
                .isFalse();
        assertThat(availability.get("seats").get(1).get("available").asBoolean())
                .isTrue();
        String text = response.getContentAsString();
        assertThat(text).doesNotContain("bob", bobsGroup.toString());
    }

    @Test
    void malformedSearchParametersAreNamed() throws Exception {
        for (Map.Entry<String, String> bad : List.of(
                Map.entry("limit", "abc"),
                Map.entry("limit", "51"),
                Map.entry("from_date", "2026-13-01"),
                Map.entry("to_date", "yesterday"))) {
            MockHttpServletResponse response = perform(get("/api/events").param(bad.getKey(), bad.getValue()));
            assertThat(response.getStatus()).as(bad.toString()).isEqualTo(400);
            JsonNode problem = body(response);
            assertThat(problem.get("code").asString()).isEqualTo("invalid_request");
            assertThat(problem.get("parameter").asString()).isEqualTo(bad.getKey());
        }
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }
}
```

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=ReadEndpointsTest`
Expected: FAIL: `GET /api/events` and `GET /api/events/{eventId}` answer 405, since only `POST /api/events` and `PATCH /api/events/{eventId}` are mapped, so `anonymousCallersSearchAndReadOnSaleEvents` gets 405, not 200. The availability `GET` has no mapping on its path at all and answers 404.

- [ ] **Step 9: Add the read endpoints**

In `EventController`, add the imports:

```java
import io.github.markusluisflores.frontrow.service.Availability;
import io.github.markusluisflores.frontrow.service.EventQuery;
import io.github.markusluisflores.frontrow.service.EventReadService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
```

Then change the field, the constructor and the class javadoc, and add three methods:

```java
/**
 * Events (spec §6): anyone may search on-sale events and read one event and its availability; an organiser creates a
 * DRAFT and changes its status, cancellation included, and its sales window. Any ORGANISER may change any event,
 * because Phase 1 records no event owner (plan 4A decision 2).
 */
@RestController
public class EventController {

    private final EventPatchService patches;
    private final EventCreateService creations;
    private final EventReadService reads;

    public EventController(EventPatchService patches, EventCreateService creations, EventReadService reads) {
        this.patches = patches;
        this.creations = creations;
        this.reads = reads;
    }
```

```java
    /** Mirrors search_events: ON_SALE only, soonest first. The parameters are named as the wire names them. */
    @GetMapping("/api/events")
    public List<EventDetail> search(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "from_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @RequestParam(name = "to_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return reads.search(new EventQuery(query, fromDate, toDate, limit));
    }

    @GetMapping("/api/events/{eventId}")
    public EventDetail event(@PathVariable long eventId) {
        return reads.event(eventId);
    }

    @GetMapping("/api/events/{eventId}/availability")
    public Availability availability(
            @PathVariable long eventId, @RequestParam(name = "section", required = false) String section) {
        return reads.availability(eventId, section);
    }
```

A mistyped `limit`, `from_date` or `to_date` reaches `RestExceptionHandler#handleTypeMismatch`. Its property name is the request parameter's own name, already snake_case, so the error names it as sent.

- [ ] **Step 10: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS. Then re-run the lock-vocabulary grep from Global Constraints: only `LockingGateway.java`.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow src/test/java
git commit -m "feat(rest): add the event search, detail and availability reads"
```

---

### Task 5: The caller's own holds and orders

**Files:**
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/SeatHoldRepository.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/persistence/TicketOrderRepository.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/HeldGroup.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/service/OwnerReadService.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/OrderResult.java`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/service/ConfirmService.java`
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/MeController.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/TransactionIsolationTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/service/ServiceTransactionBoundaryTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/service/OwnerReadServiceTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/rest/MeEndpointsTest.java`

**Interfaces:**
- Consumes: `HoldService#hold`, `ConfirmService#confirm`, `HoldResult` and `OrderResult` (plans 3A and 3B).
- Produces:
  - `HeldGroup(UUID holdGroupId, long eventId, String eventName, List<GroupSeat> seats, long totalCents, String currency, Instant expiresAt)` with `HeldGroup.GroupSeat(long eventSeatId, String section, String rowLabel, int seatNumber, long priceCents)`;
  - `OwnerReadService#holds(String owner)` → `List<HeldGroup>`, and `#orders(String owner)` → `List<OrderResult>`;
  - package-private `static OrderResult of(TicketOrder, List<OrderLine>)` on `OrderResult`.

- [ ] **Step 1: Write the failing service test**

`src/test/java/io/github/markusluisflores/frontrow/service/OwnerReadServiceTest.java`:

```java
package io.github.markusluisflores.frontrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ServiceTestConfiguration.class)
class OwnerReadServiceTest {

    private static final Instant NOW = HoldFixtures.NOW;

    @Autowired
    OwnerReadService reads;

    @Autowired
    HoldService holds;

    @Autowired
    ConfirmService confirms;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    HoldFixtures fixtures;
    long eventId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 6);
    }

    @Test
    void holdsListsOnlyTheCallersGroupsThatStillHaveALiveSeat() {
        UUID alices = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(0), seats.get(1)), "k1"))
                .holdGroupId();
        UUID bobs = holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(2)), "k1"))
                .holdGroupId();
        // Lapsed and never swept: no live row, so not listed.
        fixtures.holdInGroup(seats.get(3), UUID.randomUUID(), "alice", "ACTIVE", NOW.minusSeconds(1));
        // A live group that expires sooner than the new hold's 10-minute TTL.
        UUID sooner = UUID.randomUUID();
        fixtures.holdInGroup(seats.get(4), sooner, "alice", "ACTIVE", NOW.plusSeconds(60));

        List<HeldGroup> alicesGroups = reads.holds("alice");

        // Soonest expiry first: the first group expires in 60 s, the new hold in the 10-minute TTL.
        assertThat(alicesGroups).extracting(HeldGroup::holdGroupId).containsExactly(sooner, alices);
        HeldGroup held = alicesGroups.get(1);
        assertThat(held.eventId()).isEqualTo(eventId);
        assertThat(held.eventName()).isEqualTo("Fixture event");
        assertThat(held.seats()).extracting(HeldGroup.GroupSeat::eventSeatId).containsExactly(seats.get(0), seats.get(1));
        assertThat(held.totalCents()).isEqualTo(2 * HoldFixtures.PRICE_CENTS);
        assertThat(held.expiresAt()).isEqualTo(NOW.plusSeconds(600));

        assertThat(reads.holds("bob")).extracting(HeldGroup::holdGroupId).containsExactly(bobs);
        assertThat(reads.holds("carol")).isEmpty();
    }

    @Test
    void ordersListsOnlyTheCallersOrdersNewestFirst() {
        UUID first = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(0)), "k1"))
                .holdGroupId();
        long firstOrder = confirms.confirm(new HoldGroupCommand("alice", first)).orderId();
        UUID bobs = holds.hold(new HoldCommand("bob", eventId, List.of(seats.get(1)), "k1"))
                .holdGroupId();
        long bobsOrder = confirms.confirm(new HoldGroupCommand("bob", bobs)).orderId();
        clock.set(NOW.plusSeconds(60));
        UUID second = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(2), seats.get(3)), "k2"))
                .holdGroupId();
        long secondOrder = confirms.confirm(new HoldGroupCommand("alice", second)).orderId();

        List<OrderResult> alicesOrders = reads.orders("alice");

        assertThat(alicesOrders).extracting(OrderResult::orderId).containsExactly(secondOrder, firstOrder);
        assertThat(alicesOrders.get(0).lines())
                .extracting(OrderResult.Line::eventSeatId)
                .containsExactly(seats.get(2), seats.get(3));
        assertThat(reads.orders("bob")).extracting(OrderResult::orderId).containsExactly(bobsOrder);
        // A converted group is bought, not held: it leaves holds and appears in orders.
        assertThat(reads.holds("alice")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=OwnerReadServiceTest`
Expected: compilation failure, `cannot find symbol: class OwnerReadService`.

- [ ] **Step 3: Add the queries**

Add to `SeatHoldRepository`:

```java
    /**
     * GET /api/me/holds (spec §6): every row of the owner's groups that still have a live row, the same rule as an
     * active group for the cap (spec §4). Groups come out contiguous, soonest expiry first: a group's rows share one
     * expires_at. Loaded only in read-only transactions, which never flush (ADR-005).
     */
    @Query("""
            SELECT h FROM SeatHold h JOIN FETCH h.eventSeat s JOIN FETCH s.seat JOIN FETCH s.event
            WHERE h.owner = :owner AND h.holdGroupId IN (
                SELECT l.holdGroupId FROM SeatHold l
                WHERE l.owner = :owner AND l.status = :active AND l.expiresAt > :now)
            ORDER BY h.expiresAt, h.holdGroupId, s.id""")
    List<SeatHold> findGroupsWithALiveRow(String owner, Instant now, HoldStatus active);
```

Add to `TicketOrderRepository` (with `import java.util.List;`):

```java
    /** GET /api/me/orders (spec §6): the owner's own orders, newest first. */
    List<TicketOrder> findByOwnerOrderByCreatedAtDescIdDesc(String owner);
```

- [ ] **Step 4: Share `OrderResult`'s mapping with confirm**

In `OrderResult`, add the imports `io.github.markusluisflores.frontrow.persistence.OrderLine` and `io.github.markusluisflores.frontrow.persistence.TicketOrder`, then add after the compact constructor:

```java
    /** One order as confirm and GET /api/me/orders both return it. */
    static OrderResult of(TicketOrder order, List<OrderLine> lines) {
        return new OrderResult(
                order.getId(),
                order.getHoldGroupId(),
                order.getEvent().getId(),
                lines.stream()
                        .map(line -> new Line(line.getEventSeat().getId(), line.getPriceCents()))
                        .toList(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getCreatedAt());
    }
```

In `ConfirmService`, delete the private `result(TicketOrder, List<OrderLine>)` method, and replace its two calls, `result(` with `OrderResult.of(`.

- [ ] **Step 5: Write the result type and the service**

`service/HeldGroup.java`:

```java
package io.github.markusluisflores.frontrow.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of the caller's hold groups (spec §6, GET /api/me/holds), listed while at least one of its rows is live: the
 * rule that makes it count against the hold-group cap (spec §4). A user and their agent are one owner, so this lists
 * the agent's holds too (spec §6, the handover).
 */
public record HeldGroup(
        UUID holdGroupId,
        long eventId,
        String eventName,
        List<GroupSeat> seats,
        long totalCents,
        String currency,
        Instant expiresAt) {

    public HeldGroup {
        seats = List.copyOf(seats);
    }

    public record GroupSeat(
            long eventSeatId, String section, String rowLabel, int seatNumber, long priceCents) {}
}
```

`service/OwnerReadService.java`:

```java
package io.github.markusluisflores.frontrow.service;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.persistence.Event;
import io.github.markusluisflores.frontrow.persistence.EventSeat;
import io.github.markusluisflores.frontrow.persistence.OrderLineRepository;
import io.github.markusluisflores.frontrow.persistence.SeatHold;
import io.github.markusluisflores.frontrow.persistence.SeatHoldRepository;
import io.github.markusluisflores.frontrow.persistence.TicketOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own holds and orders (spec §6, GET /api/me/*). Owner scoping is this service's job, not the role
 * table's: every query filters by the owner the adapter passes, which is always the authenticated principal (spec §3).
 * Read-only transactions with no lock (ADR-005).
 */
@Service
public class OwnerReadService {

    private final SeatHoldRepository seatHolds;
    private final TicketOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final Clock clock;

    public OwnerReadService(
            SeatHoldRepository seatHolds,
            TicketOrderRepository orders,
            OrderLineRepository orderLines,
            Clock clock) {
        this.seatHolds = seatHolds;
        this.orders = orders;
        this.orderLines = orderLines;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<HeldGroup> holds(String owner) {
        TransactionBoundary.requireOwnTransaction();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<UUID, List<SeatHold>> groups = new LinkedHashMap<>();
        for (SeatHold row : seatHolds.findGroupsWithALiveRow(owner, now, HoldStatus.ACTIVE)) {
            groups.computeIfAbsent(row.getHoldGroupId(), group -> new ArrayList<>())
                    .add(row);
        }
        return groups.values().stream().map(OwnerReadService::group).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<OrderResult> orders(String owner) {
        TransactionBoundary.requireOwnTransaction();
        return orders.findByOwnerOrderByCreatedAtDescIdDesc(owner).stream()
                .map(order -> OrderResult.of(order, orderLines.findByOrderIdInSeatOrder(order.getId())))
                .toList();
    }

    /** The whole group as held. */
    private static HeldGroup group(List<SeatHold> rows) {
        Event event = rows.getFirst().getEventSeat().getEvent();
        List<HeldGroup.GroupSeat> seats = rows.stream()
                .map(row -> {
                    EventSeat seat = row.getEventSeat();
                    return new HeldGroup.GroupSeat(
                            seat.getId(),
                            seat.getSeat().getSection(),
                            seat.getSeat().getRowLabel(),
                            seat.getSeat().getSeatNumber(),
                            seat.getPriceCents());
                })
                .toList();
        long total = seats.stream().mapToLong(HeldGroup.GroupSeat::priceCents).sum();
        return new HeldGroup(
                rows.getFirst().getHoldGroupId(),
                event.getId(),
                event.getName(),
                seats,
                total,
                event.getCurrency(),
                rows.getFirst().getExpiresAt());
    }
}
```

- [ ] **Step 6: Register the service with the transaction checks**

In `TransactionIsolationTest`, append `OwnerReadService.class` to `SERVICES`. In `ServiceTransactionBoundaryTest`, add the field and two calls:

```java
    @Autowired
    OwnerReadService ownerReads;
```

```java
        calls.put("OwnerReadService#holds", () -> ownerReads.holds("alice"));
        calls.put("OwnerReadService#orders", () -> ownerReads.orders("alice"));
```

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=OwnerReadServiceTest+ConfirmServiceTest+TransactionIsolationTest+ServiceTransactionBoundaryTest`
Expected: PASS. `ConfirmServiceTest` confirms the move of `OrderResult.of` changed nothing.

- [ ] **Step 7: Write the failing endpoint test**

`src/test/java/io/github/markusluisflores/frontrow/rest/MeEndpointsTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.markusluisflores.frontrow.service.HoldCommand;
import io.github.markusluisflores.frontrow.service.HoldService;
import io.github.markusluisflores.frontrow.support.HoldFixtures;
import io.github.markusluisflores.frontrow.support.ServiceTestConfiguration;
import io.github.markusluisflores.frontrow.support.TestClock;
import io.github.markusluisflores.frontrow.support.TestCredentials;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * GET /api/me/holds and GET /api/me/orders return only the caller's rows, never another buyer's (spec §6, §12). The
 * role table admits any BUYER to both paths; the scoping under test is the service's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ServiceTestConfiguration.class)
class MeEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    HoldService holds;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    long eventId;
    List<Long> seats;

    @BeforeEach
    void seed() {
        HoldFixtures fixtures = new HoldFixtures(jdbc);
        fixtures.truncateAll();
        clock.set(HoldFixtures.NOW);
        long venueId = fixtures.venue();
        eventId = fixtures.onSaleEvent(venueId);
        seats = fixtures.eventSeats(eventId, venueId, 4);
    }

    @Test
    void eachBuyerSeesOnlyTheirOwnHoldsAndOrders() throws Exception {
        String alicesHeld = groupOf(perform(hold("alice", "k1", seats.get(0))));
        String alicesBought = groupOf(perform(hold("alice", "k2", seats.get(1))));
        perform(post("/api/holds/" + alicesBought + "/confirm").with(as("alice")));
        String bobsHeld = groupOf(perform(hold("bob", "k1", seats.get(2))));
        // What alice's agent holds over MCP lands under the same owner: the service call an MCP tool makes.
        String agentHeld = holds.hold(new HoldCommand("alice", eventId, List.of(seats.get(3)), "agent-k1"))
                .holdGroupId()
                .toString();

        assertThat(groupIds(get("/api/me/holds"), "alice")).containsExactlyInAnyOrder(alicesHeld, agentHeld);
        assertThat(groupIds(get("/api/me/holds"), "bob")).containsExactly(bobsHeld);
        assertThat(groupIds(get("/api/me/orders"), "alice")).containsExactly(alicesBought);
        assertThat(groupIds(get("/api/me/orders"), "bob")).isEmpty();
    }

    private List<String> groupIds(MockHttpServletRequestBuilder request, String user) throws Exception {
        MockHttpServletResponse response = perform(request.with(as(user)));
        assertThat(response.getStatus()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode row : JSON.readTree(response.getContentAsString())) {
            ids.add(row.get("hold_group_id").asString());
        }
        return ids;
    }

    private MockHttpServletRequestBuilder hold(String user, String key, Long seatId) {
        return post("/api/events/" + eventId + "/holds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("event_seat_ids", List.of(seatId))))
                .with(as(user));
    }

    private static String groupOf(MockHttpServletResponse held) throws Exception {
        assertThat(held.getStatus()).isEqualTo(201);
        return JSON.readTree(held.getContentAsString()).get("hold_group_id").asString();
    }

    private static RequestPostProcessor as(String user) {
        return httpBasic(user, TestCredentials.password(user));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }
}
```

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=MeEndpointsTest`
Expected: FAIL. `GET /api/me/holds` has no handler: the role table admits `alice` and MVC answers 404, not 200.

- [ ] **Step 8: Add the controller**

`rest/MeController.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import io.github.markusluisflores.frontrow.service.HeldGroup;
import io.github.markusluisflores.frontrow.service.OrderResult;
import io.github.markusluisflores.frontrow.service.OwnerReadService;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A buyer's own holds and orders (spec §6). The owner is the authenticated user's name, never a parameter (spec §3), and
 * the service filters by it: the role table only admits BUYER.
 */
@RestController
public class MeController {

    private final OwnerReadService reads;

    public MeController(OwnerReadService reads) {
        this.reads = reads;
    }

    /** Own live holds, including the ones the user's agent made: they are one owner (spec §6, the handover). */
    @GetMapping("/api/me/holds")
    public List<HeldGroup> holds(Principal principal) {
        return reads.holds(principal.getName());
    }

    @GetMapping("/api/me/orders")
    public List<OrderResult> orders(Principal principal) {
        return reads.orders(principal.getName());
    }
}
```

- [ ] **Step 9: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow src/test/java
git commit -m "feat(rest): add the caller's own holds and orders reads"
```

---

### Task 6: springdoc and Swagger UI

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/io/github/markusluisflores/frontrow/rest/OpenApiConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/io/github/markusluisflores/frontrow/rest/OpenApiDocsTest.java`

**Interfaces:**
- Consumes: every controller from Tasks 3–5 and plan 4A.
- Produces: `/v3/api-docs` and `/swagger-ui/index.html`, both anonymous through the catch-all chain.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/markusluisflores/frontrow/rest/OpenApiDocsTest.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The OpenAPI document and Swagger UI (spec §6, §10): anonymous, the whole REST table, and the wire's own names. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Test
    void theDocumentListsExactlyTheRestSurface() throws Exception {
        JsonNode docs = docs();
        List<String> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : docs.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                operations.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
            }
        }
        // Spec §6's REST table, row for row; nothing from /mcp, and nothing revision 2.7 cut.
        assertThat(operations)
                .containsExactlyInAnyOrder(
                        "GET /api/events",
                        "POST /api/events",
                        "GET /api/events/{eventId}",
                        "PATCH /api/events/{eventId}",
                        "GET /api/events/{eventId}/availability",
                        "POST /api/events/{eventId}/holds",
                        "DELETE /api/holds/{holdGroupId}",
                        "POST /api/holds/{holdGroupId}/confirm",
                        "GET /api/me/holds",
                        "GET /api/me/orders");
        assertThat(docs.at("/components/securitySchemes/basic/scheme").asString())
                .isEqualTo("basic");
    }

    @Test
    void schemasUseTheWireNames() throws Exception {
        // swagger-core renders schemas with its own Jackson 2 mapper; without OpenApiConfig's resolver these would be
        // camelCase, and Swagger UI's "Try it out" would send bodies the API refuses.
        JsonNode schemas = docs().at("/components/schemas");
        assertThat(names(schemas.at("/HoldRequest/properties"))).containsExactly("event_seat_ids");
        assertThat(names(schemas.at("/EventCreateRequest/properties")))
                .contains("venue_id", "starts_at", "sales_open_at", "sales_close_at", "prices")
                .doesNotContain("venueId", "startsAt");
        assertThat(names(schemas.at("/Availability/properties"))).contains("event_id", "seats");
        // swagger-core names a schema by its class's simple name, so two seat records must not share one: the hold
        // response's seat and me/holds' seat each keep their own fields.
        assertThat(names(schemas.at("/GroupSeat/properties"))).contains("event_seat_id", "section");
        assertThat(names(schemas.at("/HeldSeat/properties")))
                .contains("event_seat_id", "price_cents")
                .doesNotContain("section");
        // A 3.0-mode resolver under springdoc's default OpenAPI 3.1 document would drop the schema's type.
        assertThat(schemas.at("/EventCreateRequest/type").asString()).isEqualTo("object");
    }

    @Test
    void theServerUrlDoesNotComeFromTheHostHeader() throws Exception {
        // springdoc otherwise writes the requesting Host into the shared cached document, and Swagger UI's "Try it
        // out" would send Basic credentials there.
        MockHttpServletResponse response = mvc.perform(get("/v3/api-docs").header("Host", "evil.example"))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).at("/servers/0/url").asString())
                .isEqualTo("/");
    }

    @Test
    void swaggerUiIsServedAnonymously() throws Exception {
        assertThat(mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private JsonNode docs() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString());
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=OpenApiDocsTest`
Expected: FAIL. `/v3/api-docs` passes the catch-all as anonymous, but nothing serves it, so the status is 404.

- [ ] **Step 3: Add the dependency**

In `pom.xml`, add `<springdoc.version>3.1.1</springdoc.version>` to `<properties>`, and this dependency after `spring-boot-starter-actuator`:

```xml
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
```

- [ ] **Step 4: Configure the document**

`rest/OpenApiConfig.java`:

```java
package io.github.markusluisflores.frontrow.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document behind Swagger UI (spec §10: Swagger UI is the human-facing demo). Served anonymously by the
 * catch-all chain at /v3/api-docs and /swagger-ui/index.html (spec §3).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BASIC = "basic";

    /**
     * HTTP Basic for the whole document; the three event reads need no credentials. Swagger UI's Authorize button sends
     * the header itself, because no 401 carries a challenge (spec §3). The server URL is pinned to "/": it must not
     * come from a request's Host header.
     */
    @Bean
    OpenAPI frontRowOpenApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(new Info()
                        .title("FrontRow")
                        .version("phase-1")
                        .description("Event ticketing. Agents hold over MCP; only a human buys, here."))
                .components(new Components()
                        .addSecuritySchemes(
                                BASIC, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC));
    }

    /**
     * Schemas named as the wire names them (spec §6, snake_case). springdoc renders them with swagger-core's own Jackson
     * 2 mapper, which knows nothing of the application's Jackson 3 naming strategy.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json31.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
                .openapi31(true);
    }
}
```

In `application.yml`, add this top-level block after `management`:

```yaml
springdoc:
  # Only the REST surface. /mcp is described by its own checked-in tool schemas (spec §7), not by OpenAPI.
  paths-to-match: /api/**
```

- [ ] **Step 5: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS. If springdoc pulls a Jackson 2 module that Boot 4 auto-configures, or `-Werror` flags a deprecation in `Json31.mapper()`, apply decision 1's deviation rule. Keep the snake_case schemas and the anonymous paths.

- [ ] **Step 6: Check the CI dependency scan locally**

Run: `./mvnw -q dependency:tree -Dincludes=org.springdoc,io.swagger.core.v3,com.fasterxml.jackson.core`
Expected: springdoc 3.1.1 and swagger-core 2.2.x, with Jackson 2 only as swagger-core's dependency. Note the versions in the task report: the PR's `dependency-review` check reads them.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/io/github/markusluisflores/frontrow/rest/OpenApiConfig.java src/main/resources/application.yml src/test/java/io/github/markusluisflores/frontrow/rest/OpenApiDocsTest.java
git commit -m "feat(rest): serve the openapi document and swagger ui"
```

---

### Task 7: Seed data on the demo profile

**Files:**
- Create: `src/main/resources/db/seed/V1_1__demo_seed.sql`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/io/github/markusluisflores/frontrow/schema/SeedDataTest.java`

**Interfaces:**
- Consumes: the V1 schema.
- Produces:
  - two venues: `Riverside Hall`, with Floor rows A and B of 10 seats each and Balcony row A of 6 (26 seats), and `The Lantern Room`, with Stalls rows A and B of 8 (16 seats);
  - three `ON_SALE` events in `CAD`, with 68 `event_seat` rows between them.

  Task 8's compose run and the README rely on these.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/markusluisflores/frontrow/schema/SeedDataTest.java`:

```java
package io.github.markusluisflores.frontrow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The demo profile's seed (plan 4B decision 2). There is no schema CHECK for sales_close_at <= starts_at (Markus,
 * 2026-10-28), so this test guards the seeded rows instead (spec §5, §12). Its own context and database: no other test
 * runs with the demo profile, so every other test database starts empty.
 */
@SpringBootTest
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class SeedDataTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void everySeededEventClosesSalesByItsStart() {
        List<String> late = jdbc.sql("SELECT name FROM event WHERE sales_close_at > starts_at")
                .query(String.class)
                .list();
        assertThat(eventCount()).isEqualTo(3);
        assertThat(late).isEmpty();
    }

    @Test
    void everySeededEventIsOnSaleAndOffersEverySeatOfItsVenue() {
        assertThat(jdbc.sql("SELECT DISTINCT status FROM event").query(String.class).list())
                .containsExactly("ON_SALE");
        int unoffered = jdbc.sql("""
                        SELECT count(*) FROM event e JOIN seat s ON s.venue_id = e.venue_id
                        LEFT JOIN event_seat es ON es.event_id = e.id AND es.seat_id = s.id
                        WHERE es.id IS NULL""")
                .query(Integer.class)
                .single();
        assertThat(unoffered).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM event_seat").query(Integer.class).single())
                .isEqualTo(68);
    }

    private int eventCount() {
        return jdbc.sql("SELECT count(*) FROM event").query(Integer.class).single();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=SeedDataTest`
Expected: FAIL in both tests. `eventCount()` is 0, because nothing seeds yet.

- [ ] **Step 3: Write the seed**

`src/main/resources/db/seed/V1_1__demo_seed.sql`:

```sql
-- Demo seed (plan 4B decision 2), applied only by the demo profile (application.yml). Fixed dates: every event starts
-- in 2030 or 2031 and its sales opened on 2026-01-01, so the demo stays on sale for years and reads no clock. Each
-- event's sales close two hours before it starts (spec §5: sales_close_at <= starts_at; SeedDataTest checks it).

INSERT INTO venue (name) VALUES ('Riverside Hall'), ('The Lantern Room');

INSERT INTO seat (venue_id, section, row_label, seat_number)
SELECT v.id, layout.section, layout.row_label, n
FROM venue v
JOIN (VALUES
        ('Riverside Hall', 'Floor', 'A', 10),
        ('Riverside Hall', 'Floor', 'B', 10),
        ('Riverside Hall', 'Balcony', 'A', 6),
        ('The Lantern Room', 'Stalls', 'A', 8),
        ('The Lantern Room', 'Stalls', 'B', 8)
    ) AS layout (venue, section, row_label, seats) ON layout.venue = v.name
CROSS JOIN LATERAL generate_series(1, layout.seats) AS n;

INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
SELECT v.id, e.name, e.starts_at::timestamptz, '2026-01-01T00:00:00Z'::timestamptz,
       e.starts_at::timestamptz - interval '2 hours', 'ON_SALE', 'CAD'
FROM (VALUES
        ('Night Owls Live', 'Riverside Hall', '2030-06-14T19:00:00Z'),
        ('Quiet Strings Quartet', 'The Lantern Room', '2030-09-05T18:30:00Z'),
        ('Synth City', 'Riverside Hall', '2031-02-20T20:00:00Z')
    ) AS e (name, venue, starts_at)
JOIN venue v ON v.name = e.venue;

INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
SELECT e.id, s.id, s.venue_id, p.price_cents
FROM event e
JOIN seat s ON s.venue_id = e.venue_id
JOIN (VALUES
        ('Night Owls Live', 'Floor', 6500),
        ('Night Owls Live', 'Balcony', 4500),
        ('Quiet Strings Quartet', 'Stalls', 3800),
        ('Synth City', 'Floor', 7200),
        ('Synth City', 'Balcony', 5000)
    ) AS p (event, section, price_cents) ON p.event = e.name AND p.section = s.section;
```

- [ ] **Step 4: Turn it on for the demo profile only**

Append to `application.yml`, after the `frontrow:` block's trailing comments:

```yaml
---
# The demo profile adds the seed data (plan 4B decision 2): docker compose and the documented non-container run both
# use it. The test suite never does, so every test database starts empty.
spring:
  config:
    activate:
      on-profile: demo
  flyway:
    locations: classpath:db/migration,classpath:db/seed
```

- [ ] **Step 5: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS.

If a generated `config/application-demo.yml` exists in your checkout, `SeedDataTest` loads its users, because the demo profile reads `./config/application-demo.yml`, and its user list replaces the test's. A file generated after Task 1 is harmless: the test authenticates nobody. A plan-4A file is not: it gives `adam` the role `ADMIN`, which no longer binds, so this context fails to start. Delete a plan-4A file first. CI has no such file.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/seed src/main/resources/application.yml src/test/java/io/github/markusluisflores/frontrow/schema/SeedDataTest.java
git commit -m "feat(db): seed demo venues and events on the demo profile"
```

---

### Task 8: `docker compose` on loopback, with a generated Postgres password

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `compose.yaml`
- Create: `.env.example`
- Modify: `scripts/demo-credentials.sh`
- Modify: `src/main/java/io/github/markusluisflores/frontrow/security/DemoCredentials.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/security/DemoCredentialsTest.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/ComposeFileTest.java`

**Interfaces:**
- Consumes: the demo profile and seed (Task 7), and Swagger UI (Task 6).
- Produces:
  - `DemoCredentials.envFile(SecureRandom)` → `String`, and `DemoCredentials env` on the command line;
  - the `app` service on `127.0.0.1:8080`.

- [ ] **Step 1: Write the failing tests**

Add to `DemoCredentialsTest`:

```java
    @Test
    void theEnvFileHoldsOneRandomPostgresPassword() {
        String first = DemoCredentials.envFile(new SecureRandom());
        String second = DemoCredentials.envFile(new SecureRandom());

        assertThat(first).containsPattern("(?m)^POSTGRES_PASSWORD=[A-Za-z0-9_-]{32}$");
        assertThat(first).isNotEqualTo(second);
    }
```

`src/test/java/io/github/markusluisflores/frontrow/ComposeFileTest.java`:

```java
package io.github.markusluisflores.frontrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The local run is local only (spec §3, §12): the app published on host loopback, Postgres not published at all, and
 * Postgres's password only from the generated, gitignored .env. Read from the committed files, so an edit that
 * publishes a port fails the build.
 */
class ComposeFileTest {

    private static final String PASSWORD = "${POSTGRES_PASSWORD:?run scripts/demo-credentials.sh first}";

    @Test
    void theAppIsPublishedOnLoopbackOnlyAndPostgresNotAtAll() throws IOException {
        Map<?, ?> services = (Map<?, ?>) compose().get("services");

        assertThat(List.<Object>copyOf(services.keySet())).containsExactlyInAnyOrder("app", "postgres");
        assertThat(((Map<?, ?>) services.get("app")).get("ports")).isEqualTo(List.of("127.0.0.1:8080:8080"));
        assertThat(((Map<?, ?>) services.get("postgres")).containsKey("ports")).isFalse();
        // network_mode: host would put a service on every host interface without touching ports.
        for (String name : List.of("app", "postgres")) {
            assertThat(((Map<?, ?>) services.get(name)).containsKey("network_mode"))
                    .as(name)
                    .isFalse();
        }
    }

    @Test
    void thePostgresPasswordComesOnlyFromTheGeneratedEnvFile() throws IOException {
        Map<?, ?> services = (Map<?, ?>) compose().get("services");
        Map<?, ?> postgres = (Map<?, ?>) ((Map<?, ?>) services.get("postgres")).get("environment");
        Map<?, ?> app = (Map<?, ?>) ((Map<?, ?>) services.get("app")).get("environment");

        assertThat(postgres.get("POSTGRES_PASSWORD")).isEqualTo(PASSWORD);
        assertThat(app.get("SPRING_DATASOURCE_PASSWORD")).isEqualTo(PASSWORD);

        // The committed example is a placeholder that does not work: compose's :? refuses an empty value.
        Properties example = new Properties();
        example.load(new StringReader(Files.readString(Path.of(".env.example"), StandardCharsets.UTF_8)));
        assertThat(example.getProperty("POSTGRES_PASSWORD")).isEmpty();
    }

    private static Map<?, ?> compose() throws IOException {
        Map<?, ?> compose = new Yaml().load(Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8));
        return compose;
    }
}
```

SnakeYAML comes with `spring-boot-starter`. Check it is on the test classpath with `./mvnw -q dependency:tree -Dincludes=org.yaml`; if it is not, add `org.yaml:snakeyaml` with `<scope>test</scope>`, version managed by Boot.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw spotless:apply -q && ./mvnw -q test -Dtest=DemoCredentialsTest+ComposeFileTest`
Expected: compilation failure in `DemoCredentialsTest` (`cannot find symbol: method envFile`). Once that compiles, `ComposeFileTest` fails with `NoSuchFileException: compose.yaml`.

- [ ] **Step 3: Generate the `.env`**

In `DemoCredentials`, replace `main` and add `envFile`:

```java
    /**
     * With no argument, the users and agent token (hashes to stdout, raw values to stderr). With "env", the .env file
     * docker compose reads: a SecureRandom Postgres password (spec §12).
     */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length == 1 && "env".equals(args[0])) {
            out.print(envFile(new SecureRandom()));
            return;
        }
        Generated generated = generate(new SecureRandom());
        out.print(generated.yaml());
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.print(generated.rawValues());
    }

    /** Postgres keeps the password it first started with in its volume; the script writes this file only once. */
    static String envFile(SecureRandom random) {
        return "# Generated by scripts/demo-credentials.sh. Never commit.\nPOSTGRES_PASSWORD=" + randomValue(random)
                + "\n";
    }
```

Update the class javadoc to say that it also writes the `.env` (`main` with `env`).

- [ ] **Step 4: Write the container files**

`Dockerfile`:

```dockerfile
# The local demo image (spec §2: local only). CI runs the tests, Spotless and SpotBugs; this build skips them, because
# a Windows checkout's CRLF files would fail Spotless inside Linux.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# The wrapper needs unzip: without it, it fetches the tar.gz and fails the zip's distributionSha256Sum.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
RUN ./mvnw -B -q -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 frontrow
COPY --from=build /src/target/frontrow-*.jar app.jar
USER frontrow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore`:

```
.git
.idea
.vscode
.superpowers
.claude
target
config
**/config
.env
.env.*
**/.env
**/.env.*
logs
docs
```

`compose.yaml`:

```yaml
# The local demo (spec §3, §12). Run scripts/demo-credentials.sh first: it writes .env and config/application-demo.yml.
# The app is published on host loopback only, and Postgres is not published at all. "Local only" assumes Docker Engine
# 28.3.3 or later on Linux: earlier engines let a host on the same network reach container ports directly, and those
# before 28.3.3 do so again after a firewalld reload (CVE-2025-54388).
name: frontrow

services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: frontrow
      POSTGRES_USER: frontrow
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?run scripts/demo-credentials.sh first}
    volumes:
      - pgdata:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U frontrow -d frontrow"]
      interval: 2s
      timeout: 5s
      retries: 30

  app:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: demo
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/frontrow
      SPRING_DATASOURCE_USERNAME: frontrow
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?run scripts/demo-credentials.sh first}
    volumes:
      # Boot reads ./config/application-demo.yml from its working directory, /app.
      - ./config:/app/config:ro
    ports:
      - "127.0.0.1:8080:8080"

volumes:
  pgdata:
```

`.env.example`:

```
# Copy nothing from here: scripts/demo-credentials.sh writes .env with a SecureRandom password. This empty value does
# not work on purpose: compose refuses to start with it.
POSTGRES_PASSWORD=
```

- [ ] **Step 5: Teach the script to write `.env`**

Replace `scripts/demo-credentials.sh` with:

```bash
#!/usr/bin/env bash
# Generates the local demo's secrets (spec §9, §12). Each file is written only if it is missing; both are gitignored.
# - config/application-demo.yml: bcrypt and SHA-256 hashes of the demo users' passwords and alice's agent token. The
#   raw values are printed once. Delete the file and re-run for new credentials.
# - .env: docker compose's Postgres password. Postgres keeps the password it first started with in its volume, so a new
#   one also needs `docker compose down -v`, which deletes the demo data.
# Then run the demo, bound to loopback only, with docker compose:
#   docker compose up --build
# or without a container, against your own Postgres (SPRING_DATASOURCE_URL, _USERNAME and _PASSWORD):
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.address=127.0.0.1
set -euo pipefail
cd "$(dirname "$0")/.."

target=config/application-demo.yml
if [ -e "$target" ] && [ -e .env ]; then
  echo "$target and .env already exist; delete $target to generate new credentials" >&2
  exit 1
fi

./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=runtime
separator=":"
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) separator=";" ;; esac
classpath="target/classes${separator}$(cat target/demo-classpath.txt)"

# Before the umask, so the container's non-root user can enter the directory on a Linux bind mount.
mkdir -p config
umask 077
# Each file goes to a temporary file first and is moved into place only on success, so a failed run leaves nothing.
if [ ! -e .env ]; then
  partial_env="$(mktemp .env.XXXXXX)"
  trap 'rm -f "$partial_env"' EXIT
  java -cp "$classpath" io.github.markusluisflores.frontrow.security.DemoCredentials env > "$partial_env"
  mv "$partial_env" .env
  trap - EXIT
  echo "Wrote .env (the compose Postgres password)." >&2
fi

if [ ! -e "$target" ]; then
  partial="$(mktemp config/.application-demo.yml.XXXXXX)"
  trap 'rm -f "$partial"' EXIT
  java -cp "$classpath" io.github.markusluisflores.frontrow.security.DemoCredentials > "$partial"
  # Readable by the container's non-root user on a Linux bind mount (plan 4B decision 9). It holds only hashes of
  # 192-bit random values; the raw values exist only in this run's output.
  chmod 644 "$partial"
  mv "$partial" "$target"
  trap - EXIT
  echo "Wrote $target (hashes only)." >&2
fi
```

- [ ] **Step 6: Run the suite**

Run: `./mvnw spotless:apply -q && ./mvnw verify`
Expected: BUILD SUCCESS. `git status --short` shows no `.env` and nothing under `config/` as untracked; the `.gitignore` entries `.env`, `.env.*`, `!.env.example` and `/config/` already cover them.

- [ ] **Step 7: Run the demo for real**

This is the task's own verification. If it cannot be completed, the task is BLOCKED (global `CLAUDE.md`): a human runs it, or the controller gets it waived.

If `config/application-demo.yml` exists from before Task 1 (plan 4A's script wrote it with `adam`/`ADMIN`, mode 600), delete it first: the script keeps an existing file, `ADMIN` no longer binds, and the container's user can't read a mode-600 file.

```bash
bash scripts/demo-credentials.sh
```

```bash
docker compose up --build -d
```

Then check each of these, and record the output in the task report with every credential redacted:
- `curl -s http://127.0.0.1:8080/api/events` lists `Night Owls Live`, `Quiet Strings Quartet` and `Synth City`;
- `docker compose ps` shows `127.0.0.1:8080->8080/tcp` for `app`, and no published port for `postgres`;
- `docker compose port postgres 5432` prints nothing, or an error;
- `http://127.0.0.1:8080/swagger-ui/index.html` loads in a browser. Authorize as `alice` with the password the script printed, then `GET /api/me/holds` answers `[]`;
- `docker compose exec app id -u` prints `10001`.

Finish with:

```bash
docker compose down
```

The generated `.env` and `config/application-demo.yml` stay in the checkout, gitignored. When an agent runs this step, it then deletes `config/application-demo.yml`, so the passwords and token the script printed into its transcript stop working; Markus regenerates them with the script when he next runs the demo. `.env` is kept: its password is never printed.

- [ ] **Step 8: Commit**

```bash
git add Dockerfile .dockerignore compose.yaml .env.example scripts/demo-credentials.sh src/main/java/io/github/markusluisflores/frontrow/security/DemoCredentials.java src/test/java/io/github/markusluisflores/frontrow/security/DemoCredentialsTest.java src/test/java/io/github/markusluisflores/frontrow/ComposeFileTest.java
git commit -m "feat(deploy): run the demo with docker compose on loopback only"
```

---

### Task 9: The run instructions and the current state

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: Tasks 1–8.
- Produces: §12's run-command box. Every documented non-container run command is `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.address=127.0.0.1`: in the README, in `CLAUDE.md` and in the script header (Task 8).

- [ ] **Step 1: Replace the README's stale status, "Where things are" and "Next steps"**

In `README.md`, replace the `> **Status: design only. …**` block with:

```markdown
> **Status:** the REST API, its Swagger UI and the local `docker compose` run are built. The MCP tools and the
> evidence artifacts are the last plan of Phase 1, which is the whole project (spec revision 2.7).
```

Replace everything from `## Where things are` to the end of the file with:

````markdown
## Run it locally

FrontRow runs only on your own machine, and both ways of running it are reachable only from `127.0.0.1`. Demo-grade security
on a public URL would overstate the security story (spec §2, §3).

1. Generate the demo secrets. The passwords and the agent token are printed once; only their hashes are stored. If a
   `config/application-demo.yml` from before plan 4B exists, delete it first:

   ```bash
   bash scripts/demo-credentials.sh
   ```

2. Start the app and Postgres, with seeded venues and events:

   ```bash
   docker compose up --build
   ```

   This needs Docker Engine 28.3.3 or later on Linux, so that the loopback-only port binding holds.

3. Open Swagger UI at <http://127.0.0.1:8080/swagger-ui/index.html>. Choose **Authorize** and sign in as `alice` or
   `bob` (buyers) or `olivia` (organiser), with the password the script printed.

Without a container, against your own Postgres given through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`
and `SPRING_DATASOURCE_PASSWORD`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

A bare `--server.address` on the `mvnw` line goes to Maven, not to the app.

## Where things are

| | |
|---|---|
| Design spec | [`docs/superpowers/specs/2026-09-17-frontrow-design.md`](docs/superpowers/specs/2026-09-17-frontrow-design.md) |
| Decisions | [`docs/adr/`](docs/adr/) |
| Implementation plans | [`docs/superpowers/plans/`](docs/superpowers/plans/) |
````

- [ ] **Step 2: Update `CLAUDE.md`'s Conventions**

Replace the bullet that begins `- No usable credential is committed.` with:

```markdown
- No usable credential is committed. `scripts/demo-credentials.sh` writes bcrypt and SHA-256 hashes to the gitignored
  `config/application-demo.yml` and compose's Postgres password to the gitignored `.env`, each only if missing, and
  prints the raw credentials once. Run the demo with `docker compose up --build` (the app on `127.0.0.1:8080` only,
  Postgres unpublished, seeded), or without a container, against a Postgres supplied through `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`:
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments=--server.address=127.0.0.1`.
  The demo profile also applies the seed (`db/seed`). Without a datasource startup fails at DataSource
  auto-configuration, and without the demo profile it fails because there are no users. Tests generate their own
  credentials per run (`support/TestCredentials`).
```

- [ ] **Step 3: Update `CLAUDE.md`'s Current state**

In the bold paragraph under `## Current state`:
- `3B (confirm, release,\ncancellation, the sweeper) and 4A (the security edge and the REST write endpoints) are complete.` becomes `3B (confirm, release, cancellation, the sweeper), 4A (the security edge and the REST write endpoints) and 4B (the reads, event creation and the local run) are complete.`
- Replace `No read endpoint, no event creation and no MCP tool exists yet.**` with:

```markdown
Plan 4B adds `POST /api/events`, the five `GET` endpoints over two read services (`EventReadService`, which plan 5's
read tools reuse, and `OwnerReadService`), the post-start window check, springdoc / Swagger UI, the demo seed and
`docker compose` on loopback; it removed the cut `ADMIN` role. No MCP tool exists yet.**
```

- Replace `264 test runs pass` with the count from this branch's last `./mvnw verify`: the `Tests run:` total in its summary line. Use the real number, not an estimate.
- Replace `Plans 4B (the read services and GET endpoints, event creation, OpenAPI) and 5 (MCP)\nremain.` with `Plan 5 (MCP tools and the evidence) remains.`
- After the sentence naming plan 4A's file, add: `Plan 4B: \`docs/superpowers/plans/2026-11-02-phase-1-plan-4b-reads-and-local-run.md\`.`

- [ ] **Step 4: Check every run command in the repo**

Run: `grep -rn "spring-boot:run" README.md CLAUDE.md scripts/`
Expected: every match is the full loopback command above. No bare `-Dspring-boot.run.profiles=demo` line remains.

- [ ] **Step 5: Run the suite**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, and the same count as Step 3.

- [ ] **Step 6: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs(readme): add the local run instructions"
```

---

## Coverage

Spec §12's "Plan 4B" boxes, each against the task that ticks it:

| §12 box | Task | Proof |
|---|---|---|
| Event creation, the read services and the five `GET` endpoints, springdoc / Swagger UI | 3, 4, 5, 6 | `EventCreateServiceTest`, `EventReadServiceTest`, `OwnerReadServiceTest`, `ReadEndpointsTest`, `MeEndpointsTest`, `OpenApiDocsTest` (the REST table, row for row) |
| Seed data, a datasource configuration and the `docker compose` setup | 7, 8 | `SeedDataTest`; Task 8 Step 7's live run; compose supplies the `SPRING_DATASOURCE_*` configuration |
| The cut removed: the orders rule, `ADMIN`, `adam`, and their assertions | 1 | Task 1 Step 4's grep; `SecurityChainsTest` |
| `adam` tests repointed, not deleted | 1 | `anUnlistedApiPathIsDeniedEvenToAnAuthenticatedUser`, `theCatchAllOpensOnlyItsAnonymousPaths` |
| Compose publishes the app on `127.0.0.1` only; Postgres unpublished | 8 | `ComposeFileTest#theAppIsPublishedOnLoopbackOnlyAndPostgresNotAtAll`; Step 7 |
| Compose's Postgres password from a generated `.env`; `.env.example` doesn't work | 8 | `ComposeFileTest#thePostgresPasswordComesOnlyFromTheGeneratedEnvFile`, `DemoCredentialsTest#theEnvFileHoldsOneRandomPostgresPassword` |
| Every documented non-container run command binds loopback | 8, 9 | Task 9 Step 4's grep over the README, `CLAUDE.md` and the script header |
| Creation's instant bound: `starts_at` and `sales_window` named | 3 | `EventCreateServiceTest#anInstantPostgresCannotStoreNamesItsParameter`, `#theWidestStorableEventIsStoredExactly` |
| The window check on creation and on a bound-setting `PATCH`, merged; status-only not checked | 2, 3 | `EventPatchServiceTest#aWindowMayCloseAtTheStartButNotAfterIt`, `#noPatchCanReopenSalesForAnEventThatHasStarted`, `#theCheckUsesTheMergedWindowAndSkipsAStatusOnlyPatch`; `WriteEndpointsTest#aPatchThatClosesSalesAfterTheStartIsInvalid`; `EventCreateServiceTest#theWindowMayCloseAtTheStartButNotAfterIt` |
| `EventRow` and `lockEventForUpdate` carry `starts_at` | 2 | The `PATCH` check reads `event.startsAt()` from the locked row |
| A test asserts `sales_close_at <= starts_at` for every seeded event | 7 | `SeedDataTest#everySeededEventClosesSalesByItsStart` |
| The availability read returns no hold or owner field, in the service and over `GET` | 4 | `EventReadServiceTest#theAvailabilityReadCarriesNoHoldOrOwnerField`, `ReadEndpointsTest#availabilityShowsWhetherASeatIsFreeAndNeverWhoseHoldItIs` |
| `GET /api/me/holds` and `GET /api/me/orders` return only the caller's rows | 5 | `MeEndpointsTest#eachBuyerSeesOnlyTheirOwnHoldsAndOrders`, `OwnerReadServiceTest` |

The whole-project boxes this plan moves forward without ticking: `docker compose up` with seeded data is proven by Task 8 Step 7, but its box also needs plan 5. Swagger UI is browsable (Task 6, Task 8 Step 7). "Every endpoint exercised end to end" is covered by the endpoint tests here and plan 4A's.

## How this plan was checked

- **Not built** (decision 1). Every signature was read against `main` at `1c502eb`:
  - `LockingGateway`, `EventPatchService`, `ConfirmService` and the entities and repositories;
  - `HoldFixtures`, `TestCredentials`, `SecurityChainsTest`, `TransactionIsolationTest`, `ServiceTransactionBoundaryTest`, `EventPatchServiceTest` and `WriteEndpointsTest`.
- **springdoc's version and Boot baseline** come from Maven Central's metadata and the release POMs:
  - 3.1.1's parent is Boot 4.1.0, and it was published 2026-09-06;
  - 3.2.0's parent is Boot 4.1.2, published 2026-10-24.
- **The Jackson 2 schema mapper** was confirmed from springdoc-openapi-starter-common 3.1.1's dependencies (`swagger-core-jakarta`).
- **The unconfirmed API assumptions**, which decision 1's deviation rule covers:
  - `JsonNode#properties()` on Jackson 3.1.5;
  - Spring Data JPA's `Limit` parameter on a `@Query` method;
  - JPQL `lower(:pattern)` and `ESCAPE '!'` under Hibernate 7.4.5.
- **No in-repo precedent exists** for a `Dockerfile`, `compose.yaml` or a Flyway seed location. Global `CLAUDE.md`'s trigger T4 names that case. A framing consult was not dispatched, because this plan was authored on the stronger model tier that would have answered it. That is recorded here for Markus to overrule.
- **Every commit message** above was run through `.githooks/commit-msg`.
