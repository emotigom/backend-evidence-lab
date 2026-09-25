# Backend Evidence Lab

This repository is a small, evidence-driven laboratory for learning and demonstrating production backend engineering. Each task follows:

```text
problem -> test -> minimal implementation -> verification -> evidence
```

TASK-001 bootstraps a Spring Boot application with a real PostgreSQL development database, Flyway migrations, a local Actuator health endpoint, and one PostgreSQL Testcontainer integration test. It deliberately contains no domain or webhook behavior.

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

The integration test starts a real PostgreSQL `16-alpine` Testcontainer and overrides the application datasource with the container's JDBC connection. It checks the active Spring context, PostgreSQL JDBC metadata, the container database name, the Flyway history table, and the migrated schema.

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Docker smoke checks can be run with:

```powershell
docker compose config
docker compose exec -T postgres pg_isready -U evidence_lab -d evidence_lab
```

The reproducible record for TASK-001 is in [evidence/TASK-001.md](evidence/TASK-001.md). The current design is described in [docs/architecture.md](docs/architecture.md), and the database decision is recorded in [adr/0001-start-with-postgres.md](adr/0001-start-with-postgres.md).
