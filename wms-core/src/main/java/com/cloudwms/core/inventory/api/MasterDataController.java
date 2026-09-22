package com.cloudwms.core.inventory.api;

import java.util.Map;

import com.cloudwms.core.inventory.api.MasterDataViews.LocationView;
import com.cloudwms.core.inventory.api.MasterDataViews.SkuView;
import com.cloudwms.core.inventory.api.MasterDataViews.ZoneView;
import com.cloudwms.core.inventory.domain.LocationType;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Master data", description = "Zones, locations, and SKUs")
class MasterDataController {

	private final MasterDataQueries queries;

	MasterDataController(MasterDataQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/zones")
	@Operation(operationId = "listZones", summary = "List zones")
	Page<ZoneView> zones(@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.zones(cursor, limit);
	}

	@GetMapping("/locations")
	@Operation(operationId = "listLocations", summary = "List locations", description = "Optionally filtered by zone code and location type.")
	Page<LocationView> locations(@RequestParam(required = false) String zone,
			@RequestParam(required = false) LocationType type, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.locations(zone, type, cursor, limit);
	}

	@GetMapping("/locations/{code}")
	@Operation(operationId = "getLocation", summary = "Get a location", description = "Includes the pick slot for forward-pick locations.")
	LocationView location(@PathVariable String code) {
		return queries.location(code).orElseThrow(() -> notFound("Location", code));
	}

	@GetMapping("/skus")
	@Operation(operationId = "listSkus", summary = "List SKUs")
	Page<SkuView> skus(@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.skus(cursor, limit);
	}

	@GetMapping("/skus/{code}")
	@Operation(operationId = "getSku", summary = "Get a SKU")
	SkuView sku(@PathVariable String code) {
		return queries.sku(code).orElseThrow(() -> notFound("SKU", code));
	}

	private static DomainException notFound(String what, String code) {
		return new DomainException(ErrorCode.NOT_FOUND, "%s %s does not exist".formatted(what, code),
				Map.of(what.toLowerCase(), code));
	}

}
