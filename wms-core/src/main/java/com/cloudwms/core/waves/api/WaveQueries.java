package com.cloudwms.core.waves.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.waves.api.WaveViews.AtRiskOrderView;
import com.cloudwms.core.waves.api.WaveViews.PickCounts;
import com.cloudwms.core.waves.api.WaveViews.ShortageView;
import com.cloudwms.core.waves.api.WaveViews.WaveDiagnosisView;
import com.cloudwms.core.waves.domain.WaveDiagnosis;
import com.cloudwms.core.waves.domain.WaveDiagnosis.PendingReplenishment;
import com.cloudwms.core.waves.domain.WaveDiagnosis.ShortLine;
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

	/** Everything the diagnosis needs, straight from the database. */
	Optional<WaveDiagnosisView> diagnose(long waveId, int atRiskWithinHours) {
		Optional<String> status = jdbc.sql("SELECT status FROM wave WHERE id = ?")
			.param(waveId)
			.query(String.class)
			.optional();
		if (status.isEmpty()) {
			return Optional.empty();
		}
		List<PendingReplenishment> replenishments = jdbc.sql("""
				SELECT t.id, s.code AS sku, t.status, f.code AS from_location, d.code AS to_location, t.quantity,
				       t.required_equipment, w.code AS worker,
				       TIMESTAMPDIFF(MINUTE, t.created_at, CURRENT_TIMESTAMP(6)) AS age_minutes,
				       (SELECT COUNT(*) FROM task p WHERE p.depends_on_task_id = t.id AND p.status = 'WAITING')
				           AS waiting_picks,
				       (SELECT COUNT(DISTINCT ol.order_id) FROM task p
				        JOIN allocation a ON a.id = p.allocation_id
				        JOIN order_line ol ON ol.id = a.order_line_id
				        WHERE p.depends_on_task_id = t.id AND p.status = 'WAITING') AS affected_orders
				FROM task t
				JOIN sku s ON s.id = t.sku_id
				LEFT JOIN location f ON f.id = t.from_location_id
				LEFT JOIN location d ON d.id = t.to_location_id
				LEFT JOIN worker w ON w.id = t.assigned_worker_id
				WHERE t.wave_id = ? AND t.type = 'REPLENISH' AND t.status IN ('READY', 'ASSIGNED', 'IN_PROGRESS')
				  AND EXISTS (SELECT 1 FROM task p WHERE p.depends_on_task_id = t.id AND p.status = 'WAITING')""")
			.param(waveId)
			.query((rs, n) -> new PendingReplenishment(rs.getLong("id"), rs.getString("sku"), rs.getString("status"),
					rs.getString("from_location"), rs.getString("to_location"), rs.getInt("quantity"),
					rs.getString("required_equipment"), rs.getString("worker"), rs.getLong("age_minutes"),
					rs.getInt("waiting_picks"), rs.getInt("affected_orders")))
			.list();

		List<ShortLine> shortLines = jdbc.sql("""
				SELECT o.external_ref, s.code AS sku, (l.qty_ordered - l.qty_allocated) AS short_qty
				FROM wave_order wo
				JOIN orders o ON o.id = wo.order_id
				JOIN order_line l ON l.order_id = o.id
				JOIN sku s ON s.id = l.sku_id
				WHERE wo.wave_id = ? AND l.qty_allocated < l.qty_ordered""")
			.param(waveId)
			.query((rs, n) -> new ShortLine(rs.getString("external_ref"), rs.getString("sku"), rs.getInt("short_qty")))
			.list();

		// Only workers who could actually take this wave's work count: available, and either assigned to one of
		// its zones or to none at all.
		String inWaveZones = """
				AND (w.home_zone_id IS NULL OR w.home_zone_id IN (
				      SELECT DISTINCT t.zone_id FROM task t
				      WHERE t.wave_id = ? AND t.status IN ('WAITING', 'READY', 'ASSIGNED', 'IN_PROGRESS')))""";
		Map<String, Integer> available = new HashMap<>();
		available.put("", jdbc.sql("SELECT COUNT(*) FROM worker w WHERE w.status = 'AVAILABLE' " + inWaveZones)
			.param(waveId)
			.query(Integer.class)
			.single());
		jdbc.sql("""
				SELECT we.equipment, COUNT(*) AS available
				FROM worker_equipment we JOIN worker w ON w.id = we.worker_id
				WHERE w.status = 'AVAILABLE' """ + inWaveZones + " GROUP BY we.equipment")
			.param(waveId)
			.query((rs, n) -> available.put(rs.getString("equipment"), rs.getInt("available")))
			.list();

		PickCounts picks = jdbc.sql("""
				SELECT COUNT(*) AS total, SUM(status = 'WAITING') AS waiting, SUM(status = 'READY') AS ready,
				       SUM(status = 'ASSIGNED') AS assigned, SUM(status = 'IN_PROGRESS') AS in_progress,
				       SUM(status = 'COMPLETED') AS completed
				FROM task WHERE wave_id = ? AND type = 'PICK'""")
			.param(waveId)
			.query((rs, n) -> new PickCounts(rs.getInt("total"), rs.getInt("waiting"), rs.getInt("ready"),
					rs.getInt("assigned"), rs.getInt("in_progress"), rs.getInt("completed")))
			.single();

		List<AtRiskOrderView> atRisk = jdbc.sql("""
				SELECT o.external_ref, o.carrier_cutoff_at,
				       (SELECT COUNT(*) FROM task t JOIN allocation a ON a.id = t.allocation_id
				        JOIN order_line ol ON ol.id = a.order_line_id
				        WHERE ol.order_id = o.id AND t.status NOT IN ('COMPLETED', 'CANCELLED')) AS remaining_picks
				FROM wave_order wo JOIN orders o ON o.id = wo.order_id
				WHERE wo.wave_id = ?
				  AND o.status NOT IN ('PICKED', 'PACKED', 'SHIPPED', 'CANCELLED')
				  AND o.carrier_cutoff_at <= TIMESTAMPADD(HOUR, ?, CURRENT_TIMESTAMP(6))
				HAVING remaining_picks > 0
				ORDER BY o.carrier_cutoff_at""")
			.params(waveId, atRiskWithinHours)
			.query((rs, n) -> new AtRiskOrderView(rs.getString("external_ref"),
					rs.getTimestamp("carrier_cutoff_at").toInstant(), rs.getInt("remaining_picks")))
			.list();

		int orders = jdbc.sql("SELECT COUNT(*) FROM wave_order WHERE wave_id = ?")
			.param(waveId)
			.query(Integer.class)
			.single();
		return Optional.of(new WaveDiagnosisView(waveId, status.get(), orders, picks,
				WaveDiagnosis.diagnose(replenishments, shortLines, available), atRisk));
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
