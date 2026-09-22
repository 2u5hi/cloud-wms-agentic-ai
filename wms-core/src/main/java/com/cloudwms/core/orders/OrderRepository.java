package com.cloudwms.core.orders;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.cloudwms.core.orders.domain.Order;
import com.cloudwms.core.orders.domain.OrderLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {

	private final JdbcClient jdbc;

	OrderRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** SKU code to id for the codes that exist. */
	Map<String, Long> skuIds(Collection<String> codes) {
		Map<String, Long> ids = new HashMap<>();
		if (codes.isEmpty()) {
			return ids;
		}
		jdbc.sql("SELECT id, code FROM sku WHERE code IN (:codes)")
			.param("codes", codes)
			.query((rs, n) -> ids.put(rs.getString("code"), rs.getLong("id")))
			.list();
		return ids;
	}

	/** Which of these external references already exist. */
	Set<String> existingRefs(Collection<String> externalRefs) {
		if (externalRefs.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jdbc.sql("SELECT external_ref FROM orders WHERE external_ref IN (:refs)")
			.param("refs", externalRefs)
			.query(String.class)
			.list());
	}

	long insert(Order order) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO orders (external_ref, customer, priority, status, carrier, carrier_cutoff_at)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(order.externalRef(), order.customer(), order.priority(), order.status().name(), order.carrier(),
					Timestamp.from(order.carrierCutoffAt()))
			.update(keys);
		long orderId = keys.getKey().longValue();
		for (OrderLine line : order.lines()) {
			jdbc.sql("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, ?, ?, ?)")
				.params(orderId, line.lineNo(), line.skuId(), line.ordered())
				.update();
		}
		return orderId;
	}

}
