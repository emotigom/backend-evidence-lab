# Workplan

Only one task is active at a time.

## Active

| Task | Status | Scope |
| --- | --- | --- |
| TASK-001 | DONE | Repository, Spring Boot, Gradle Wrapper, PostgreSQL, Flyway, Actuator health, and PostgreSQL Testcontainer proof |
| TASK-002 | DONE | PostgreSQL events table, Spring JDBC insert/find-by-ID behavior, and Testcontainers evidence |

## Planned sequence

| Task | Status | Scope |
| --- | --- | --- |
| TASK-003 | PLANNED | Idempotency |
| TASK-004 | PLANNED | Concurrent idempotency experiment |
| TASK-005 | PLANNED | Transactional outbox |
| TASK-006 | PLANNED | Delivery worker |
| TASK-007 | PLANNED | Retry and backoff |
| TASK-008 | PLANNED | Dead-letter and replay |

Future tasks are listed only to make the learning sequence explicit. They are not implemented by TASK-001 or TASK-002.
