package com.cloudwms.core.inventory;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Creates master data with unique codes, so tests that commit (and can never delete ledger rows) don't
 * collide with each other in the shared test database.
 */
final class InventoryFixtures {

	private final JdbcClient jdbc;
	private final String prefix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

	InventoryFixtures(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	long zone() {
		return insert("INSERT INTO zone (code, name) VALUES (?, 'Test zone')", prefix);
	}

	long location(long zoneId, String type) {
		return insert("INSERT INTO location (code, zone_id, type) VALUES (?, ?, ?)",
				prefix + "-" + UUID.randomUUID().toString().substring(0, 8), zoneId, type);
	}

	long location(long zoneId, String type, Integer pickSequence) {
		return insert("INSERT INTO location (code, zone_id, type, pick_sequence) VALUES (?, ?, ?, ?)",
				prefix + "-" + UUID.randomUUID().toString().substring(0, 8), zoneId, type, pickSequence);
	}

	void pickSlot(long locationId, long skuId, int minQty, int maxQty) {
		insert("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, ?, ?)", locationId, skuId,
				minQty, maxQty);
	}

	/** The business code of a zone, location, or SKU row. */
	String code(String table, long id) {
		if (!table.matches("zone|location|sku")) {
			throw new IllegalArgumentException(table);
		}
		return jdbc.sql("SELECT code FROM " + table + " WHERE id = ?").param(id).query(String.class).single();
	}

	long locationIdByCode(String code) {
		return jdbc.sql("SELECT id FROM location WHERE code = ?").param(code).query(Long.class).single();
	}

	long skuIdByCode(String code) {
		return jdbc.sql("SELECT id FROM sku WHERE code = ?").param(code).query(Long.class).single();
	}

	long sku() {
		return insert("INSERT INTO sku (code, description) VALUES (?, 'Test SKU')",
				prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
	}

	int onHand(long locationId, long skuId) {
		return jdbc.sql("SELECT on_hand FROM inventory_balance WHERE location_id = ? AND sku_id = ?")
			.params(locationId, skuId)
			.query(Integer.class)
			.optional()
			.orElse(0);
	}

	boolean balanceRowExists(long locationId, long skuId) {
		return jdbc.sql("SELECT COUNT(*) FROM inventory_balance WHERE location_id = ? AND sku_id = ?")
			.params(locationId, skuId)
			.query(Long.class)
			.single() > 0;
	}

	long ledgerRows(long skuId) {
		return jdbc.sql("SELECT COUNT(*) FROM inventory_txn WHERE sku_id = ?").param(skuId).query(Long.class).single();
	}

	/** On-hand according to the ledger: arrivals minus departures. */
	int ledgerNet(long locationId, long skuId) {
		return jdbc.sql("""
				SELECT COALESCE(SUM(CASE WHEN to_location_id = ? THEN quantity ELSE -quantity END), 0)
				FROM inventory_txn
				WHERE sku_id = ? AND (to_location_id = ? OR from_location_id = ?)""")
			.params(locationId, skuId, locationId, locationId)
			.query(Integer.class)
			.single();
	}

	private long insert(String sql, Object... params) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

}
