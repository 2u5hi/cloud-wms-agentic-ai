package com.cloudwms.core.orders.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;

/**
 * A customer order. Immutable: every operation returns a new order or throws
 * INVALID_STATE_TRANSITION. Inventory allocation itself happens in the inventory module; this records
 * how much of each line was allocated.
 *
 * @param priority 1 (most urgent) to 5
 */
public record Order(String externalRef, String customer, int priority, OrderStatus status, String carrier,
		Instant carrierCutoffAt, boolean onHold, String holdReason, List<OrderLine> lines) {

	public Order {
		Objects.requireNonNull(externalRef, "external reference is required");
		Objects.requireNonNull(status, "status is required");
		Objects.requireNonNull(carrierCutoffAt, "carrier cutoff is required");
		if (priority < 1 || priority > 5) {
			throw new IllegalArgumentException("priority must be 1-5, was " + priority);
		}
		if (onHold != (holdReason != null)) {
			throw new IllegalArgumentException("a held order needs a reason, and only a held order has one");
		}
		if (lines == null || lines.isEmpty()) {
			throw new IllegalArgumentException("an order needs at least one line");
		}
		Set<Integer> lineNumbers = new HashSet<>();
		for (OrderLine line : lines) {
			if (!lineNumbers.add(line.lineNo())) {
				throw new IllegalArgumentException("duplicate line number " + line.lineNo());
			}
		}
		lines = List.copyOf(lines);
	}

	/** A new order as imported from the host. */
	public static Order received(String externalRef, String customer, int priority, String carrier,
			Instant carrierCutoffAt, List<OrderLine> lines) {
		return new Order(externalRef, customer, priority, OrderStatus.RECEIVED, carrier, carrierCutoffAt, false, null,
				lines);
	}

	/** Units ordered but not allocated, across all lines. */
	public int shortQuantity() {
		return lines.stream().mapToInt(OrderLine::shortQuantity).sum();
	}

	public boolean canBePlanned() {
		return status == OrderStatus.RECEIVED && !onHold;
	}

	/**
	 * Records the stock allocated to each line when the order is planned into a wave. Lines missing from
	 * {@code allocatedByLine} get nothing; at least one unit must be allocated, or the order stays unplanned.
	 */
	public Order allocate(Map<Integer, Integer> allocatedByLine) {
		requireNotHeld("allocate");
		requireStatus(OrderStatus.ALLOCATED, "allocate");
		Map<Integer, Integer> remaining = new HashMap<>(allocatedByLine);
		List<OrderLine> allocated = lines.stream().map(line -> {
			int quantity = remaining.getOrDefault(line.lineNo(), 0);
			remaining.remove(line.lineNo());
			if (quantity < 0 || quantity > line.ordered()) {
				throw new IllegalArgumentException(
						"cannot allocate %d to line %d (ordered %d)".formatted(quantity, line.lineNo(), line.ordered()));
			}
			return line.withAllocated(quantity);
		}).toList();
		if (!remaining.isEmpty()) {
			throw new IllegalArgumentException("unknown order lines " + remaining.keySet());
		}
		if (allocated.stream().mapToInt(OrderLine::allocated).sum() == 0) {
			throw new IllegalArgumentException("an allocation must allocate at least one unit");
		}
		return with(OrderStatus.ALLOCATED, allocated);
	}

	/** Takes the order back out of a cancelled wave. The inventory module releases the stock itself. */
	public Order unallocate() {
		requireStatus(OrderStatus.RECEIVED, "unallocate");
		return with(OrderStatus.RECEIVED, lines.stream().map(line -> line.withAllocated(0)).toList());
	}

	public Order release() {
		requireNotHeld("release");
		requireStatus(OrderStatus.RELEASED, "release");
		return with(OrderStatus.RELEASED, lines);
	}

	public Order cancel() {
		requireStatus(OrderStatus.CANCELLED, "cancel");
		return with(OrderStatus.CANCELLED, lines.stream().map(line -> line.withAllocated(0)).toList());
	}

	public Order hold(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("a hold needs a reason");
		}
		if (onHold) {
			throw invalid("hold", "the order is already on hold");
		}
		if (!status.canBeHeld()) {
			throw invalid("hold", "a %s order cannot be held".formatted(status));
		}
		return new Order(externalRef, customer, priority, status, carrier, carrierCutoffAt, true, reason, lines);
	}

	public Order releaseHold() {
		if (!onHold) {
			throw invalid("release the hold on", "the order is not on hold");
		}
		return new Order(externalRef, customer, priority, status, carrier, carrierCutoffAt, false, null, lines);
	}

	public Order withPriority(int newPriority) {
		if (status.isTerminal()) {
			throw invalid("reprioritize", "the order is %s".formatted(status));
		}
		return new Order(externalRef, customer, newPriority, status, carrier, carrierCutoffAt, onHold, holdReason,
				lines);
	}

	private Order with(OrderStatus newStatus, List<OrderLine> newLines) {
		return new Order(externalRef, customer, priority, newStatus, carrier, carrierCutoffAt, onHold, holdReason,
				newLines);
	}

	private void requireStatus(OrderStatus target, String action) {
		if (!status.canMoveTo(target)) {
			throw invalid(action, "a %s order cannot move to %s".formatted(status, target));
		}
	}

	private void requireNotHeld(String action) {
		if (onHold) {
			throw invalid(action, "the order is on hold: " + holdReason);
		}
	}

	private DomainException invalid(String action, String why) {
		return new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
				"Cannot %s order %s: %s".formatted(action, externalRef, why),
				Map.of("order", externalRef, "status", status.name(), "onHold", onHold));
	}

}
