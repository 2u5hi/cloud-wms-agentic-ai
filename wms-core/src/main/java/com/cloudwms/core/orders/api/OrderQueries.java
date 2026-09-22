package com.cloudwms.core.orders.api;

import java.util.List;
import java.util.Optional;

import com.cloudwms.core.orders.api.OrderViews.OrderLineView;
import com.cloudwms.core.orders.api.OrderViews.OrderSummaryView;
import com.cloudwms.core.orders.api.OrderViews.OrderView;
import com.cloudwms.core.orders.domain.OrderStatus;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class OrderQueries {

	private final JdbcClient jdbc;

	OrderQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Orders in the order they were received. */
	Page<OrderSummaryView> orders(OrderStatus status, Boolean onHold, String cursor, int limit) {
		List<Row> rows = jdbc.sql("""
				SELECT o.id, o.external_ref, o.customer, o.priority, o.status, o.carrier, o.carrier_cutoff_at,
				       o.on_hold, o.hold_reason, o.created_at,
				       COUNT(l.id) AS line_count, SUM(l.qty_ordered) AS ordered, SUM(l.qty_allocated) AS allocated
				FROM orders o
				JOIN order_line l ON l.order_id = o.id
				WHERE o.id > :after
				  AND (:status IS NULL OR o.status = :status)
				  AND (:onHold IS NULL OR o.on_hold = :onHold)
				GROUP BY o.id
				ORDER BY o.id
				LIMIT :limit""")
			.param("after", Cursor.decode(cursor))
			.param("status", status == null ? null : status.name())
			.param("onHold", onHold)
			.param("limit", limit + 1)
			.query((rs, n) -> {
				int ordered = rs.getInt("ordered");
				int allocated = rs.getInt("allocated");
				return new Row(rs.getLong("id"), new OrderSummaryView(rs.getString("external_ref"), rs.getString("customer"),
						rs.getInt("priority"), OrderStatus.valueOf(rs.getString("status")), rs.getString("carrier"),
						rs.getTimestamp("carrier_cutoff_at").toInstant(), rs.getBoolean("on_hold"),
						rs.getString("hold_reason"), rs.getInt("line_count"), ordered, allocated, ordered - allocated,
						rs.getTimestamp("created_at").toInstant()));
			})
			.list();
		return Cursor.page(rows, limit, Row::id, Row::view);
	}

	Optional<OrderView> order(String externalRef) {
		return jdbc.sql("""
				SELECT id, external_ref, customer, priority, status, carrier, carrier_cutoff_at, on_hold, hold_reason,
				       created_at
				FROM orders WHERE external_ref = ?""")
			.param(externalRef)
			.query((rs, n) -> new OrderView(rs.getString("external_ref"), rs.getString("customer"), rs.getInt("priority"),
					OrderStatus.valueOf(rs.getString("status")), rs.getString("carrier"),
					rs.getTimestamp("carrier_cutoff_at").toInstant(), rs.getBoolean("on_hold"), rs.getString("hold_reason"),
					rs.getTimestamp("created_at").toInstant(), lines(rs.getLong("id"))))
			.optional();
	}

	private List<OrderLineView> lines(long orderId) {
		return jdbc.sql("""
				SELECT l.line_no, s.code AS sku, l.qty_ordered, l.qty_allocated, l.qty_picked, l.qty_shipped
				FROM order_line l JOIN sku s ON s.id = l.sku_id
				WHERE l.order_id = ?
				ORDER BY l.line_no""")
			.param(orderId)
			.query((rs, n) -> new OrderLineView(rs.getInt("line_no"), rs.getString("sku"), rs.getInt("qty_ordered"),
					rs.getInt("qty_allocated"), rs.getInt("qty_picked"), rs.getInt("qty_shipped"),
					rs.getInt("qty_ordered") - rs.getInt("qty_allocated")))
			.list();
	}

	private record Row(long id, OrderSummaryView view) {
	}

}
