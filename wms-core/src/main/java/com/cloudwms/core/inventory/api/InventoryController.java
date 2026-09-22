package com.cloudwms.core.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.cloudwms.core.inventory.InventoryService.RecordedMovement;
import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.inventory.api.InventoryCommands.AdjustmentRequest;
import com.cloudwms.core.inventory.api.InventoryCommands.MoveRequest;
import com.cloudwms.core.inventory.api.InventoryCommands.ReceiptRequest;
import com.cloudwms.core.inventory.api.InventoryQueries.BalanceFilter;
import com.cloudwms.core.inventory.api.InventoryQueries.TransactionFilter;
import com.cloudwms.core.inventory.api.InventoryViews.BalanceView;
import com.cloudwms.core.inventory.api.InventoryViews.MovementResultView;
import com.cloudwms.core.inventory.api.InventoryViews.SkuAvailabilityView;
import com.cloudwms.core.inventory.api.InventoryViews.TransactionView;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.inventory.domain.InventoryTxnType;
import com.cloudwms.core.inventory.domain.LocationType;
import com.cloudwms.core.inventory.domain.Reference;
import com.cloudwms.core.shared.actor.CurrentActor;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Inventory", description = "Stock by location and SKU")
class InventoryController {

	private final InventoryQueries queries;
	private final InventoryService inventory;
	private final CurrentActor actor;

	InventoryController(InventoryQueries queries, InventoryService inventory, CurrentActor actor) {
		this.queries = queries;
		this.inventory = inventory;
		this.actor = actor;
	}

	@PostMapping("/inventory/receipts")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "recordReceipt", summary = "Receive stock into a location")
	ResponseEntity<MovementResultView> receive(@Valid @RequestBody ReceiptRequest request) {
		Reference reference = request.reference() == null ? null
				: new Reference(request.reference().type(), request.reference().id());
		return created(InventoryMovement.receipt(inventory.skuId(request.sku()), inventory.locationId(request.location()),
				request.quantity(), reference, actor.get()));
	}

	@PostMapping("/inventory/moves")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "recordMove", summary = "Move stock between locations",
			description = "For example replenishment from reserve to forward-pick. All or nothing.")
	ResponseEntity<MovementResultView> move(@Valid @RequestBody MoveRequest request) {
		if (request.fromLocation().equals(request.toLocation())) {
			throw invalid("toLocation", "must differ from fromLocation");
		}
		return created(InventoryMovement.move(inventory.skuId(request.sku()),
				inventory.locationId(request.fromLocation()), inventory.locationId(request.toLocation()),
				request.quantity(), null, actor.get()));
	}

	@PostMapping("/inventory/adjustments")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "recordAdjustment", summary = "Correct stock at a location",
			description = "A signed correction with a mandatory reason, e.g. -4 for damaged units.")
	ResponseEntity<MovementResultView> adjust(@Valid @RequestBody AdjustmentRequest request) {
		if (request.quantity() == 0) {
			throw invalid("quantity", "must not be 0");
		}
		return created(InventoryMovement.adjustment(inventory.skuId(request.sku()),
				inventory.locationId(request.location()), request.quantity(), request.reason(), actor.get()));
	}

	@GetMapping("/inventory/transactions")
	@Operation(operationId = "listTransactions", summary = "List ledger entries", description = "Newest first.")
	Page<TransactionView> transactions(@RequestParam(required = false) String sku,
			@RequestParam(required = false) String location, @RequestParam(required = false) InventoryTxnType type,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.transactions(new TransactionFilter(sku, location, type), cursor, limit);
	}

	@GetMapping("/inventory/transactions/{id}")
	@Operation(operationId = "getTransaction", summary = "Get a ledger entry")
	TransactionView transaction(@PathVariable long id) {
		return queries.transaction(id)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Transaction %d does not exist".formatted(id),
					Map.of("transactionId", id)));
	}

	/** Records the movement, then answers 201 with the ledger entry's URL and the balances it changed. */
	private ResponseEntity<MovementResultView> created(InventoryMovement movement) {
		RecordedMovement recorded = inventory.record(movement);
		TransactionView transaction = queries.transaction(recorded.transactionId()).orElseThrow();
		List<BalanceView> balances = queries.balancesAt(recorded.balances()
			.stream()
			.map(balance -> new long[] { balance.key().locationId(), balance.key().skuId() })
			.toList());
		return ResponseEntity.created(URI.create("/api/v1/inventory/transactions/" + recorded.transactionId()))
			.body(new MovementResultView(transaction, balances));
	}

	private static DomainException invalid(String field, String message) {
		return new DomainException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.",
				Map.of("errors", List.of(Map.of("field", field, "message", message))));
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
