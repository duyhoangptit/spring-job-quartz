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
