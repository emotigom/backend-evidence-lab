package com.emotigom.backend.event;

public record EventCreationRequest(String eventType, String payload, String idempotencyKey) {
}
