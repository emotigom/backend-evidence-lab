# Evidence: TASK-001

- **Timestamp:** `2026-09-25T19:34:19+09:00` (Asia/Seoul).
- **Task:** Repository and Spring Boot bootstrap only.

## Environment discovered

- OS: Microsoft Windows 11 Pro, version 10.0.26200, build 26200, 64-bit.
- Java: OpenJDK 21.0.12.1 LTS, Temurin build `21.0.12.1+1-LTS`.
- Git: `2.51.0.windows.1`.
- Docker CLI and Engine: `29.6.1`, context `desktop-linux`, Docker Desktop Linux engine available.
- Docker Compose: `v5.3.0`.
- Global Gradle: not installed or not on `PATH`; the build uses the checked-in Gradle Wrapper.
- Docker usability: `docker run --rm hello-world` pulled and ran successfully.

## Selected versions

- Spring Boot: `4.1.1`.
- Gradle Wrapper distribution: `9.7.1`.
- Java toolchain: `21`.
- PostgreSQL image: `postgres:16-alpine` for Compose and Testcontainers.

## Architectural decisions

- PostgreSQL is the only local database service and the integration-test database.
- Flyway owns startup migrations. TASK-001 creates only the `evidence_lab` schema; it introduces no domain tables.
- Actuator exposes only `/actuator/health`, with local health details enabled.
- The integration test supplies the Testcontainer JDBC properties dynamically and verifies PostgreSQL-specific metadata, database identity, Flyway history, and the migrated schema.
- No webhook, event, broker, authentication, frontend, deployment, Kubernetes, or cloud behavior is included.

## Commands executed

Initial inspection and environment discovery:

```text
Get-Location; Get-ChildItem -Force; rg --files -g '!*.class' -g '!build' -g '!out'
git status --short --branch
git remote -v
git log -1 --oneline
java -version
git --version
docker --version
docker compose version
gradle --version
docker info
Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,OSArchitecture,LastBootUpTime
docker run --rm hello-world
```

Bootstrap generation and file preparation:

```text
Invoke-WebRequest https://start.spring.io/starter.zip with Spring Boot 4.1.1, Java 21, and Gradle project parameters
Expand-Archive the temporary starter archive
```

The Spring Initializr archive was used to obtain the official Gradle Wrapper files; only the wrapper files were carried into the repository, and the application files were authored for TASK-001.

Final verification commands:

```text
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat build
docker compose config
docker compose up -d
docker compose ps
docker compose exec -T postgres pg_isready -U evidence_lab -d evidence_lab
java -jar build\libs\backend-evidence-lab-0.0.1-SNAPSHOT.jar
Invoke-WebRequest http://localhost:8080/actuator/health
docker compose down
```

The jar was started as a hidden background process for the health smoke, polled until the endpoint returned, and then stopped by its process ID.

## Tests and build results

- `.\gradlew.bat --version`: Gradle `9.7.1`, using Java `21.0.12.1` on Windows 11.
- `.\gradlew.bat test`: `BUILD SUCCESSFUL`; `PostgresIntegrationTest` passed against a real `postgres:16-alpine` Testcontainer. The test verified an active Spring context, PostgreSQL JDBC metadata, the Testcontainer database name, the Flyway history table, and the `evidence_lab` schema.
- `.\gradlew.bat build`: `BUILD SUCCESSFUL`; the executable boot jar was produced.
- `docker compose config`: valid configuration with one service, `postgres`.
- `docker compose up -d`: PostgreSQL container started and published port `5432`.
- `docker compose exec -T postgres pg_isready -U evidence_lab -d evidence_lab`: `/var/run/postgresql:5432 - accepting connections`.
- Local jar health smoke: HTTP `200` from `/actuator/health`; response status `UP`, database component `UP`, database product `PostgreSQL`.
- `docker compose down`: completed successfully after the smoke check.

## Failures and resolutions

- `gradle --version` reported that the command was not found. This was expected from the environment check and was resolved by using the checked-in Gradle Wrapper; no global Gradle was installed.
- The initial `git log -1 --oneline` reported that the branch had no commits. This confirmed the repository was empty and required no repair.
- The first `.\gradlew.bat test` compile failed because the Testcontainers 2 PostgreSQL container type is non-generic and Spring's plain `ApplicationContext` does not expose `isActive()`. The test was corrected to use the actual non-generic container type and `ConfigurableApplicationContext`; the next test run passed.
- The first local health smoke failed because Actuator alone did not add an HTTP server. The application started, applied Flyway, and exited without exposing a port. Adding `spring-boot-starter-webmvc` made the health endpoint usable; the subsequent test, build, and HTTP smoke checks passed.

## Remaining limitations

- TASK-001 has no domain model or event persistence.
- The Compose database uses development credentials and a local volume; this is not production configuration.
- The Testcontainers integration test requires Docker and downloads the PostgreSQL image when it is not cached.
- Only the Actuator health endpoint is exposed. No webhook or event endpoint exists.
