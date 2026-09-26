package com.emotigom.backend.event;

public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException(String idempotencyKey) {
		super("Idempotency key was reused with different event data: " + idempotencyKey);
	}
}
