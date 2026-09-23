package com.cloudwms.core.waves.api;

import java.util.List;
import java.util.Optional;

import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.waves.api.WaveViews.ShortageView;
import com.cloudwms.core.waves.api.WaveViews.TaskCounts;
import com.cloudwms.core.waves.api.WaveViews.WaveOrderView;
import com.cloudwms.core.waves.api.WaveViews.WaveSummaryView;
import com.cloudwms.core.waves.api.WaveViews.WaveView;
import com.cloudwms.core.waves.domain.WavePlanner.Shortage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class WaveQueries {

	private final JdbcClient jdbc;

	WaveQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Page<WaveSummaryView> waves(String status, String cursor, int limit) {
		List<Row> rows = jdbc.sql("""
				SELECT w.id, w.status, w.planned_at, w.released_at,
				       (SELECT COUNT(*) FROM wave_order wo WHERE wo.wave_id = w.id) AS orders,
				       COALESCE((SELECT SUM(a.quantity) FROM allocation a
				                 WHERE a.wave_id = w.id AND a.status <> 'CANCELLED'), 0) AS units
				FROM wave w
				WHERE w.id > :after AND (:status IS NULL OR w.status = :status)
				ORDER BY w.id
				LIMIT :limit""")
			.param("after", Cursor.decode(cursor))
			.param("status", status)
			.param("limit", limit + 1)
			.query((rs, n) -> {
				long id = rs.getLong("id");
				return new Row(id, new WaveSummaryView(id, rs.getString("status"),
						rs.getTimestamp("planned_at").toInstant(),
						rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
						rs.getInt("orders"), rs.getInt("units"), taskCounts(id)));
			})
			.list();
		return Cursor.page(rows, limit, Row::id, Row::view);
	}

	Optional<WaveView> wave(long waveId) {
		return jdbc.sql("""
				SELECT w.id, w.status, w.planned_at, w.released_at,
				       COALESCE((SELECT SUM(a.quantity) FROM allocation a
				                 WHERE a.wave_id = w.id AND a.status <> 'CANCELLED'), 0) AS units
				FROM wave w WHERE w.id = ?""")
			.param(waveId)
			.query((rs, n) -> new WaveView(rs.getLong("id"), rs.getString("status"),
					rs.getTimestamp("planned_at").toInstant(),
					rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
					rs.getInt("units"), taskCounts(waveId), orders(waveId)))
			.optional();
	}

	TaskCounts taskCounts(long waveId) {
		return jdbc.sql("""
				SELECT COUNT(*) AS total,
				       SUM(status = 'WAITING') AS waiting, SUM(status = 'READY') AS ready,
				       SUM(status = 'ASSIGNED') AS assigned, SUM(status = 'IN_PROGRESS') AS in_progress,
				       SUM(status = 'COMPLETED') AS completed, SUM(status = 'CANCELLED') AS cancelled
				FROM task WHERE wave_id = ?""")
			.param(waveId)
			.query((rs, n) -> new TaskCounts(rs.getInt("total"), rs.getInt("waiting"), rs.getInt("ready"),
					rs.getInt("assigned"), rs.getInt("in_progress"), rs.getInt("completed"), rs.getInt("cancelled")))
			.single();
	}

	private List<WaveOrderView> orders(long waveId) {
		return jdbc.sql("""
				SELECT o.external_ref, o.customer, o.priority, o.status, o.carrier_cutoff_at,
				       SUM(l.qty_ordered) AS ordered, SUM(l.qty_allocated) AS allocated
				FROM wave_order wo
				JOIN orders o ON o.id = wo.order_id
				JOIN order_line l ON l.order_id = o.id
				WHERE wo.wave_id = ?
				GROUP BY o.id
				ORDER BY o.priority, o.carrier_cutoff_at, o.id""")
			.param(waveId)
			.query((rs, n) -> new WaveOrderView(rs.getString("external_ref"), rs.getString("customer"),
					rs.getInt("priority"), rs.getString("status"), rs.getTimestamp("carrier_cutoff_at").toInstant(),
					rs.getInt("ordered"), rs.getInt("allocated")))
			.list();
	}

	/** Shortages carry internal ids; clients see order and SKU codes. */
	List<ShortageView> describe(List<Shortage> shortages) {
		return shortages.stream()
			.map(shortage -> new ShortageView(
					jdbc.sql("SELECT external_ref FROM orders WHERE id = ?")
						.param(shortage.orderId())
						.query(String.class)
						.single(),
					jdbc.sql("SELECT code FROM sku WHERE id = ?").param(shortage.skuId()).query(String.class).single(),
					shortage.quantity()))
			.toList();
	}

	private record Row(long id, WaveSummaryView view) {
	}

}
