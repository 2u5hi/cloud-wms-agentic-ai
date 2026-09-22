package com.cloudwms.core.inventory.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.cloudwms.core.shared.actor.Actor;

/**
 * One ledger entry: a quantity of a SKU leaving {@code fromLocationId} and/or arriving at
 * {@code toLocationId}. Quantity is always positive; the locations give the direction. This mirrors the
 * inventory_txn table, so a location's on-hand is everything that arrived minus everything that left.
 */
public record InventoryMovement(InventoryTxnType type, long skuId, Long fromLocationId, Long toLocationId,
		int quantity, String reason, Reference reference, Actor actor) {

	public InventoryMovement {
		Objects.requireNonNull(type, "type is required");
		Objects.requireNonNull(actor, "actor is required");
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, was " + quantity);
		}
		if (fromLocationId == null && toLocationId == null) {
			throw new IllegalArgumentException("a movement needs a from or to location");
		}
		if (Objects.equals(fromLocationId, toLocationId)) {
			throw new IllegalArgumentException("from and to location must differ");
		}
		switch (type) {
			case RECEIPT -> require(fromLocationId == null && toLocationId != null, "a receipt only has a to location");
			case PICK -> require(fromLocationId != null && toLocationId == null, "a pick only has a from location");
			case MOVE -> require(fromLocationId != null && toLocationId != null, "a move needs both locations");
			case ADJUST, COUNT_VARIANCE -> {
				require(fromLocationId == null || toLocationId == null, "a correction affects one location");
				require(reason != null && !reason.isBlank(), "a correction needs a reason");
			}
		}
	}

	public static InventoryMovement receipt(long skuId, long toLocationId, int quantity, Reference reference,
			Actor actor) {
		return new InventoryMovement(InventoryTxnType.RECEIPT, skuId, null, toLocationId, quantity, null, reference,
				actor);
	}

	public static InventoryMovement pick(long skuId, long fromLocationId, int quantity, Reference reference,
			Actor actor) {
		return new InventoryMovement(InventoryTxnType.PICK, skuId, fromLocationId, null, quantity, null, reference,
				actor);
	}

	public static InventoryMovement move(long skuId, long fromLocationId, long toLocationId, int quantity,
			Reference reference, Actor actor) {
		return new InventoryMovement(InventoryTxnType.MOVE, skuId, fromLocationId, toLocationId, quantity, null,
				reference, actor);
	}

	/** A correction at one location. A positive delta adds stock, a negative delta removes it. */
	public static InventoryMovement adjustment(long skuId, long locationId, int delta, String reason, Actor actor) {
		return correction(InventoryTxnType.ADJUST, skuId, locationId, delta, reason, null, actor);
	}

	public static InventoryMovement countVariance(long skuId, long locationId, int delta, String reason,
			Reference reference, Actor actor) {
		return correction(InventoryTxnType.COUNT_VARIANCE, skuId, locationId, delta, reason, reference, actor);
	}

	/** How this movement changes on-hand at each location it touches. */
	public List<StockDelta> effects() {
		List<StockDelta> effects = new ArrayList<>(2);
		if (fromLocationId != null) {
			effects.add(new StockDelta(new StockKey(fromLocationId, skuId), -quantity));
		}
		if (toLocationId != null) {
			effects.add(new StockDelta(new StockKey(toLocationId, skuId), quantity));
		}
		return List.copyOf(effects);
	}

	/**
	 * Computes the balances after this movement, all or nothing: if any location lacks available stock,
	 * this throws before returning anything, so a move never removes stock without also adding it.
	 *
	 * @param current the balance at a location before the movement (an empty balance if none exists)
	 */
	public List<InventoryBalance> applyTo(Function<StockKey, InventoryBalance> current) {
		return effects().stream().map(change -> current.apply(change.key()).apply(change)).toList();
	}

	private static InventoryMovement correction(InventoryTxnType type, long skuId, long locationId, int delta,
			String reason, Reference reference, Actor actor) {
		if (delta == 0) {
			throw new IllegalArgumentException("a correction must change the quantity");
		}
		Long from = delta < 0 ? locationId : null;
		Long to = delta > 0 ? locationId : null;
		return new InventoryMovement(type, skuId, from, to, Math.abs(delta), reason, reference, actor);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}

}
