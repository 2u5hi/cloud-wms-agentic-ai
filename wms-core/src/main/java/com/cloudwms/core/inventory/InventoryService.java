package com.cloudwms.core.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

import com.cloudwms.core.inventory.domain.InventoryBalance;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.inventory.domain.StockDelta;
import com.cloudwms.core.inventory.domain.StockKey;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

	/** Every transaction locks balance rows in this order, so two transactions can never wait on each other. */
	static final Comparator<StockKey> LOCK_ORDER = Comparator.comparingLong(StockKey::locationId)
		.thenComparingLong(StockKey::skuId);

	private final InventoryRepository repository;

	InventoryService(InventoryRepository repository) {
		this.repository = repository;
	}

	/**
	 * Records a movement: updates the affected balances and appends the ledger row in one transaction.
	 * Throws INSUFFICIENT_INVENTORY (and changes nothing) if a source location lacks available stock.
	 */
	@Transactional
	public RecordedMovement record(InventoryMovement movement) {
		requireExists(movement);

		Map<StockKey, InventoryBalance> locked = new TreeMap<>(LOCK_ORDER);
		movement.effects()
			.stream()
			.map(StockDelta::key)
			.sorted(LOCK_ORDER)
			.forEach(key -> locked.put(key, repository.lockBalance(key)));

		List<InventoryBalance> updated = movement.applyTo(locked::get);
		updated.forEach(repository::saveBalance);
		long transactionId = repository.insertMovement(movement);
		return new RecordedMovement(transactionId, updated);
	}

	/**
	 * Promises stock to orders: raises {@code allocated} at each location, locking rows in {@link #LOCK_ORDER}.
	 * Throws INSUFFICIENT_INVENTORY (rolling the caller back) if another transaction took the stock first.
	 */
	@Transactional
	public void allocate(Map<StockKey, Integer> quantities) {
		apply(quantities, InventoryBalance::allocate);
	}

	/**
	 * Moves stock that is promised to orders, e.g. a replenishment from reserve to a forward-pick slot.
	 * The promise travels with the stock: it is released at the source, moved, and re-promised at the
	 * destination, all in one transaction, so {@code allocated <= on_hand} holds at both ends.
	 */
	@Transactional
	public RecordedMovement moveAllocated(InventoryMovement move, int allocatedQuantity) {
		StockKey from = new StockKey(requireLocation(move.fromLocationId(), "move"), move.skuId());
		StockKey to = new StockKey(requireLocation(move.toLocationId(), "move"), move.skuId());
		releaseAllocation(Map.of(from, allocatedQuantity));
		RecordedMovement recorded = record(move);
		allocate(Map.of(to, allocatedQuantity));
		return recorded;
	}

	/** Takes promised stock off the shelf: the promise is consumed, then the stock leaves the location. */
	@Transactional
	public RecordedMovement pickAllocated(InventoryMovement pick) {
		StockKey from = new StockKey(requireLocation(pick.fromLocationId(), "pick"), pick.skuId());
		releaseAllocation(Map.of(from, pick.quantity()));
		return record(pick);
	}

	private static long requireLocation(Long locationId, String action) {
		if (locationId == null) {
			throw new IllegalArgumentException("a " + action + " needs a location");
		}
		return locationId;
	}

	/** Gives promised stock back, e.g. when a wave is cancelled. */
	@Transactional
	public void releaseAllocation(Map<StockKey, Integer> quantities) {
		apply(quantities, InventoryBalance::deallocate);
	}

	private void apply(Map<StockKey, Integer> quantities, BiFunction<InventoryBalance, Integer, InventoryBalance> change) {
		quantities.entrySet()
			.stream()
			.filter(entry -> entry.getValue() > 0)
			.sorted(Map.Entry.comparingByKey(LOCK_ORDER))
			.forEach(entry -> repository
				.saveBalance(change.apply(repository.lockBalance(entry.getKey()), entry.getValue())));
	}

	/** Resolves a SKU code to its id, or NOT_FOUND. */
	public long skuId(String code) {
		return repository.findSkuId(code).orElseThrow(() -> notFound("SKU", code));
	}

	/** Resolves a location code to its id, or NOT_FOUND. */
	public long locationId(String code) {
		return repository.findLocationId(code).orElseThrow(() -> notFound("Location", code));
	}

	private void requireExists(InventoryMovement movement) {
		if (!repository.skuExists(movement.skuId())) {
			throw notFound("SKU", movement.skuId());
		}
		for (Long locationId : new Long[] { movement.fromLocationId(), movement.toLocationId() }) {
			if (locationId != null && !repository.locationExists(locationId)) {
				throw notFound("Location", locationId);
			}
		}
	}

	private static DomainException notFound(String what, long id) {
		return new DomainException(ErrorCode.NOT_FOUND, "%s %d does not exist".formatted(what, id),
				Map.of(what.toLowerCase() + "Id", id));
	}

	private static DomainException notFound(String what, String code) {
		return new DomainException(ErrorCode.NOT_FOUND, "%s %s does not exist".formatted(what, code),
				Map.of(what.toLowerCase(), code));
	}

	/** The ledger row id and the balances after the movement. */
	public record RecordedMovement(long transactionId, List<InventoryBalance> balances) {
	}

}
