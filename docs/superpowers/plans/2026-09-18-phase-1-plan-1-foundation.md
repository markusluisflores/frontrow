# FrontRow Phase 1, Plan 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a buildable, CI-checked Spring Boot project. Prove how an
authenticated principal reaches an MCP tool method, and land the Postgres schema
whose constraints carry the double-booking invariant.

**Architecture:** This is plan 1 of 3 for Phase 1. Plan 2 covers the domain core,
services, concurrency tests, REST and security chains. Plan 3 covers the MCP
tools and evidence artifacts. Plan 3 is written only after this plan's spike
result is recorded. This plan has four tasks:

1. The Maven scaffold, with formatter and static analysis.
2. CI, CodeQL, dependency review and Dependabot.
3. The principal-propagation spike, all in test code, with its outcome recorded
   as ADR-004.
4. The Flyway V1 schema, with one integration test per constraint.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1 (MCP Java SDK 2.0.0),
Postgres 18, Flyway 12.4.0, JUnit Jupiter 6.0.3, AssertJ, Testcontainers 2.0.5,
Maven 3.9.16 via the wrapper.

**Spec:** `docs/superpowers/specs/2026-09-17-frontrow-design.md` (rev 2.6.1).
Also read `docs/adr/ADR-001` to `ADR-003`.

## Global Constraints

- **Versions** were read from Maven Central on 2026-09-18, which is what spec §10
  requires ("re-check and pin exact versions at scaffold time"):
  - Spring Boot parent `4.1.1` (latest GA; `4.2.0-M1` is a milestone, not used)
  - `spring-ai-bom` `2.0.1`
  - Maven `3.9.16`, wrapper `3.3.4` (`only-script`)
  - Spotless Maven plugin `3.10.2` with palantir-java-format `2.98.0`
  - SpotBugs Maven plugin `4.10.4.1`
- **Boot 4.1.1 manages these; do not override them:**
  - Hibernate `7.4.5.Final`
  - Flyway `12.4.0`
  - PostgreSQL JDBC `42.7.13`
  - Testcontainers `2.0.5`
  - JUnit Jupiter `6.0.3`
  - AssertJ `3.27.7`
  - Spring Security `7.1.1`
  - Jackson `3.1.5`
- **JUnit version.** The spec (§8) and `CLAUDE.md` say "JUnit 5". Boot 4.1.1
  manages **JUnit Jupiter 6.0.3**, which keeps the same `org.junit.jupiter.api`
  API. Use the managed version. Task 1 updates `CLAUDE.md`. The spec's wording
  is corrected at its next revision.
- **Java 25**, and the base package is `io.github.markusluisflores.frontrow`.
- **Java 25 must be installed first.** This machine has JDK 21 and no Maven. The
  controller confirms JDK 25 with the user before Task 1 (see Task 1, Step 0).
- **Postgres only.** Integration tests use real Postgres (`postgres:18-alpine`)
  through Testcontainers: never H2, never a mocked repository (`CLAUDE.md`,
  spec §8).
- **The domain core has no Spring Web and no MCP types** (`CLAUDE.md`). Nothing
  in this plan creates domain classes. The spike lives entirely in `src/test`.
- **Time comes from the injected `Clock`**, never the database clock (spec §4).
  So no column in V1 has a `DEFAULT now()`.
- **Commit messages** follow `.githooks/commit-msg`:
  `<type>(<scope>): <subject>`, 72 characters at most, no trailing period. Hooks
  are active only after `git config core.hooksPath .githooks`.
- **The test command is `./mvnw verify`.** It runs Spotless check, compile with
  `-Werror`, tests and SpotBugs. Every task ends with it green.
- **Before every commit**, run `./mvnw spotless:apply`. The code blocks in this
  plan follow palantir style, but the formatter's output wins.

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven wrapper, script-only | 1 |
| `pom.xml` | Build: dependencies, Spotless, SpotBugs, `-Werror` | 1 (MCP and security dependencies added in 3) |
| `src/main/java/io/github/markusluisflores/frontrow/FrontRowApplication.java` | Boot entry point | 1 |
| `src/main/resources/application.yml` | App name; MCP server properties (Task 3) | 1, 3 |
| `src/test/java/io/github/markusluisflores/frontrow/TestcontainersConfiguration.java` | One `@ServiceConnection` Postgres for all tests | 1 |
| `src/test/java/io/github/markusluisflores/frontrow/FrontRowApplicationTests.java` | Context loads against real Postgres | 1 |
| `.claude/settings.json`, `.claude/hooks/spotless-file.ps1` | Per-file format-on-edit hook | 1 |
| `.github/workflows/ci.yml` | Build and test on push, PR and manual trigger | 2 |
| `.github/workflows/codeql.yml` | CodeQL `java-kotlin` | 2 |
| `.github/workflows/dependency-review.yml` | Vulnerable-dependency gate on PRs | 2 |
| `.github/dependabot.yml` | `maven` and `github-actions` updates | 2 |
| `src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java` | The spike: a real bearer token over real HTTP into an `@McpTool` | 3 |
| `docs/adr/ADR-004-mcp-principal-propagation.md`, `docs/adr/README.md` | The spike's recorded outcome | 3 |
| `src/main/resources/db/migration/V1__core_schema.sql` | Every table, and every §5 constraint | 4 |
| `src/test/java/io/github/markusluisflores/frontrow/schema/SchemaConstraintsTest.java` | One violating-row test per constraint | 4 |
| `src/test/java/io/github/markusluisflores/frontrow/schema/SchemaFixtures.java` | Test-row inserts shared by the schema tests | 4 |
| `CLAUDE.md` | Deferred-bootstrap list ticked off; JUnit note; CI runbook | 1, 2 |

---

### Task 1: Maven scaffold, formatter, static analysis

Owns these deferred bootstrap items from `CLAUDE.md`: Maven wrapper, pinned
versions, the test framework, `./mvnw verify`, Spotless plus the per-file hook,
static analysis, and compile plus tests as the type-check gate.

**Files:**
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `pom.xml`
- Create: `src/main/java/io/github/markusluisflores/frontrow/FrontRowApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/io/github/markusluisflores/frontrow/TestcontainersConfiguration.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/FrontRowApplicationTests.java`
- Create: `.claude/hooks/spotless-file.ps1`
- Modify: `.claude/settings.json`, `CLAUDE.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `TestcontainersConfiguration`, a `@TestConfiguration(proxyBeanMethods = false)`
  class with a `@Bean @ServiceConnection PostgreSQLContainer postgresContainer()`.
  Tests `@Import(TestcontainersConfiguration.class)` to get a live datasource.
  Also produces the `./mvnw verify` gate.

- [ ] **Step 0: Confirm prerequisites (controller, with the user)**

Run: `java -version` and `docker version --format '{{.Server.Version}}'`
Expected: Java `25.x`, and a Docker server version.

If Java is not 25, stop and ask the user to install it. Installing software
needs their explicit permission. The command to offer is
`winget install --id EclipseAdoptium.Temurin.25.JDK` (version 25.0.4 on
2026-09-18). After the install, `JAVA_HOME` must point at the JDK 25 directory
in a new shell.

If Docker is not running, stop: every test in this plan needs it. Also run
`git config core.hooksPath` and expect `.githooks`. If it's empty, run
`git config core.hooksPath .githooks`.

- [ ] **Step 1: Add the Maven wrapper (script-only)**

```bash
curl -fsSLo wrapper.zip https://repo1.maven.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/maven-wrapper-distribution-3.3.4-only-script.zip
unzip -o wrapper.zip mvnw mvnw.cmd
rm wrapper.zip
mkdir -p .mvn/wrapper
chmod +x mvnw
git update-index --chmod=+x mvnw 2>/dev/null || true
```

Create `.mvn/wrapper/maven-wrapper.properties`:

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
```

Run: `./mvnw -v`
Expected: `Apache Maven 3.9.16` and `Java version: 25`.

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>io.github.markusluisflores</groupId>
    <artifactId>frontrow</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>frontrow</name>
    <description>Event ticketing with REST and MCP adapters over one domain core</description>

    <properties>
        <java.version>25</java.version>
        <spring-ai.version>2.0.1</spring-ai.version>
        <spotless-maven-plugin.version>3.10.2</spotless-maven-plugin.version>
        <palantir-java-format.version>2.98.0</palantir-java-format.version>
        <spotbugs-maven-plugin.version>4.10.4.1</spotbugs-maven-plugin.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <compilerArgs>
                        <arg>-Xlint:all,-processing</arg>
                        <arg>-Werror</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>com.diffplug.spotless</groupId>
                <artifactId>spotless-maven-plugin</artifactId>
                <version>${spotless-maven-plugin.version}</version>
                <configuration>
                    <java>
                        <palantirJavaFormat>
                            <version>${palantir-java-format.version}</version>
                        </palantirJavaFormat>
                        <removeUnusedImports/>
                        <importOrder/>
                    </java>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>check</goal>
                        </goals>
                        <phase>validate</phase>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <version>${spotbugs-maven-plugin.version}</version>
                <configuration>
                    <effort>Max</effort>
                    <threshold>Medium</threshold>
                    <includeTests>false</includeTests>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Write the failing test**

`src/test/java/io/github/markusluisflores/frontrow/TestcontainersConfiguration.java`:

```java
package io.github.markusluisflores.frontrow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }
}
```

`src/test/java/io/github/markusluisflores/frontrow/FrontRowApplicationTests.java`:

```java
package io.github.markusluisflores.frontrow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FrontRowApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAgainstRealPostgres() {
        String version = jdbc.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("18");
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=FrontRowApplicationTests`
Expected: FAIL. The test does not compile, because no `@SpringBootConfiguration`
exists yet ("Unable to find a @SpringBootConfiguration").

- [ ] **Step 5: Write the minimal implementation**

`src/main/java/io/github/markusluisflores/frontrow/FrontRowApplication.java`:

```java
package io.github.markusluisflores.frontrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FrontRowApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrontRowApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: frontrow
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw spotless:apply` and then `./mvnw verify`
Expected: `BUILD SUCCESS`. `FrontRowApplicationTests` reports 1 test run and 0
failures, and SpotBugs reports no bugs.

If SpotBugs flags `FrontRowApplication` for `EI_EXPOSE_REP` or anything else,
fix the code. Do not add an exclusion.

**Fallback if SpotBugs itself fails on Java 25 class files:** that is an error
from the plugin, not a finding against our code (for example
`Unsupported class file major version 69`). Record the exact message and mark
this step BLOCKED for the controller. Do not remove the plugin to get green.

- [ ] **Step 7: Prove the gate catches violations (both directions)**

Temporarily change `FrontRowApplication` to use a 2-space indent on the `main`
line.
Run: `./mvnw verify`
Expected: FAIL at `spotless:check` ("The following files had format violations").

Restore the file with `./mvnw spotless:apply`.

Then temporarily add `java.util.List raw = new java.util.ArrayList();` inside
`main`.
Run: `./mvnw -q compile`
Expected: FAIL with `warning: [rawtypes]` and "warnings found and -Werror
specified".

Revert that change, then run `./mvnw verify` and expect `BUILD SUCCESS`.

- [ ] **Step 8: Add the per-file format hook**

`.claude/hooks/spotless-file.ps1`:

```powershell
# PostToolUse hook: format the one Java file Claude just wrote or edited.
# Reads the hook's JSON input from stdin; never blocks the edit (always exits 0).
$payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
$file = $payload.tool_input.file_path
if (-not $file -or -not $file.EndsWith('.java')) { exit 0 }
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    & "$root\mvnw.cmd" -q spotless:apply "-DspotlessFiles=$([regex]::Escape($file))" | Out-Null
} finally {
    Pop-Location
}
exit 0
```

`.claude/settings.json` (the permissions block is unchanged; `hooks` is added):

```json
{
  "permissions": {
    "allow": [
      "Bash(./mvnw *)",
      "Bash(mvnw.cmd *)",
      "Bash(docker compose *)",
      "Bash(docker ps*)",
      "Bash(gitleaks *)"
    ]
  },
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "powershell -NoProfile -ExecutionPolicy Bypass -File .claude/hooks/spotless-file.ps1",
            "statusMessage": "Formatting Java file..."
          }
        ]
      }
    ]
  }
}
```

- [ ] **Step 9: Verify the hook live**

This check runs in the main session, not a subagent, because hooks load at
session start. Restart the session so it picks up the new settings.

Use the Edit tool to change `FrontRowApplication.java` so the `main` line has a
2-space indent. Then read the file.
Expected: the indent is back to palantir's 4 spaces, and `./mvnw spotless:check`
passes.

If the file is unchanged, this task is BLOCKED. Do not continue with an
unverified hook.

- [ ] **Step 10: Update `CLAUDE.md`**

In "Deferred bootstrap items", strike through the items this task delivered.
Keep the list, so Task 2 can strike its own items:
- Maven wrapper; versions pinned
- Test framework; `./mvnw verify`
- Formatter plus hook
- Linter / static analysis (SpotBugs, and `-Werror` as the compile gate)

Leave "enforced in CI" open for Task 2. Also add this line under "Conventions":

```markdown
- Tests: JUnit Jupiter 6 (Boot 4.1.1-managed; same API as the "JUnit 5" named in
  the spec) + AssertJ + Testcontainers. Run everything with `./mvnw verify`.
```

- [ ] **Step 11: Commit**

```bash
git add mvnw mvnw.cmd .mvn pom.xml src .claude/settings.json .claude/hooks CLAUDE.md
git commit -m "chore(build): scaffold Maven build with Spotless and SpotBugs gates"
```

---

### Task 2: CI, CodeQL, dependency review, Dependabot

Owns these deferred bootstrap items: the CI workflow, CodeQL `java-kotlin`, the
dependency vulnerability scan, Dependabot, and branch-protection required checks.
`cicd-standards` applies: `workflow_dispatch` on every workflow, named steps,
secrets listed, idempotent runs, and a manual-trigger note.

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/codeql.yml`,
  `.github/workflows/dependency-review.yml`, `.github/dependabot.yml`
- Modify: `CLAUDE.md` (a CI Runbook section, and the deferred list)

**Interfaces:**
- Consumes: `./mvnw verify` from Task 1.
- Produces: required-check names `Build and test` (from `ci.yml`) and
  `Analyze (java-kotlin)` (from `codeql.yml`), which branch protection uses.

- [ ] **Step 1: Write `.github/workflows/ci.yml`**

```yaml
# Build and test: compile (-Werror), Spotless check, unit + Testcontainers
# integration tests, SpotBugs.
# Secrets: none. GITHUB_TOKEN is not used beyond checkout's default read scope.
# Manual trigger: gh workflow run ci.yml --ref <branch>
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Build and test
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - name: Check out the repository
        uses: actions/checkout@v7

      - name: Set up Temurin JDK 25 with Maven cache
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'
          cache: maven

      - name: Verify Docker is available for Testcontainers
        run: docker version --format 'Docker server {{.Server.Version}}' || { echo "::error::Docker is unavailable on this runner - Testcontainers integration tests cannot run."; exit 1; }

      - name: Build, test and run static analysis (./mvnw verify)
        run: ./mvnw -B -ntp verify

      - name: Upload test reports when the build fails
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: surefire-reports
          path: target/surefire-reports/
          if-no-files-found: ignore
```

- [ ] **Step 2: Check the upload-artifact major before committing**

Run: `gh api repos/actions/upload-artifact/releases/latest --jq .tag_name`
Expected: a tag such as `v5.x.y`. Put that major in `ci.yml` in place of `@v4`.
This plan pinned the other three actions on 2026-09-18 (checkout `v7`,
setup-java `v6`, codeql-action `v4`, dependency-review-action `v5`). This one
was not checked then.

- [ ] **Step 3: Write `.github/workflows/codeql.yml`**

```yaml
# CodeQL static security analysis for Java.
# Secrets: none. Needs security-events: write to upload results.
# Manual trigger: gh workflow run codeql.yml --ref <branch>
name: CodeQL

on:
  push:
    branches: [main]
  pull_request:
  schedule:
    - cron: '17 6 * * 1'
  workflow_dispatch:

permissions:
  contents: read
  security-events: write

jobs:
  analyze:
    name: Analyze (java-kotlin)
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Check out the repository
        uses: actions/checkout@v7

      - name: Set up Temurin JDK 25 with Maven cache
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'
          cache: maven

      - name: Initialize CodeQL for java-kotlin
        uses: github/codeql-action/init@v4
        with:
          languages: java-kotlin
          build-mode: manual

      - name: Compile for CodeQL (tests skipped; CI runs them)
        run: ./mvnw -B -ntp -DskipTests -Dspotless.check.skip=true -Dspotbugs.skip=true package

      - name: Run CodeQL analysis and upload results
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:java-kotlin
```

- [ ] **Step 4: Write `.github/workflows/dependency-review.yml`**

```yaml
# Fails a PR that adds a dependency with a known vulnerability (moderate or above).
# Secrets: none.
# Manual trigger: not applicable - this action needs a pull_request base/head
# to diff; re-run it from the PR's Checks tab ("Re-run jobs").
name: Dependency review

on:
  pull_request:

permissions:
  contents: read
  pull-requests: write

jobs:
  review:
    name: Dependency review
    runs-on: ubuntu-latest
    steps:
      - name: Check out the repository
        uses: actions/checkout@v7

      - name: Review dependency changes for known vulnerabilities
        uses: actions/dependency-review-action@v5
        with:
          fail-on-severity: moderate
          comment-summary-in-pr: on-failure
```

This workflow can't run on `workflow_dispatch`: the action needs a PR diff. The
header comment says so, which is `cicd-standards`' documented exception path.

- [ ] **Step 5: Write `.github/dependabot.yml`**

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: /
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
    groups:
      spring-boot:
        patterns: ['org.springframework.boot*']
      spring-ai:
        patterns: ['org.springframework.ai*']
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
```

`open-pull-requests-limit: 5` enforces the global rule "never accumulate more
than 5 Dependabot PRs", per ecosystem.

- [ ] **Step 6: Lint the workflows locally**

Run: `docker run --rm -v "$(pwd):/repo" -w /repo rhysd/actionlint:latest -color`
Expected: no output and exit code 0. Fix anything it reports.

- [ ] **Step 7: Add the CI Runbook to `CLAUDE.md`, and strike Task 2's deferred items**

Add this section after "Conventions":

```markdown
## CI Runbook

| Workflow | Runs on | Manual trigger |
|---|---|---|
| `ci.yml` — Build and test | push to `main`, PRs | `gh workflow run ci.yml --ref <branch>` |
| `codeql.yml` — Analyze (java-kotlin) | push to `main`, PRs, Mondays | `gh workflow run codeql.yml --ref <branch>` |
| `dependency-review.yml` | PRs only | Re-run from the PR's Checks tab |

No workflow reads a secret. Never push an empty commit to trigger CI.
```

Strike these in the deferred list: CI workflow, CodeQL, dependency scan,
Dependabot, and the "enforced in CI" half of the linter item. Leave
"branch-protection required status checks" open until Step 9.

- [ ] **Step 8: Commit, push and open the PR so the checks run**

```bash
git add .github/workflows .github/dependabot.yml CLAUDE.md
git commit -m "ci(github): add build, CodeQL, dependency review and Dependabot"
git push -u origin HEAD
```

Open the PR, or push to the already-open PR. Then run `gh pr checks --watch`.
Expected: `Build and test`, `Analyze (java-kotlin)` and `Dependency review` all
pass.

A red check here goes through the `bug` skill before anyone reads logs.

- [ ] **Step 9: Require the checks on `main` (the user must confirm first)**

This changes repository settings, so the controller asks the user in chat first
and runs it only after a clear yes:

```bash
gh api -X PATCH repos/markusluisflores/frontrow/branches/main/protection/required_status_checks \
  -f strict=true -f 'contexts[]=Build and test' -f 'contexts[]=Analyze (java-kotlin)'
gh api repos/markusluisflores/frontrow/branches/main/protection/required_status_checks --jq '.contexts'
```

Expected read-back: `["Build and test","Analyze (java-kotlin)"]`.

Then strike the last deferred item in `CLAUDE.md` and commit:

```bash
git add CLAUDE.md
git commit -m "docs(claude): record required status checks on main"
```

---

### Task 3: Principal-propagation spike (spec §3)

The spike answers one question with evidence: when a bearer-authenticated
request calls an `@McpTool` method over Streamable HTTP, how does the method
learn who the caller is? It tests the two mechanisms the API offers (both
checked in the 2.0.1 sources on 2026-09-18):

- **A.** `SecurityContextHolder`, if the tool runs on the request thread (or the
  context propagates to it).
- **B.** `McpTransportContext`, filled from the servlet request by a
  `contextExtractor` on `WebMvcStreamableServerTransportProvider`. A tool method
  receives it as a parameter; `AbstractMcpToolMethodCallback` injects any
  `McpTransportContext` parameter.

Everything here is test code: a spike-only bearer filter, a spike-only tool, and
a spike-only transport provider. None of it pre-empts Plan 2's real security
chains. Only the MCP starter and the security starter enter `pom.xml`, because
Plans 2 and 3 need them anyway.

**Files:**
- Modify: `pom.xml` (two dependencies)
- Modify: `src/main/resources/application.yml` (MCP server properties)
- Test: `src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java`
- Create: `docs/adr/ADR-004-mcp-principal-propagation.md`; Modify: `docs/adr/README.md`

**Interfaces:**
- Consumes: `TestcontainersConfiguration` (Task 1).
- Produces: ADR-004, naming the mechanism Plan 3's MCP adapter uses to get the
  owner. It also produces a permanent regression test pinning that mechanism.

- [ ] **Step 1: Add the dependencies**

Add these to `<dependencies>` in `pom.xml`. Versions come from Boot and the
Spring AI BOM:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
```

Add this to `application.yml`, merging it under the existing `spring:` key:

```yaml
spring:
  application:
    name: frontrow
  ai:
    mcp:
      server:
        name: frontrow
        protocol: STREAMABLE
        type: SYNC
```

`protocol` and `type` repeat the 2.0.1 defaults (`STREAMABLE`, `SYNC`), so a
future default change can't silently switch the transport (ADR-003).

Run: `./mvnw -q test -Dtest=FrontRowApplicationTests`
Expected: PASS. The context still loads with the MCP server and security on the
classpath.

- [ ] **Step 2: Write the spike test**

`src/test/java/io/github/markusluisflores/frontrow/mcp/PrincipalPropagationSpikeTest.java`:

```java
package io.github.markusluisflores.frontrow.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spike (spec §3): how does an authenticated principal reach an {@code @McpTool} method over Streamable HTTP?
 * Real bearer token, real HTTP, real MCP client. Mechanism A is SecurityContextHolder, mechanism B is
 * McpTransportContext populated by a contextExtractor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, PrincipalPropagationSpikeTest.SpikeConfig.class})
class PrincipalPropagationSpikeTest {

    static final String SPIKE_TOKEN = "spike-only-not-a-secret";
    static final String SPIKE_USER = "alice";
    static final String PRINCIPAL_KEY = "frontrow.principal";
    static final String ANONYMOUS = "<anonymous>";

    @LocalServerPort
    int port;

    @Test
    void mechanismA_securityContextHolderSeesThePrincipal() {
        assertThat(callTool("whoami_security_context")).isEqualTo(SPIKE_USER);
    }

    @Test
    void mechanismB_transportContextCarriesThePrincipal() {
        assertThat(callTool("whoami_transport_context")).isEqualTo(SPIKE_USER);
    }

    @Test
    void unauthenticatedMcpRequestIsRejectedWith401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    private String callTool(String toolName) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        "http://localhost:" + port)
                .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + SPIKE_TOKEN))
                .build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            CallToolResult result = client.callTool(new CallToolRequest(toolName, Map.of()));
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

        @Bean
        SecurityFilterChain spikeMcpChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/mcp", "/mcp/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("AGENT"))
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(
                            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .addFilterBefore(new SpikeBearerFilter(), AnonymousAuthenticationFilter.class);
            return http.build();
        }

        /** Replaces the auto-configured provider (it is @ConditionalOnMissingBean) to add mechanism B. */
        @Bean
        WebMvcStreamableServerTransportProvider spikeTransportProvider(
                @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
                McpServerStreamableHttpProperties properties) {
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

    /** Spike-only: one fixed token maps to one user with the AGENT authority. Plan 2 builds the real chain. */
    static final class SpikeBearerFilter extends OncePerRequestFilter {

        private final RequestAttributeSecurityContextRepository repository =
                new RequestAttributeSecurityContextRepository();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (("Bearer " + SPIKE_TOKEN).equals(request.getHeader(HttpHeaders.AUTHORIZATION))) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        SPIKE_USER, null, List.of(new SimpleGrantedAuthority("AGENT"))));
                SecurityContextHolder.setContext(context);
                repository.saveContext(context, request, response);
            }
            chain.doFilter(request, response);
        }
    }
}
```

The context is saved to the request-attribute repository, as Spring's own
authentication filters do. That makes an async dispatch (Streamable HTTP can
answer as an SSE stream) re-read the same authentication instead of arriving
anonymous. Without it, the spike could fail with a 401 on the dispatch, which
would look like a mechanism failure when it is really the spike's own filter.

- [ ] **Step 3: Run the spike**

Run: `./mvnw -q test -Dtest=PrincipalPropagationSpikeTest`

This is an experiment, so each result is data. Record, for each of the three
tests, PASS or FAIL and the exact assertion message. A FAIL on test A or B
usually reads `expected: "alice" but was: "<anonymous>"`.

`unauthenticatedMcpRequestIsRejectedWith401` must PASS. If it fails, the spike
harness is wrong, not the mechanism: fix the chain before reading A or B.

- [ ] **Step 4: Apply the decision rule**

| A | B | Decision |
|---|---|---|
| PASS | PASS | **Mechanism B.** `McpTransportContext` is the transport's own carrier for request data, and it doesn't depend on which thread runs the tool. SecurityContextHolder working today is a threading detail a Spring AI upgrade could change silently. |
| FAIL | PASS | **Mechanism B.** It is the only one that works. |
| PASS | FAIL | **Mechanism A**, and record why B failed (for example, `request.principal()` was empty). |
| FAIL | FAIL | **BLOCKED.** Stop and report both messages to the controller. This is a replacement-design point for the user, not something to patch here. |

Then make the suite green without deleting evidence. Rename the losing test so
its name states the observed behaviour, and assert that behaviour. For example,
if A fails:

```java
    @Test
    void mechanismA_securityContextHolderIsEmptyInToolMethods() {
        assertThat(callTool("whoami_security_context")).isEqualTo(ANONYMOUS);
    }
```

That turns it into a tripwire. If a Spring AI upgrade changes the threading,
this test fails and the choice in ADR-004 gets revisited. If both pass (row 1),
leave both tests as they are.

Run: `./mvnw verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Record ADR-004**

Create `docs/adr/ADR-004-mcp-principal-propagation.md`. Fill it from the results
of Steps 3 and 4 (exact outcomes, never "presumably"). Use this structure:

```markdown
# ADR-004: MCP tools get the caller from <McpTransportContext | SecurityContextHolder>

**Date:** <date of the spike run>
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

**Chosen: <A or B>**, because <the Step 4 row that applied, with the observed
results: A = <PASS/FAIL: message>, B = <PASS/FAIL: message>>.

### Consequences

* ✅ <Plan 3's adapter reads the owner via the chosen mechanism and passes it to
  the application service as a plain `String`, so the domain core stays free of
  MCP types.>
* ⚠️ <If B: the app defines its own `WebMvcStreamableServerTransportProvider`
  bean, replacing the auto-configured one, so it must track the auto-config's
  settings (endpoint, keep-alive, disallow-delete) on Spring AI upgrades.>
* ⚠️ The spike test stays in the suite as a tripwire: a Spring AI upgrade that
  changes tool threading fails it.
```

Every `<...>` above is filled from the run before committing. None may remain.
Run: `grep -n '<' docs/adr/ADR-004-mcp-principal-propagation.md`
Expected: no output.

Add this row to `docs/adr/README.md`:

```markdown
| [ADR-004](ADR-004-mcp-principal-propagation.md) | MCP tools get the caller from <mechanism> | Accepted | <date> |
```

Here too, both placeholders are filled from the run.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/java/io/github/markusluisflores/frontrow/mcp docs/adr
git commit -m "test(mcp): prove how the caller principal reaches MCP tool methods"
```

---

### Task 4: Flyway V1 schema and constraint tests (spec §4, §5; ADR-001)

**Files:**
- Create: `src/main/resources/db/migration/V1__core_schema.sql`
- Test: `src/test/java/io/github/markusluisflores/frontrow/schema/SchemaFixtures.java`
- Test: `src/test/java/io/github/markusluisflores/frontrow/schema/SchemaConstraintsTest.java`

**Interfaces:**
- Consumes: `TestcontainersConfiguration` (Task 1).
- Produces: the tables and constraint names Plan 2's services and error
  mapping depend on:
  - `uq_claimed_seat`, which maps to `seat_taken`
  - `uq_sold_once`
  - `uq_order_hold_group`
  - `pk_hold_request`, the idempotency key
  - `fk_event_seat_event_venue`, `fk_event_seat_seat_venue`
  - `ck_*` status and value checks

  `SchemaFixtures` exposes these helpers:
  - `long venue(String name)`
  - `long seat(long venueId, String section, String row, int number)`
  - `long event(long venueId, String status)`
  - `long eventSeat(long eventId, long seatId, long venueId)`
  - `void hold(long eventSeatId, UUID groupId, String owner, String status)`
  - `long order(long eventId, UUID groupId, String owner)`
  - `void orderLine(long orderId, long eventSeatId)`
  - `static String constraintName(Throwable t)` and
    `static String sqlState(Throwable t)`, which walk the cause chain (and
    `BatchUpdateException.getNextException()`) to the `PSQLException`, per
    spec §5. Plan 2's error translator reuses this walk.

**Plan-level choices, made here because the spec leaves them open:**
- Surrogate ids are `bigint GENERATED ALWAYS AS IDENTITY`. This is consistent
  with the numeric `seat_ids` in the spec §6 error example.
- `hold_group_id` is a `uuid`, generated by the application.
- `hold_request.hold_group_id` and `response_json` are **nullable**. The row is
  inserted first, with only `(owner, idempotency_key, request_hash)`, and
  completed in step 5 (spec §5, *Hold creation* steps 1 and 5).
- The CHECK constraints below go beyond spec §5. Each is listed in
  `SchemaConstraintsTest` so a reviewer can see and reject any of them
  individually. The same goes for `uq_seat_position`.

- [ ] **Step 1: Write the fixtures**

`src/test/java/io/github/markusluisflores/frontrow/schema/SchemaFixtures.java`:

```java
package io.github.markusluisflores.frontrow.schema;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Row inserts for schema tests. Times are fixed, never the database clock (spec §4). */
final class SchemaFixtures {

    static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private final JdbcTemplate jdbc;

    SchemaFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long venue(String name) {
        return jdbc.queryForObject("INSERT INTO venue (name) VALUES (?) RETURNING id", Long.class, name);
    }

    long seat(long venueId, String section, String row, int number) {
        return jdbc.queryForObject(
                "INSERT INTO seat (venue_id, section, row_label, seat_number) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                venueId,
                section,
                row,
                number);
    }

    long event(long venueId, String status) {
        return jdbc.queryForObject(
                """
                INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                VALUES (?, 'Test event', ?, ?, ?, ?, 'CAD') RETURNING id""",
                Long.class,
                venueId,
                ts(NOW.plus(30, ChronoUnit.DAYS)),
                ts(NOW.minus(1, ChronoUnit.DAYS)),
                ts(NOW.plus(29, ChronoUnit.DAYS)),
                status);
    }

    long eventSeat(long eventId, long seatId, long venueId) {
        return jdbc.queryForObject(
                "INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents) VALUES (?, ?, ?, 5000) RETURNING id",
                Long.class,
                eventId,
                seatId,
                venueId);
    }

    void hold(long eventSeatId, UUID groupId, String owner, String status) {
        jdbc.update(
                """
                INSERT INTO seat_hold (event_seat_id, hold_group_id, owner, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                eventSeatId,
                groupId,
                owner,
                status,
                ts(NOW.plus(10, ChronoUnit.MINUTES)),
                ts(NOW));
    }

    long order(long eventId, UUID groupId, String owner) {
        return jdbc.queryForObject(
                """
                INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                VALUES (?, ?, ?, 'CONFIRMED', 5000, 'CAD', ?) RETURNING id""",
                Long.class,
                eventId,
                groupId,
                owner,
                ts(NOW));
    }

    void orderLine(long orderId, long eventSeatId) {
        jdbc.update(
                "INSERT INTO order_line (order_id, event_seat_id, price_cents) VALUES (?, ?, 5000)",
                orderId,
                eventSeatId);
    }

    static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    /** Constraint name from the driver's structured error, never from message text (spec §5). */
    static String constraintName(Throwable thrown) {
        PSQLException psql = findPsqlException(thrown);
        return psql == null || psql.getServerErrorMessage() == null
                ? null
                : psql.getServerErrorMessage().getConstraint();
    }

    static String sqlState(Throwable thrown) {
        PSQLException psql = findPsqlException(thrown);
        return psql == null ? null : psql.getSQLState();
    }

    private static PSQLException findPsqlException(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                return psql;
            }
            if (t instanceof BatchUpdateException batch) {
                for (SQLException next = batch.getNextException(); next != null; next = next.getNextException()) {
                    if (next instanceof PSQLException psql) {
                        return psql;
                    }
                }
            }
        }
        return null;
    }
}
```

`org.postgresql` is a `runtime` dependency from Task 1, so code can't compile
against `PSQLException` yet. In `pom.xml`, delete the
`<scope>runtime</scope>` line from the `postgresql` dependency, which makes it
`compile` scope. Don't add a second declaration: Maven warns on duplicates.
`compile` is the scope Plan 2 needs anyway, because its error translator reads
`PSQLException` in main code (spec §5).

- [ ] **Step 2: Write the failing tests**

`src/test/java/io/github/markusluisflores/frontrow/schema/SchemaConstraintsTest.java`:

```java
package io.github.markusluisflores.frontrow.schema;

import static io.github.markusluisflores.frontrow.schema.SchemaFixtures.constraintName;
import static io.github.markusluisflores.frontrow.schema.SchemaFixtures.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.markusluisflores.frontrow.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every schema constraint gets a violating-row test (spec §12). Each test does its setup, then exactly one violating
 * statement last, because Postgres aborts the transaction on the first error. Every test rolls back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintsTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    @Autowired
    JdbcTemplate jdbc;

    SchemaFixtures db;
    long venueId;
    long eventId;
    long eventSeatId;

    @BeforeEach
    void seed() {
        db = new SchemaFixtures(jdbc);
        venueId = db.venue("Main Hall");
        long seatId = db.seat(venueId, "Floor", "A", 1);
        eventId = db.event(venueId, "ON_SALE");
        eventSeatId = db.eventSeat(eventId, seatId, venueId);
    }

    // --- uq_claimed_seat (ADR-001): at most one ACTIVE or CONVERTED hold per seat ---

    @Test
    void secondActiveHoldOnASeatIsRejected() {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", "ACTIVE");
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "ACTIVE"), UNIQUE_VIOLATION, "uq_claimed_seat");
    }

    @Test
    void soldSeatCannotBeHeldAgain() {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", "CONVERTED");
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "ACTIVE"), UNIQUE_VIOLATION, "uq_claimed_seat");
    }

    @Test
    void activeHoldBlocksConversionOfAnotherHold() {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", "ACTIVE");
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "CONVERTED"),
                UNIQUE_VIOLATION,
                "uq_claimed_seat");
    }

    @ParameterizedTest
    @CsvSource({"EXPIRED", "RELEASED"})
    void endedHoldsDoNotBlockANewHold(String endedStatus) {
        db.hold(eventSeatId, UUID.randomUUID(), "alice", endedStatus);
        assertThatCode(() -> db.hold(eventSeatId, UUID.randomUUID(), "bob", "ACTIVE"))
                .doesNotThrowAnyException();
    }

    // --- uq_sold_once: a seat is on at most one order line ---

    @Test
    void seatCannotBeSoldOnTwoOrderLines() {
        long first = db.order(eventId, UUID.randomUUID(), "alice");
        long second = db.order(eventId, UUID.randomUUID(), "bob");
        db.orderLine(first, eventSeatId);
        assertViolation(() -> db.orderLine(second, eventSeatId), UNIQUE_VIOLATION, "uq_sold_once");
    }

    // --- uq_order_hold_group: one order per hold group ---

    @Test
    void holdGroupCannotHaveTwoOrders() {
        UUID group = UUID.randomUUID();
        db.order(eventId, group, "alice");
        assertViolation(() -> db.order(eventId, group, "alice"), UNIQUE_VIOLATION, "uq_order_hold_group");
    }

    // --- composite FKs: an event only offers seats from its own venue ---

    @Test
    void eventCannotOfferASeatFromAnotherVenue() {
        long otherVenue = db.venue("Annex");
        long foreignSeat = db.seat(otherVenue, "Floor", "A", 1);
        assertViolation(
                () -> db.eventSeat(eventId, foreignSeat, venueId), FK_VIOLATION, "fk_event_seat_seat_venue");
    }

    @Test
    void eventSeatCannotClaimTheWrongVenueForItsEvent() {
        long otherVenue = db.venue("Annex");
        long otherSeat = db.seat(otherVenue, "Floor", "B", 1);
        assertViolation(
                () -> db.eventSeat(eventId, otherSeat, otherVenue), FK_VIOLATION, "fk_event_seat_event_venue");
    }

    @Test
    void eventCannotOfferTheSameSeatTwice() {
        long seatId = jdbc.queryForObject("SELECT seat_id FROM event_seat WHERE id = ?", Long.class, eventSeatId);
        assertViolation(() -> db.eventSeat(eventId, seatId, venueId), UNIQUE_VIOLATION, "uq_event_seat");
    }

    // --- pk_hold_request: an idempotency key is unique per owner ---

    @Test
    void idempotencyKeyIsUniquePerOwner() {
        insertHoldRequest("alice", "key-1");
        assertViolation(() -> insertHoldRequest("alice", "key-1"), UNIQUE_VIOLATION, "pk_hold_request");
    }

    @Test
    void sameIdempotencyKeyIsAllowedForDifferentOwners() {
        insertHoldRequest("alice", "key-1");
        assertThatCode(() -> insertHoldRequest("bob", "key-1")).doesNotThrowAnyException();
    }

    // --- additions beyond spec §5 (plan-level): value checks and seat position ---

    @Test
    void seatPositionIsUniqueWithinAVenue() {
        assertViolation(() -> db.seat(venueId, "Floor", "A", 1), UNIQUE_VIOLATION, "uq_seat_position");
    }

    @Test
    void unknownHoldStatusIsRejected() {
        assertViolation(
                () -> db.hold(eventSeatId, UUID.randomUUID(), "alice", "PENDING"),
                CHECK_VIOLATION,
                "ck_seat_hold_status");
    }

    @Test
    void unknownEventStatusIsRejected() {
        assertViolation(() -> db.event(venueId, "SOLD_OUT"), CHECK_VIOLATION, "ck_event_status");
    }

    @Test
    void salesWindowMustOpenBeforeItCloses() {
        assertViolation(
                () -> jdbc.update(
                        """
                        INSERT INTO event (venue_id, name, starts_at, sales_open_at, sales_close_at, status, currency)
                        VALUES (?, 'Backwards', ?, ?, ?, 'DRAFT', 'CAD')""",
                        venueId,
                        SchemaFixtures.ts(SchemaFixtures.NOW),
                        SchemaFixtures.ts(SchemaFixtures.NOW),
                        SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_event_sales_window");
    }

    @Test
    void negativePriceIsRejected() {
        long seatId = db.seat(venueId, "Floor", "A", 2);
        assertViolation(
                () -> jdbc.update(
                        "INSERT INTO event_seat (event_id, seat_id, venue_id, price_cents) VALUES (?, ?, ?, -1)",
                        eventId,
                        seatId,
                        venueId),
                CHECK_VIOLATION,
                "ck_event_seat_price");
    }

    @Test
    void currencyMustBeAThreeLetterCode() {
        assertViolation(
                () -> jdbc.update(
                        """
                        INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                        VALUES (?, ?, 'alice', 'CONFIRMED', 0, 'cad', ?)""",
                        eventId,
                        UUID.randomUUID(),
                        SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_ticket_order_currency");
    }

    @Test
    void unknownOrderStatusIsRejected() {
        assertViolation(
                () -> jdbc.update(
                        """
                        INSERT INTO ticket_order (event_id, hold_group_id, owner, status, total_cents, currency, created_at)
                        VALUES (?, ?, 'alice', 'REFUNDED', 0, 'CAD', ?)""",
                        eventId,
                        UUID.randomUUID(),
                        SchemaFixtures.ts(SchemaFixtures.NOW)),
                CHECK_VIOLATION,
                "ck_ticket_order_status");
    }

    private void insertHoldRequest(String owner, String key) {
        jdbc.update(
                "INSERT INTO hold_request (owner, idempotency_key, request_hash, created_at) VALUES (?, ?, 'h', ?)",
                owner,
                key,
                SchemaFixtures.ts(SchemaFixtures.NOW));
    }

    private static void assertViolation(Executable statement, String expectedSqlState, String expectedConstraint) {
        Throwable thrown = catchThrowable(statement::execute);
        assertThat(thrown).as("expected %s to reject the row", expectedConstraint).isNotNull();
        assertThat(sqlState(thrown)).as("SQLState").isEqualTo(expectedSqlState);
        assertThat(constraintName(thrown)).as("constraint name").isEqualTo(expectedConstraint);
    }
}
```

`JdbcTemplate` inserts one row per call, so no JDBC batching is involved. The
`BatchUpdateException` branch in `SchemaFixtures` is there for Plan 2's reuse,
and Plan 2's service tests exercise it.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=SchemaConstraintsTest`
Expected: FAIL. Every test errors in `@BeforeEach` with
`relation "venue" does not exist`.

- [ ] **Step 4: Write the migration**

`src/main/resources/db/migration/V1__core_schema.sql`:

```sql
-- FrontRow core schema (spec §4, §5; ADR-001).
-- No column defaults to now(): time comes from the application's injected Clock.

CREATE TABLE venue (
    id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL
);

CREATE TABLE seat (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id    bigint NOT NULL REFERENCES venue (id),
    section     text   NOT NULL,
    row_label   text   NOT NULL,
    seat_number int    NOT NULL,
    CONSTRAINT uq_seat_position UNIQUE (venue_id, section, row_label, seat_number),
    CONSTRAINT uq_seat_venue UNIQUE (id, venue_id)
);

CREATE TABLE event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id       bigint      NOT NULL REFERENCES venue (id),
    name           text        NOT NULL,
    starts_at      timestamptz NOT NULL,
    sales_open_at  timestamptz NOT NULL,
    sales_close_at timestamptz NOT NULL,
    status         text        NOT NULL,
    currency       char(3)     NOT NULL,
    CONSTRAINT uq_event_venue UNIQUE (id, venue_id),
    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT', 'ON_SALE', 'CANCELLED')),
    CONSTRAINT ck_event_sales_window CHECK (sales_open_at < sales_close_at),
    CONSTRAINT ck_event_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE event_seat (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    bigint NOT NULL,
    seat_id     bigint NOT NULL,
    venue_id    bigint NOT NULL,
    price_cents bigint NOT NULL,
    CONSTRAINT uq_event_seat UNIQUE (event_id, seat_id),
    CONSTRAINT fk_event_seat_event_venue FOREIGN KEY (event_id, venue_id) REFERENCES event (id, venue_id),
    CONSTRAINT fk_event_seat_seat_venue FOREIGN KEY (seat_id, venue_id) REFERENCES seat (id, venue_id),
    CONSTRAINT ck_event_seat_price CHECK (price_cents >= 0)
);

CREATE TABLE seat_hold (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_seat_id bigint      NOT NULL REFERENCES event_seat (id),
    hold_group_id uuid        NOT NULL,
    owner         text        NOT NULL,
    status        text        NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT ck_seat_hold_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'RELEASED', 'CONVERTED'))
);

-- The invariant (ADR-001): a seat is claimed by at most one hold that is live-pending or sold.
CREATE UNIQUE INDEX uq_claimed_seat
    ON seat_hold (event_seat_id) WHERE status IN ('ACTIVE', 'CONVERTED');

-- Inserted first with (owner, idempotency_key, request_hash); completed at commit (spec §5).
CREATE TABLE hold_request (
    owner           text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    hold_group_id   uuid,
    response_json   jsonb,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_hold_request PRIMARY KEY (owner, idempotency_key)
);

CREATE TABLE ticket_order (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      bigint      NOT NULL REFERENCES event (id),
    hold_group_id uuid        NOT NULL,
    owner         text        NOT NULL,
    status        text        NOT NULL,
    total_cents   bigint      NOT NULL,
    currency      char(3)     NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT uq_order_hold_group UNIQUE (hold_group_id),
    CONSTRAINT ck_ticket_order_status CHECK (status IN ('CONFIRMED')),
    CONSTRAINT ck_ticket_order_total CHECK (total_cents >= 0),
    CONSTRAINT ck_ticket_order_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE order_line (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      bigint NOT NULL REFERENCES ticket_order (id),
    event_seat_id bigint NOT NULL REFERENCES event_seat (id),
    price_cents   bigint NOT NULL,
    -- Defence in depth (ADR-001): a seat appears on at most one order line.
    CONSTRAINT uq_sold_once UNIQUE (event_seat_id),
    CONSTRAINT ck_order_line_price CHECK (price_cents >= 0)
);
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw spotless:apply` and then `./mvnw verify`
Expected: `BUILD SUCCESS`. `SchemaConstraintsTest` runs 19 tests (18 methods;
`endedHoldsDoNotBlockANewHold` runs twice) with 0 failures. Task 1's and
Task 3's tests still pass.

- [ ] **Step 6: Prove the central test is load-bearing (ADR-001's own claim)**

Temporarily delete the `CREATE UNIQUE INDEX uq_claimed_seat …` statement from
`V1__core_schema.sql`.
Run: `./mvnw -q test -Dtest=SchemaConstraintsTest`
Expected: FAIL. `secondActiveHoldOnASeatIsRejected`, `soldSeatCannotBeHeldAgain`
and `activeHoldBlocksConversionOfAnotherHold` fail with "expected
uq_claimed_seat to reject the row".

Restore the statement (check with `git diff --exit-code src/main/resources/db`),
then run `./mvnw verify` and expect `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/db src/test/java/io/github/markusluisflores/frontrow/schema
git commit -m "feat(schema): add V1 schema with the seat-claim invariant constraints"
```

---

## Spec coverage (Plan 1's share)

| Spec requirement | Plan 1 task | Owned by, if not here |
|---|---|---|
| §10 versions re-checked and pinned at scaffold | Global Constraints, Task 1 | |
| `CLAUDE.md` deferred items: wrapper, test framework, `./mvnw verify` | Task 1 | |
| `CLAUDE.md` deferred items: Spotless plus per-file hook | Task 1 (Steps 8–9) | |
| `CLAUDE.md` deferred items: static analysis; compile as the type gate | Task 1 (SpotBugs, `-Werror`) | |
| `CLAUDE.md` deferred items: CI, CodeQL, dependency scan, Dependabot | Task 2 | |
| `CLAUDE.md` deferred items: branch-protection required checks | Task 2 (Step 9, user-confirmed) | |
| §3 scaffold spike: principal reaches a tool method, real token, real HTTP | Task 3 | |
| §4 tables, `timestamptz`, no DB clock | Task 4 | |
| §5 `uq_claimed_seat` over `ACTIVE`+`CONVERTED` | Task 4 | |
| §5 `uq_sold_once`, composite venue FKs, one order per group, `hold_request` PK | Task 4 | |
| §5 constraint name from the driver's structured error, via the cause chain | Task 4 (`SchemaFixtures`) | Plan 2 reuses it in the translator |
| §12 every §5 constraint has a violating-row test | Task 4 | |
| §12 hold-race test fails if `uq_claimed_seat` is removed | Task 4 Step 6 (schema level) | Plan 2 (the concurrency test) |
| §8 migrations apply cleanly | Tasks 1 and 4 (Flyway runs on every context start) | |
| §5 locking, hold/confirm/release/cancel, sweeper, concurrency tests 1–7 | — | Plan 2 |
| §3 security chains, the explicit 401 bearer filter, §6 REST surface | — | Plan 2 |
| §6 MCP tools, guardrails, error contracts; §7 evidence artifacts | — | Plan 3 (after ADR-004) |
| §12 `docker compose`, seed data, README | — | Plan 3 |

## Self-review record

- **Placeholders:** the only `<...>` are in Task 3 Step 5's ADR-004 template.
  They are filled from the spike's own output, and Step 5 greps to prove none
  remain.
- **Type consistency:** checked across all four tasks:
  - `TestcontainersConfiguration`, `SchemaFixtures` and their method names
  - the constraint names in V1, the tests and the Interfaces block
  - the spike's `PRINCIPAL_KEY` and `ANONYMOUS`
- **Sibling reuse:** there's no existing code, so nothing to reuse yet.
  `SchemaFixtures.constraintName` is built for Plan 2 to reuse, and says so.
- **Tooling:**
  - All five literal commit subjects pass `.githooks/commit-msg`; see the PR
    for the run.
  - Java and SQL snippets can't be run through Spotless or compiled until JDK 25
    exists. Every task therefore runs `spotless:apply` and then `verify` before
    committing, so the executor's run is the first real check. The APIs were
    checked against the 2.0.1 sources, so they aren't guessed.
- **Crossing check:** the spike crosses mechanism (A/B) with authentication
  (token/none). The unauthenticated case must pass before A or B means anything.
  Otherwise a harness 401 on async dispatch would read as a mechanism failure.
  Those two are the most likely to interact.
