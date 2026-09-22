package com.cloudwms.core.orders.api;

import java.util.Map;

import com.cloudwms.core.orders.api.OrderViews.OrderSummaryView;
import com.cloudwms.core.orders.api.OrderViews.OrderView;
import com.cloudwms.core.orders.domain.OrderStatus;
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
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Customer orders and their fulfilment status")
class OrdersController {

	private final OrderQueries queries;

	OrdersController(OrderQueries queries) {
		this.queries = queries;
	}

	@GetMapping
	@Operation(operationId = "listOrders", summary = "List orders", description = "In the order they were received.")
	Page<OrderSummaryView> orders(@RequestParam(required = false) OrderStatus status,
			@RequestParam(required = false) Boolean onHold, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.orders(status, onHold, cursor, limit);
	}

	@GetMapping("/{externalRef}")
	@Operation(operationId = "getOrder", summary = "Get an order with its lines")
	OrderView order(@PathVariable String externalRef) {
		return queries.order(externalRef)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Order %s does not exist".formatted(externalRef),
					Map.of("order", externalRef)));
	}

}
