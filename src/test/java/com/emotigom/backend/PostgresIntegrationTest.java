package com.emotigom.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
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
		assertEquals("text", columnDataType("idempotency_key"));
		assertEquals("NO", columnNullable("idempotency_key"));
		assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1
				    FROM pg_constraint c
				    JOIN pg_class t ON t.oid = c.conrelid
				    JOIN pg_namespace n ON n.oid = t.relnamespace
				    WHERE n.nspname = 'evidence_lab'
				      AND t.relname = 'events'
				      AND c.conname = 'events_idempotency_key_key'
				      AND c.contype = 'u'
				)
				""", Boolean.class)),
				"PostgreSQL must own the idempotency-key uniqueness constraint");
	}

	@Test
	void insertedEventCanBeLoadedByIdWithValuesPreserved() {
		Event event = new Event(
				UUID.fromString("8d1f8e4a-3b2f-4ac8-9f22-2cf2d8f82a41"),
				"account.created",
				"{\"accountId\":\"acct-123\",\"active\":true}",
				Instant.parse("2026-09-25T10:15:30Z"),
				"task-002-account-123");

		eventRepository.insert(event);

		assertEquals(Optional.of(event), eventRepository.findById(event.id()));
	}

	@Test
	void sequentialDuplicateKeyRaisesDuplicateKeyAndLeavesOriginalUnchanged() {
		Event original = new Event(
				UUID.fromString("b4d7c61a-6d26-4c30-9b47-15370a8d5a01"),
				"account.created",
				"{\"accountId\":\"original\"}",
				Instant.parse("2026-09-25T11:00:00Z"),
				"request-sequential-001");
		Event duplicate = new Event(
				UUID.fromString("c0f5a1b9-9cc6-48f0-ae0b-2e6f7f3a5b02"),
				"account.updated",
				"{\"accountId\":\"duplicate\"}",
				Instant.parse("2026-09-25T11:01:00Z"),
				original.idempotencyKey());

		eventRepository.insert(original);

		assertThrows(DuplicateKeyException.class, () -> eventRepository.insert(duplicate));
		assertEquals(Optional.of(original), eventRepository.findById(original.id()));
		assertTrue(eventRepository.findById(duplicate.id()).isEmpty());
		assertEquals(1, jdbcTemplate.queryForObject(
				"SELECT count(*) FROM evidence_lab.events WHERE idempotency_key = ?",
				Integer.class,
				original.idempotencyKey()));
	}

	@Test
	void postgresRejectsDuplicateKeyWhenInsertBypassesEventRepository() {
		Event original = new Event(
				UUID.fromString("d1b8d7ef-8d2b-4a68-9c52-65ae4de1cc03"),
				"invoice.created",
				"{\"invoiceId\":\"original\"}",
				Instant.parse("2026-09-25T12:00:00Z"),
				"request-database-001");
		Event duplicate = new Event(
				UUID.fromString("e2c9e8f0-9e3c-4b79-ad63-76bf5ef2dd04"),
				"invoice.updated",
				"{\"invoiceId\":\"duplicate\"}",
				Instant.parse("2026-09-25T12:01:00Z"),
				original.idempotencyKey());

		eventRepository.insert(original);

		assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
				INSERT INTO evidence_lab.events (id, event_type, payload, created_at, idempotency_key)
				VALUES (?, ?, ?, ?, ?)
				""",
				duplicate.id(),
				duplicate.eventType(),
				duplicate.payload(),
				OffsetDateTime.ofInstant(duplicate.createdAt(), ZoneOffset.UTC),
				duplicate.idempotencyKey()));

		assertEquals(Optional.of(original), eventRepository.findById(original.id()));
	}

	@Test
	void differentIdempotencyKeysCanBothBeInserted() {
		Event first = new Event(
				UUID.fromString("f3dae901-af4d-4c8a-be74-87c06fa3ee05"),
				"shipment.created",
				"{\"shipmentId\":\"ship-1\"}",
				Instant.parse("2026-09-25T13:00:00Z"),
				"request-different-001");
		Event second = new Event(
				UUID.fromString("04ebfa12-b05e-4d9b-cf85-98d170b4ff06"),
				"shipment.created",
				"{\"shipmentId\":\"ship-2\"}",
				Instant.parse("2026-09-25T13:01:00Z"),
				"request-different-002");

		eventRepository.insert(first);
		eventRepository.insert(second);

		assertEquals(Optional.of(first), eventRepository.findById(first.id()));
		assertEquals(Optional.of(second), eventRepository.findById(second.id()));
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

	private String columnNullable(String columnName) {
		return jdbcTemplate.queryForObject("""
				SELECT is_nullable
				FROM information_schema.columns
				WHERE table_schema = 'evidence_lab'
				  AND table_name = 'events'
				  AND column_name = ?
				""", String.class, columnName);
	}

}
