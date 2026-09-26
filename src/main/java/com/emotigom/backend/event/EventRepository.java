package com.emotigom.backend.event;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

	private static final String INSERT_SQL = """
			INSERT INTO evidence_lab.events (id, event_type, payload, created_at, idempotency_key)
			VALUES (?, ?, ?, ?, ?)
			""";

	private static final String INSERT_IF_IDEMPOTENCY_KEY_ABSENT_SQL = """
			INSERT INTO evidence_lab.events (id, event_type, payload, created_at, idempotency_key)
			VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (idempotency_key) DO NOTHING
			""";

	private static final String FIND_BY_ID_SQL = """
			SELECT id, event_type, payload, created_at, idempotency_key
			FROM evidence_lab.events
			WHERE id = ?
			""";

	private static final String FIND_BY_IDEMPOTENCY_KEY_SQL = """
			SELECT id, event_type, payload, created_at, idempotency_key
			FROM evidence_lab.events
			WHERE idempotency_key = ?
			""";

	private static final RowMapper<Event> EVENT_ROW_MAPPER = (resultSet, rowNumber) -> new Event(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("event_type"),
			resultSet.getString("payload"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
			resultSet.getString("idempotency_key"));

	private final JdbcTemplate jdbcTemplate;

	public EventRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void insert(Event event) {
		jdbcTemplate.update(
				INSERT_SQL,
				event.id(),
				event.eventType(),
				event.payload(),
				OffsetDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC),
				event.idempotencyKey());
	}

	public boolean insertIfIdempotencyKeyAbsent(Event event) {
		return jdbcTemplate.update(
				INSERT_IF_IDEMPOTENCY_KEY_ABSENT_SQL,
				event.id(),
				event.eventType(),
				event.payload(),
				OffsetDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC),
				event.idempotencyKey()) == 1;
	}

	public Optional<Event> findById(UUID id) {
		return jdbcTemplate.query(FIND_BY_ID_SQL, EVENT_ROW_MAPPER, id).stream().findFirst();
	}

	public Optional<Event> findByIdempotencyKey(String idempotencyKey) {
		return jdbcTemplate.query(FIND_BY_IDEMPOTENCY_KEY_SQL, EVENT_ROW_MAPPER, idempotencyKey)
				.stream()
				.findFirst();
	}
}
