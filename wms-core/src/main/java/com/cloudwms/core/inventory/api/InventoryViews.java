package com.cloudwms.core.inventory.api;

import java.util.List;

import com.cloudwms.core.inventory.domain.LocationType;

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

}
