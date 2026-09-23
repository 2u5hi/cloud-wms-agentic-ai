package com.cloudwms.core.tasks.api;

import java.util.List;
import java.util.Optional;

import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.tasks.api.TaskViews.CreateWorkerRequest;
import com.cloudwms.core.tasks.api.TaskViews.TaskView;
import com.cloudwms.core.tasks.api.TaskViews.WorkerView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TaskQueries {

	private static final String TASK_SELECT = """
			SELECT t.id, t.type, t.status, t.priority, s.code AS sku, f.code AS from_location, d.code AS to_location,
			       t.quantity, t.wave_id, t.depends_on_task_id, t.required_equipment, w.code AS worker
			FROM task t
			JOIN sku s ON s.id = t.sku_id
			LEFT JOIN location f ON f.id = t.from_location_id
			LEFT JOIN location d ON d.id = t.to_location_id
			LEFT JOIN worker w ON w.id = t.assigned_worker_id
			""";

	private final JdbcClient jdbc;

	TaskQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Page<TaskView> tasks(Long wave, String status, String type, String worker, String cursor, int limit) {
		List<TaskView> rows = jdbc.sql(TASK_SELECT + """
				WHERE t.id > :after
				  AND (:wave IS NULL OR t.wave_id = :wave)
				  AND (:status IS NULL OR t.status = :status)
				  AND (:type IS NULL OR t.type = :type)
				  AND (:worker IS NULL OR w.code = :worker)
				ORDER BY t.id
				LIMIT :limit""")
			.param("after", Cursor.decode(cursor))
			.param("wave", wave)
			.param("status", status)
			.param("type", type)
			.param("worker", worker)
			.param("limit", limit + 1)
			.query((rs, n) -> task(rs))
			.list();
		return Cursor.page(rows, limit, TaskView::id, view -> view);
	}

	Optional<TaskView> task(long taskId) {
		return jdbc.sql(TASK_SELECT + "WHERE t.id = ?").param(taskId).query((rs, n) -> task(rs)).optional();
	}

	Page<WorkerView> workers(String cursor, int limit) {
		List<Row> rows = jdbc.sql("""
				SELECT w.id, w.code, w.name, z.code AS zone, w.status
				FROM worker w LEFT JOIN zone z ON z.id = w.home_zone_id
				WHERE w.id > ? ORDER BY w.id LIMIT ?""")
			.params(Cursor.decode(cursor), limit + 1)
			.query((rs, n) -> new Row(rs.getLong("id"), new WorkerView(rs.getString("code"), rs.getString("name"),
					rs.getString("zone"), rs.getString("status"), equipment(rs.getLong("id")))))
			.list();
		return Cursor.page(rows, limit, Row::id, Row::view);
	}

	Optional<WorkerView> worker(String code) {
		return jdbc.sql("""
				SELECT w.id, w.code, w.name, z.code AS zone, w.status
				FROM worker w LEFT JOIN zone z ON z.id = w.home_zone_id WHERE w.code = ?""")
			.param(code)
			.query((rs, n) -> new WorkerView(rs.getString("code"), rs.getString("name"), rs.getString("zone"),
					rs.getString("status"), equipment(rs.getLong("id"))))
			.optional();
	}

	/** Demo setup: workers normally come from a labour system. */
	@Transactional
	WorkerView create(CreateWorkerRequest request) {
		Long zoneId = request.homeZone() == null ? null
				: jdbc.sql("SELECT id FROM zone WHERE code = ?")
					.param(request.homeZone())
					.query(Long.class)
					.optional()
					.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
							"Zone %s does not exist".formatted(request.homeZone()), java.util.Map.of("zone",
									request.homeZone())));
		jdbc.sql("INSERT INTO worker (code, name, home_zone_id) VALUES (?, ?, ?)")
			.params(request.code(), request.name(), zoneId)
			.update();
		long workerId = jdbc.sql("SELECT id FROM worker WHERE code = ?").param(request.code()).query(Long.class).single();
		if (request.equipment() != null) {
			request.equipment()
				.stream()
				.filter(equipment -> equipment != null && !equipment.isBlank())
				.forEach(equipment -> jdbc.sql("INSERT INTO worker_equipment (worker_id, equipment) VALUES (?, ?)")
					.params(workerId, equipment)
					.update());
		}
		return worker(request.code()).orElseThrow();
	}

	Optional<Long> zoneId(String code) {
		return jdbc.sql("SELECT id FROM zone WHERE code = ?").param(code).query(Long.class).optional();
	}

	private List<String> equipment(long workerId) {
		return jdbc.sql("SELECT equipment FROM worker_equipment WHERE worker_id = ? ORDER BY equipment")
			.param(workerId)
			.query(String.class)
			.list();
	}

	private static TaskView task(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new TaskView(rs.getLong("id"), rs.getString("type"), rs.getString("status"), rs.getInt("priority"),
				rs.getString("sku"), rs.getString("from_location"), rs.getString("to_location"), rs.getInt("quantity"),
				rs.getObject("wave_id", Long.class), rs.getObject("depends_on_task_id", Long.class),
				rs.getString("required_equipment"), rs.getString("worker"));
	}

	private record Row(long id, WorkerView view) {
	}

}
