package com.cloudwms.core.waves;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cloudwms.core.inventory.domain.LocationType;
import com.cloudwms.core.waves.domain.WavePlanner.ForwardSlot;
import com.cloudwms.core.waves.domain.WavePlanner.PlannableLine;
import com.cloudwms.core.waves.domain.WavePlanner.StockAtLocation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class WaveRepository {

	private final JdbcClient jdbc;

	WaveRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Open lines of orders that can be planned: received, not on hold, not already in a wave. */
	List<PlannableLine> plannableLines(String carrier, Instant cutoffBefore, int maxOrders) {
		return jdbc.sql("""
				SELECT o.id AS order_id, o.priority, o.carrier_cutoff_at, l.id AS line_id, l.sku_id, l.qty_ordered
				FROM order_line l
				JOIN (
				    SELECT o.id, o.priority, o.carrier_cutoff_at
				    FROM orders o
				    LEFT JOIN wave_order wo ON wo.order_id = o.id
				    WHERE o.status = 'RECEIVED' AND o.on_hold = FALSE AND wo.order_id IS NULL
				      AND (:carrier IS NULL OR o.carrier = :carrier)
				      AND (:cutoffBefore IS NULL OR o.carrier_cutoff_at <= :cutoffBefore)
				    ORDER BY o.priority, o.carrier_cutoff_at, o.id
				    LIMIT :maxOrders
				) o ON o.id = l.order_id
				ORDER BY o.priority, o.carrier_cutoff_at, o.id, l.line_no""")
			.param("carrier", carrier)
			.param("cutoffBefore", cutoffBefore == null ? null : Timestamp.from(cutoffBefore))
			.param("maxOrders", maxOrders)
			.query((rs, n) -> new PlannableLine(rs.getLong("order_id"), rs.getInt("priority"),
					rs.getTimestamp("carrier_cutoff_at").toInstant(), rs.getLong("line_id"), rs.getLong("sku_id"),
					rs.getInt("qty_ordered")))
			.list();
	}

	List<StockAtLocation> availableStock(Collection<Long> skuIds) {
		if (skuIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT b.location_id, b.sku_id, l.type, (b.on_hand - b.allocated) AS available, l.pick_sequence,
				       l.zone_id, l.required_equipment
				FROM inventory_balance b
				JOIN location l ON l.id = b.location_id
				WHERE b.sku_id IN (:skuIds) AND b.on_hand > b.allocated AND l.active = TRUE
				  AND l.type IN ('FORWARD_PICK', 'RESERVE')""")
			.param("skuIds", skuIds)
			.query((rs, n) -> new StockAtLocation(rs.getLong("location_id"), rs.getLong("sku_id"),
					LocationType.valueOf(rs.getString("type")), rs.getInt("available"),
					rs.getObject("pick_sequence", Integer.class), rs.getLong("zone_id"),
					rs.getString("required_equipment")))
			.list();
	}

	Map<Long, ForwardSlot> forwardSlots(Collection<Long> skuIds) {
		Map<Long, ForwardSlot> slots = new HashMap<>();
		if (skuIds.isEmpty()) {
			return slots;
		}
		jdbc.sql("""
				SELECT ps.sku_id, ps.location_id, l.zone_id, l.pick_sequence
				FROM pick_slot ps JOIN location l ON l.id = ps.location_id
				WHERE ps.sku_id IN (:skuIds) AND l.active = TRUE""")
			.param("skuIds", skuIds)
			.query((rs, n) -> slots.put(rs.getLong("sku_id"), new ForwardSlot(rs.getLong("sku_id"),
					rs.getLong("location_id"), rs.getLong("zone_id"), rs.getObject("pick_sequence", Integer.class))))
			.list();
		return slots;
	}

	/**
	 * Serializes wave planning: the lock is held for the rest of this transaction, so the next planner reads
	 * the stock this one promised. A wait longer than the database's lock timeout surfaces as a retryable 503.
	 */
	void lockForPlanning() {
		jdbc.sql("SELECT id FROM planning_lock WHERE id = 1 FOR UPDATE").query(Integer.class).single();
	}

	long insertWave() {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("INSERT INTO wave (status) VALUES ('PLANNED')").update(keys);
		return keys.getKey().longValue();
	}

	void addOrderToWave(long waveId, long orderId) {
		jdbc.sql("INSERT INTO wave_order (wave_id, order_id) VALUES (?, ?)").params(waveId, orderId).update();
	}

	long insertAllocation(long waveId, long orderLineId, long skuId, long locationId, int quantity) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO allocation (order_line_id, wave_id, sku_id, location_id, quantity)
				VALUES (?, ?, ?, ?, ?)""").params(orderLineId, waveId, skuId, locationId, quantity).update(keys);
		return keys.getKey().longValue();
	}

	long insertTask(NewTask task) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO task (type, status, priority, wave_id, allocation_id, depends_on_task_id, sku_id,
				    from_location_id, to_location_id, quantity, zone_id, sequence, required_equipment)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(task.type(), task.status(), task.priority(), task.waveId(), task.allocationId(),
					task.dependsOnTaskId(), task.skuId(), task.fromLocationId(), task.toLocationId(), task.quantity(),
					task.zoneId(), task.sequence(), task.requiredEquipment())
			.update(keys);
		return keys.getKey().longValue();
	}

	void allocateLine(long orderLineId, int quantity) {
		jdbc.sql("UPDATE order_line SET qty_allocated = qty_allocated + ? WHERE id = ?")
			.params(quantity, orderLineId)
			.update();
	}

	void setOrderStatus(Collection<Long> orderIds, String status) {
		jdbc.sql("UPDATE orders SET status = :status, version = version + 1 WHERE id IN (:ids)")
			.param("status", status)
			.param("ids", orderIds)
			.update();
	}

	/** Where a location sits and what it takes to reach it, used to shape the tasks. */
	LocationInfo locationInfo(long locationId) {
		return jdbc.sql("SELECT zone_id, pick_sequence, required_equipment FROM location WHERE id = ?")
			.param(locationId)
			.query((rs, n) -> new LocationInfo(rs.getLong("zone_id"), rs.getObject("pick_sequence", Integer.class),
					rs.getString("required_equipment")))
			.single();
	}

	record LocationInfo(long zoneId, Integer pickSequence, String requiredEquipment) {
	}

	Optional<String> waveStatus(long waveId) {
		return jdbc.sql("SELECT status FROM wave WHERE id = ?").param(waveId).query(String.class).optional();
	}

	void setWaveStatus(long waveId, String status, String timestampColumn) {
		String setTimestamp = timestampColumn == null ? "" : ", " + timestampColumn + " = CURRENT_TIMESTAMP(6)";
		jdbc.sql("UPDATE wave SET status = ?, version = version + 1" + setTimestamp + " WHERE id = ?")
			.params(status, waveId)
			.update();
	}

	List<Long> waveOrderIds(long waveId) {
		return jdbc.sql("SELECT order_id FROM wave_order WHERE wave_id = ?").param(waveId).query(Long.class).list();
	}

	/** Stock still promised by this wave, so it can be released when the wave is cancelled. */
	List<ActiveAllocation> activeAllocations(long waveId) {
		return jdbc.sql("""
				SELECT id, order_line_id, sku_id, location_id, quantity FROM allocation
				WHERE wave_id = ? AND status = 'ACTIVE'""")
			.param(waveId)
			.query((rs, n) -> new ActiveAllocation(rs.getLong("id"), rs.getLong("order_line_id"), rs.getLong("sku_id"),
					rs.getLong("location_id"), rs.getInt("quantity")))
			.list();
	}

	void cancelWaveWork(long waveId) {
		jdbc.sql("""
				UPDATE task SET status = 'CANCELLED', version = version + 1
				WHERE wave_id = ? AND status NOT IN ('COMPLETED', 'CANCELLED')""").param(waveId).update();
		jdbc.sql("UPDATE allocation SET status = 'CANCELLED' WHERE wave_id = ? AND status = 'ACTIVE'")
			.param(waveId)
			.update();
		jdbc.sql("DELETE FROM wave_order WHERE wave_id = ?").param(waveId).update();
	}

	void deallocateLine(long orderLineId, int quantity) {
		jdbc.sql("UPDATE order_line SET qty_allocated = qty_allocated - ? WHERE id = ?")
			.params(quantity, orderLineId)
			.update();
	}

	record ActiveAllocation(long id, long orderLineId, long skuId, long locationId, int quantity) {
	}

	record NewTask(String type, String status, int priority, Long waveId, Long allocationId, Long dependsOnTaskId,
			long skuId, Long fromLocationId, Long toLocationId, int quantity, Long zoneId, Integer sequence,
			String requiredEquipment) {
	}

}
