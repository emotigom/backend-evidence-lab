package com.emotigom.backend.event;

import java.time.Instant;
import java.util.UUID;

public record Event(UUID id, String eventType, String payload, Instant createdAt, String idempotencyKey) {
}
