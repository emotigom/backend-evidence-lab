# Evidence: TASK-002

- **Timestamp:** `2026-09-25T20:09:23+09:00` (Asia/Seoul).
- **Task:** Basic event persistence.

## Problem being solved

TASK-001 proved that the Spring application can start against real PostgreSQL and run Flyway, but it persisted no domain data. TASK-002 adds the smallest useful persistence behavior: insert an `Event` and load it back by its ID. The task has no HTTP API and does not attempt idempotency or delivery.

## Schema introduced

Flyway migration `V2__create_events_table.sql` creates `evidence_lab.events`:

| Column | PostgreSQL type | Constraint | Reason |
| --- | --- | --- | --- |
| `id` | `UUID` | `PRIMARY KEY` | Stable identity used by the repository lookup and row identity. |
| `event_type` | `TEXT` | `NOT NULL` | Records the kind of event without imposing a premature enum or length policy. |
| `payload` | `TEXT` | `NOT NULL` | Keeps the original payload as an opaque string while the task focuses on persistence. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Records the event creation instant with PostgreSQL timezone-aware timestamp semantics. |

The migration intentionally adds no idempotency key, unique business constraint, delivery state, outbox data, or retry fields.

## Persistence design

`Event` is a Java record with `UUID id`, `String eventType`, `String payload`, and `Instant createdAt`. The repository is one concrete `EventRepository` class using `JdbcTemplate` and parameterized SQL:

- `insert(Event)` inserts all four values into `evidence_lab.events`.
- `findById(UUID)` returns the row as `Optional<Event>` and returns `Optional.empty()` when no row matches.

Spring JDBC was used instead of JPA because this task is specifically about seeing the SQL, PostgreSQL types, and round-trip mapping. JPA would add entity annotations, persistence-context behavior, generated SQL, and lifecycle rules before this laboratory has a problem that requires them. Spring JDBC still provides connection management, parameter binding, exception translation, and row mapping while keeping the SQL explicit.

The payload is `TEXT`, rather than `JSONB`, because TASK-002 does not query, validate, or index payload fields. `TEXT` preserves the supplied representation and avoids adding serialization rules. JSONB could be appropriate when a later task needs PostgreSQL JSON operators, structural validation, or payload-field indexing; choosing it now would add semantics that this task does not exercise.

## Test cases

All cases run through the existing Spring Boot Testcontainers setup with a real `postgres:16-alpine` database:

1. The context starts against PostgreSQL, not H2 or an embedded substitute.
2. The Flyway migration creates `evidence_lab.events`, and the test checks the table plus the UUID, text, and timestamp-with-time-zone column types through PostgreSQL metadata.
3. An event is inserted and loaded by ID; UUID, event type, payload, and creation instant are asserted as an exact round trip.
4. A missing UUID returns `Optional.empty()`.

## Verification commands and results

```text
.\gradlew.bat test
.\gradlew.bat build
docker compose config
docker compose up -d
docker compose ps
docker compose exec -T postgres pg_isready -U evidence_lab -d evidence_lab
Start-Process java.exe -ArgumentList '-jar', 'build\libs\backend-evidence-lab-0.0.1-SNAPSHOT.jar' -WindowStyle Hidden
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
docker compose exec -T postgres psql -U evidence_lab -d evidence_lab -c "SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema = 'evidence_lab' AND table_name = 'events'; SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'evidence_lab' AND table_name = 'events' ORDER BY ordinal_position;"
docker compose down
```

- The test-first `.\gradlew.bat test` run failed to compile before the new production classes existed; this was expected and exposed the missing implementation.
- Final `.\gradlew.bat test`: `BUILD SUCCESSFUL`; all context, migration, insert/read, round-trip, and missing-ID assertions passed against Testcontainers PostgreSQL.
- Final `.\gradlew.bat build`: `BUILD SUCCESSFUL`.
- `docker compose config`: valid configuration with PostgreSQL as the only service.
- Compose smoke verification: PostgreSQL started healthy, `pg_isready` reported `/var/run/postgresql:5432 - accepting connections`, and Compose shut down successfully.
- Local jar smoke: Actuator returned `status=UP` and reported the PostgreSQL database component as `UP`.
- Direct `psql` verification returned `evidence_lab | events` and the expected column types: `uuid`, `text`, `text`, and `timestamp with time zone`.

## Failures encountered and changes made

- The first test run reported missing `Event` and `EventRepository` classes. The implementation was added instead of weakening the tests.
- No runtime or database mapping failure remained after the implementation was added; the final Testcontainers test passed with `TIMESTAMPTZ` mapped through `OffsetDateTime` and then to `Instant`.

## Remaining limitations

- There is no controller or API endpoint.
- The repository has no update or delete operation.
- There is no idempotency key or duplicate-event policy.
- There is no concurrency experiment, transactional outbox, delivery worker, retry, or replay behavior.
- Payload contents are opaque text; no JSON validation or field querying is provided.
- The database uses the existing local development/Testcontainers setup and is not production deployment configuration.

## What TASK-002 intentionally does NOT solve

TASK-002 does not decide whether two logically identical events are duplicates, guarantee exactly-once processing, publish work to another system, deliver webhooks, retry failures, authenticate callers, or expose an HTTP contract. Those concerns remain for later tasks.

## Interview notes

1. **Why does the database need a primary key?** It gives every row a database-enforced identity and lets the repository address one event unambiguously.
2. **Why UUID here instead of an auto-increment integer?** UUIDs can be created by the application without coordinating a database sequence and do not expose insertion order as an identifier. TASK-002 needs stable identity, not ordering.
3. **What does TIMESTAMPTZ mean in PostgreSQL?** It represents a timestamp with time-zone-aware instant semantics; PostgreSQL normalizes the stored instant and presents it using the session time zone.
4. **What guarantee does a successful INSERT give us?** It means PostgreSQL accepted the row into the current transaction and its constraints passed; visibility and durability outside that transaction depend on commit.
5. **What does it NOT guarantee about future webhook delivery?** It does not prove that any worker will read the event, that a destination will receive it, or that retries and failures will be handled.
6. **Why use a real PostgreSQL Testcontainer instead of mocking the repository?** The test needs to prove the real migration, PostgreSQL data types, SQL, constraints, and JDBC mapping. A mock would prove only that application code called an invented interface.
7. **What abstraction is Spring JDBC providing?** It manages JDBC interaction around a `DataSource`, binds parameters, executes SQL, translates database exceptions, and maps result rows.
8. **What would JPA hide from us at this stage?** Entity state tracking, persistence-context behavior, generated SQL, flush timing, and much of the mapping between Java fields and PostgreSQL would be outside the explicit code under study.
