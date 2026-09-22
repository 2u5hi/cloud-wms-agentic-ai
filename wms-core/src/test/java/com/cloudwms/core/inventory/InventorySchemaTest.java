package com.cloudwms.core.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudwms.core.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the database itself enforces the inventory invariants, independent of application code.
 * Each test runs in a transaction that is rolled back.
 */
@IntegrationTest
@Transactional
class InventorySchemaTest {

	@Autowired
	JdbcClient jdbc;

	long zoneId;
	long forwardPickId;
	long reserveId;
	long skuId;

	@BeforeEach
	void seed() {
		zoneId = insert("INSERT INTO zone (code, name) VALUES ('T', 'Test zone')");
		forwardPickId = insert("INSERT INTO location (code, zone_id, type, pick_sequence) VALUES ('T-01-01-A', "
				+ zoneId + ", 'FORWARD_PICK', 10)");
		reserveId = insert(
				"INSERT INTO location (code, zone_id, type) VALUES ('T-01-01-D', " + zoneId + ", 'RESERVE')");
		skuId = insert("INSERT INTO sku (code, description) VALUES ('TEST-SKU-1', 'Test SKU')");
	}

	@Test
	void validBalanceAndLedgerRowsAreAccepted() {
		jdbc.sql("INSERT INTO inventory_balance (location_id, sku_id, on_hand, allocated) VALUES (?, ?, 10, 4)")
			.params(forwardPickId, skuId)
			.update();
		jdbc.sql("""
				INSERT INTO inventory_txn (type, sku_id, to_location_id, quantity, actor_type, actor_id)
				VALUES ('RECEIPT', ?, ?, 10, 'SYSTEM', 'test')""").params(skuId, forwardPickId).update();

		Integer available = jdbc.sql("SELECT on_hand - allocated FROM inventory_balance WHERE location_id = ?")
			.param(forwardPickId)
			.query(Integer.class)
			.single();
		assertThat(available).isEqualTo(6);
	}

	@Test
	void negativeOnHandIsRejected() {
		// Negative on_hand breaks both ck_balance_on_hand and ck_balance_allocated (allocated 0 > on_hand -1);
		// MySQL reports whichever it evaluates first, so only assert that a check constraint rejected it.
		assertRejected("INSERT INTO inventory_balance (location_id, sku_id, on_hand) VALUES (?, ?, -1)",
				"Check constraint 'ck_balance_", forwardPickId, skuId);
	}

	@Test
	void allocatingMoreThanOnHandIsRejected() {
		assertRejected("INSERT INTO inventory_balance (location_id, sku_id, on_hand, allocated) VALUES (?, ?, 5, 6)",
				"ck_balance_allocated", forwardPickId, skuId);
	}

	@Test
	void unknownLocationTypeIsRejected() {
		assertRejected("INSERT INTO location (code, zone_id, type) VALUES ('T-99', ?, 'ROOF')", "ck_location_type",
				zoneId);
	}

	@Test
	void pickSlotMaxMustExceedMin() {
		assertRejected("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, 10, 10)",
				"ck_pick_slot_thresholds", forwardPickId, skuId);
	}

	@Test
	void ledgerRowNeedsPositiveQuantity() {
		assertRejected("""
				INSERT INTO inventory_txn (type, sku_id, to_location_id, quantity, actor_type, actor_id)
				VALUES ('RECEIPT', ?, ?, 0, 'SYSTEM', 'test')""", "ck_txn_quantity", skuId, forwardPickId);
	}

	@Test
	void ledgerRowNeedsAtLeastOneLocation() {
		assertRejected("""
				INSERT INTO inventory_txn (type, sku_id, quantity, actor_type, actor_id)
				VALUES ('ADJUST', ?, 5, 'SYSTEM', 'test')""", "ck_txn_locations", skuId);
	}

	@Test
	void ledgerMoveCannotHaveSameFromAndTo() {
		assertRejected("""
				INSERT INTO inventory_txn (type, sku_id, from_location_id, to_location_id, quantity, actor_type, actor_id)
				VALUES ('MOVE', ?, ?, ?, 5, 'SYSTEM', 'test')""", "ck_txn_locations", skuId, reserveId, reserveId);
	}

	@Test
	void ledgerRowsCannotBeUpdatedOrDeleted() {
		long txnId = insert("INSERT INTO inventory_txn (type, sku_id, to_location_id, quantity, actor_type, actor_id) "
				+ "VALUES ('RECEIPT', " + skuId + ", " + reserveId + ", 5, 'SYSTEM', 'test')");

		assertThatThrownBy(() -> jdbc.sql("UPDATE inventory_txn SET quantity = 500 WHERE id = ?").param(txnId).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM inventory_txn WHERE id = ?").param(txnId).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
	}

	private long insert(String sql) {
		jdbc.sql(sql).update();
		return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
	}

	private void assertRejected(String sql, String constraint, Object... params) {
		assertThatThrownBy(() -> jdbc.sql(sql).params(params).update()).isInstanceOf(DataAccessException.class)
			.hasMessageContaining(constraint);
	}

}
