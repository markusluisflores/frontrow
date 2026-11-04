# FrontRow

> **Status:** the REST API, its Swagger UI and the local `docker compose` run are built. The MCP tools and the
> evidence artifacts are the last plan of Phase 1, which is the whole project (spec revision 2.7).

An event ticketing and seat-reservation service in Spring Boot, exposed through
two inbound adapters over one domain core: a REST API for humans, and an MCP
server for agents.

Two things it is built to demonstrate:

1. **Correctness under concurrency** — double-booking is prevented by a
   Postgres partial unique index rather than by application logic, and that is
   proven by a test where N threads race for the same seat and exactly one
   wins. Run against real Postgres via Testcontainers.
2. **Agent-facing API design** — thin, single-responsibility MCP tools with
   typed schemas, structured error contracts, and a stated threat model. The
   central decision: **an agent can hold seats; only a human can buy them.**
   There is no `confirm_order` tool.

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
