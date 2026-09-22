package com.cloudwms.core.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

	/** The ledger row id and the balances after the movement. */
	public record RecordedMovement(long transactionId, List<InventoryBalance> balances) {
	}

}
