# Backend Evidence Lab

This repository is a small, evidence-driven laboratory for learning and demonstrating production backend engineering. Each task follows:

```text
problem -> test -> minimal implementation -> verification -> evidence
```

TASK-001 bootstraps Spring Boot with real PostgreSQL, Flyway, Actuator, and Testcontainers. TASK-002 adds explicit Spring JDBC event persistence. TASK-003 gives PostgreSQL ownership of idempotency-key uniqueness, TASK-004 demonstrates the concurrent check-then-insert race, and TASK-005 adds graceful CREATED/REPLAYED/conflict application semantics with targeted PostgreSQL `ON CONFLICT`. There is still no HTTP API or webhook delivery behavior.

## Prerequisites

- Java 21
- Docker Desktop with a running Linux engine
- Git

Global Gradle is not required. Use the checked-in Gradle Wrapper so the build uses the version recorded in `gradle/wrapper/gradle-wrapper.properties`.

## Run PostgreSQL locally

```powershell
docker compose up -d
docker compose ps
```

The only Compose service is PostgreSQL. The application defaults are:

| Setting | Value |
| --- | --- |
| JDBC URL | `jdbc:postgresql://localhost:5432/evidence_lab` |
| Database | `evidence_lab` |
| Username | `evidence_lab` |
| Password | `evidence_lab` |

Override the datasource with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` when needed.

## Run the application

```powershell
.\gradlew.bat bootRun
```

Flyway applies migrations at startup. The local health endpoint is:

```text
http://localhost:8080/actuator/health
```

Only the Actuator `health` endpoint is exposed over HTTP, with local health details enabled so the database health can be inspected.

## Verify

The integration suites start real PostgreSQL `16-alpine` Testcontainers and override the application datasource with each container's JDBC connection. They verify schema/migrations, raw persistence and uniqueness, the concurrent race observed in TASK-004, and TASK-005 create/replay/conflict semantics including repeated two-caller races.

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Docker smoke checks can be run with:

```powershell
docker compose config
docker compose exec -T postgres pg_isready -U evidence_lab -d evidence_lab
```

The reproducible records for TASK-001 through TASK-005 are in [evidence/](evidence/). The current design is described in [docs/architecture.md](docs/architecture.md), and the database decision is recorded in [adr/0001-start-with-postgres.md](adr/0001-start-with-postgres.md).
