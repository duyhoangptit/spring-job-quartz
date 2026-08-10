# system-report-job

Dynamic Quartz job/trigger management service. Clean Architecture: `domain` → `usecase` → `infrastructure`.
See `docs/superpowers/specs/2026-08-09-system-report-job-v2-design.md` (repo root) for the full design,
and `docs/superpowers/plans/2026-08-10-system-report-job-implementation.md` for the implementation plan.

- Base package: `com.corebanking.systemreportjob`
- Java 21, Spring Boot 4.1.0
- `domain/` must never import Spring/JPA/Quartz types.
- Schema changes go through Flyway (`src/main/resources/db/migration/`) — never `ddl-auto`.
