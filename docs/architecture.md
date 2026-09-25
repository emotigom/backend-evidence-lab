# Architecture

## TASK-001 scope

The application is intentionally small. Spring Boot starts an executable JVM application, creates a JDBC datasource from environment-backed configuration, runs Flyway migrations against PostgreSQL, and exposes the Actuator health endpoint at `/actuator/health`.

There are no domain controllers, event tables, delivery workers, or webhook behaviors in this task.

## Runtime components

- **Application:** Java 21 with Spring Boot 4.1.1, Spring MVC's embedded web server, and Gradle Wrapper 9.7.1.
- **Database:** PostgreSQL 16 Alpine for local development through `compose.yaml`.
- **Migration:** Flyway runs `src/main/resources/db/migration` at application startup. The first migration creates the `evidence_lab` schema and introduces no domain tables.
- **Health:** Spring Boot Actuator exposes only the `health` endpoint over HTTP. Local health details are enabled to make database connectivity observable.
- **Integration proof:** `PostgresIntegrationTest` starts PostgreSQL 16 Alpine with Testcontainers and supplies its JDBC URL, username, and password through Spring's dynamic test properties.

## Configuration boundary

The default local connection is `jdbc:postgresql://localhost:5432/evidence_lab`. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` can replace those defaults without changing source code.

The integration test does not use the local Compose database. Its dynamic properties point the Spring context at the isolated Testcontainer, and PostgreSQL-specific assertions prove which database engine is active.

## Deliberate omissions

No connection pool tuning, repository layer, domain model, message broker, cache, authentication, deployment configuration, or cloud service is introduced until a concrete task provides a reason and evidence for it.
