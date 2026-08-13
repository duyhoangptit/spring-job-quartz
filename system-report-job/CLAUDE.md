# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Dynamic Quartz job/trigger management service — a Clean Architecture rewrite of the legacy
`system-report-job` module (`vn.tiger:microservice-java` monorepo, Spring Boot 3.4.5) on
**Spring Boot 4.1 / Java 21**. Users create a `Task` (schedule) that references a `JobDefinition`
("what to run", dispatched by `jobType` through a `JobAction` registry — e.g. an outbound HTTP
call or in-process logic); Quartz fires the trigger and the configured action executes.

Design docs (repo root, one level above this module):
- `docs/superpowers/specs/2026-08-09-system-report-job-v2-design.md` — full design spec (Vietnamese)
- `docs/superpowers/plans/2026-08-10-system-report-job-implementation.md` — implementation plan

## Commands

Run from the `system-report-job/` directory (or pass `-f system-report-job/pom.xml` from the repo root).

```bash
# Start Postgres for local dev
docker run -d --name system-report-job-db -e POSTGRES_DB=db_system_report_job \
  -e POSTGRES_PASSWORD=root -p 5432:5432 postgres:16-alpine

mvn spring-boot:run                    # Swagger UI: http://localhost:8080/system-report-job/swagger-ui.html
mvn test                               # full test suite — Docker must be running (Testcontainers)
mvn test -Dtest=JobDefinitionServiceTest                  # single test class
mvn test -Dtest=JobDefinitionServiceTest#createSavesNewJobDefinition  # single test method
mvn spotless:apply                     # auto-format (palantir-java-format); runs automatically on `compile`
mvn spotless:check                     # verify formatting without modifying files
```

There's no separate lint step — `spotless:check` (bound to the `compile` phase) is the formatting
gate; `mvn test`/`mvn package` will fail the build on unformatted code.

Unit tests (`usecase/service/*`) run with plain Mockito, no Spring context. Everything under
`infrastructure/persistence`, `infrastructure/scheduler`, and the `e2e` package spins up
Testcontainers PostgreSQL, so those require Docker locally and in CI.

## Architecture

Clean Architecture, three layers, one-directional dependency rule:
`infrastructure → usecase → domain`. **`domain/` must never import Spring, JPA, or Quartz types** —
it's plain Java records/enums. Package root: `com.corebanking.systemreportjob`.

- **`domain/model`** — `ScheduledTask` (the "when"), `JobDefinition` (the "what", `jobType` +
  `expression` JSON), `TriggerDefinition` (sealed interface: `Cron` / `Simple` / `CalendarInterval`
  / `DailyTimeInterval` — a Java 21 `switch` over this is exhaustive at compile time), `TriggerState`,
  `TaskExecutionRecord`, `TaskDetail` (read-model joining task + definition + live trigger state),
  `PageResult<T>`. Validation lives in compact constructors, not Bean Validation.
- **`domain/exception`** — `ErrorCode` enum (stable `SRJ-xxx` codes + i18n message keys) and
  `BusinessException`. `GlobalExceptionHandler` (`infrastructure/common`) maps `ErrorCode` →
  `HttpStatus` via `statusFor(ErrorCode)` — **new error codes must be added to that `switch` too**
  (it's exhaustive, so the compiler enforces it).
- **`usecase/ports/in`** — the four driving-adapter contracts: `TaskManagementUseCase`,
  `JobDefinitionUseCase`, `TaskHistoryQueryUseCase`, and `ExecuteScheduledJobUseCase` (the one
  Quartz itself calls back into — see below).
- **`usecase/ports/out`** — driven-adapter contracts implemented by infrastructure:
  `TaskRepositoryPort`, `JobDefinitionRepositoryPort`, `TaskExecutionHistoryRepositoryPort`,
  `SchedulerGatewayPort`, `JobActionExecutorPort`.
- **`usecase/service`** — one implementation per in-port: `TaskOrchestrator`, `JobDefinitionService`,
  `TaskHistoryQueryService`, `JobExecutionOrchestrator`. `JobExecutionOrchestrator` is deliberately
  separate from `TaskOrchestrator` (task lifecycle vs. "Quartz just fired, go execute").
- **`infrastructure/scheduler`** — `ScheduledJobExecutor` is the **only** `org.quartz.Job`
  implementation in the system; every trigger points at it, with `taskId` in the `JobDataMap`. It
  calls straight into `ExecuteScheduledJobUseCase` — it must never load repositories or the
  `JobAction` registry directly, even though Quartz is "calling back into" application code.
  `QuartzSchedulerGatewayAdapter` implements `SchedulerGatewayPort`. `QuartzTriggerFactory` turns a
  `TriggerDefinition` into an `org.quartz.Trigger` via exhaustive `switch` pattern matching.
  `QuartzJobListener`/`QuartzJobTriggerListener` are global listeners that persist
  `TaskExecutionHistory` via the port (not JPA directly).
- **`infrastructure/jobactions`** — Strategy pattern keyed by `jobType`. `JobActionRegistry`
  implements `JobActionExecutorPort`, injects `List<JobAction>`, and dispatches via
  `JobAction.matches(jobType)`. `HttpCallJobAction` (`jobType = "HTTP_CALL"`) parses
  `JobDefinition.expression()` as JSON and calls out via `RestClient` on a virtual-thread executor
  (`VirtualThreadConfig`) with explicit connect/read timeouts (`app.http-client.*` in
  `application.yml`) so a slow downstream can't pin a Quartz worker thread. Adding a new job type
  means adding a new `JobAction` bean — no registry code changes needed.
- **`infrastructure/persistence`** — `entity/` (JPA entities, soft-delete via `is_deleted` +
  `@SQLRestriction` so deleted rows never leak back through reads), `repository/` (Spring Data JPA),
  `adapter/` (one `*RepositoryAdapter implements *RepositoryPort` per aggregate, mapping entity ⇄
  domain record — domain records never touch JPA).
- **`infrastructure/web`** — controllers only call `usecase/ports/in/*`, never a repository or
  Quartz directly; DTO ⇄ domain mapping happens in the controller so domain models never cross the
  HTTP boundary.

## Data & migrations

Postgres via Flyway — **schema changes always go through `src/main/resources/db/migration/`, never
`ddl-auto`** (`spring.jpa.hibernate.ddl-auto` is pinned to `validate`). This includes the Quartz
`QRTZ_*` tables (`V1__create_quartz_tables.sql`): `spring.quartz.jdbc.initialize-schema` is `never`
so Quartz's own auto-init never runs — Flyway is the single source of truth for every table.

## Known deliberate design choices (vs. the legacy module)

These read like they might be bugs/gaps but are intentional — see spec §8 before "fixing" them:

- No reflection-based class loading from DB data (the old `TaskUtil.getClazz(...)`) — job dispatch
  is exclusively through the `JobAction` registry keyed by `jobType`.
- `TriggerTypeEnum.CUSTOM_TRIGGER` from the legacy module was dead code and was intentionally not
  ported; `TriggerDefinition` only models the four trigger kinds that actually work. Add a new
  sealed-interface case if a real custom-trigger need shows up.
- `management.endpoints.web.exposure.include` is deliberately narrow (`health,info,prometheus`),
  not `*`.
- Auth is out of scope here (assumed handled by a gateway/other service) — Swagger
  `@SecurityRequirement` annotations are documentation only, not enforcement.