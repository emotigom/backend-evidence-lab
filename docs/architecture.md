# Architecture

## TASK-001 through TASK-005 scope

The application is intentionally small. Spring Boot starts an executable JVM application, creates a JDBC datasource from environment-backed configuration, runs Flyway migrations against PostgreSQL, exposes the Actuator health endpoint at `/actuator/health`, and persists an `Event` through explicit Spring JDBC SQL.

TASK-003 gives PostgreSQL final ownership of idempotency-key uniqueness, and TASK-004 proves that a check-then-insert pre-check races under concurrency even though the UNIQUE constraint keeps the database correct. TASK-005 adds the first application boundary: `EventCreationService` returns CREATED for the first request, REPLAYED with the original event for an equivalent retry, and an explicit conflict for reuse of the same key with different event type or payload. There is still no HTTP controller, outbox, delivery worker, or webhook behavior.

## Runtime components

- **Application:** Java 21 with Spring Boot 4.1.1, Spring MVC's embedded web server, and Gradle Wrapper 9.7.1.
- **Database:** PostgreSQL 16 Alpine for local development through `compose.yaml`.
- **Migration:** Flyway runs `src/main/resources/db/migration` at application startup. V1 creates the `evidence_lab` schema, V2 creates `evidence_lab.events`, and V3 adds the non-null unique `idempotency_key` column. Flyway's history schema is explicitly pinned to `public` so creating a schema with the same name as the database user cannot change the history-table lookup on restart.
- **Event persistence:** The immutable `Event` record contains a UUID, event type, text payload, `Instant` creation time, and caller-supplied idempotency key. Raw `insert` still exposes duplicate failures for TASK-003/004 evidence. TASK-005 adds a targeted `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` path plus lookup by idempotency key; unrelated primary-key failures are not swallowed.
- **Application semantics:** `EventCreationService` is transactional. It generates event timestamps at PostgreSQL's microsecond storage precision, returns CREATED when its targeted insert wins, and after a conflict reads the committed winner and returns REPLAYED only when event type and textual payload match. Mismatched reuse raises `IdempotencyConflictException`.
- **Health:** Spring Boot Actuator exposes only the `health` endpoint over HTTP. Local health details are enabled to make database connectivity observable.
- **Integration proof:** `PostgresIntegrationTest` preserves the raw database and race evidence. `EventCreationIntegrationTest` proves first-create, sequential replay/conflict, targeted constraint behavior, concurrent same-request convergence, 10 repeated races, and concurrent conflicting-key reuse against real PostgreSQL 16 Alpine Testcontainers.

## Configuration boundary

The default local connection is `jdbc:postgresql://localhost:5432/evidence_lab`. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` can replace those defaults without changing source code.

The integration test does not use the local Compose database. Its dynamic properties point the Spring context at the isolated Testcontainer, and PostgreSQL-specific assertions prove which database engine is active.

## Deliberate omissions

No HTTP API, outbox, delivery worker, retry policy, message broker, cache, authentication, deployment configuration, or cloud service is introduced until a concrete task provides a reason and evidence for it.
