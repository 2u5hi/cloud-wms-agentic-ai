package com.cloudwms.core.orders.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an order is in fulfilment. Holds are tracked separately ({@link Order#onHold()}), so these
 * states only move forward, except ALLOCATED -> RECEIVED when a planned wave is cancelled.
 */
public enum OrderStatus {

	/** Imported from the host; waiting to be planned into a wave. */
	RECEIVED,
	/** Planned into a wave with stock allocated (possibly short on some lines). */
	ALLOCATED,
	/** Its wave was released; pick tasks are available to workers. */
	RELEASED,
	PICKING,
	PICKED,
	PACKED,
	SHIPPED,
	CANCELLED;

	public Set<OrderStatus> next() {
		return switch (this) {
			case RECEIVED -> EnumSet.of(ALLOCATED, CANCELLED);
			case ALLOCATED -> EnumSet.of(RELEASED, RECEIVED, CANCELLED);
			case RELEASED -> EnumSet.of(PICKING);
			case PICKING -> EnumSet.of(PICKED);
			case PICKED -> EnumSet.of(PACKED);
			case PACKED -> EnumSet.of(SHIPPED);
			case SHIPPED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
		};
	}

	public boolean canMoveTo(OrderStatus target) {
		return next().contains(target);
	}

	/** Holds stop work in progress; once packed, the order is effectively out of the building. */
	public boolean canBeHeld() {
		return this == RECEIVED || this == ALLOCATED || this == RELEASED || this == PICKING || this == PICKED;
	}

	public boolean isTerminal() {
		return next().isEmpty();
	}

}
