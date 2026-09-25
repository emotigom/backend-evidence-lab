package com.emotigom.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;

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

}
