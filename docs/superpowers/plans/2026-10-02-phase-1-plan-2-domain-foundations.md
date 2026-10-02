# FrontRow Phase 1, Plan 2 (Domain Foundations) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build everything the write paths need before a single write path exists: the pure domain rules, the structured error contract with its driver-level constraint lookup, the JPA persistence model over the V1 schema, and a locking gateway whose Postgres lock semantics are proven by test.

**Architecture:** Plan 1 (merged, `b24d94a`) landed the build, CI, the V1 schema and ADR-004. This plan adds the layer underneath the services, and nothing else: no `hold_seats`, no confirm, no release, no REST, no MCP.

Persistence follows spec §5 and the user's decision of 2026-10-02: **Spring Data JPA for ordinary reads and writes, explicit `JdbcClient` SQL for every locking read, lazy expiry and advisory lock.** The spec requires exactly that split — "A locking query must return the current database row, not an entity already cached in the persistence context: refresh it, or run the query through JDBC" — because Hibernate flushes inserts before updates, so a lazy expiry left as a dirty entity would run after the new hold's insert.

Phase 1's remaining plans, after this one:
- **Plan 3:** the write paths — hold creation with idempotent replay, confirm, release, event cancellation, the expiry sweeper, and the seven §5 concurrency tests.
- **Plan 4:** the REST surface, OpenAPI, and the two security chains (Mandatory tier).
- **Plan 5:** the MCP tools and the §7 evidence artifacts.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Data JPA (Hibernate 7.4.5), `JdbcClient` (Spring Framework 7.0.9), Postgres 18, Flyway 12.4.0, JUnit Jupiter 6.0.3, AssertJ, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.6.1). Read §4 (domain model, statuses, the live-hold predicate), §5 (the invariant, locking discipline, error mapping), §6 (the error-code table) and §8 (testing strategy). Also `docs/adr/ADR-001` (the invariant) and `docs/adr/ADR-002` (the catch-all-chain warning, which Plan 4 owns).

## Global Constraints

- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). This plan adds a `domain` package with no framework imports at all — not even JPA. Entities live in `persistence`.
- **Time comes from an injected `java.time.Clock`**, passed into queries as a parameter. Never `now()` in SQL, never `Instant.now()` in a service or entity (spec §4).
- **A hold is live iff `status = 'ACTIVE' AND expires_at > :now`.** One definition, used everywhere (spec §4). An **active hold group** is a group with at least one live row.
- **The sales window is half-open:** `sales_open_at <= :now < sales_close_at`, and the event must be `ON_SALE` (spec §4).
- **READ COMMITTED** is required on every transaction that locks (spec §5). **No code in this plan sets it**, because
  nothing here owns a transaction boundary — the gateway is called inside someone else's transaction. Plan 3 sets it
  on each service method and the coverage table assigns it there. Postgres defaults to READ COMMITTED, so a forgotten
  setting fails nothing, which is exactly why it needs a named owner rather than an assumption.
- **Constraint violations are identified by SQLState plus the constraint name from the driver's structured error** — `PSQLException.getServerErrorMessage().getConstraint()`, found by walking the cause chain and any `BatchUpdateException.getNextException()`. Never by message text, and never via Hibernate's `ConstraintViolationException.getConstraintName()`, which parses the message and returns null under a non-English locale (spec §5).
- **Only `uq_claimed_seat` maps to `seat_taken`.** Any other `23505` is a bug and must surface as a 500 in tests (spec §5).
- **Money is `long` cents plus an ISO currency code**, never `double`, never `BigDecimal` (spec §4).
- **Error codes are exactly the eleven in spec §6**, always under the key `code`, always with a hint.
- Postgres only, real Postgres in tests via Testcontainers (`postgres:18-alpine`) — never H2, never a mocked repository.
- Java 25, `-Xlint:all,-processing -Werror` (tests included, so a deprecated call fails the build), Spotless, SpotBugs. `./mvnw verify` is the test command and must be green at every commit.
- Commit messages: `<type>(<scope>): <subject>`, 72 characters max, no trailing period (`.githooks/commit-msg`). No local device path in a committed file (`.githooks/pre-commit`).

## Plan-level decisions the spec leaves open

Each is listed so a reviewer can reject it individually.

1. **The advisory-lock key is computed in Java, not by Postgres.** Spec §5 says `pg_advisory_xact_lock(hash(owner))` without naming the hash. Postgres's `hashtext()` is undocumented internal API whose value has changed between major versions. This plan derives a stable `long` from the first 8 bytes of `SHA-256(owner)` and passes it as a bind parameter, so the key is reproducible, testable, and independent of the server version.
2. **Entities are the persistence model; the domain package holds pure rules.** Spec §3 forbids Spring Web and MCP types in the core, and §8 wants domain unit tests with a fixed `Clock` and no database. Both are satisfied by a pure `domain` package (value objects and rules, no imports beyond the JDK) plus JPA entities in `persistence`. There is deliberately **no mapper layer** between them: the services in Plan 3 read entities and call domain rules with their values. A full hexagonal mapping layer would be more code than this project's size justifies (YAGNI).
3. **A `CONVERTED` row reports `hold_not_active`, not `hold_expired`.** Spec §5's *Hold confirmation* step 4 names
   only `RELEASED` for `hold_not_active`, so read literally a `CONVERTED` row would fall into `hold_expired`. This
   plan treats `CONVERTED` like `RELEASED`, because §5's *Release* rule 1 does exactly that and because "already
   bought" is not "lapsed". The branch is also unreachable in practice: confirm step 2 returns the existing order
   before the state check. If a reviewer prefers the literal reading, only `HoldRules.confirmOutcome` changes.

4. **`SET LOCAL lock_timeout` is formatted as a literal, not bound.** Postgres does not accept bind parameters in `SET`. The value comes from validated configuration (`@Min`/`@Max` on a `Duration`), never from a request, and the plan's code converts it to milliseconds with `toMillis()` so no string from outside the application ever reaches that statement.
5. **`Instant` is converted to `OffsetDateTime` at the JDBC boundary, in both directions.** The pinned driver,
   pgjdbc 42.7.13, has **no `Instant` branch** in `PgResultSet.getObject(int, Class)` — the supported temporal types
   are `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime` and `OffsetTime`, and anything else falls through
   to `conversion to {0} from {1} not supported`. `PgPreparedStatement` likewise infers a SQL type for
   `OffsetDateTime` but not for `Instant` (`Can't infer the SQL type to use for an instance of java.time.Instant`).
   So every raw-JDBC read does `rs.getObject(col, OffsetDateTime.class).toInstant()` and every raw-JDBC bind does
   `OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)`. Plan 1's `SchemaFixtures` already hit this and used
   `Timestamp.from(...)`; `OffsetDateTime` is preferred because it carries the offset explicitly instead of depending
   on the JVM default zone. **JPA is unaffected** — Hibernate maps `Instant` to `timestamptz` itself — so entities
   keep `Instant` fields.

6. **The locking gateway returns small records, not entities.** A locking read exists precisely to see the committed row rather than the persistence context, so returning a detached record keeps that honest and makes it impossible to hand the result back to Hibernate as a managed entity by accident.

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `src/main/java/.../domain/Money.java` | `long` cents + ISO currency, with same-currency arithmetic | 1 |
| `src/main/java/.../domain/HoldStatus.java`, `EventStatus.java`, `OrderStatus.java` | The §4 status enums; `HoldStatus` also carries its legal transitions | 1 |
| `src/main/java/.../domain/HoldRules.java` | The live-hold predicate and the confirm precedence rule | 1 |
| `src/main/java/.../domain/SalesWindow.java` | The half-open window test | 1 |
| `src/test/java/.../domain/*Test.java` | Pure unit tests, fixed `Clock`, no Spring, no database | 1 |
| `src/main/java/.../error/ErrorCode.java` | The eleven §6 codes with their hints | 2 |
| `src/main/java/.../error/DomainException.java` | Code + details, the one exception services throw | 2 |
| `src/main/java/.../error/PostgresErrors.java` | SQLState and constraint name from the driver, via the cause chain | 2 |
| `src/test/java/.../error/*Test.java` | Unit tests for the walk and the code table | 2 |
| `src/main/java/.../config/FrontRowProperties.java` | Hold TTL, caps, lock timeout — validated configuration | 3 |
| `src/main/java/.../config/TimeConfig.java` | The `Clock` bean | 3 |
| `src/main/java/.../persistence/*.java` | Eight JPA entities over the V1 tables | 3 |
| `src/main/java/.../persistence/*Repository.java` | Spring Data repositories, only the methods used now | 3 |
| `src/test/java/.../persistence/PersistenceMappingTest.java` | Round-trip every entity against real Postgres | 3 |
| `src/main/java/.../persistence/LockingGateway.java` | Every locking read, the advisory lock, lazy expiry, `SET LOCAL` | 4 |
| `src/test/java/.../persistence/LockingGatewayTest.java` | Lock semantics proven with concurrent transactions | 4 |
| `pom.xml` | Adds `spring-boot-starter-data-jpa` and validation | 3 |
| `src/main/resources/application.yml` | JPA settings and the `frontrow.*` properties | 3 |
| `CLAUDE.md` | Current state | 4 |

---

### Task 1: The pure domain core (spec §4)

No framework imports. Every class in `domain` must compile with only `java.*` on the classpath, which is what makes the §8 domain unit tests instant and database-free.

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/domain/Money.java`
- Create: `.../domain/HoldStatus.java`, `.../domain/EventStatus.java`, `.../domain/OrderStatus.java`
- Create: `.../domain/SalesWindow.java`, `.../domain/HoldRules.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/domain/MoneyTest.java`, `SalesWindowTest.java`, `HoldRulesTest.java`, `HoldStatusTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces, for Tasks 2-4 and Plan 3:
  - `Money.ofCents(long cents, String currency)`, `Money#plus(Money)`, `Money#times(int)`, `Money#cents()`, `Money#currency()`
  - `HoldStatus.ACTIVE|EXPIRED|RELEASED|CONVERTED`, with `HoldStatus#canTransitionTo(HoldStatus)`
  - `EventStatus.DRAFT|ON_SALE|CANCELLED`, `OrderStatus.CONFIRMED`
  - `SalesWindow.of(Instant openAt, Instant closeAt)`, `SalesWindow#state(Instant now)` returning `WindowState.BEFORE|OPEN|AFTER`
  - `HoldRules.isLive(HoldStatus status, Instant expiresAt, Instant now)`
  - `HoldRules.confirmOutcome(Collection<HoldRow> rows, Instant now)` returning `ConfirmOutcome.PROCEED|HOLD_NOT_ACTIVE|HOLD_EXPIRED`, where `HoldRow` is the nested record `HoldRules.HoldRow(long eventSeatId, HoldStatus status, Instant expiresAt)`

- [ ] **Step 1: Write the failing tests**

`src/test/java/io/github/markusluisflores/frontrow/domain/MoneyTest.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAmountsInTheSameCurrency() {
        assertThat(Money.ofCents(5000, "CAD").plus(Money.ofCents(2550, "CAD")))
                .isEqualTo(Money.ofCents(7550, "CAD"));
    }

    @Test
    void multipliesBySeatCount() {
        assertThat(Money.ofCents(5000, "CAD").times(3)).isEqualTo(Money.ofCents(15000, "CAD"));
    }

    @Test
    void rejectsMixedCurrencies() {
        Money cad = Money.ofCents(100, "CAD");
        Money usd = Money.ofCents(100, "USD");
        assertThatIllegalArgumentException().isThrownBy(() -> cad.plus(usd)).withMessageContaining("CAD");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ofCents(-1, "CAD"));
    }

    @Test
    void rejectsCurrencyThatIsNotThreeUppercaseLetters() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ofCents(100, "cad"));
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ofCents(100, "CANADA"));
    }

    @Test
    void zeroIsAllowed() {
        assertThat(Money.ofCents(0, "CAD").cents()).isZero();
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/domain/SalesWindowTest.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SalesWindowTest {

    private static final Instant OPEN = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant CLOSE = Instant.parse("2026-10-08T10:00:00Z");
    private static final SalesWindow WINDOW = SalesWindow.of(OPEN, CLOSE);

    @Test
    void beforeTheWindowOpens() {
        assertThat(WINDOW.state(OPEN.minusMillis(1))).isEqualTo(SalesWindow.WindowState.BEFORE);
    }

    @Test
    void theOpeningInstantIsInsideTheWindow() {
        assertThat(WINDOW.state(OPEN)).isEqualTo(SalesWindow.WindowState.OPEN);
    }

    @Test
    void theClosingInstantIsOutsideTheWindow() {
        assertThat(WINDOW.state(CLOSE)).isEqualTo(SalesWindow.WindowState.AFTER);
    }

    @Test
    void oneMillisecondBeforeClosingIsStillOpen() {
        assertThat(WINDOW.state(CLOSE.minusMillis(1))).isEqualTo(SalesWindow.WindowState.OPEN);
    }

    @Test
    void rejectsAWindowThatClosesBeforeItOpens() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesWindow.of(CLOSE, OPEN));
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/domain/HoldRulesTest.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.domain.HoldRules.ConfirmOutcome;
import io.github.markusluisflores.frontrow.domain.HoldRules.HoldRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoldRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final Instant EARLIER = NOW.minusSeconds(1);

    @Test
    void anActiveHoldInTheFutureIsLive() {
        assertThat(HoldRules.isLive(HoldStatus.ACTIVE, LATER, NOW)).isTrue();
    }

    @Test
    void anActiveHoldAtItsExpiryInstantIsNotLive() {
        assertThat(HoldRules.isLive(HoldStatus.ACTIVE, NOW, NOW)).isFalse();
    }

    @Test
    void endedStatusesAreNeverLive() {
        assertThat(HoldRules.isLive(HoldStatus.EXPIRED, LATER, NOW)).isFalse();
        assertThat(HoldRules.isLive(HoldStatus.RELEASED, LATER, NOW)).isFalse();
        assertThat(HoldRules.isLive(HoldStatus.CONVERTED, LATER, NOW)).isFalse();
    }

    @Test
    void confirmProceedsWhenEveryRowIsLive() {
        List<HoldRow> rows = List.of(
                new HoldRow(1L, HoldStatus.ACTIVE, LATER), new HoldRow(2L, HoldStatus.ACTIVE, LATER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.PROCEED);
    }

    @Test
    void releasedTakesPrecedenceOverExpired() {
        List<HoldRow> rows = List.of(
                new HoldRow(1L, HoldStatus.RELEASED, LATER), new HoldRow(2L, HoldStatus.ACTIVE, EARLIER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_NOT_ACTIVE);
    }

    @Test
    void aPartlyExpiredGroupIsExpired() {
        List<HoldRow> rows = List.of(
                new HoldRow(1L, HoldStatus.ACTIVE, LATER), new HoldRow(2L, HoldStatus.ACTIVE, EARLIER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_EXPIRED);
    }

    @Test
    void anExplicitlyExpiredRowIsExpired() {
        List<HoldRow> rows = List.of(new HoldRow(1L, HoldStatus.EXPIRED, LATER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_EXPIRED);
    }

    @Test
    void aConvertedRowIsNotActive() {
        List<HoldRow> rows = List.of(new HoldRow(1L, HoldStatus.CONVERTED, LATER));
        assertThat(HoldRules.confirmOutcome(rows, NOW)).isEqualTo(ConfirmOutcome.HOLD_NOT_ACTIVE);
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/domain/HoldStatusTest.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HoldStatusTest {

    @ParameterizedTest
    @EnumSource(
            value = HoldStatus.class,
            names = {"EXPIRED", "RELEASED", "CONVERTED"})
    void activeCanReachEveryEndedStatus(HoldStatus target) {
        assertThat(HoldStatus.ACTIVE.canTransitionTo(target)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = HoldStatus.class,
            names = {"EXPIRED", "RELEASED", "CONVERTED"})
    void endedStatusesAreTerminal(HoldStatus from) {
        for (HoldStatus target : HoldStatus.values()) {
            assertThat(from.canTransitionTo(target))
                    .as("%s -> %s must be rejected", from, target)
                    .isFalse();
        }
    }

    @Test
    void activeCannotTransitionToItself() {
        assertThat(HoldStatus.ACTIVE.canTransitionTo(HoldStatus.ACTIVE)).isFalse();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest='io.github.markusluisflores.frontrow.domain.*Test'`
Expected: FAIL — compilation errors, because none of the `domain` classes exist yet.

- [ ] **Step 3: Write the domain classes**

`Money.java`:

```java
package io.github.markusluisflores.frontrow.domain;

/**
 * An amount in minor units plus an ISO-4217 currency code. Integer cents, never double (precision) and never
 * BigDecimal by default (spec §4).
 */
public record Money(long cents, String currency) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + cents);
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be three uppercase letters: " + currency);
        }
    }

    public static Money ofCents(long cents, String currency) {
        return new Money(cents, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.cents, other.cents), this.currency);
    }

    public Money times(int factor) {
        return new Money(Math.multiplyExact(this.cents, factor), this.currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("cannot combine " + this.currency + " with " + other.currency);
        }
    }
}
```

`HoldStatus.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import java.util.EnumSet;
import java.util.Set;

/** Hold lifecycle (spec §4). ACTIVE is the only non-terminal status. */
public enum HoldStatus {
    ACTIVE,
    EXPIRED,
    RELEASED,
    CONVERTED;

    private static final Set<HoldStatus> FROM_ACTIVE = EnumSet.of(EXPIRED, RELEASED, CONVERTED);

    public boolean canTransitionTo(HoldStatus target) {
        return this == ACTIVE && FROM_ACTIVE.contains(target);
    }
}
```

`EventStatus.java`:

```java
package io.github.markusluisflores.frontrow.domain;

/** Event lifecycle (spec §4). */
public enum EventStatus {
    DRAFT,
    ON_SALE,
    CANCELLED
}
```

`OrderStatus.java`:

```java
package io.github.markusluisflores.frontrow.domain;

/** Order lifecycle. CONFIRMED is the only value in Phase 1 — cancellation is an explicit non-goal (spec §2). */
public enum OrderStatus {
    CONFIRMED
}
```

`SalesWindow.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import java.time.Instant;

/** The half-open sales window: open <= now < close (spec §4). The same bound applies everywhere. */
public record SalesWindow(Instant openAt, Instant closeAt) {

    public enum WindowState {
        BEFORE,
        OPEN,
        AFTER
    }

    public SalesWindow {
        if (openAt == null || closeAt == null) {
            throw new IllegalArgumentException("sales window bounds must not be null");
        }
        if (!openAt.isBefore(closeAt)) {
            throw new IllegalArgumentException("sales window must open before it closes: " + openAt + ".." + closeAt);
        }
    }

    public static SalesWindow of(Instant openAt, Instant closeAt) {
        return new SalesWindow(openAt, closeAt);
    }

    public WindowState state(Instant now) {
        if (now.isBefore(openAt)) {
            return WindowState.BEFORE;
        }
        return now.isBefore(closeAt) ? WindowState.OPEN : WindowState.AFTER;
    }
}
```

`HoldRules.java`:

```java
package io.github.markusluisflores.frontrow.domain;

import java.time.Instant;
import java.util.Collection;

/** The hold predicates that decide outcomes. Pure functions over values, so they are testable without a database. */
public final class HoldRules {

    private HoldRules() {}

    /** One hold row as read under the lock. */
    public record HoldRow(long eventSeatId, HoldStatus status, Instant expiresAt) {}

    /** What a confirm should do, in the precedence order spec §5 defines. */
    public enum ConfirmOutcome {
        PROCEED,
        HOLD_NOT_ACTIVE,
        HOLD_EXPIRED
    }

    /** A hold is live iff it is ACTIVE and has not reached its expiry instant (spec §4). */
    public static boolean isLive(HoldStatus status, Instant expiresAt, Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }

    /**
     * Confirm precedence: every row live proceeds; any RELEASED or CONVERTED row reports hold_not_active; anything
     * else — EXPIRED, or ACTIVE past its expiry, including a group only partly expired by an overlapping hold —
     * reports hold_expired. Spec §5 step 4 names only RELEASED for hold_not_active; grouping CONVERTED with it is a
     * plan-level decision (see the plan's "Plan-level decisions" section), consistent with §5's Release rule 1.
     */
    public static ConfirmOutcome confirmOutcome(Collection<HoldRow> rows, Instant now) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("confirmOutcome needs at least one row");
        }
        boolean allLive = true;
        for (HoldRow row : rows) {
            if (row.status() == HoldStatus.RELEASED || row.status() == HoldStatus.CONVERTED) {
                return ConfirmOutcome.HOLD_NOT_ACTIVE;
            }
            if (!isLive(row.status(), row.expiresAt(), now)) {
                allLive = false;
            }
        }
        return allLive ? ConfirmOutcome.PROCEED : ConfirmOutcome.HOLD_EXPIRED;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw spotless:apply` then `./mvnw verify`
Expected: `BUILD SUCCESS`. The four new test classes contribute 22 test methods (26 runs, because two
`@ParameterizedTest` methods expand to three cases each); Plan 1's 27 runs still pass.

- [ ] **Step 5: Prove the domain core has no framework dependency**

Run: `grep -rn "^import" src/main/java/io/github/markusluisflores/frontrow/domain/ | grep -v "^.*:import java\."`
Expected: no output. Every import in `domain` is a `java.*` import.

This is the mechanical form of `CLAUDE.md`'s boundary rule. Plan 3 and Plan 4 must keep it passing.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/domain src/test/java/io/github/markusluisflores/frontrow/domain
git commit -m "feat(domain): add money, statuses, sales window and hold rules"
```

Body must state: the package has no framework imports by design, and the rules are the single definition of the live-hold predicate and the confirm precedence from spec §5.

---

### Task 2: The structured error contract (spec §5, §6)

**Files:**
- Create: `src/main/java/io/github/markusluisflores/frontrow/error/ErrorCode.java`
- Create: `.../error/DomainException.java`
- Create: `.../error/PostgresErrors.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/error/ErrorCodeTest.java`, `PostgresErrorsTest.java`
- Modify: `src/test/java/io/github/markusluisflores/frontrow/schema/SchemaFixtures.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `ErrorCode` with the eleven §6 values, `ErrorCode#code()` (the wire string) and `ErrorCode#hint()`
  - `DomainException(ErrorCode code, Map<String, Object> details)`, plus `DomainException.of(ErrorCode)`; `#code()`, `#details()`
  - `PostgresErrors.sqlState(Throwable)`, `PostgresErrors.constraintName(Throwable)` — nullable, cause-chain and `BatchUpdateException` aware
  - `PostgresErrors.UNIQUE_VIOLATION` (`23505`), `FOREIGN_KEY_VIOLATION` (`23503`), `CHECK_VIOLATION` (`23514`), `DEADLOCK_DETECTED` (`40P01`), `SERIALIZATION_FAILURE` (`40001`), `LOCK_NOT_AVAILABLE` (`55P03`), `NOT_NULL_VIOLATION` (`23502`)
  - `PostgresErrors.UQ_CLAIMED_SEAT` (`uq_claimed_seat`)

Plan 3's services use these to map a violation to `seat_taken` or `contention_retry`; Plan 4's REST adapter renders `DomainException` as RFC 9457 Problem Details with `code` as an extension member.

- [ ] **Step 1: Write the failing tests**

`src/test/java/io/github/markusluisflores/frontrow/error/ErrorCodeTest.java`:

```java
package io.github.markusluisflores.frontrow.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ErrorCodeTest {

    @Test
    void containsExactlyTheElevenCodesTheSpecDefines() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code))
                .containsExactlyInAnyOrder(
                        "seat_taken",
                        "not_found",
                        "sales_not_open",
                        "sales_closed",
                        "hold_limit_exceeded",
                        "too_many_seats",
                        "hold_expired",
                        "hold_not_active",
                        "idempotency_key_reused",
                        "contention_retry",
                        "invalid_request");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeIsSnakeCaseAndCarriesARecoveryHint(ErrorCode errorCode) {
        assertThat(errorCode.code()).matches("[a-z][a-z_]*[a-z]");
        assertThat(errorCode.hint()).isNotBlank();
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/error/PostgresErrorsTest.java`:

```java
package io.github.markusluisflores.frontrow.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

class PostgresErrorsTest {

    @Test
    void findsTheStateAndConstraintOnADirectException() {
        PSQLException thrown = uniqueViolation("uq_claimed_seat");
        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(PostgresErrors.UNIQUE_VIOLATION);
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("uq_claimed_seat");
    }

    @Test
    void walksTheCauseChain() {
        Throwable wrapped = new RuntimeException("service", new IllegalStateException("jpa", uniqueViolation("uq_sold_once")));
        assertThat(PostgresErrors.constraintName(wrapped)).isEqualTo("uq_sold_once");
    }

    @Test
    void looksInsideABatchUpdateExceptionsNextException() {
        BatchUpdateException batch = new BatchUpdateException("batch failed", new int[] {1});
        batch.setNextException(uniqueViolation("uq_claimed_seat"));
        Throwable wrapped = new RuntimeException("service", batch);
        assertThat(PostgresErrors.constraintName(wrapped)).isEqualTo("uq_claimed_seat");
        assertThat(PostgresErrors.sqlState(wrapped)).isEqualTo(PostgresErrors.UNIQUE_VIOLATION);
    }

    @Test
    void returnsNullWhenNoPostgresExceptionIsPresent() {
        Throwable unrelated = new IllegalStateException("no database involved");
        assertThat(PostgresErrors.sqlState(unrelated)).isNull();
        assertThat(PostgresErrors.constraintName(unrelated)).isNull();
    }

    @Test
    void survivesAPostgresExceptionWithNoServerMessage() {
        PSQLException noServerMessage = new PSQLException("connection reset", PSQLState.CONNECTION_FAILURE);
        assertThat(PostgresErrors.sqlState(noServerMessage)).isEqualTo("08006");
        assertThat(PostgresErrors.constraintName(noServerMessage)).isNull();
    }

    @Test
    void doesNotLoopForeverOnASelfReferencingCause() {
        SQLException first = new SQLException("first");
        SQLException second = new SQLException("second", first);
        first.initCause(second);
        assertThat(PostgresErrors.constraintName(first)).isNull();
    }

    private static PSQLException uniqueViolation(String constraint) {
        String serverMessage = "SERROR\u0000C23505\u0000Mduplicate key value violates unique constraint\u0000n"
                + constraint + "\u0000";
        return new PSQLException(new ServerErrorMessage(serverMessage));
    }
}
```

The `ServerErrorMessage` constructor takes the wire format the backend sends: field-type bytes (`S` severity, `C` code, `M` message, `n` constraint) each followed by a NUL-terminated value. Building one directly is how the test gets a realistic exception without a database.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest='io.github.markusluisflores.frontrow.error.*Test'`
Expected: FAIL — compilation errors; the `error` package does not exist.

- [ ] **Step 3: Write the error classes**

`ErrorCode.java`:

```java
package io.github.markusluisflores.frontrow.error;

/**
 * The eleven error codes both adapters share (spec §6). The wire form is always under the key "code", and every code
 * carries a recovery hint — an unstructured string error is a review BLOCKER.
 */
public enum ErrorCode {
    SEAT_TAKEN("seat_taken", "call check_availability for current seats"),
    NOT_FOUND("not_found", "call search_events to find a valid id"),
    SALES_NOT_OPEN("sales_not_open", "sales have not opened for this event yet"),
    SALES_CLOSED("sales_closed", "this event is no longer on sale"),
    HOLD_LIMIT_EXCEEDED("hold_limit_exceeded", "release an existing hold first"),
    TOO_MANY_SEATS("too_many_seats", "request fewer seats in one hold"),
    HOLD_EXPIRED("hold_expired", "the hold lapsed; hold the seats again"),
    HOLD_NOT_ACTIVE("hold_not_active", "the hold was already released or confirmed"),
    IDEMPOTENCY_KEY_REUSED("idempotency_key_reused", "use a new key for a different request"),
    CONTENTION_RETRY("contention_retry", "transient conflict; retry the same request"),
    INVALID_REQUEST("invalid_request", "check the named parameter and try again");

    private final String code;
    private final String hint;

    ErrorCode(String code, String hint) {
        this.code = code;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }
}
```

`DomainException.java`:

```java
package io.github.markusluisflores.frontrow.error;

import java.util.Map;

/**
 * The one exception the application services throw. Carries a spec §6 code and the details that code promises — for
 * example seat_ids for SEAT_TAKEN, or sales_open_at for SALES_NOT_OPEN. Both adapters render it structurally.
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public DomainException(ErrorCode errorCode, Map<String, Object> details) {
        super(errorCode.code());
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public static DomainException of(ErrorCode errorCode) {
        return new DomainException(errorCode, Map.of());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
```

`PostgresErrors.java`:

```java
package io.github.markusluisflores.frontrow.error;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads SQLState and constraint names from the driver's structured error, never from message text (spec §5).
 * Hibernate's ConstraintViolationException#getConstraintName parses the message instead and returns null when
 * Postgres reports in a language other than English, so it is deliberately not used.
 */
public final class PostgresErrors {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    public static final String NOT_NULL_VIOLATION = "23502";
    public static final String CHECK_VIOLATION = "23514";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String LOCK_NOT_AVAILABLE = "55P03";

    public static final String UQ_CLAIMED_SEAT = "uq_claimed_seat";

    private PostgresErrors() {}

    public static String sqlState(Throwable thrown) {
        PSQLException psql = find(thrown);
        return psql == null ? null : psql.getSQLState();
    }

    public static String constraintName(Throwable thrown) {
        PSQLException psql = find(thrown);
        if (psql == null) {
            return null;
        }
        ServerErrorMessage serverError = psql.getServerErrorMessage();
        return serverError == null ? null : serverError.getConstraint();
    }

    /**
     * Walks the cause chain, and any BatchUpdateException's own chain, to the first PSQLException. A single instanceof
     * on the top-level exception would miss it and turn seat_taken into a 500 (spec §5).
     */
    private static PSQLException find(Throwable thrown) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = thrown; current != null && seen.put(current, Boolean.TRUE) == null; ) {
            if (current instanceof PSQLException psql) {
                return psql;
            }
            if (current instanceof BatchUpdateException batch) {
                for (SQLException next = batch.getNextException();
                        next != null && seen.put(next, Boolean.TRUE) == null;
                        next = next.getNextException()) {
                    if (next instanceof PSQLException psql) {
                        return psql;
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }
}
```

The `IdentityHashMap` guard is why the self-referencing-cause test passes. A plain `for (t = thrown; t != null; t = t.getCause())` loop never terminates on a cycle, and JDBC drivers do produce them.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw spotless:apply` then `./mvnw -q test -Dtest='io.github.markusluisflores.frontrow.error.*Test'`
Expected: PASS — 8 test methods, 18 runs (`everyCodeIsSnakeCaseAndCarriesARecoveryHint` expands to eleven).

- [ ] **Step 5: Point the schema tests at the real implementation**

`SchemaFixtures` carries its own copy of this walk, written in Plan 1 for exactly this reuse. Delete the private `findPsqlException` method and the two static helpers from `SchemaFixtures`, and change `SchemaConstraintsTest` to import the real ones:

```java
import static io.github.markusluisflores.frontrow.error.PostgresErrors.constraintName;
import static io.github.markusluisflores.frontrow.error.PostgresErrors.sqlState;
```

Also replace the three SQLState literals in `SchemaConstraintsTest` (`"23505"`, `"23503"`, `"23514"`) with `PostgresErrors.UNIQUE_VIOLATION`, `FOREIGN_KEY_VIOLATION` and `CHECK_VIOLATION`, and delete the private constants.

Run: `./mvnw verify`
Expected: `BUILD SUCCESS`, with `SchemaConstraintsTest` still at 22 runs. One implementation of the walk now serves tests and production, which is what spec §5 requires of the error translator.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/error src/test/java/io/github/markusluisflores/frontrow/error src/test/java/io/github/markusluisflores/frontrow/schema
git commit -m "feat(error): add the shared error codes and driver-level lookup"
```

---

### Task 3: Configuration, the clock, and the JPA persistence model (spec §4)

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`)
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/.../config/FrontRowProperties.java`, `.../config/TimeConfig.java`
- Create: `src/main/java/.../persistence/Venue.java`, `Seat.java`, `Event.java`, `EventSeat.java`, `SeatHold.java`, `HoldRequest.java`, `HoldRequestId.java`, `TicketOrder.java`, `OrderLine.java`
- Create: `src/main/java/.../persistence/VenueRepository.java`, `SeatRepository.java`, `EventRepository.java`, `EventSeatRepository.java`, `SeatHoldRepository.java`, `HoldRequestRepository.java`, `TicketOrderRepository.java`, `OrderLineRepository.java`
- Test: `src/test/java/.../persistence/PersistenceMappingTest.java`

**Interfaces:**
- Consumes: `HoldStatus`, `EventStatus`, `OrderStatus`, `Money` from Task 1.
- Produces, for Plan 3:
  - Entities whose columns match `V1__core_schema.sql` exactly, with statuses mapped `@Enumerated(EnumType.STRING)` and all timestamps as `Instant`
  - `FrontRowProperties#holdTtl()` (default 10 minutes), `#maxActiveHoldGroups()` (3), `#maxSeatsPerHold()` (8), `#lockTimeout()` (5 seconds)
  - A `Clock` bean (`Clock.systemUTC()`), overridable in tests
  - Repositories exposing only what is used today

**Why `hold_request` needs a composite id:** its primary key is `(owner, idempotency_key)` — spec §4 — so it maps with `@IdClass`.

- [ ] **Step 1: Add the dependencies and configuration**

Add to `pom.xml` `<dependencies>`, after `spring-boot-starter-jdbc`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

Add to `application.yml`, merged under the existing `spring:` key, and a new top-level `frontrow:` key:

```yaml
spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.batch_size: 0

frontrow:
  hold-ttl: 10m
  max-active-hold-groups: 3
  max-seats-per-hold: 8
  lock-timeout: 5s
```

Three of those are load-bearing, so do not "tidy" them away:
- `open-in-view: false` — the default `true` keeps a session open for the whole request, which is exactly the persistence-context behaviour spec §5 warns against.
- `ddl-auto: validate` — Flyway owns the schema; this makes a mismatch between an entity and `V1__core_schema.sql` fail at startup instead of at runtime.
- `hibernate.jdbc.batch_size: 0` — spec §5 requires a constraint violation to surface inside the service, where it can be translated, not at commit time. Batching defers inserts and would also wrap the violation in a `BatchUpdateException`. `PostgresErrors` handles that case anyway, but the per-seat flush in Plan 3 depends on statements going out when flushed.

- [ ] **Step 2: Write the failing test**

`src/test/java/io/github/markusluisflores/frontrow/persistence/PersistenceMappingTest.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.domain.OrderStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Round-trips every entity against real Postgres, so a column or enum mismatch fails here rather than in a service. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PersistenceMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    VenueRepository venues;

    @Autowired
    SeatRepository seats;

    @Autowired
    EventRepository events;

    @Autowired
    EventSeatRepository eventSeats;

    @Autowired
    SeatHoldRepository seatHolds;

    @Autowired
    HoldRequestRepository holdRequests;

    @Autowired
    TicketOrderRepository orders;

    @Autowired
    OrderLineRepository orderLines;

    @Test
    void roundTripsTheWholeGraph() {
        Venue venue = venues.save(new Venue("Main Hall"));
        Seat seat = seats.save(new Seat(venue, "Floor", "A", 1));
        Event event = events.save(new Event(
                venue,
                "Test event",
                NOW.plus(30, ChronoUnit.DAYS),
                NOW.minus(1, ChronoUnit.DAYS),
                NOW.plus(29, ChronoUnit.DAYS),
                EventStatus.ON_SALE,
                "CAD"));
        EventSeat eventSeat = eventSeats.save(new EventSeat(event, seat, venue.getId(), 5000L));

        UUID group = UUID.randomUUID();
        SeatHold hold = seatHolds.save(
                new SeatHold(eventSeat, group, "alice", HoldStatus.ACTIVE, NOW.plus(10, ChronoUnit.MINUTES), NOW));
        TicketOrder order =
                orders.save(new TicketOrder(event, group, "alice", OrderStatus.CONFIRMED, 5000L, "CAD", NOW));
        orderLines.save(new OrderLine(order, eventSeat, 5000L));
        holdRequests.save(new HoldRequest("alice", "key-1", "hash-1", group, "{\"ok\":true}", NOW));

        seatHolds.flush();

        assertThat(hold.getId()).isNotNull();
        assertThat(seatHolds.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.ON_SALE);
        assertThat(orders.findByHoldGroupId(group).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(holdRequests.findById(new HoldRequestId("alice", "key-1")).orElseThrow().getHoldGroupId())
                .isEqualTo(group);
    }

    @Test
    void storesTimestampsToTheMillisecondWithoutDrift() {
        Venue venue = venues.save(new Venue("Precision Hall"));
        Instant odd = Instant.parse("2026-10-01T12:34:56.789Z");
        Event event = events.save(new Event(
                venue, "Precise", odd, odd.minusSeconds(60), odd.plusSeconds(60), EventStatus.DRAFT, "CAD"));
        events.flush();

        assertThat(events.findById(event.getId()).orElseThrow().getStartsAt()).isEqualTo(odd);
    }

    @Test
    void statusesAreStoredAsTextNotOrdinals() {
        Venue venue = venues.save(new Venue("Text Hall"));
        Event event = events.save(new Event(
                venue, "Textual", NOW, NOW.minusSeconds(1), NOW.plusSeconds(1), EventStatus.CANCELLED, "CAD"));
        events.flush();

        String stored = events.findStatusTextById(event.getId());
        assertThat(stored).isEqualTo("CANCELLED");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=PersistenceMappingTest`
Expected: FAIL — compilation errors; no entity or repository exists.

- [ ] **Step 4: Write the entities**

All eight follow the same shape, so here are two in full and the rest by their distinguishing details.

`Venue.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "venue")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    protected Venue() {}

    public Venue(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
```

`SeatHold.java` — the table the invariant lives on:

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat_hold")
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_seat_id", nullable = false)
    private EventSeat eventSeat;

    @Column(name = "hold_group_id", nullable = false)
    private UUID holdGroupId;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SeatHold() {}

    public SeatHold(
            EventSeat eventSeat, UUID holdGroupId, String owner, HoldStatus status, Instant expiresAt,
            Instant createdAt) {
        this.eventSeat = eventSeat;
        this.holdGroupId = holdGroupId;
        this.owner = owner;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public EventSeat getEventSeat() {
        return eventSeat;
    }

    public UUID getHoldGroupId() {
        return holdGroupId;
    }

    public String getOwner() {
        return owner;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Only ACTIVE may move, and only to an ended status (domain rule). */
    public void transitionTo(HoldStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException("illegal hold transition " + this.status + " -> " + target);
        }
        this.status = target;
    }
}
```

The remaining six, each in the same style — package-level javadoc not required, but every field's `@Column(name = ...)` must match `V1__core_schema.sql` exactly, because `ddl-auto: validate` will reject a mismatch at startup:

- **`Seat`** — `@ManyToOne` `venue` on `venue_id`; `section`, `row_label` (field `rowLabel`), `seat_number` (field `seatNumber`, `int`). Constructor `(Venue, String section, String rowLabel, int seatNumber)`.
- **`Event`** — `@ManyToOne` `venue`; `name`; `starts_at`, `sales_open_at`, `sales_close_at` as `Instant`; `status` as `@Enumerated(EnumType.STRING) EventStatus`; `currency` as `String` **annotated `@JdbcTypeCode(SqlTypes.CHAR)`** (see below). Constructor in that order. Add `salesWindow()` returning `SalesWindow.of(salesOpenAt, salesCloseAt)` so services never rebuild the window by hand.
- **`EventSeat`** — `@ManyToOne` `event`, `@ManyToOne` `seat`, plus a plain `@Column(name = "venue_id", nullable = false) Long venueId` (the composite FKs in V1 need the column written explicitly), and `price_cents` as `long`. Add `price(String currency)` returning `Money.ofCents(priceCents, currency)`.
- **`HoldRequest`** — `@IdClass(HoldRequestId.class)`, with `@Id String owner` and `@Id @Column(name = "idempotency_key") String idempotencyKey`; `request_hash`; nullable `hold_group_id` (`UUID`) and `response_json` (`String`, annotated `@JdbcTypeCode(SqlTypes.JSON)` from `org.hibernate.annotations`/`org.hibernate.type` so Hibernate writes it to the `jsonb` column); `created_at` as `Instant` and **not null**. Spec §5 inserts the row with `(owner, idempotency_key, request_hash)` first, so the plan's constructor takes `(owner, key, hash, Instant createdAt)` as well as the full form.
- **`HoldRequestId`** — a `Serializable` class with `owner`, `idempotencyKey`, a no-arg constructor (required of a JPA `@IdClass`, which is why this is not a record), `equals`/`hashCode`, **and `private static final long serialVersionUID = 1L;`**. Without that field the build fails: `-Xlint:all` includes `serial`, and `-Werror` turns the warning into `error: warnings found and -Werror specified`. Confirmed by compiling the class with javac 25.
- **`TicketOrder`** — `@ManyToOne` `event`; `hold_group_id` (`UUID`, unique); `owner`; `status` as `@Enumerated(EnumType.STRING) OrderStatus`; `total_cents` as `long`; `currency` **with `@JdbcTypeCode(SqlTypes.CHAR)`**; `created_at`.
- **`OrderLine`** — `@ManyToOne` `order` on `order_id`, `@ManyToOne` `eventSeat` on `event_seat_id`, `price_cents` as `long`.

**The two `currency` columns are `char(3)`, not `text`, and that breaks `ddl-auto: validate` unless it is
declared.** A plain `String` field maps to `VARCHAR`; Hibernate's validator compares type codes and names, pgjdbc
reports `bpchar` as `Types.CHAR`, and `PostgreSQLDialect` does not remap `CHAR`, so startup fails with
`wrong column type ... found [bpchar (Types#CHAR)], but expecting [varchar(255) (Types#VARCHAR)]`. A
`columnDefinition` does **not** fix it, because the type code stays `VARCHAR`. The fix is
`@JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)` on both `currency` fields. Every other column validates as
written: `timestamptz` against `Instant`, `uuid`, `jsonb` with `@JdbcTypeCode(SqlTypes.JSON)`, and every `text`
column.

Every entity: a `protected` no-arg constructor for Hibernate, a public constructor taking the required fields, getters only, and **no setters** except the explicit state-transition methods (`SeatHold#transitionTo`). Mutating a managed entity is how deferred updates happen, and spec §5 wants those few places visible by name.

Repositories, each extending `JpaRepository<T, ID>` and declaring only what is used today:

```java
package io.github.markusluisflores.frontrow.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketOrderRepository extends JpaRepository<TicketOrder, Long> {

    Optional<TicketOrder> findByHoldGroupId(UUID holdGroupId);
}
```

`EventRepository` additionally needs the raw status read the mapping test asserts:

```java
    @Query(value = "SELECT status FROM event WHERE id = :id", nativeQuery = true)
    String findStatusTextById(Long id);
```

The others (`VenueRepository`, `SeatRepository`, `EventSeatRepository`, `SeatHoldRepository`, `HoldRequestRepository` with id `HoldRequestId`, `OrderLineRepository`) declare no extra methods yet. Plan 3 adds what its services need; do not pre-add finders now (YAGNI).

`FrontRowProperties.java`:

```java
package io.github.markusluisflores.frontrow.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Caps and timeouts are configuration, not constants (spec §6). */
@Validated
@ConfigurationProperties(prefix = "frontrow")
public record FrontRowProperties(
        @NotNull Duration holdTtl,
        @Min(1) @Max(100) int maxActiveHoldGroups,
        @Min(1) @Max(100) int maxSeatsPerHold,
        @NotNull Duration lockTimeout) {}
```

`TimeConfig.java`:

```java
package io.github.markusluisflores.frontrow.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One Clock, injected everywhere. Tests replace this bean; nothing calls Instant.now() (spec §4). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FrontRowProperties.class)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw spotless:apply` then `./mvnw verify`
Expected: `BUILD SUCCESS`, `PersistenceMappingTest` 3 tests passing.

If startup fails with a Hibernate schema-validation error, the entity disagrees with `V1__core_schema.sql`. **Fix the entity, never the migration** — the migration was reviewed against spec §4 and proven by `SchemaConstraintsTest`. If you believe the migration is genuinely wrong, stop and report it.

- [ ] **Step 6: Prove `ddl-auto: validate` is actually guarding**

Temporarily rename `SeatHold`'s `owner` field mapping to `@Column(name = "owner_name", nullable = false)`.
Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=PersistenceMappingTest`
Expected: FAIL at startup — `Schema-validation: missing column [owner_name] in table [seat_hold]`.

Revert, then re-run and expect green. Without this check, `validate` could be silently inactive and nobody would know until a column name drifted.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/io/github/markusluisflores/frontrow/config src/main/java/io/github/markusluisflores/frontrow/persistence src/test/java/io/github/markusluisflores/frontrow/persistence
git commit -m "feat(persistence): map the V1 schema with JPA entities and properties"
```

Body must name the three load-bearing JPA settings and why each is set.

---

### Task 4: The locking gateway (spec §5, *Locking discipline*)

Every lock in the system is taken here, through explicit SQL, in the global order spec §5 defines. Plan 3's services call these methods and never write lock SQL of their own — which is what makes "one global lock order" a property you can check by reading one file.

**Files:**
- Create: `src/main/java/.../persistence/LockingGateway.java`
- Create: `src/main/java/.../persistence/AdvisoryLockKey.java`
- Test: `src/test/java/.../persistence/LockingGatewayTest.java`
- Test: `src/test/java/.../persistence/AdvisoryLockKeyTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `FrontRowProperties#lockTimeout`, `HoldStatus`, `PostgresErrors`.
- Produces, for Plan 3 — the lock vocabulary **this plan needs**, in the order the locks may be taken. It is not
  the complete set: Plan 3 **extends this class** with the sweeper's `FOR UPDATE ... SKIP LOCKED`, cancellation's
  "lock the event's ACTIVE holds ascending by `event_seat_id`", and hold creation's `hold_request ... ON CONFLICT DO
  NOTHING` (lock-order step 1). Step 8 installs a `CLAUDE.md` rule that lock SQL appears nowhere else in
  `src/main`, so extending this file is the only way Plan 3 can add a lock.
  - `setLockTimeout()` — `SET LOCAL lock_timeout`, for the event `PATCH` path only
  - `lockOwner(String owner)` — `pg_advisory_xact_lock`, hold creation only
  - `lockEventForShare(long eventId)` / `lockEventForUpdate(long eventId)` → `Optional<EventRow>`
  - `lockActiveHoldForSeat(long eventSeatId)` → `Optional<HoldRow>`
  - `expireHold(long holdId)` → `boolean` (an immediate SQL `UPDATE`, never a dirty entity)
  - `lockGroupRows(UUID holdGroupId, String owner)` → `List<HoldRow>`, ascending `event_seat_id`
  - `countActiveHoldGroups(String owner, Instant now)` → `int`
  - records `EventRow(long id, long venueId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt, String currency)` and `HoldRow(long id, long eventSeatId, UUID holdGroupId, String owner, HoldStatus status, Instant expiresAt)`

- [ ] **Step 1: Write the failing tests**

`src/test/java/io/github/markusluisflores/frontrow/persistence/AdvisoryLockKeyTest.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdvisoryLockKeyTest {

    @Test
    void isStableForTheSameOwner() {
        assertThat(AdvisoryLockKey.forOwner("alice")).isEqualTo(AdvisoryLockKey.forOwner("alice"));
    }

    @Test
    void differsBetweenOwners() {
        assertThat(AdvisoryLockKey.forOwner("alice")).isNotEqualTo(AdvisoryLockKey.forOwner("bob"));
    }

    @Test
    void isPinnedSoTheKeyCannotChangeUnnoticed() {
        assertThat(AdvisoryLockKey.forOwner("alice")).isEqualTo(-4_874_397_818_267_858_104L);
    }
}
```

The pinned value is **an output of the first run, not a prediction**. Implement `AdvisoryLockKey` first, run the test, and paste the actual value into the assertion. Its purpose is to fail if the hash ever changes — which would make two processes disagree about which lock protects an owner during a rolling restart.

`src/test/java/io/github/markusluisflores/frontrow/persistence/LockingGatewayTest.java`:

```java
package io.github.markusluisflores.frontrow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import io.github.markusluisflores.frontrow.error.PostgresErrors;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the Postgres lock semantics spec §5 depends on, with real concurrent transactions: a single-threaded test
 * cannot tell a lock that waits from one that does not.
 *
 * <p>Two rules make these proofs honest. Every task runs on a dedicated virtual-thread executor, never the common
 * ForkJoinPool — a latch-blocked task there can starve the pool on a small machine, and a test would then "prove" a
 * lock waits when nothing was ever scheduled. And a waiter is only treated as blocked once Postgres itself reports a
 * session waiting on a lock, read from pg_stat_activity, rather than inferring it from a TimeoutException.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LockingGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    LockingGateway gateway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    ExecutorService pool;
    long eventId;
    long venueId;
    long eventSeatId;
    long secondEventSeatId;

    @BeforeEach
    void seed() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        jdbc.sql("TRUNCATE order_line, ticket_order, hold_request, seat_hold, event_seat, event, seat, venue")
                .update();
        venueId = jdbc.sql("INSERT INTO venue (name) VALUES ('Lock Hall') RETURNING id")
                .query(Long.class)
                .single();
        long seatOne = insertSeat("A", 1);
        long seatTwo = insertSeat("A", 2);
        eventId = jdbc.sql(
                        """
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (:venue, 'Lock test', :starts, :open, :close, 'ON_SALE', 'CAD') RETURNING id""")
                .param("venue", venueId)
                .param("starts", utc(NOW.plus(30, ChronoUnit.DAYS)))
                .param("open", utc(NOW.minus(1, ChronoUnit.DAYS)))
                .param("close", utc(NOW.plus(29, ChronoUnit.DAYS)))
                .query(Long.class)
                .single();
        eventSeatId = insertEventSeat(seatOne);
        secondEventSeatId = insertEventSeat(seatTwo);
    }

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    @Test
    void readsTheEventRowUnderAShareLock() {
        EventRow row = tx.execute(status -> gateway.lockEventForShare(eventId)).orElseThrow();
        assertThat(row.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(row.venueId()).isEqualTo(venueId);
        assertThat(row.currency()).isEqualTo("CAD");
        assertThat(row.salesOpenAt()).isEqualTo(NOW.minus(1, ChronoUnit.DAYS));
    }

    @Test
    void reportsAMissingEvent() {
        assertThat(tx.execute(status -> gateway.lockEventForShare(-1L))).isEmpty();
    }

    @Test
    void twoShareLocksOnTheSameEventDoNotBlockEachOther() throws Exception {
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForShare(eventId);
            firstHolds.countDown();
            awaitQuietly(release);
        }));
        assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();

        // No latch games here: if a share lock blocked a share lock, this call would simply never return.
        Future<Boolean> second = pool.submit(
                () -> tx.execute(status -> gateway.lockEventForShare(eventId).isPresent()));
        assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
    }

    @Test
    void anExclusiveEventLockMakesAShareLockWait() throws Exception {
        CountDownLatch exclusiveHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            exclusiveHeld.countDown();
            awaitQuietly(release);
        }));
        assertThat(exclusiveHeld.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> waiter = pool.submit(() -> tx.execute(status -> {
            waiterStarted.countDown();
            return gateway.lockEventForShare(eventId).isPresent();
        }));
        assertThat(waiterStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // Postgres's own view of the world: a session is blocked on a lock, not merely unscheduled.
        awaitSessionsWaitingOnLocks(1);
        assertThat(waiter.isDone()).isFalse();

        release.countDown();
        assertThat(waiter.get(10, TimeUnit.SECONDS)).isTrue();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void lockTimeoutTurnsAnUnavailableLockIntoAPostgresTimeout() throws Exception {
        CountDownLatch exclusiveHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockEventForUpdate(eventId);
            exclusiveHeld.countDown();
            awaitQuietly(release);
        }));
        assertThat(exclusiveHeld.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> {
                gateway.setLockTimeout();
                gateway.lockEventForUpdate(eventId);
            }));
            assertThat(thrown).as("the second exclusive lock must time out, not wait forever").isNotNull();
            assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(PostgresErrors.LOCK_NOT_AVAILABLE);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theAdvisoryLockSerializesOneOwnerAndNotTwo() throws Exception {
        CountDownLatch aliceHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAliceStarted = new CountDownLatch(1);

        Future<?> alice = pool.submit(() -> tx.executeWithoutResult(status -> {
            gateway.lockOwner("alice");
            aliceHolds.countDown();
            awaitQuietly(release);
        }));
        assertThat(aliceHolds.await(10, TimeUnit.SECONDS)).isTrue();

        // A different owner is a different key, so this must not wait at all.
        pool.submit(() -> tx.executeWithoutResult(status -> gateway.lockOwner("bob"))).get(10, TimeUnit.SECONDS);

        Future<?> secondAlice = pool.submit(() -> tx.executeWithoutResult(status -> {
            secondAliceStarted.countDown();
            gateway.lockOwner("alice");
        }));
        assertThat(secondAliceStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitSessionsWaitingOnLocks(1);
        assertThat(secondAlice.isDone()).isFalse();

        release.countDown();
        secondAlice.get(10, TimeUnit.SECONDS);
        alice.get(10, TimeUnit.SECONDS);
    }

    @Test
    void expiringAHoldIsAnImmediateUpdateAndIsIdempotent() {
        UUID group = UUID.randomUUID();
        long holdId = insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.minusSeconds(1));

        tx.executeWithoutResult(status -> {
            assertThat(gateway.expireHold(holdId)).isTrue();
            // Read back through SQL inside the same transaction: the UPDATE already reached the database.
            assertThat(statusOf(holdId)).isEqualTo("EXPIRED");
            assertThat(gateway.expireHold(holdId)).isFalse();
        });
    }

    @Test
    void readsTheActiveHoldForASeatAndIgnoresEndedOnes() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.RELEASED, NOW.plusSeconds(600));
        assertThat(tx.execute(status -> gateway.lockActiveHoldForSeat(eventSeatId))).isEmpty();

        long holdId = insertHold(eventSeatId, UUID.randomUUID(), "bob", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        HoldRow row = tx.execute(status -> gateway.lockActiveHoldForSeat(eventSeatId)).orElseThrow();
        assertThat(row.id()).isEqualTo(holdId);
        assertThat(row.owner()).isEqualTo("bob");
        assertThat(row.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(row.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void locksGroupRowsInAscendingSeatOrder() {
        UUID group = UUID.randomUUID();
        // Insert the higher seat id first, so ascending order cannot come from insertion order.
        insertHold(secondEventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));

        List<HoldRow> rows = tx.execute(status -> gateway.lockGroupRows(group, "alice"));
        assertThat(rows).extracting(HoldRow::eventSeatId).containsExactly(eventSeatId, secondEventSeatId);
    }

    @Test
    void doesNotReturnAnotherOwnersGroup() {
        UUID group = UUID.randomUUID();
        insertHold(eventSeatId, group, "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        assertThat(tx.execute(status -> gateway.lockGroupRows(group, "bob"))).isEmpty();
    }

    @Test
    void countsOnlyGroupsWithAtLeastOneLiveRow() {
        insertHold(eventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.plusSeconds(600));
        insertHold(secondEventSeatId, UUID.randomUUID(), "alice", HoldStatus.ACTIVE, NOW.minusSeconds(1));
        assertThat(tx.execute(status -> gateway.countActiveHoldGroups("alice", NOW))).isEqualTo(1);
    }

    /** Waits until Postgres reports at least `expected` sessions blocked on a lock, so "it waits" is observed. */
    private void awaitSessionsWaitingOnLocks(int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int waiting = jdbc.sql(
                            """
                            SELECT count(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock'""")
                    .query(Integer.class)
                    .single();
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no session reported waiting on a lock within 10s");
    }

    private long insertSeat(String row, int number) {
        return jdbc.sql(
                        """
                        INSERT INTO seat (venue_id, section, row_label, seat_number)
                        VALUES (:venue, 'Floor', :row, :number) RETURNING id""")
                .param("venue", venueId)
                .param("row", row)
                .param("number", number)
                .query(Long.class)
                .single();
    }

    private long insertEventSeat(long seatId) {
        return jdbc.sql(
                        """
                        INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents)
                        VALUES (:event, :seat, :venue, 5000) RETURNING id""")
                .param("event", eventId)
                .param("seat", seatId)
                .param("venue", venueId)
                .query(Long.class)
                .single();
    }

    private long insertHold(long eventSeat, UUID group, String owner, HoldStatus status, Instant expiresAt) {
        return jdbc.sql(
                        """
                        INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                        VALUES (:seat, :group, :owner, :status, :expires, :created) RETURNING id""")
                .param("seat", eventSeat)
                .param("group", group)
                .param("owner", owner)
                .param("status", status.name())
                .param("expires", utc(expiresAt))
                .param("created", utc(NOW))
                .query(Long.class)
                .single();
    }

    private String statusOf(long holdId) {
        return jdbc.sql("SELECT status FROM seat_hold WHERE id = :id")
                .param("id", holdId)
                .query(String.class)
                .single();
    }

    /** pgjdbc cannot infer a SQL type for a bare Instant, so every bind goes through OffsetDateTime. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A lock holder waits longer than the 10s an assertion spends polling for a waiter, so a slow poll cannot make
     * the holder give up its lock mid-assertion and turn a real pass into a flake.
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch did not open within 30s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
```

Two things about this test class are deliberate and must not be "simplified":

- **The virtual-thread executor, not `CompletableFuture`.** `CompletableFuture.runAsync` uses the common
  ForkJoinPool, whose parallelism is `cores - 1`. A latch-blocked task there occupies a worker with no compensation,
  so on a 2-core machine the waiter may never be scheduled at all — and the test would pass for that reason while
  proving nothing about locks. Spec §8 asks for an executor pool; virtual threads give one where blocking is free.
- **`awaitSessionsWaitingOnLocks` instead of a `TimeoutException`.** A timeout cannot distinguish "blocked on the
  lock" from "has not started yet". Reading `pg_stat_activity.wait_event_type = 'Lock'` asks Postgres directly, so
  the assertion is an observation rather than an inference. The `started` latch plus `isDone()` then pins down that
  the waiter reached the locking call and has not returned.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest='LockingGatewayTest,AdvisoryLockKeyTest'`
Expected: FAIL — compilation errors; neither class exists.

- [ ] **Step 3: Write `AdvisoryLockKey`**

```java
package io.github.markusluisflores.frontrow.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The bigint key for pg_advisory_xact_lock. Derived in Java from SHA-256 rather than from Postgres's undocumented
 * hashtext(), whose value has changed between major versions — a per-owner lock must mean the same thing on every
 * server and in every process.
 */
final class AdvisoryLockKey {

    private AdvisoryLockKey() {}

    static long forOwner(String owner) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JRE", impossible);
        }
    }
}
```

- [ ] **Step 4: Pin the hash**

Run: `./mvnw -q test -Dspotless.check.skip=true -Dtest=AdvisoryLockKeyTest`
Expected: two tests pass, `isPinnedSoTheKeyCannotChangeUnnoticed` fails with the actual value in the message. Paste that value into the assertion and re-run; all three pass.

Record the value in the commit body as well, so a future reader sees where it came from.

- [ ] **Step 5: Write `LockingGateway`**

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.config.FrontRowProperties;
import io.github.markusluisflores.frontrow.domain.EventStatus;
import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every lock in the system, taken through explicit SQL in the one global order spec §5 defines: the hold_request key,
 * the per-owner advisory lock, the event row, then seat_hold rows ascending by event_seat_id.
 *
 * <p>These reads deliberately bypass the JPA persistence context. A locking query must return the row as currently
 * committed, not an entity Hibernate already cached, and lazy expiry must reach the database before the insert that
 * depends on it — Hibernate flushes inserts before updates (spec §5).
 */
@Component
public class LockingGateway {

    private static final String EVENT_COLUMNS = "id, venue_id, status, sales_open_at, sales_close_at, currency";
    private static final String HOLD_COLUMNS = "id, event_seat_id, hold_group_id, owner, status, expires_at";

    private final JdbcClient jdbc;
    private final FrontRowProperties properties;

    public LockingGateway(JdbcClient jdbc, FrontRowProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Bounds every lock wait in this transaction. Used by the event PATCH path, where a steady stream of FOR SHARE
     * holders could otherwise starve a FOR UPDATE indefinitely (spec §5, Liveness). Postgres does not accept bind
     * parameters in SET, so the validated configuration value is formatted as a literal; no request data reaches it.
     */
    public void setLockTimeout() {
        long millis = properties.lockTimeout().toMillis();
        jdbc.sql("SET LOCAL lock_timeout = '" + millis + "ms'").update();
    }

    /** Step 2 of the lock order: serializes one owner's concurrent hold creations so the cap cannot be raced. */
    public void lockOwner(String owner) {
        // Wrapped in a SELECT 1 because pg_advisory_xact_lock returns void, and a void column's mapped value is not
        // a thing worth depending on.
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) locked")
                .param("key", AdvisoryLockKey.forOwner(owner))
                .query(Integer.class)
                .single();
    }

    /** Step 3, shared: hold creation and confirmation. Blocks a concurrent event PATCH without blocking each other. */
    public Optional<EventRow> lockEventForShare(long eventId) {
        return lockEvent(eventId, "FOR SHARE");
    }

    /** Step 3, exclusive: every event PATCH, including cancellation. */
    public Optional<EventRow> lockEventForUpdate(long eventId) {
        return lockEvent(eventId, "FOR UPDATE");
    }

    private Optional<EventRow> lockEvent(long eventId, String lockClause) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM event WHERE id = :id " + lockClause)
                .param("id", eventId)
                .query((rs, rowNum) -> new EventRow(
                        rs.getLong("id"),
                        rs.getLong("venue_id"),
                        EventStatus.valueOf(rs.getString("status")),
                        instantAt(rs, "sales_open_at"),
                        instantAt(rs, "sales_close_at"),
                        rs.getString("currency")))
                .optional();
    }

    /** Step 4, one seat: the ACTIVE hold on a seat, if any, locked so lazy expiry can decide on the committed row. */
    public Optional<HoldRow> lockActiveHoldForSeat(long eventSeatId) {
        return jdbc.sql("SELECT " + HOLD_COLUMNS
                        + " FROM seat_hold WHERE event_seat_id = :seat AND status = 'ACTIVE' FOR UPDATE")
                .param("seat", eventSeatId)
                .query(LockingGateway::mapHold)
                .optional();
    }

    /**
     * Lazy expiry, as an immediate SQL UPDATE (spec §5). Returns true when this call expired the row; false means it
     * was no longer ACTIVE, which is a legal race outcome, not an error.
     */
    public boolean expireHold(long holdId) {
        return jdbc.sql("UPDATE seat_hold SET status = 'EXPIRED' WHERE id = :id AND status = 'ACTIVE'")
                        .param("id", holdId)
                        .update()
                == 1;
    }

    /** Step 4, a whole group: ascending event_seat_id, which is what makes confirm and release deadlock-free. */
    public List<HoldRow> lockGroupRows(UUID holdGroupId, String owner) {
        return jdbc.sql("SELECT " + HOLD_COLUMNS
                        + " FROM seat_hold WHERE hold_group_id = :group AND owner = :owner"
                        + " ORDER BY event_seat_id FOR UPDATE")
                .param("group", holdGroupId)
                .param("owner", owner)
                .query(LockingGateway::mapHold)
                .list();
    }

    /** The per-owner cap counts groups with at least one live row, so lapsed-but-unswept groups never count (spec §4). */
    public int countActiveHoldGroups(String owner, Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(DISTINCT hold_group_id) FROM seat_hold
                        WHERE owner = :owner AND status = 'ACTIVE' AND expires_at > :now""")
                .param("owner", owner)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    private static HoldRow mapHold(ResultSet rs, int rowNum) throws SQLException {
        return new HoldRow(
                rs.getLong("id"),
                rs.getLong("event_seat_id"),
                rs.getObject("hold_group_id", UUID.class),
                rs.getString("owner"),
                HoldStatus.valueOf(rs.getString("status")),
                instantAt(rs, "expires_at"));
    }

    /**
     * pgjdbc 42.7.13 has no Instant branch in getObject(column, Class) — OffsetDateTime is the supported
     * offset-carrying type, and converting from it needs no zone assumption.
     */
    private static Instant instantAt(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
```

Plus the two row records, each in its own file beside the gateway:

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.EventStatus;
import java.time.Instant;

/** An event row as committed, read under a lock — deliberately not an entity (spec §5). */
public record EventRow(
        long id, long venueId, EventStatus status, Instant salesOpenAt, Instant salesCloseAt, String currency) {}
```

```java
package io.github.markusluisflores.frontrow.persistence;

import io.github.markusluisflores.frontrow.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/** A seat_hold row as committed, read under a lock — deliberately not an entity (spec §5). */
public record HoldRow(
        long id, long eventSeatId, UUID holdGroupId, String owner, HoldStatus status, Instant expiresAt) {}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw spotless:apply` then `./mvnw verify`
Expected: `BUILD SUCCESS`. `LockingGatewayTest` runs 11 tests; everything from Tasks 1-3 and Plan 1 still passes.

Two likely stumbles, both worth reporting rather than working around:
- If `lockTimeoutTurnsAnUnavailableLockIntoAPostgresTimeout` fails because no exception is thrown, `SET LOCAL` did not apply — check the transaction actually began before `setLockTimeout()`.
- If `rs.getObject(column, OffsetDateTime.class)` throws, or a bind of an `OffsetDateTime` is rejected, the driver in use differs from the pinned pgjdbc 42.7.13. Report the exact message rather than falling back to `Timestamp` arithmetic, which reintroduces a JVM-default-zone assumption spec §4 forbids.

- [ ] **Step 7: Prove the lock order is written down in one place**

Run: `grep -rln "FOR UPDATE\|FOR SHARE\|pg_advisory" src/main/java/`
Expected: exactly one file, `.../persistence/LockingGateway.java`.

Plan 3 must keep that true: a service that writes its own lock SQL breaks the single-lock-order property, and this grep is how a reviewer checks it in one step.

- [ ] **Step 8: Update `CLAUDE.md`**

Replace the "Current state" paragraph so it describes what now exists: the domain core with no framework imports, the shared error contract, the JPA persistence model over V1, and the locking gateway — and that no write path, REST endpoint or MCP tool exists yet.

Add to "Architecture rules":

```markdown
- All lock SQL lives in `persistence/LockingGateway.java` — `FOR UPDATE`, `FOR SHARE`
  and `pg_advisory_xact_lock` appear nowhere else in `src/main`. A service that writes
  its own lock SQL breaks the single global lock order (spec §5) — that is a review
  BLOCKER. Check with:
  `grep -rln "FOR UPDATE\|FOR SHARE\|pg_advisory" src/main/java/`
- The `domain` package imports only `java.*`. Check with:
  `grep -rn "^import" src/main/java/io/github/markusluisflores/frontrow/domain/ | grep -v "import java\."`
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/markusluisflores/frontrow/persistence src/test/java/io/github/markusluisflores/frontrow/persistence CLAUDE.md
git commit -m "feat(persistence): add the locking gateway with proven lock semantics"
```

Body must record the pinned advisory-lock key value and state that the concurrent tests prove share/share does not block, exclusive/share does, and `lock_timeout` surfaces as `55P03`.

---

## Spec coverage (this plan's share)

| Spec requirement | Task | Owned by, if not here |
|---|---|---|
| §4 `Money` as integer cents plus ISO currency | 1 | |
| §4 statuses and their legal transitions | 1 | |
| §4 the live-hold predicate, one definition | 1 | |
| §4 the half-open sales window | 1 | |
| §5 confirm precedence (released before expired) | 1 (the rule) | Plan 3 (its use in confirm) |
| §6 the eleven error codes, each with a hint | 2 | Plan 4 renders them as Problem Details |
| §5 constraint name from the driver, cause chain and `BatchUpdateException` | 2 | Plan 3's translator uses it |
| §4 all eight tables mapped, `timestamptz` as `Instant`, no DB clock | 3 | |
| §6 caps and TTL as configuration, not constants | 3 | Plan 3 enforces them |
| §5 the global lock order, in one place | 4 | Plan 3 calls it |
| §5 `SET LOCAL lock_timeout` for the PATCH path | 4 (the mechanism) | Plan 3 (the PATCH path) |
| §5 lazy expiry as an immediate `UPDATE` | 4 (the mechanism) | Plan 3 (hold creation step 4) |
| §5 per-owner advisory lock | 4 | Plan 3's cap check |
| §5 READ COMMITTED set explicitly on every locking transaction | — | **Plan 3** — this plan's gateway takes locks but owns no transaction boundary. Postgres already defaults to READ COMMITTED, so nothing fails if it is forgotten, which is exactly why it needs an owner |
| §5 the sweeper's `SKIP LOCKED`, cancellation's per-event hold locking, `hold_request` insert | — | Plan 3, extending `LockingGateway` |
| §8 domain unit tests with a fixed `Clock`, no database | 1 | |
| §8 repository behaviour against real Postgres | 3, 4 | |
| §5 hold creation, confirm, release, cancel, sweeper; concurrency tests 1-7 | — | Plan 3 |
| §3 security chains, §6 REST surface, OpenAPI | — | Plan 4 |
| §6 MCP tools, §7 evidence artifacts | — | Plan 5 |

## Self-review record

- **Placeholders:** none. The one value filled in during execution is the pinned advisory-lock hash, and Task 4 Step 4 says explicitly that it is an output of the first run, not a prediction.
- **Type consistency:** checked across tasks — `HoldStatus`/`EventStatus`/`OrderStatus` from Task 1 are the same types the entities map in Task 3 and the gateway returns in Task 4; `HoldRules.HoldRow` (domain, three fields) is deliberately distinct from `persistence.HoldRow` (six fields, as read under the lock), and Plan 3 converts between them.
- **Sibling reuse:** Task 2 Step 5 deletes `SchemaFixtures`'s private copy of the cause-chain walk rather than leaving two implementations — the duplicate exists only because Plan 1 needed it before `src/main` had an error package.
- **Tooling:** all four literal commit subjects were run through `.githooks/commit-msg` and pass (62, 63, 69 and 69 characters, measured not estimated). Most Java snippets were not compiled — that happens on the executor's first `./mvnw verify`. The exception is the `@IdClass` shape from review round 1, which was compiled with javac 25 under the project's own flags to confirm the `serial` lint failure. Every API used was verified against the pinned sources on 2026-10-02: `JdbcClient.sql/param/query/single/optional/list/update` in spring-jdbc 7.0.9, and `JdbcClientAutoConfiguration` in `spring-boot-jdbc` 4.1.1.
- **Review round 1** (fresh reviewer, verified against the pinned driver, Hibernate and Spring sources rather than
  from memory): **5 BLOCKERs**, 5 SUGGESTIONs, 3 NITs — all applied.
  - `rs.getObject(column, Instant.class)` is unsupported by pgjdbc 42.7.13, so every locking read would have thrown.
  - Binding a bare `Instant` fails the same way; the seed data would not have inserted.
  - `currency char(3)` mapped as a plain `String` fails `ddl-auto: validate` at startup — needs
    `@JdbcTypeCode(SqlTypes.CHAR)`.
  - `HoldRequestId` without `serialVersionUID` fails the build under `-Werror` (confirmed by compiling it).
  - The lock-wait proofs could pass vacuously on the common ForkJoinPool, and a `TimeoutException` cannot tell
    "blocked on a lock" from "never scheduled". They now use a virtual-thread executor and read
    `pg_stat_activity` to observe the wait.
  - I re-verified the first two and the fourth myself before applying them.
- **Review round 2** (fresh, on the fix pass `96f0c1f`): **0 BLOCKERs**, 1 SUGGESTION, 4 NITs — all applied here.
  It verified the five round-1 fixes rather than ticking them, including running a real `postgres:18-alpine`
  container to confirm the rewritten `lockOwner` does take the advisory lock (visible in `pg_locks`) and that
  `pg_stat_activity` reports `Lock/advisory` and `Lock/transactionid` for the two waits the tests assert on. It also
  audited every JDBC boundary rather than only the two round 1 named, and confirmed `instantAt`'s unguarded
  `toInstant()` is safe because all four columns it reads are `NOT NULL` in V1.
  - Its findings were numbering drift in the decisions list, an import left unused by the fix pass, READ COMMITTED
    stated two ways, a holder-latch budget with no flake margin, and a self-review line that contradicted its own
    round-1 bullet about compiling.
  - Still open by design: SpotBugs on the new `src/main`, Spotless reformatting, and Hibernate's runtime CHAR
    binding — none observable until execution.
- **Verification-matrix crossing check:** the lock tests cross *lock mode* (share, exclusive, advisory) with *contention* (uncontended, contended, contended with a timeout). Those two are the pair most likely to interact, because a lock that looks correct uncontended is exactly the bug a single-threaded test cannot see — which is why every lock assertion here runs in a second transaction on another thread.
