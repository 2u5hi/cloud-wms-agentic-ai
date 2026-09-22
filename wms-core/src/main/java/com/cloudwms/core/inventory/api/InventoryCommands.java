package com.cloudwms.core.inventory.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request bodies for inventory commands. SKUs and locations are referenced by business code. */
public final class InventoryCommands {

	private InventoryCommands() {
	}

	public record ReceiptRequest(@NotBlank @Size(max = 40) String sku, @NotBlank @Size(max = 30) String location,
			@Positive int quantity,
			@Valid @Schema(nullable = true, description = "What the receipt belongs to, e.g. a purchase order") ReferenceBody reference) {
	}

	public record MoveRequest(@NotBlank @Size(max = 40) String sku, @NotBlank @Size(max = 30) String fromLocation,
			@NotBlank @Size(max = 30) String toLocation, @Positive int quantity) {
	}

	public record AdjustmentRequest(@NotBlank @Size(max = 40) String sku, @NotBlank @Size(max = 30) String location,
			@NotNull @Schema(description = "Signed change: positive adds stock, negative removes it. Must not be 0.") Integer quantity,
			@NotBlank @Size(max = 200) String reason) {
	}

	public record ReferenceBody(@NotBlank @Size(max = 30) String type, @NotBlank @Size(max = 64) String id) {
	}

}
