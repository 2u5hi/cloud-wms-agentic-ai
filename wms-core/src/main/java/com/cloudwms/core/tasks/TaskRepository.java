package com.cloudwms.core.tasks;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TaskRepository {

	/** How many ordered candidates to try before giving up; only contention makes a claim fall through. */
	private static final int CLAIM_CANDIDATES = 20;

	private final JdbcClient jdbc;

	TaskRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<WorkerRow> findWorker(String code) {
		return jdbc.sql("SELECT id, code, name, home_zone_id, status FROM worker WHERE code = ?")
			.param(code)
			.query((rs, n) -> new WorkerRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
					rs.getObject("home_zone_id", Long.class), rs.getString("status")))
			.optional();
	}

	/**
	 * Claims the next workable task for a worker. Only tasks of released waves are workable, and only those
	 * whose equipment the worker is certified for. Replenishments come first because they unblock waiting
	 * picks, then work in the worker's own zone, then pick-path order.
	 *
	 * <p>Candidates are read without locking, then claimed with a conditional update: whoever's update
	 * changes the row wins, and the others move on to the next candidate. {@code ORDER BY ... LIMIT 1 FOR
	 * UPDATE SKIP LOCKED} looks simpler but is wrong here: MySQL locks every row it scans before sorting,
	 * so the first worker locks the whole queue and everyone else sees nothing to do.
	 */
	Optional<Long> claimNext(long workerId, Long homeZoneId, Long zoneId) {
		List<Long> candidates = jdbc.sql("""
				SELECT t.id
				FROM task t
				JOIN wave w ON w.id = t.wave_id
				WHERE t.status = 'READY'
				  AND w.status IN ('RELEASED', 'IN_PROGRESS')
				  AND (:zone IS NULL OR t.zone_id = :zone)
				  AND (t.required_equipment IS NULL OR EXISTS (
				        SELECT 1 FROM worker_equipment we
				        WHERE we.worker_id = :workerId AND we.equipment = t.required_equipment))
				ORDER BY t.priority, (t.type = 'REPLENISH') DESC, (t.zone_id <=> :homeZone) DESC, t.sequence, t.id
				LIMIT :candidates""")
			.param("workerId", workerId)
			.param("homeZone", homeZoneId)
			.param("zone", zoneId)
			.param("candidates", CLAIM_CANDIDATES)
			.query(Long.class)
			.list();
		for (Long candidate : candidates) {
			int claimed = jdbc.sql("""
					UPDATE task SET status = 'ASSIGNED', assigned_worker_id = ?, version = version + 1
					WHERE id = ? AND status = 'READY'""").params(workerId, candidate).update();
			if (claimed == 1) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	Optional<TaskRow> find(long taskId) {
		return jdbc.sql("""
				SELECT t.id, t.type, t.status, t.sku_id, t.from_location_id, t.to_location_id, t.quantity,
				       t.allocation_id, t.wave_id, t.assigned_worker_id, t.depends_on_task_id, t.required_equipment
				FROM task t WHERE t.id = ?""")
			.param(taskId)
			.query((rs, n) -> new TaskRow(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
					rs.getLong("sku_id"), rs.getObject("from_location_id", Long.class),
					rs.getObject("to_location_id", Long.class), rs.getInt("quantity"),
					rs.getObject("allocation_id", Long.class), rs.getObject("wave_id", Long.class),
					rs.getObject("assigned_worker_id", Long.class), rs.getObject("depends_on_task_id", Long.class),
					rs.getString("required_equipment")))
			.optional();
	}

	void setStatus(long taskId, String status) {
		jdbc.sql("UPDATE task SET status = ?, version = version + 1 WHERE id = ?").params(status, taskId).update();
	}

	void assign(long taskId, Long workerId, String status) {
		jdbc.sql("UPDATE task SET assigned_worker_id = ?, status = ?, version = version + 1 WHERE id = ?")
			.params(workerId, status, taskId)
			.update();
	}

	/** Picks that were waiting on this replenishment become workable. */
	int releaseDependents(long taskId) {
		return jdbc
			.sql("UPDATE task SET status = 'READY', version = version + 1 WHERE depends_on_task_id = ? AND status = 'WAITING'")
			.param(taskId)
			.update();
	}

	/** The promise travels with the stock, so dependent picks now hold stock at the destination. */
	void moveAllocationsOfDependents(long replenishTaskId, long toLocationId) {
		jdbc.sql("""
				UPDATE allocation a
				JOIN task t ON t.allocation_id = a.id
				SET a.location_id = ?
				WHERE t.depends_on_task_id = ? AND a.status = 'ACTIVE'""")
			.params(toLocationId, replenishTaskId)
			.update();
	}

	Optional<AllocationRow> findAllocation(long allocationId) {
		return jdbc.sql("""
				SELECT a.id, a.order_line_id, a.quantity, a.location_id, l.order_id
				FROM allocation a JOIN order_line l ON l.id = a.order_line_id
				WHERE a.id = ?""")
			.param(allocationId)
			.query((rs, n) -> new AllocationRow(rs.getLong("id"), rs.getLong("order_line_id"), rs.getLong("order_id"),
					rs.getLong("location_id"), rs.getInt("quantity")))
			.optional();
	}

	void completeAllocation(long allocationId, int quantity, long orderLineId) {
		jdbc.sql("UPDATE allocation SET status = 'PICKED' WHERE id = ?").param(allocationId).update();
		jdbc.sql("UPDATE order_line SET qty_picked = qty_picked + ? WHERE id = ?")
			.params(quantity, orderLineId)
			.update();
	}

	/** RELEASED -> PICKING on the first pick, and PICKING -> PICKED once nothing is left to pick. */
	void advanceOrder(long orderId) {
		jdbc.sql("UPDATE orders SET status = 'PICKING', version = version + 1 WHERE id = ? AND status = 'RELEASED'")
			.param(orderId)
			.update();
		jdbc.sql("""
				UPDATE orders o SET o.status = 'PICKED', o.version = o.version + 1
				WHERE o.id = ? AND o.status = 'PICKING'
				  AND NOT EXISTS (SELECT 1 FROM order_line l WHERE l.order_id = o.id AND l.qty_picked < l.qty_allocated)
				  AND NOT EXISTS (SELECT 1 FROM allocation a JOIN order_line l ON l.id = a.order_line_id
				                  WHERE l.order_id = o.id AND a.status = 'ACTIVE')""")
			.param(orderId)
			.update();
	}

	/** A wave is in progress once work has started, and complete when nothing is left to do. */
	void advanceWave(long waveId) {
		jdbc.sql("UPDATE wave SET status = 'IN_PROGRESS', version = version + 1 WHERE id = ? AND status = 'RELEASED'")
			.param(waveId)
			.update();
		jdbc.sql("""
				UPDATE wave w SET w.status = 'COMPLETED', w.completed_at = CURRENT_TIMESTAMP(6), w.version = w.version + 1
				WHERE w.id = ? AND w.status = 'IN_PROGRESS'
				  AND NOT EXISTS (SELECT 1 FROM task t WHERE t.wave_id = w.id
				                  AND t.status NOT IN ('COMPLETED', 'CANCELLED'))""")
			.param(waveId)
			.update();
	}

	List<String> equipment(long workerId) {
		return jdbc.sql("SELECT equipment FROM worker_equipment WHERE worker_id = ? ORDER BY equipment")
			.param(workerId)
			.query(String.class)
			.list();
	}

	record WorkerRow(long id, String code, String name, Long homeZoneId, String status) {
	}

	record TaskRow(long id, String type, String status, long skuId, Long fromLocationId, Long toLocationId,
			int quantity, Long allocationId, Long waveId, Long assignedWorkerId, Long dependsOnTaskId,
			String requiredEquipment) {
	}

	record AllocationRow(long id, long orderLineId, long orderId, long locationId, int quantity) {
	}

}
