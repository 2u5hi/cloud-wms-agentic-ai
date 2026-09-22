package com.cloudwms.core.inventory;

import java.util.Optional;

import com.cloudwms.core.inventory.domain.InventoryBalance;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.inventory.domain.StockKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class InventoryRepository {

	private final JdbcClient jdbc;

	InventoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Long> findLocationId(String code) {
		return jdbc.sql("SELECT id FROM location WHERE code = ?").param(code).query(Long.class).optional();
	}

	Optional<Long> findSkuId(String code) {
		return jdbc.sql("SELECT id FROM sku WHERE code = ?").param(code).query(Long.class).optional();
	}

	boolean locationExists(long locationId) {
		return jdbc.sql("SELECT COUNT(*) FROM location WHERE id = ?").param(locationId).query(Long.class).single() > 0;
	}

	boolean skuExists(long skuId) {
		return jdbc.sql("SELECT COUNT(*) FROM sku WHERE id = ?").param(skuId).query(Long.class).single() > 0;
	}

	/**
	 * Locks the balance row for update, creating an empty one first if this SKU has never been at the
	 * location. Must be called inside a transaction; the lock is held until it commits or rolls back.
	 *
	 * <p>The upsert uses ON DUPLICATE KEY rather than INSERT IGNORE, which would also silently swallow
	 * foreign key errors.
	 */
	InventoryBalance lockBalance(StockKey key) {
		jdbc.sql("""
				INSERT INTO inventory_balance (location_id, sku_id) VALUES (?, ?)
				ON DUPLICATE KEY UPDATE location_id = location_id""")
			.params(key.locationId(), key.skuId())
			.update();
		return jdbc.sql("""
				SELECT on_hand, allocated FROM inventory_balance
				WHERE location_id = ? AND sku_id = ?
				FOR UPDATE""")
			.params(key.locationId(), key.skuId())
			.query((rs, row) -> new InventoryBalance(key, rs.getInt("on_hand"), rs.getInt("allocated")))
			.single();
	}

	void saveBalance(InventoryBalance balance) {
		jdbc.sql("""
				UPDATE inventory_balance SET on_hand = ?, allocated = ?, version = version + 1
				WHERE location_id = ? AND sku_id = ?""")
			.params(balance.onHand(), balance.allocated(), balance.key().locationId(), balance.key().skuId())
			.update();
	}

	long insertMovement(InventoryMovement movement) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO inventory_txn (type, sku_id, from_location_id, to_location_id, quantity, reason,
				    reference_type, reference_id, actor_type, actor_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(movement.type().name(), movement.skuId(), movement.fromLocationId(), movement.toLocationId(),
					movement.quantity(), movement.reason(),
					movement.reference() == null ? null : movement.reference().type(),
					movement.reference() == null ? null : movement.reference().id(), movement.actor().type().name(),
					movement.actor().id())
			.update(keys);
		return keys.getKey().longValue();
	}

}
