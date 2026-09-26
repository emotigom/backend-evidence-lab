package com.emotigom.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.emotigom.backend.event.Event;
import com.emotigom.backend.event.EventCreationOutcome;
import com.emotigom.backend.event.EventCreationRequest;
import com.emotigom.backend.event.EventCreationResult;
import com.emotigom.backend.event.EventCreationService;
import com.emotigom.backend.event.EventRepository;
import com.emotigom.backend.event.IdempotencyConflictException;

@Testcontainers
@SpringBootTest
class EventCreationIntegrationTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	@Autowired
	EventCreationService eventCreationService;

	@Autowired
	EventRepository eventRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;
	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Test
	void firstRequestCreatesExactlyThePersistedEvent() {
		EventCreationRequest request = request(
				"task-005.first", "{\"request\":\"first\"}", freshKey("first"));

		EventCreationResult result = eventCreationService.create(request);

		assertEquals(EventCreationOutcome.CREATED, result.outcome());
		assertEquals(Optional.of(result.event()), eventRepository.findById(result.event().id()));
		assertEquals(1, countByKey(request.idempotencyKey()));
	}

	@Test
	void sequentialReplayReturnsOriginalEvent() {
		EventCreationRequest request = request(
				"task-005.replay", "{\"request\":\"same\"}", freshKey("replay"));

		EventCreationResult first = eventCreationService.create(request);
		EventCreationResult replay = eventCreationService.create(request);

		assertEquals(EventCreationOutcome.CREATED, first.outcome());
		assertEquals(EventCreationOutcome.REPLAYED, replay.outcome());
		assertEquals(first.event(), replay.event());
		assertEquals(Optional.of(first.event()),
				eventRepository.findByIdempotencyKey(request.idempotencyKey()));
		assertEquals(1, countByKey(request.idempotencyKey()));
	}

	@Test
	void sequentialPayloadMismatchIsExplicitConflict() {
		String key = freshKey("payload-conflict");
		EventCreationResult first = eventCreationService.create(
				request("task-005.payload", "{\"version\":1}", key));

		assertThrows(IdempotencyConflictException.class, () -> eventCreationService.create(
				request("task-005.payload", "{\"version\":2}", key)));

		assertEquals(Optional.of(first.event()), eventRepository.findByIdempotencyKey(key));
		assertEquals(1, countByKey(key));
	}

	@Test
	void sequentialEventTypeMismatchIsExplicitConflict() {
		String key = freshKey("type-conflict");
		EventCreationResult first = eventCreationService.create(
				request("task-005.original-type", "{\"same\":\"payload\"}", key));

		assertThrows(IdempotencyConflictException.class, () -> eventCreationService.create(
				request("task-005.different-type", "{\"same\":\"payload\"}", key)));

		assertEquals(Optional.of(first.event()), eventRepository.findByIdempotencyKey(key));
		assertEquals(1, countByKey(key));
	}

	@Test
	void targetedIdempotencyConflictDoesNotSwallowPrimaryKeyViolation() {
		Event original = new Event(
				UUID.randomUUID(),
				"task-005.pk.original",
				"{\"row\":1}",
				Instant.parse("2026-09-26T01:00:00Z"),
				freshKey("pk-original"));
		eventRepository.insert(original);

		Event duplicatePrimaryKey = new Event(
				original.id(),
				"task-005.pk.other",
				"{\"row\":2}",
				Instant.parse("2026-09-26T01:00:01Z"),
				freshKey("pk-other"));

		assertThrows(DuplicateKeyException.class,
				() -> eventRepository.insertIfIdempotencyKeyAbsent(duplicatePrimaryKey));
		assertEquals(Optional.of(original), eventRepository.findById(original.id()));
	}

	@Test
	void concurrentSameRequestConvergesOnCreatedAndReplayed() throws Exception {
		EventCreationRequest request = request(
				"task-005.concurrent",
				"{\"request\":\"same-concurrent\"}",
				freshKey("concurrent"));

		ConcurrentResult race = runConcurrent(request, request);

		assertSameRequestInvariant(request, race);
	}

	@Test
	void repeatedConcurrentSameRequestPreservesInvariant() throws Exception {
		for (int iteration = 1; iteration <= 10; iteration++) {
			EventCreationRequest request = request(
					"task-005.repeat",
					"{\"iteration\":" + iteration + ",\"request\":\"same\"}",
					freshKey("repeat-" + iteration));
			assertSameRequestInvariant(request, runConcurrent(request, request));
		}
	}

	@Test
	void concurrentConflictingRequestsHaveOneWinnerAndOneExplicitConflict() throws Exception {
		String key = freshKey("concurrent-conflict");
		EventCreationRequest first = request(
				"task-005.conflict.first", "{\"worker\":\"first\"}", key);
		EventCreationRequest second = request(
				"task-005.conflict.second", "{\"worker\":\"second\"}", key);

		ConcurrentResult race = runConcurrent(first, second);
		List<CreateAttempt> attempts = List.of(race.first(), race.second());

		assertEquals(1L, attempts.stream().filter(CreateAttempt::succeeded).count());
		assertEquals(1L, attempts.stream()
				.filter(attempt -> attempt.failure() instanceof IdempotencyConflictException)
				.count());

		EventCreationResult winner = attempts.stream()
				.filter(CreateAttempt::succeeded)
				.map(CreateAttempt::result)
				.findFirst()
				.orElseThrow();
		assertEquals(EventCreationOutcome.CREATED, winner.outcome());
		assertEquals(Optional.of(winner.event()), eventRepository.findByIdempotencyKey(key));
		assertEquals(1, countByKey(key));
	}

	private void assertSameRequestInvariant(
			EventCreationRequest request,
			ConcurrentResult race) {
		assertTrue(race.first().succeeded(), "first concurrent caller must not fail");
		assertTrue(race.second().succeeded(), "second concurrent caller must not fail");

		List<EventCreationOutcome> outcomes = List.of(
				race.first().result().outcome(),
				race.second().result().outcome());
		assertEquals(1L, outcomes.stream()
				.filter(outcome -> outcome == EventCreationOutcome.CREATED).count());
		assertEquals(1L, outcomes.stream()
				.filter(outcome -> outcome == EventCreationOutcome.REPLAYED).count());

		assertEquals(race.first().result().event(), race.second().result().event());
		assertEquals(Optional.of(race.first().result().event()),
				eventRepository.findByIdempotencyKey(request.idempotencyKey()));
		assertEquals(1, countByKey(request.idempotencyKey()));
	}

	private ConcurrentResult runConcurrent(
			EventCreationRequest first,
			EventCreationRequest second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);

		try {
			Future<CreateAttempt> firstFuture = executor.submit(
					() -> awaitAndCreate(first, ready, release));
			Future<CreateAttempt> secondFuture = executor.submit(
					() -> awaitAndCreate(second, ready, release));

			assertTrue(ready.await(10, TimeUnit.SECONDS), "both callers must be ready");
			release.countDown();

			return new ConcurrentResult(
					firstFuture.get(10, TimeUnit.SECONDS),
					secondFuture.get(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
					"worker executor must terminate");
		}
	}

	private CreateAttempt awaitAndCreate(
			EventCreationRequest request,
			CountDownLatch ready,
			CountDownLatch release) throws InterruptedException {
		ready.countDown();
		if (!release.await(10, TimeUnit.SECONDS)) {
			throw new AssertionError("concurrent callers were not released");
		}

		try {
			return new CreateAttempt(eventCreationService.create(request), null);
		} catch (RuntimeException failure) {
			return new CreateAttempt(null, failure);
		}
	}

	private EventCreationRequest request(String eventType, String payload, String key) {
		return new EventCreationRequest(eventType, payload, key);
	}

	private String freshKey(String marker) {
		return "task-005-" + marker + "-" + UUID.randomUUID();
	}

	private int countByKey(String key) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM evidence_lab.events WHERE idempotency_key = ?",
				Integer.class,
				key);
	}
	private record CreateAttempt(EventCreationResult result, Throwable failure) {
		boolean succeeded() {
			return failure == null;
		}
	}

	private record ConcurrentResult(CreateAttempt first, CreateAttempt second) {
	}
}
