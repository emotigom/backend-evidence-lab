package com.emotigom.backend.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventCreationService {

	private final EventRepository eventRepository;

	public EventCreationService(EventRepository eventRepository) {
		this.eventRepository = eventRepository;
	}

	@Transactional
	public EventCreationResult create(EventCreationRequest request) {
		Event candidate = new Event(
				UUID.randomUUID(),
				request.eventType(),
				request.payload(),
				Instant.now().truncatedTo(ChronoUnit.MICROS),
				request.idempotencyKey());

		if (eventRepository.insertIfIdempotencyKeyAbsent(candidate)) {
			return new EventCreationResult(candidate, EventCreationOutcome.CREATED);
		}
		Event original = eventRepository.findByIdempotencyKey(request.idempotencyKey())
				.orElseThrow(() -> new IllegalStateException(
						"Idempotency conflict did not expose a persisted event: " + request.idempotencyKey()));

		if (sameSemanticRequest(original, request)) {
			return new EventCreationResult(original, EventCreationOutcome.REPLAYED);
		}
		throw new IdempotencyConflictException(request.idempotencyKey());
	}

	private boolean sameSemanticRequest(Event original, EventCreationRequest request) {
		return original.eventType().equals(request.eventType())
				&& original.payload().equals(request.payload());
	}
}
