package com.cloudwms.core.inventory.api;

import java.time.Instant;
import java.util.List;

import com.cloudwms.core.inventory.domain.ActorType;
import com.cloudwms.core.inventory.domain.InventoryTxnType;
import com.cloudwms.core.inventory.domain.LocationType;
import io.swagger.v3.oas.annotations.media.Schema;

/** Response bodies for inventory. {@code available = onHand - allocated}. */
public final class InventoryViews {

	private InventoryViews() {
	}

	/** Stock of one SKU at one location. */
	public record BalanceView(String location, String zone, LocationType locationType, String sku, int onHand,
			int allocated, int available) {
	}

	public record Quantities(int onHand, int allocated, int available) {
	}

	/** A SKU's stock across the warehouse, split by where it sits (forward-pick vs. reserve, ...). */
	public record SkuAvailabilityView(String sku, Quantities total, List<LocationTypeAvailability> byLocationType) {
	}

	public record LocationTypeAvailability(LocationType locationType, int locations, int onHand, int allocated,
			int available) {
	}

	/** One ledger entry. Stock left {@code fromLocation} and/or arrived at {@code toLocation}. */
	public record TransactionView(long id, InventoryTxnType type, String sku,
			@Schema(nullable = true) String fromLocation, @Schema(nullable = true) String toLocation, int quantity,
			@Schema(nullable = true) String reason, @Schema(nullable = true) ReferenceView reference, ActorView actor,
			Instant occurredAt) {
	}

	public record ReferenceView(String type, String id) {
	}

	public record ActorView(ActorType type, String id) {
	}

	/** The result of a command: the ledger entry written and the balances it changed. */
	public record MovementResultView(TransactionView transaction, List<BalanceView> balances) {
	}

}
