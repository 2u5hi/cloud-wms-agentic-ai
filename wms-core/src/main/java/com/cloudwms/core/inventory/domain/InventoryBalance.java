package com.cloudwms.core.inventory.domain;

import java.util.Map;
import java.util.Objects;

import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;

/**
 * Stock of one SKU at one location. Immutable: every operation returns a new balance.
 *
 * <p>Invariant: {@code 0 <= allocated <= onHand}. {@code available} is what can still be picked,
 * moved, or allocated; allocated stock is promised to orders and can't be taken by anything else.
 */
public record InventoryBalance(StockKey key, int onHand, int allocated) {

	public InventoryBalance {
		Objects.requireNonNull(key, "key is required");
		if (onHand < 0 || allocated < 0 || allocated > onHand) {
			throw new IllegalArgumentException(
					"invalid balance for %s: onHand=%d allocated=%d".formatted(key, onHand, allocated));
		}
	}

	public static InventoryBalance empty(StockKey key) {
		return new InventoryBalance(key, 0, 0);
	}

	public int available() {
		return onHand - allocated;
	}

	public InventoryBalance increase(int quantity) {
		requirePositive(quantity);
		return new InventoryBalance(key, Math.addExact(onHand, quantity), allocated);
	}

	/** Removes stock. Only available stock can be removed; allocated stock must be deallocated first. */
	public InventoryBalance decrease(int quantity) {
		requirePositive(quantity);
		requireAvailable(quantity);
		return new InventoryBalance(key, onHand - quantity, allocated);
	}

	public InventoryBalance allocate(int quantity) {
		requirePositive(quantity);
		requireAvailable(quantity);
		return new InventoryBalance(key, onHand, allocated + quantity);
	}

	public InventoryBalance deallocate(int quantity) {
		requirePositive(quantity);
		if (quantity > allocated) {
			throw new IllegalArgumentException(
					"cannot deallocate %d at %s: only %d allocated".formatted(quantity, key, allocated));
		}
		return new InventoryBalance(key, onHand, allocated - quantity);
	}

	/** Applies a movement's effect on this location: positive deltas add stock, negative remove it. */
	public InventoryBalance apply(StockDelta change) {
		if (!change.key().equals(key)) {
			throw new IllegalArgumentException("delta for %s applied to %s".formatted(change.key(), key));
		}
		return change.delta() > 0 ? increase(change.delta()) : decrease(-change.delta());
	}

	private void requireAvailable(int quantity) {
		if (quantity > available()) {
			throw new DomainException(ErrorCode.INSUFFICIENT_INVENTORY,
					"Only %d of %d units available".formatted(available(), quantity),
					Map.of("locationId", key.locationId(), "skuId", key.skuId(), "requested", quantity, "available",
							available()));
		}
	}

	private static void requirePositive(int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, was " + quantity);
		}
	}

}
