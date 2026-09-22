package com.cloudwms.core.shared.error;

import java.util.Map;

/**
 * Thrown by domain and application code when a business rule rejects a request.
 * {@code properties} are added to the problem response so clients get structured context
 * (for example the SKU and quantities behind an INSUFFICIENT_INVENTORY error).
 */
public class DomainException extends RuntimeException {

	private final ErrorCode code;
	private final Map<String, Object> properties;

	public DomainException(ErrorCode code, String detail) {
		this(code, detail, Map.of());
	}

	public DomainException(ErrorCode code, String detail, Map<String, Object> properties) {
		super(detail);
		this.code = code;
		this.properties = Map.copyOf(properties);
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}

}
