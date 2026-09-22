package com.cloudwms.core.inventory.api;

import com.cloudwms.core.inventory.domain.LocationType;
import io.swagger.v3.oas.annotations.media.Schema;

/** Response bodies for zones, locations, and SKUs. Resources are identified by business code. */
public final class MasterDataViews {

	private MasterDataViews() {
	}

	public record ZoneView(String code, String name) {
	}

	public record LocationView(String code, String zone, LocationType type,
			@Schema(nullable = true, description = "Walking order for pick-path sequencing") Integer pickSequence,
			@Schema(nullable = true) Integer capacityUnits,
			@Schema(nullable = true, description = "Equipment needed to reach it, e.g. REACH_TRUCK") String requiredEquipment,
			boolean active,
			@Schema(nullable = true, description = "Set for forward-pick slots") PickSlotView pickSlot) {
	}

	/** The SKU slotted in a forward-pick location and its replenishment thresholds. */
	public record PickSlotView(String sku, int minQty, int maxQty) {
	}

	public record SkuView(String code, String description, String uom,
			@Schema(nullable = true, allowableValues = { "A", "B", "C" }) String velocityClass, boolean active) {
	}

}
