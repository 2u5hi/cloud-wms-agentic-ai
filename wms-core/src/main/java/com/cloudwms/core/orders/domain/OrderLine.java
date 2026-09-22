package com.cloudwms.core.orders.domain;

/**
 * One SKU on an order. Invariant: {@code 0 <= shipped <= picked <= allocated <= ordered}, with at least
 * one unit ordered.
 */
public record OrderLine(int lineNo, long skuId, int ordered, int allocated, int picked, int shipped) {

	public OrderLine {
		if (lineNo <= 0) {
			throw new IllegalArgumentException("line number must be positive");
		}
		if (ordered <= 0 || allocated < 0 || picked < 0 || shipped < 0 || allocated > ordered || picked > allocated
				|| shipped > picked) {
			throw new IllegalArgumentException("invalid quantities on line %d: ordered=%d allocated=%d picked=%d shipped=%d"
				.formatted(lineNo, ordered, allocated, picked, shipped));
		}
	}

	public static OrderLine of(int lineNo, long skuId, int ordered) {
		return new OrderLine(lineNo, skuId, ordered, 0, 0, 0);
	}

	/** Units ordered but not allocated. */
	public int shortQuantity() {
		return ordered - allocated;
	}

	OrderLine withAllocated(int quantity) {
		return new OrderLine(lineNo, skuId, ordered, quantity, picked, shipped);
	}

}
