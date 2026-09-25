# Contribution rules

This repository is an evidence-driven backend engineering laboratory. Work one backlog task at a time and keep each change small enough to explain.

## Required workflow

1. Read the active task and inspect the current repository before changing it.
2. State the problem and write the smallest meaningful test that can expose it.
3. Implement only the active task.
4. Run the relevant tests, build, and local infrastructure smoke checks.
5. Record the commands, results, failures, resolutions, and limitations in the task evidence file.
6. Create one clean commit after the task is green. Do not push unless the user explicitly asks.

## Project constraints

- Use the Gradle Wrapper (`gradlew` or `gradlew.bat`); do not require or install global Gradle.
- Keep PostgreSQL as the integration database. Do not add H2 or another embedded substitute.
- Use Testcontainers when an integration test needs a real PostgreSQL instance.
- Do not implement future backlog tasks early.
- Do not add webhook endpoints, Redis, Kafka, authentication, frontend code, deployment, Kubernetes, or cloud infrastructure without a task that explicitly requires them.
- Prefer database-enforced correctness when a later task demonstrates that it is needed.
- Do not weaken or delete a test to make a build green.

## Evidence expectations

Meaningful backend behavior needs reproducible evidence. Keep architecture notes and ADRs focused on decisions that exist in the code. Update the active task's evidence file after verification so another developer can follow the same commands and understand the remaining limitations.
