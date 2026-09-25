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
			INSERT INTO evidence_lab.events (id, event_type, payload, created_at)
			VALUES (?, ?, ?, ?)
			""";

	private static final String FIND_BY_ID_SQL = """
			SELECT id, event_type, payload, created_at
			FROM evidence_lab.events
			WHERE id = ?
			""";

	private static final RowMapper<Event> EVENT_ROW_MAPPER = (resultSet, rowNumber) -> new Event(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("event_type"),
			resultSet.getString("payload"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant());

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
				OffsetDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC));
	}

	public Optional<Event> findById(UUID id) {
		return jdbcTemplate.query(FIND_BY_ID_SQL, EVENT_ROW_MAPPER, id).stream().findFirst();
	}
}
