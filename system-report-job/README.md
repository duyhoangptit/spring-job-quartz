# system-report-job

Dynamic Quartz job/trigger management service — Clean Architecture rewrite of the legacy
`system-report-job` module (Spring Boot 3.4.5, `vn.tiger:microservice-java` monorepo) on
**Spring Boot 4.1 / Java 21**.

## Run locally

```bash
docker run -d --name system-report-job-db -e POSTGRES_DB=db_system_report_job \
  -e POSTGRES_PASSWORD=root -p 5432:5432 postgres:16-alpine

mvn -f system-report-job/pom.xml spring-boot:run
```

Swagger UI: http://localhost:8080/system-report-job/swagger-ui.html

## Architecture

See `docs/superpowers/specs/2026-08-09-system-report-job-v2-design.md` (design spec) and
`docs/superpowers/plans/2026-08-10-system-report-job-implementation.md` (implementation plan),
both in this repo's root.

- `domain/` — framework-free models and exceptions
- `usecase/` — ports (in/out) + services
- `infrastructure/` — Quartz scheduler, JPA persistence, REST controllers, job-action strategies

## Testing

```bash
mvn -f system-report-job/pom.xml test
```

Persistence/scheduler/end-to-end tests use Testcontainers — Docker must be running locally.

## Sample data: 1M mock users + Spring Batch export job

Seed the `users` table with 1,000,000 mock rows (local dev DB only — **not** run by tests or on
startup):

```bash
psql "postgresql://tigerpro:secret@localhost:5432/db_system_report_job" \
  -f src/main/resources/db/seed/seed_users_1m.sql
```

Create and start a job that exports `users` into `user_exports` in chunks of 1000 via Spring Batch:

```bash
curl -X POST localhost:8080/system-report-job/api/job-definitions \
  -H 'Content-Type: application/json' \
  -d '{"jobType":"EXPORT_USERS","expression":"{}"}'
# -> note the returned "id" as JOB_DEFINITION_ID

curl -X POST localhost:8080/system-report-job/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"export-users-hourly","group":"reports","jobDefinitionId":"JOB_DEFINITION_ID",
       "triggerType":"CRON","cronExpression":"0 0 * * * ?"}'
# -> note the returned "id" as TASK_ID

curl -X POST localhost:8080/system-report-job/api/tasks/start/TASK_ID
```

Watch progress: `SELECT COUNT(*) FROM user_exports;` grows in chunks of 1000 as the job runs.
