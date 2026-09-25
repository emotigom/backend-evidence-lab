# TASK-004 Concurrent idempotency experiment

- **Timestamp:** 2026-09-25T20:52:31+09:00 (Asia/Seoul).
- **Task:** Concurrent idempotency experiment.
- **Production behavior change:** None. TASK-004 adds integration-test experiment code and evidence only.

## Question

The database correctness question is: when two transactions concurrently
attempt distinct events with the same idempotency key, does PostgreSQL still
preserve the invariant that at most one row uses that key?

The application behavior question is: what does the current repository expose
to the losing concurrent caller when PostgreSQL rejects its insert?

## Hypothesis

Before the experiment, the hypothesis was that PostgreSQL's UNIQUE constraint
would preserve at most one row per key, one insert would succeed, and a
competing insert would surface Spring JDBC's DuplicateKeyException. That
would preserve database correctness while leaving concurrent application
behavior undesirable because the loser would receive an exception rather than
a graceful idempotent result.

## Experiment A

Two workers used the same key, task-004-experiment-a, with different event
IDs, types, payloads, and timestamps:

| Worker position | Event ID | Payload marker |
| --- | --- | --- |
| first | 105e4c62-0f55-4f7a-8c09-000000000401 | first |
| second | 205e4c62-0f55-4f7a-8c09-000000000402 | second |

The test creates exactly two worker threads through a fixed two-thread
executor. Each worker signals a CountDownLatch after it is ready. The test
thread waits for both signals, then releases a second latch so both repository
inserts begin as closely together as the test can arrange. No Thread.sleep is
used for synchronization.

Each repository insert uses the existing explicit Spring JDBC implementation.
The JdbcTemplate.update call runs as its own autocommit database operation;
the experiment does not add a service transaction, lock, isolation change, or
retry.

Each attempt is recorded with its event UUID, success flag, exception type,
root exception type, and SQLSTATE. The first focused run observed the second
event winning. The first worker returned DuplicateKeyException, whose root
cause was PSQLException with SQLSTATE 23505; the second worker succeeded. A
later forced rerun observed the first event winning instead. The test does not
assert which position wins.

## Experiment B

The same two-worker arrangement was used with key task-004-experiment-b and
these event IDs:

| Worker position | Event ID | Payload marker |
| --- | --- | --- |
| first | 305e4c62-0f55-4f7a-8c09-000000000403 | first-check |
| second | 405e4c62-0f55-4f7a-8c09-000000000404 | second-check |

Each worker first ran a direct JdbcTemplate SELECT EXISTS check. A latch was
decremented only after that check completed. The test thread waited for both
checks to finish, recorded both results, and released a second latch so both
workers could then attempt insertion. Both workers observed false for the
existence check. One insert succeeded and the other received the same
duplicate-key failure as Experiment A.

This keeps the naive check-then-insert sequence in test code only. It does not
add that algorithm to EventRepository.

## Observed database behavior

For every two-worker race in the focused runs:

- exactly one attempt succeeded;
- exactly one attempt failed with Spring DuplicateKeyException;
- the root database exception was PostgreSQL's PSQLException;
- the observed PostgreSQL SQLSTATE was 23505;
- the final count for the shared idempotency key was exactly 1;
- the persisted row matched one of the two submitted events, including its
  UUID, event type, payload, timestamp, and key;
- the losing event UUID did not appear as a second row.

The direct database constraint check also found the PostgreSQL constraint
events_idempotency_key_key with constraint type u. No Java pre-check is
involved in the production repository insert.

## Database correctness vs application semantics

PostgreSQL remained correct: its unique constraint admitted one key-bearing
row and rejected the competing row. The current application behavior was less
useful for a caller: the losing repository call surfaced
DuplicateKeyException. The database therefore preserved the invariant while
the application still exposed a conflict exception instead of a graceful
idempotent outcome. TASK-004 records this behavior and does not change it.

## Why exists-then-insert is insufficient

Experiment B showed the time-of-check/time-of-use race directly. Both workers
read the same state, both observed that the key was absent, and both therefore
had evidence that insertion appeared safe. That evidence became stale before
the writes completed. The database UNIQUE constraint evaluated the shared
invariant at insertion time and rejected one write. The pre-check did not
protect the invariant; it only described an earlier observation.

## Repeatability

The bounded repeat test ran 10 fresh-key races per focused suite run. It was
run three times in total: once after the test implementation and twice with
--rerun-tasks, for 30 measured repeat races. All runs preserved the same
invariants. The first/second winner counts were:

| Focused run | First position won | Second position won |
| --- | ---: | ---: |
| initial focused run | 6 | 4 |
| forced rerun 1 | 9 | 1 |
| forced rerun 2 | 8 | 2 |

Both positions won across the repeated runs, so the experiment leaves winner
identity nondeterministic as intended.

## Verification

Exact commands and results:

~~~text
.\gradlew.bat test --tests com.emotigom.backend.PostgresIntegrationTest
~~~

Passed: 10 PostgreSQL integration tests, including Experiments A and B and
the 10-iteration repeat test.

~~~text
.\gradlew.bat test --tests com.emotigom.backend.PostgresIntegrationTest --rerun-tasks
~~~

Passed twice. Each forced run again passed all 10 PostgreSQL integration tests;
the race output was recorded above.

~~~text
.\gradlew.bat test
.\gradlew.bat build
~~~

Both completed with BUILD SUCCESSFUL. The full test task passed all 10 tests;
the build completed successfully after compilation, tests, and packaging.

~~~text
docker compose config
docker compose up -d
docker compose ps
docker compose exec -T postgres pg_isready -U evidence_lab -d evidence_lab
~~~

The Compose file was valid and contained only PostgreSQL. The container became
healthy and pg_isready returned /var/run/postgresql:5432 - accepting
connections.

After recreating the repository's stale local Compose volume, the application
smoke was run with the built jar:

~~~text
Start-Process java.exe -ArgumentList '-jar', 'build\libs\backend-evidence-lab-0.0.1-SNAPSHOT.jar' -WindowStyle Hidden
Invoke-RestMethod -UseBasicParsing http://localhost:8080/actuator/health
docker compose exec -T postgres psql -U evidence_lab -d evidence_lab -c "SELECT installed_rank, version, description, success FROM public.flyway_schema_history ORDER BY installed_rank; SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'evidence_lab' AND table_name = 'events' AND column_name = 'idempotency_key'; SELECT conname, contype FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'evidence_lab' AND t.relname = 'events' AND c.conname = 'events_idempotency_key_key';"
docker compose down
~~~

Actuator returned status=UP with the PostgreSQL database component UP.
The application applied Flyway versions 1, 2, and 3. Direct PostgreSQL
inspection returned idempotency_key | text | NO and
events_idempotency_key_key | u. Compose was then stopped and the Java
process and temporary smoke logs were cleaned up.

## Failure / discovery log

- The first concurrent implementation run passed, but its test output did not
  expose each worker's recorded result. The test was tightened to print both
  AttemptResult records and the selected winner while retaining the same
  assertions. No production code was changed.
- The initial Compose volume had an inconsistent local state: its
  flyway_schema_history contained only V1 even though evidence_lab.events
  already contained the TASK-003 column and unique constraint. This was stale
  local database state, not a Testcontainers or repository race result. The
  verified repository-specific volume was removed with docker compose down -v,
  recreated, and the application smoke then applied V1 through V3 cleanly.
- The application smoke wrapper emitted a PowerShell null .Trim() cleanup
  error while reading an empty stderr file. The health check and SQL checks had
  already succeeded; a follow-up inspection confirmed port 8080 was free and
  the temporary logs were gone. This was a test-harness cleanup issue, not an
  application failure.
- No synchronization timeout, transaction error, flaky assertion, or
  unexpected database result occurred in the three focused runs or the full
  build.

## Limitations

- This is not load testing.
- It does not prove behavior for 50, 500, or 5000 clients.
- No graceful conflict handling exists yet.
- No HTTP semantics exist yet.
- No distributed multi-process experiment exists yet.
- Each worker uses one repository insert operation with the current
  autocommit arrangement; this does not explore longer application
  transactions or connection-pool exhaustion.

## Interview notes

1. **What is a race condition?** A race condition is behavior whose result depends on the timing or interleaving of concurrent operations.
2. **What is a time-of-check/time-of-use race?** It occurs when a program checks a condition, another operation can change it, and the program later uses the stale result.
3. **Why can two transactions both observe that a row does not exist?** Their reads can happen before either transaction commits its new row, so each read sees the key as absent.
4. **Why does exists-then-insert not guarantee uniqueness?** The existence read and insert are separate operations; another writer can pass the same check before either write is evaluated by the database.
5. **What protected the database invariant in this experiment?** PostgreSQL's UNIQUE constraint on events.idempotency_key.
6. **Why can the database be correct while the application behavior is still undesirable?** The database can reject the duplicate correctly while the repository exposes that rejection as an exception that the caller cannot yet treat as a successful repeat.
7. **What is Spring DuplicateKeyException representing here?** It is Spring JDBC's translated representation of the database duplicate-key integrity failure from the unique constraint.
8. **What does PostgreSQL SQLSTATE 23505 mean, if observed?** It identifies a unique-violation error; it was observed as the root PostgreSQL failure for the losing insert.
9. **Why should a concurrent test not assert which worker wins?** Scheduling and database timing determine the winner, so asserting an identity would make a correct test depend on an incidental interleaving.
10. **What would removing the UNIQUE constraint change?** Both inserts could commit rows with the same idempotency key, so the database would no longer protect the invariant.
11. **Why is Thread.sleep a poor primary synchronization mechanism?** A sleep does not prove that the other worker reached a required state; it makes timing assumptions and can create slow or flaky tests. Latches express the state the experiment needs.
12. **What should TASK-005 investigate next?** What minimal transactional outbox design and real PostgreSQL test can prove that an accepted event and its durable delivery work record are committed atomically, including the failure boundary between those writes?
