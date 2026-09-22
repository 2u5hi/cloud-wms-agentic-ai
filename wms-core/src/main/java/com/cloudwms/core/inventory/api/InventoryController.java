package com.cloudwms.core.inventory.api;

import java.util.Map;

import com.cloudwms.core.inventory.api.InventoryQueries.BalanceFilter;
import com.cloudwms.core.inventory.api.InventoryViews.BalanceView;
import com.cloudwms.core.inventory.api.InventoryViews.SkuAvailabilityView;
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
@Tag(name = "Inventory", description = "Stock by location and SKU")
class InventoryController {

	private final InventoryQueries queries;

	InventoryController(InventoryQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/inventory")
	@Operation(operationId = "listBalances", summary = "List inventory balances",
			description = "Stock per location and SKU. Balances with nothing on hand or allocated are omitted "
					+ "unless includeEmpty is true.")
	Page<BalanceView> balances(@RequestParam(required = false) String sku,
			@RequestParam(required = false) String location, @RequestParam(required = false) String zone,
			@RequestParam(required = false) LocationType type,
			@RequestParam(defaultValue = "false") boolean includeEmpty, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.balances(new BalanceFilter(sku, location, zone, type, includeEmpty), cursor, limit);
	}

	@GetMapping("/skus/{code}/availability")
	@Operation(operationId = "getSkuAvailability", summary = "Get a SKU's availability",
			description = "Totals across the warehouse, broken down by location type (forward-pick, reserve, ...).")
	SkuAvailabilityView availability(@PathVariable String code) {
		return queries.availability(code)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "SKU %s does not exist".formatted(code),
					Map.of("sku", code)));
	}

}
