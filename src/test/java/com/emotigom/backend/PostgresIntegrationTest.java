package com.emotigom.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.emotigom.backend.event.Event;
import com.emotigom.backend.event.EventRepository;

@Testcontainers
@SpringBootTest
class PostgresIntegrationTest {

	private static final String POSTGRES_IMAGE = "postgres:16-alpine";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(POSTGRES_IMAGE));

	@Autowired
	ConfigurableApplicationContext applicationContext;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventRepository eventRepository;

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Test
	void applicationContextUsesTheTestcontainerPostgresDatabase() throws Exception {
		assertTrue(applicationContext.isActive(), "the Spring application context must be active");

		try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
			assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
		}

		assertEquals(POSTGRES.getDatabaseName(),
				jdbcTemplate.queryForObject("SELECT current_database()", String.class));
		assertEquals("flyway_schema_history",
				jdbcTemplate.queryForObject("SELECT to_regclass('public.flyway_schema_history')", String.class));
		assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'evidence_lab')", Boolean.class)),
				"the Flyway migration must create the evidence_lab schema");
	}

	@Test
	void flywayMigrationCreatesEventsTableWithExpectedPostgresTypes() {
		assertEquals("evidence_lab.events",
				jdbcTemplate.queryForObject("SELECT to_regclass('evidence_lab.events')", String.class));
		assertEquals("uuid", columnDataType("id"));
		assertEquals("text", columnDataType("event_type"));
		assertEquals("text", columnDataType("payload"));
		assertEquals("timestamp with time zone", columnDataType("created_at"));
	}

	@Test
	void insertedEventCanBeLoadedByIdWithValuesPreserved() {
		Event event = new Event(
				UUID.fromString("8d1f8e4a-3b2f-4ac8-9f22-2cf2d8f82a41"),
				"account.created",
				"{\"accountId\":\"acct-123\",\"active\":true}",
				Instant.parse("2026-09-25T10:15:30Z"));

		eventRepository.insert(event);

		assertEquals(Optional.of(event), eventRepository.findById(event.id()));
	}

	@Test
	void findingMissingEventReturnsEmptyOptional() {
		assertTrue(eventRepository.findById(UUID.fromString("2f5e0f8a-6d2c-4c6b-8b2e-6f12f0b1a734")).isEmpty());
	}

	private String columnDataType(String columnName) {
		return jdbcTemplate.queryForObject("""
				SELECT data_type
				FROM information_schema.columns
				WHERE table_schema = 'evidence_lab'
				  AND table_name = 'events'
				  AND column_name = ?
				""", String.class, columnName);
	}

}
