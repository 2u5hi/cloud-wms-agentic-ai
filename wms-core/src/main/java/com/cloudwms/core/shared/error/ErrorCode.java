package com.cloudwms.core.shared.error;

import java.net.URI;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of every problem response.
 * Clients (the ops console, the agent, integrations) branch on these, so names must not change.
 * Deliberately free of HTTP concerns so domain code can use it; the HTTP status mapping lives in
 * {@link ApiExceptionHandler}.
 */
public enum ErrorCode {

	VALIDATION_FAILED("Request validation failed"),
	NOT_FOUND("Resource not found"),
	INVALID_STATE_TRANSITION("Invalid state transition"),
	VERSION_CONFLICT("Version conflict"),
	INSUFFICIENT_INVENTORY("Insufficient inventory"),
	PRECONDITION_FAILED("Precondition failed"),
	NOT_ELIGIBLE("Not eligible for this work"),
	IDEMPOTENCY_KEY_REUSED("Idempotency key reused with a different request"),
	CONCURRENCY_CONFLICT("Concurrent update conflict; retry the request"),
	INTERNAL_ERROR("Internal error");

	private final String title;

	ErrorCode(String title) {
		this.title = title;
	}

	public String title() {
		return title;
	}

	public URI type() {
		return URI.create("urn:wms:problem:" + name().toLowerCase().replace('_', '-'));
	}

}
