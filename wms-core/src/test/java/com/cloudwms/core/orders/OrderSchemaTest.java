package com.cloudwms.core.orders;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.cloudwms.core.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;

/** The database enforces the order invariants itself. Each test rolls back. */
@IntegrationTest
@Transactional
class OrderSchemaTest {

	@Autowired
	JdbcClient jdbc;

	long skuId;
	long orderId;
	String ref;

	@BeforeEach
	void setUp() {
		ref = "SO-" + UUID.randomUUID();
		skuId = insert("INSERT INTO sku (code, description) VALUES (?, 'Test SKU')", "ORD-" + UUID.randomUUID());
		orderId = insert("INSERT INTO orders (external_ref, customer, carrier, carrier_cutoff_at) VALUES (?, 'C', 'UPS', NOW())",
				ref);
	}

	@Test
	void externalReferenceIsUnique() {
		assertRejected("INSERT INTO orders (external_ref, customer, carrier, carrier_cutoff_at) VALUES (?, 'C', 'UPS', NOW())",
				"uq_orders_external_ref", ref);
	}

	@Test
	void priorityIsOneToFive() {
		assertRejected("UPDATE orders SET priority = 6 WHERE id = ?", "ck_orders_priority", orderId);
	}

	@Test
	void unknownStatusIsRejected() {
		assertRejected("UPDATE orders SET status = 'LOST' WHERE id = ?", "ck_orders_status", orderId);
	}

	@Test
	void holdAndReasonGoTogether() {
		assertRejected("UPDATE orders SET on_hold = TRUE WHERE id = ?", "ck_orders_hold", orderId);
		assertRejected("UPDATE orders SET hold_reason = 'why' WHERE id = ?", "ck_orders_hold", orderId);
	}

	@Test
	void lineQuantitiesMustBeOrdered() {
		assertRejected("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered, qty_allocated) VALUES (?, 1, ?, 5, 6)",
				"ck_order_line_quantities", orderId, skuId);
		assertRejected("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered, qty_allocated, qty_picked) "
				+ "VALUES (?, 1, ?, 5, 3, 4)", "ck_order_line_quantities", orderId, skuId);
		assertRejected("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 0)",
				"ck_order_line_quantities", orderId, skuId);
	}

	@Test
	void lineNumbersAreUniquePerOrder() {
		jdbc.sql("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 5)")
			.params(orderId, skuId)
			.update();
		assertRejected("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 2)",
				"uq_order_line", orderId, skuId);
	}

	private void assertRejected(String sql, String constraint, Object... params) {
		assertThatThrownBy(() -> jdbc.sql(sql).params(params).update()).isInstanceOf(DataAccessException.class)
			.hasMessageContaining(constraint);
	}

	private long insert(String sql, Object... params) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

}
