# TASK-003 Sequential idempotency contract

- **Timestamp:** `2026-09-25T20:26:08+09:00` (Asia/Seoul).
- **Task:** Sequential idempotency contract.

## Problem

Before TASK-003, two client requests carrying the same logical operation could insert two different `Event` rows by using different UUIDs. The database had no caller-supplied operation key with which to recognize that those requests represented the same attempt.

## Contract

An `Event` now carries a caller-supplied, opaque `String idempotencyKey`. The key is required by the database and is compared by its exact PostgreSQL `TEXT` value.

- The first valid event with a key may be inserted.
- A sequential second event with the same key fails with Spring JDBC's `DuplicateKeyException`, translated from PostgreSQL's unique-constraint violation.
- The duplicate attempt does not create a second row, and the original row remains unchanged.
- Different keys may be inserted independently.
- `EventRepository` does not perform an exists-before-insert check and does not silently catch integrity failures.

This is an integrity contract for sequential attempts. It is not a promise that concurrent callers receive a graceful application result.

## Schema

Migration `V3__add_event_idempotency_key.sql` adds:

```sql
idempotency_key TEXT NOT NULL
```

and the named PostgreSQL constraint:

```text
events_idempotency_key_key UNIQUE (idempotency_key)
```

The migration first adds the column as nullable, backfills existing rows with each row's already-unique UUID text, then applies `NOT NULL` and the unique constraint. This keeps a database containing TASK-002 rows migratable while requiring caller-supplied keys for all new repository inserts. `TEXT` avoids imposing an arbitrary key length policy before the application defines one; the key is opaque and is not queried as structured data.

## Why the database owns uniqueness

The database is the final integrity boundary because it evaluates the unique constraint when the row is inserted. A Java check such as `if (!exists(key)) insert(...)` is a separate read followed by a write and cannot itself guarantee that the key remains unused when the write occurs. The repository therefore sends the insert directly to PostgreSQL and lets the database decide.

The direct SQL integration test bypasses `EventRepository` and attempts a duplicate insert through `JdbcTemplate`. PostgreSQL still rejects it, demonstrating that uniqueness comes from the table constraint rather than from Java repository logic.

## Repository behavior

- **First insert:** `EventRepository.insert(event)` binds the event's UUID, type, payload, timestamp, and idempotency key in one parameterized SQL `INSERT`. A valid first key succeeds.
- **Duplicate insert:** A second insert with the same key raises `DuplicateKeyException`. The repository does not translate or swallow arbitrary `DataIntegrityViolationException` values; unrelated failures remain failures.
- **Different-key insert:** Two events with distinct keys both insert and can be loaded by their IDs.
- **Lookup:** `findById(UUID)` retains TASK-002 behavior and returns the full event, including its idempotency key. A missing ID returns `Optional.empty()`.
- **No lookup-by-key method:** It is not needed to prove this contract, so none was added.

## Tests

`PostgresIntegrationTest` uses the existing real PostgreSQL Testcontainer and covers:

1. Spring context startup against PostgreSQL.
2. The V3 column type, `NOT NULL` metadata, and named PostgreSQL unique constraint.
3. Existing UUID, event type, payload, timestamp, and idempotency-key round-trip behavior.
4. Sequential duplicate insertion through `EventRepository` raising `DuplicateKeyException`.
5. The original row remaining unchanged and no row appearing for the duplicate UUID.
6. A duplicate SQL insert bypassing `EventRepository` still being rejected by PostgreSQL.
7. Two different idempotency keys both being insertable.
8. Missing-ID behavior remaining `Optional.empty()`.

No threads, futures, executors, virtual threads, or load-testing tools are used.

## Verification

Commands executed:

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
Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
docker compose down
```

Results:

- Final `.\gradlew.bat test`: `BUILD SUCCESSFUL`; all PostgreSQL integration assertions passed.
- Final `.\gradlew.bat build`: `BUILD SUCCESSFUL`.
- `docker compose config`: valid PostgreSQL-only configuration.
- `docker compose ps`: PostgreSQL became healthy.
- `pg_isready`: `/var/run/postgresql:5432 - accepting connections`.
- Actuator smoke: `status=UP`, PostgreSQL database component `UP`.
- Flyway application smoke validated three migrations, moved the existing Compose database from version 2 to version 3, and applied V3 successfully.
- Direct `psql` inspection confirmed `evidence_lab.events`, `idempotency_key` type `text`, `NOT NULL`, and `events_idempotency_key_key` with constraint type `u`.
- `docker compose down`: completed successfully.

## Failure / discovery log

- The test-first run failed at compilation because the existing four-field `Event` record had not yet been extended with `idempotencyKey`. The model and repository were then updated; the tests were not weakened.
- The next run compiled but failed against PostgreSQL because the repository selected and inserted `idempotency_key` before V3 existed. Six of seven tests failed with PostgreSQL-backed `BadSqlGrammarException` or missing-column metadata behavior. Adding V3 resolved the schema mismatch.
- V3 backfills existing rows from `id::TEXT` before enforcing `NOT NULL`, preserving migration safety for databases that already contain TASK-002 events.
- The final Testcontainers run produced deterministic `DuplicateKeyException` behavior for duplicate keys and passed all assertions.

## Limitations

TASK-003 does not prove graceful behavior when many requests with the same key arrive concurrently. It does not use `INSERT ... ON CONFLICT`, locks, changed isolation, or a service layer. A duplicate currently surfaces as an exception for the caller to handle in a later contract. There is no API, delivery worker, outbox, retry, replay, or webhook behavior.

## Interview notes

1. **What is an idempotency key?** A caller-supplied value identifying one logical operation so repeated attempts can be recognized as the same operation.
2. **Is an idempotency key the same thing as a primary key?** No. The primary key identifies a stored row; the idempotency key identifies the caller's logical operation and has its own uniqueness constraint.
3. **Why store the idempotency key in PostgreSQL?** PostgreSQL is the shared durable boundary that all writers must satisfy, so the rule survives process restarts and multiple application instances.
4. **Why is UNIQUE preferable to relying only on `if (!exists(key)) insert(...)` for data integrity?** The check and insert are separate operations. Another write can use the key between them; the unique constraint evaluates the invariant at insertion.
5. **What guarantee does a UNIQUE constraint provide?** No two rows in its constraint scope can have the same non-null key; this column is also `NOT NULL`, so every new event participates.
6. **What does it NOT guarantee about application behavior?** It does not decide whether a duplicate should return the original result, an error, or a response code, and it does not by itself make concurrent request handling graceful.
7. **What happens to the original row after a duplicate insert attempt?** It remains unchanged; PostgreSQL rejects the second row and the repository can still load the original by its UUID.
8. **Why must unrelated database integrity failures not be disguised as duplicate-idempotency failures?** A primary-key collision, null violation, or other database defect may require a different response. Hiding it as a duplicate would misreport the cause and weaken diagnosis.
9. **Why is TASK-003 intentionally not a concurrency test?** Its purpose is to establish the sequential schema and repository contract first, so concurrent interleavings are not mixed into the initial observation.
10. **What question will TASK-004 answer that TASK-003 cannot?** It will show what happens when multiple concurrent attempts use the same key and determine what application-level behavior is needed around PostgreSQL's uniqueness result.
