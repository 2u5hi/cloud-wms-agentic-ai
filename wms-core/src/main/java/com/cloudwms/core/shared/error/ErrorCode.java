package com.cloudwms.core.shared.error;

import java.net.URI;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of every problem response.
 * Clients (the ops console, the agent, integrations) branch on these, so names must not change.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
	INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
	VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict"),
	INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT, "Insufficient inventory"),
	PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "Precondition failed"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	public URI type() {
		return URI.create("urn:wms:problem:" + name().toLowerCase().replace('_', '-'));
	}

}
