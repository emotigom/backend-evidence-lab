# Architecture

## TASK-001 through TASK-003 scope

The application is intentionally small. Spring Boot starts an executable JVM application, creates a JDBC datasource from environment-backed configuration, runs Flyway migrations against PostgreSQL, exposes the Actuator health endpoint at `/actuator/health`, and persists an `Event` through explicit Spring JDBC SQL.

TASK-003 adds a caller-supplied idempotency key for sequential duplicate detection. PostgreSQL owns the final uniqueness constraint; the repository lets the resulting `DuplicateKeyException` remain explicit. There is no HTTP controller, concurrency handling, delivery worker, or webhook behavior.

## Runtime components

- **Application:** Java 21 with Spring Boot 4.1.1, Spring MVC's embedded web server, and Gradle Wrapper 9.7.1.
- **Database:** PostgreSQL 16 Alpine for local development through `compose.yaml`.
- **Migration:** Flyway runs `src/main/resources/db/migration` at application startup. V1 creates the `evidence_lab` schema, V2 creates `evidence_lab.events`, and V3 adds the non-null unique `idempotency_key` column.
- **Event persistence:** The immutable `Event` record contains a UUID, event type, text payload, `Instant` creation time, and caller-supplied idempotency key. `EventRepository` uses parameterized Spring JDBC SQL to insert an event and return `Optional.empty()` when an ID is absent. A sequential duplicate key is surfaced as `DuplicateKeyException`.
- **Health:** Spring Boot Actuator exposes only the `health` endpoint over HTTP. Local health details are enabled to make database connectivity observable.
- **Integration proof:** `PostgresIntegrationTest` starts PostgreSQL 16 Alpine with Testcontainers and supplies its JDBC URL, username, and password through Spring's dynamic test properties. It verifies PostgreSQL column metadata, uniqueness, an insert/read round trip, sequential duplicate behavior, different keys, and missing-ID behavior.

## Configuration boundary

The default local connection is `jdbc:postgresql://localhost:5432/evidence_lab`. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` can replace those defaults without changing source code.

The integration test does not use the local Compose database. Its dynamic properties point the Spring context at the isolated Testcontainer, and PostgreSQL-specific assertions prove which database engine is active.

## Deliberate omissions

No HTTP API, concurrent idempotency handling, outbox, delivery worker, retry policy, message broker, cache, authentication, deployment configuration, or cloud service is introduced until a concrete task provides a reason and evidence for it.
