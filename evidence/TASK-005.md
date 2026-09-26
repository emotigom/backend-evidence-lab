# TASK-005 Graceful concurrent idempotent event creation

- **Date:** 2026-09-26 (Asia/Seoul)
- **Task:** Convert TASK-004's database-safe-but-exceptional duplicate behavior into explicit application semantics.
- **Schema migration:** None. TASK-005 reuses the TASK-003 UNIQUE constraint on `events.idempotency_key`.

## Problem observed in TASK-004

TASK-004 proved that PostgreSQL preserves one row per idempotency key under concurrent inserts, but the losing repository caller receives `DuplicateKeyException`. It also proved that two workers can both complete an existence check while the key is absent, so `exists -> insert` is not a correctness boundary.

TASK-005 therefore separates database integrity from caller semantics. The database still owns uniqueness, while an application service decides whether a conflict is a valid replay or a conflicting reuse of the key.

## Application contract

- **CREATED:** the first accepted request for an idempotency key creates one event and returns that exact persisted event.
- **REPLAYED:** a sequential or concurrent request with the same key, event type, and payload returns the original event. It creates no second row and exposes no `DuplicateKeyException`.
- **Conflict:** reuse of the same key with a different event type or different payload throws `IdempotencyConflictException`. The original row is unchanged.
- Event UUID and creation time are generated server-side and are not part of semantic request equality.
- Payload remains opaque `TEXT`; equality is textual, not normalized JSON equality.
## Chosen PostgreSQL strategy

`EventRepository.insertIfIdempotencyKeyAbsent` executes:

```sql
INSERT INTO evidence_lab.events
    (id, event_type, payload, created_at, idempotency_key)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (idempotency_key) DO NOTHING
```

The conflict target is specifically `idempotency_key`. A zero update count means another row owns that key; the application then loads that row by key. This avoids the TASK-004 time-of-check/time-of-use race and does not broadly swallow unrelated integrity failures.

The existing raw `insert` method remains unchanged so TASK-003 and TASK-004 evidence still demonstrates the low-level duplicate exception. A test with a duplicate primary key and a different idempotency key proves that the targeted `ON CONFLICT` path still surfaces `DuplicateKeyException`.

## Application boundary

`EventCreationService` is the first application/service boundary in the repository. It is `@Transactional` and owns CREATED, REPLAYED, and conflict semantics. `EventRepository` remains responsible for explicit PostgreSQL operations rather than caller policy.

The service generates UUID and `createdAt`, attempts the targeted insert, and returns CREATED when it inserts. If the insert returns zero rows, it loads the original event by idempotency key. Matching event type and payload returns REPLAYED; a mismatch raises an explicit conflict.

## Sequential results

Real PostgreSQL Testcontainer tests proved:
- first request -> CREATED and exactly one row;
- same request again -> REPLAYED with the original UUID and timestamp;
- same key plus different payload -> explicit conflict and unchanged original;
- same key plus different event type -> explicit conflict and unchanged original.
## Concurrent same-request result

Two fixed worker threads are coordinated with `CountDownLatch`; no sleeps are used. Both call the Spring transactional service with the same key, event type, and payload.

Observed invariant:
- one outcome is CREATED;
- one outcome is REPLAYED;
- neither caller receives `DuplicateKeyException`;
- both callers receive the same `Event`;
- the database contains exactly one row for the key.

The focused integration suite includes a bounded 10-iteration repeat with a fresh key per race. The invariant held for every iteration across repeated focused-suite executions.

## Concurrent conflicting-request result

Two synchronized callers use the same key but different event type and payload. Exactly one becomes CREATED. The other receives `IdempotencyConflictException`, not REPLAYED. The database retains exactly one row and its data matches the winning request. The test deliberately does not assert which worker wins.

## PostgreSQL visibility / transaction observation

With the service transaction using PostgreSQL's normal READ COMMITTED behavior, a competing `INSERT ... ON CONFLICT DO NOTHING` can wait for the winning transaction. After the conflict statement completes, the following SELECT is a new statement and the losing service call observed the committed winner.

The real concurrent tests are the proof: no conflict path produced the guard `IllegalStateException` for a missing winner, and same-request races converged on the same persisted event without explicit locks, isolation changes, or retries.
## Failure / discovery log

### Timestamp precision boundary

The first real TASK-005 PostgreSQL run compiled and reached the new semantics, but six assertions failed. The values were identical except for `createdAt`: Java `Instant.now()` supplied nanoseconds while PostgreSQL `TIMESTAMPTZ` persisted microsecond precision. Examples included `.012607600Z -> .012608Z` after round trip.

Returning the unnormalized candidate as CREATED therefore made an in-memory event differ from its persisted representation. The service now normalizes generated timestamps to microsecond precision before insertion. Exact event equality with the loaded PostgreSQL row is tested.

### Interrupted Codex turn recovery

The Codex weekly limit ended during that failing run. The incomplete source edits were rolled back, but compiled classes and Gradle XML test results remained. Those artifacts were used to reconstruct the intended minimal service/repository shape and identify the timestamp failure before reimplementation.

### Flyway default-schema drift

A persistent Compose database exposed a separate restart bug. PostgreSQL reported:
- `search_path = "$user", public`;
- `current_schema() = evidence_lab` after V1 created a schema with the same name as the database user;
- the existing Flyway history table was `public.flyway_schema_history`.

Without an explicit Flyway default schema, a later application start searched for history in `evidence_lab` and refused to start. `spring.flyway.default-schema: public` now pins the history location. The application then started successfully twice against the same persistent Compose volume with Flyway reporting version 3 and no migration required.

No completed Flyway migration was edited.

### Smoke-harness cleanup

The first immediate restart attempt hit port 8080 because the prior smoke JVM outlived its shell session. The listener PID was identified and only that application JVM was terminated; the restart then succeeded against the same database volume. Final cleanup stopped the application and Compose resources and left no listener on port 8080. This was a harness cleanup issue, not an application-semantic failure.

## Verification

Focused TASK-005 suite:

```powershell
.\gradlew.bat test --tests com.emotigom.backend.EventCreationIntegrationTest --rerun-tasks
```

Result: 8 tests, 0 failures, 0 errors. The focused suite was executed repeatedly after the fix; all runs were green.

Full verification:

```powershell
.\gradlew.bat test --rerun-tasks
.\gradlew.bat build --rerun-tasks
```

Both completed with `BUILD SUCCESSFUL`.

Persistent Compose smoke verification included `docker compose up -d`, `pg_isready`, two application starts against the same volume, and `/actuator/health`. Both successful starts reported health `UP` and database `UP`; Flyway reported schema `public` at version 3. Resources were stopped afterward and port 8080 was released.

## Limitations

- Payload equivalence is exact text equality; differently formatted but semantically equivalent JSON is not normalized.
- No HTTP contract exists, so no HTTP status/header semantics are defined.
- The concurrency proof uses two callers in one JVM, not separate application processes.
- This is correctness testing, not load testing.
- There is no outbox, delivery worker, webhook delivery, retry/backoff, or dead-letter behavior yet.
## Interview notes

1. **Database uniqueness vs application idempotency?** A UNIQUE constraint protects stored state. Application idempotency additionally defines what a repeated caller receives and whether the repeat is accepted or rejected.
2. **Why did TASK-004 expose DuplicateKeyException?** The database correctly rejected the losing insert, but no application layer translated that integrity outcome into replay semantics.
3. **Why is exists-then-insert insufficient?** The check and write are separate operations; concurrent callers can both observe absence before either write.
4. **What does targeted ON CONFLICT provide?** PostgreSQL atomically arbitrates the idempotency-key insert and reports whether this attempt inserted without a pre-check race.
5. **Why target idempotency_key specifically?** Other integrity failures, such as a primary-key collision, must remain errors rather than being mislabeled as a replay.
6. **Why return the original Event on replay?** Idempotency means repeated execution converges on the original accepted operation, including its generated ID and creation time.
7. **Why reject same-key different data?** Treating different operations as the same successful request would hide caller mistakes and could return data for an operation that was never accepted.
8. **Why can the SELECT see the winner under READ COMMITTED?** Each SQL statement receives a fresh snapshot. After the conflicting insert waits for the winner to commit, the following SELECT can observe that commit.
9. **What could another transaction/isolation design change?** Snapshot visibility, blocking, and retry requirements can differ; this implementation is proven only for the tested PostgreSQL transaction behavior.
10. **What does TASK-005 not guarantee about delivery?** It only durably accepts one event. It does not guarantee that a corresponding webhook-delivery job is also durably recorded.
11. **Why is transactional outbox next?** A process can commit the event and crash before creating or sending delivery work. The next task must close that failure window atomically.

## Next question

**TASK-006:** What minimal transactional outbox design and real PostgreSQL failure-boundary tests prove that accepting an event and creating its durable delivery-work record commit atomically, while rollback of either step leaves neither half-committed?
