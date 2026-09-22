package com.cloudwms.core.orders.api;

import java.time.Instant;
import java.util.List;

import com.cloudwms.core.orders.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public final class OrderViews {

	private OrderViews() {
	}

	/** An order in a list, with its line quantities summed. */
	public record OrderSummaryView(String externalRef, String customer, int priority, OrderStatus status,
			String carrier, Instant carrierCutoffAt, boolean onHold, @Schema(nullable = true) String holdReason,
			int lines, int unitsOrdered, int unitsAllocated,
			@Schema(description = "Units ordered but not allocated") int shortQuantity, Instant receivedAt) {
	}

	public record OrderView(String externalRef, String customer, int priority, OrderStatus status, String carrier,
			Instant carrierCutoffAt, boolean onHold, @Schema(nullable = true) String holdReason, Instant receivedAt,
			List<OrderLineView> lines) {
	}

	public record OrderLineView(int lineNo, String sku, int ordered, int allocated, int picked, int shipped,
			int shortQuantity) {
	}

}
