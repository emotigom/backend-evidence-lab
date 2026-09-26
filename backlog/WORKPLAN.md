# Workplan

Only one task is active at a time.

## Active

| Task | Status | Scope |
| --- | --- | --- |
| TASK-001 | DONE | Repository, Spring Boot, Gradle Wrapper, PostgreSQL, Flyway, Actuator health, and PostgreSQL Testcontainer proof |
| TASK-002 | DONE | PostgreSQL events table, Spring JDBC insert/find-by-ID behavior, and Testcontainers evidence |
| TASK-003 | DONE | Caller-supplied idempotency key, PostgreSQL uniqueness, and sequential duplicate contract |
| TASK-004 | DONE | Two-worker concurrent idempotency race and check-then-insert experiment |
| TASK-005 | DONE | Graceful concurrent idempotent event creation with explicit replay/conflict semantics |

## Planned sequence

| Task | Status | Scope |
| --- | --- | --- |
| TASK-006 | PLANNED | Transactional outbox |
| TASK-007 | PLANNED | Delivery worker |
| TASK-008 | PLANNED | Retry and backoff |
| TASK-009 | PLANNED | Dead-letter and replay |

Future tasks are listed only to make the learning sequence explicit. They are not implemented by TASK-001 through TASK-005.
