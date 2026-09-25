# ADR 0001: Start with PostgreSQL

- **Status:** Accepted
- **Date:** 2026-09-25

## Decision

Use PostgreSQL as the first database for local development and integration testing. Run local PostgreSQL with Docker Compose and run integration tests against an isolated PostgreSQL Testcontainer.

## Why PostgreSQL is the initial database

The eventual application is a reliable backend service whose behavior will depend on transactions, unique constraints, concurrent access, and durable state. PostgreSQL gives the laboratory one concrete relational engine from the beginning, so experiments are performed against the same broad database family as the intended production behavior. Docker Compose keeps the local setup reproducible without adding database software to the host.

This decision has a cost: developers need Docker resources, the database container takes time to start, and PostgreSQL-specific behavior can reduce portability. Those costs are accepted because database semantics are part of what this laboratory is intended to learn and demonstrate.

## Why an embedded database is not the primary integration substitute

An embedded database would make a context test faster and easier to run, but it would change the behavior being measured. SQL dialects, DDL handling, locking, transaction isolation, metadata, extensions, and constraint behavior can differ between an embedded engine and PostgreSQL. A green test against an embedded substitute could therefore leave a PostgreSQL integration defect undiscovered.

An embedded database may still be useful for a separate unit-level experiment if a future task provides a reason. It is not part of TASK-001, and no H2 dependency is included.

## Why Testcontainers is appropriate

Testcontainers creates an isolated, disposable PostgreSQL instance for the integration test and supplies the real JDBC connection to the Spring context. The test can therefore verify startup, migrations, and database-specific behavior in a repeatable environment without relying on a developer's local database state.

The trade-offs are Docker as a test prerequisite, image download and startup time, container resource usage, and the need to manage image versions. TASK-001 uses the same PostgreSQL 16 Alpine image family for Compose and Testcontainers to keep the two supported paths close while retaining test isolation.
