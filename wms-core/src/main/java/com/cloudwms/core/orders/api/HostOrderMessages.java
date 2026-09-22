package com.cloudwms.core.orders.api;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The host (ERP / order management) order interface. The batch itself must be well formed; each order
 * in it is then validated on its own, so one bad order doesn't reject the others.
 */
public final class HostOrderMessages {

	private HostOrderMessages() {
	}

	public record OrderImportRequest(@NotNull @Size(min = 1, max = 500) List<HostOrder> orders) {
	}

	public record HostOrder(@NotBlank @Size(max = 64) String externalRef, @NotBlank @Size(max = 100) String customer,
			@Min(1) @Max(5) @Schema(nullable = true, description = "1 (most urgent) to 5; defaults to 3") Integer priority,
			@NotBlank @Size(max = 30) String carrier, @NotNull Instant carrierCutoffAt,
			@NotEmpty @Size(max = 200) List<@Valid HostOrderLine> lines) {
	}

	public record HostOrderLine(@Positive int lineNo, @NotBlank @Size(max = 40) String sku, @Positive int quantity) {
	}

	public enum ImportResult {

		/** Stored as a new order. */
		ACCEPTED,
		/** An order with this external reference already exists; it was not changed. */
		DUPLICATE,
		/** Invalid; see errors. Nothing was stored. */
		REJECTED

	}

	public record FieldError(String field, String message) {
	}

	public record OrderResult(@Schema(nullable = true, description = "Null only if the order had no reference") String externalRef,
			ImportResult result, List<FieldError> errors) {
	}

	public record OrderImportResponse(int accepted, int duplicates, int rejected, List<OrderResult> results) {
	}

}
