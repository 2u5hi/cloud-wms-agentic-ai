package com.cloudwms.core.devdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.inventory.InventoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevDataSeederTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	@Autowired
	PlatformTransactionManager transactions;

	boolean seededHere;

	@BeforeAll
	void seedOnce() {
		seededHere = new DevDataSeeder(jdbc, inventory, transactions).seed();
	}

	@Test
	void seedsTheLayout() {
		assertThat(seededHere).isTrue();
		assertThat(count("SELECT COUNT(*) FROM location l JOIN zone z ON z.id = l.zone_id WHERE z.code IN ('A','B','C','D')"))
			.isEqualTo(4 * 5 * 15 * 4);
		assertThat(count("SELECT COUNT(*) FROM location l JOIN zone z ON z.id = l.zone_id WHERE z.code = 'S'"))
			.isEqualTo(24);
		assertThat(count("SELECT COUNT(*) FROM location WHERE code LIKE '_-__-__-A' AND type = 'FORWARD_PICK'"))
			.isEqualTo(300);
	}

	@Test
	void highReserveNeedsAReachTruck() {
		assertThat(count("""
				SELECT COUNT(*) FROM location
				WHERE code REGEXP '^[A-D]-[0-9]{2}-[0-9]{2}-[CD]$' AND (required_equipment IS NULL OR required_equipment <> 'REACH_TRUCK')"""))
			.isZero();
		assertThat(count("SELECT COUNT(*) FROM location WHERE code REGEXP '^[A-D]-[0-9]{2}-[0-9]{2}-B$' AND required_equipment IS NOT NULL"))
			.isZero();
	}

	@Test
	void everyForwardSlotHoldsOneSku() {
		assertThat(count("SELECT COUNT(*) FROM sku WHERE code LIKE 'SKU-%'")).isEqualTo(300);
		assertThat(count("""
				SELECT COUNT(*) FROM pick_slot ps JOIN sku s ON s.id = ps.sku_id WHERE s.code LIKE 'SKU-%'""")).isEqualTo(300);
	}

	@Test
	void fastMoversAreSlottedEarlierOnThePickPath() {
		double fast = average("A");
		double slow = average("C");
		assertThat(fast).isLessThan(slow);
	}

	@Test
	void someSlotsStartBelowTheirMinimum() {
		long belowMin = count("""
				SELECT COUNT(*) FROM pick_slot ps
				JOIN sku s ON s.id = ps.sku_id
				LEFT JOIN inventory_balance b ON b.location_id = ps.location_id AND b.sku_id = ps.sku_id
				WHERE s.code LIKE 'SKU-%' AND COALESCE(b.on_hand, 0) < ps.min_qty""");
		assertThat(belowMin).isBetween(1L, 30L);
	}

	@Test
	void everyBalanceInTheDatabaseReconcilesWithTheLedger() {
		long mismatches = count("""
				SELECT COUNT(*) FROM inventory_balance b
				WHERE b.on_hand <> (
				    SELECT COALESCE(SUM(CASE WHEN t.to_location_id = b.location_id THEN t.quantity ELSE -t.quantity END), 0)
				    FROM inventory_txn t
				    WHERE t.sku_id = b.sku_id AND (t.to_location_id = b.location_id OR t.from_location_id = b.location_id))""");
		assertThat(mismatches).isZero();
		assertThat(count("SELECT COUNT(*) FROM inventory_txn WHERE actor_id = 'dev-seed'")).isGreaterThan(300);
	}

	@Test
	void seedingTwiceDoesNothing() {
		long locations = count("SELECT COUNT(*) FROM location");
		assertThat(new DevDataSeeder(jdbc, inventory, transactions).seed()).isFalse();
		assertThat(count("SELECT COUNT(*) FROM location")).isEqualTo(locations);
	}

	private double average(String velocity) {
		return jdbc.sql("""
				SELECT AVG(l.pick_sequence) FROM pick_slot ps
				JOIN sku s ON s.id = ps.sku_id JOIN location l ON l.id = ps.location_id
				WHERE s.velocity_class = ? AND s.code LIKE 'SKU-%'""").param(velocity).query(Double.class).single();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
