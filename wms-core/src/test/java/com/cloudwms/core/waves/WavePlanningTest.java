package com.cloudwms.core.waves;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class WavePlanningTest {

	static final Actor SYSTEM = new Actor(ActorType.SYSTEM, "wave-test");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	String prefix;
	String carrier;
	long forwardId;
	long reserveId;
	long skuId;

	@BeforeEach
	void setUp() {
		prefix = "WAV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		carrier = prefix;
		long zoneId = insert("INSERT INTO zone (code, name) VALUES (?, 'Wave test')", prefix);
		forwardId = insert("INSERT INTO location (code, zone_id, type, pick_sequence) VALUES (?, ?, 'FORWARD_PICK', 10)",
				prefix + "-F", zoneId);
		reserveId = insert(
				"INSERT INTO location (code, zone_id, type, required_equipment) VALUES (?, ?, 'RESERVE', 'REACH_TRUCK')",
				prefix + "-R", zoneId);
		skuId = insert("INSERT INTO sku (code, description) VALUES (?, 'Wave test SKU')", prefix + "-SKU");
		insert("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, 10, 100)", forwardId, skuId);
	}

	@Test
	void planCreatesPickTasksFromForwardStock() throws Exception {
		receive(forwardId, 20);
		long orderId = order("A", 3, 6);

		String body = planWave().andExpect(status().isOk())
			.andExpect(jsonPath("$.orders").value(1))
			.andExpect(jsonPath("$.unitsAllocated").value(6))
			.andExpect(jsonPath("$.unitsShort").value(0))
			.andExpect(jsonPath("$.pickTasks").value(1))
			.andExpect(jsonPath("$.replenishmentTasks").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long wave = ((Number) JsonPath.read(body, "$.waveNumber")).longValue();

		assertThat(orderStatus(orderId)).isEqualTo("ALLOCATED");
		assertThat(allocatedOnLine(orderId)).isEqualTo(6);
		assertThat(allocatedAt(forwardId)).isEqualTo(6);
		assertThat(tasks(wave)).singleElement().satisfies(task -> {
			assertThat(task.type()).isEqualTo("PICK");
			assertThat(task.status()).isEqualTo("READY");
			assertThat(task.fromLocationId()).isEqualTo(forwardId);
			assertThat(task.quantity()).isEqualTo(6);
		});
	}

	@Test
	void shortForwardStockIsReplenishedFromReserveAndThePickWaits() throws Exception {
		receive(forwardId, 2);
		receive(reserveId, 50);
		order("A", 3, 10);

		planWave().andExpect(jsonPath("$.unitsAllocated").value(10))
			.andExpect(jsonPath("$.pickTasks").value(2))
			.andExpect(jsonPath("$.replenishmentTasks").value(1));

		long wave = jdbc.sql("SELECT MAX(id) FROM wave").query(Long.class).single();
		List<TaskRow> tasks = tasks(wave);
		TaskRow replenishment = tasks.stream().filter(t -> t.type().equals("REPLENISH")).findFirst().orElseThrow();
		assertThat(replenishment.status()).isEqualTo("READY");
		assertThat(replenishment.fromLocationId()).isEqualTo(reserveId);
		assertThat(replenishment.toLocationId()).isEqualTo(forwardId);
		assertThat(replenishment.quantity()).isEqualTo(8);
		assertThat(replenishment.requiredEquipment()).isEqualTo("REACH_TRUCK");

		List<TaskRow> picks = tasks.stream().filter(t -> t.type().equals("PICK")).toList();
		assertThat(picks).hasSize(2).allSatisfy(pick -> assertThat(pick.fromLocationId()).isEqualTo(forwardId));
		// The pick for stock still in reserve cannot be worked until the replenishment completes.
		TaskRow waiting = picks.stream().filter(p -> p.status().equals("WAITING")).findFirst().orElseThrow();
		assertThat(waiting.quantity()).isEqualTo(8);
		assertThat(waiting.dependsOnTaskId()).isEqualTo(replenishment.id());

		// Stock is promised where it physically is, so allocated never exceeds on hand.
		assertThat(allocatedAt(forwardId)).isEqualTo(2);
		assertThat(allocatedAt(reserveId)).isEqualTo(8);
	}

	@Test
	void aLineThatCannotBeCoveredIsReportedShort() throws Exception {
		receive(forwardId, 4);
		order("A", 3, 10);

		planWave().andExpect(jsonPath("$.unitsAllocated").value(4))
			.andExpect(jsonPath("$.unitsShort").value(6))
			.andExpect(jsonPath("$.shortages[0].sku").value(prefix + "-SKU"))
			.andExpect(jsonPath("$.shortages[0].quantity").value(6));
	}

	@Test
	void previewChangesNothing() throws Exception {
		receive(forwardId, 20);
		long orderId = order("A", 3, 6);

		mockMvc.perform(planRequest().param("preview", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.preview").value(true))
			.andExpect(jsonPath("$.waveNumber").isEmpty())
			.andExpect(jsonPath("$.unitsAllocated").value(6))
			.andExpect(jsonPath("$.pickTasks").value(1));

		assertThat(orderStatus(orderId)).isEqualTo("RECEIVED");
		assertThat(allocatedOnLine(orderId)).isZero();
		assertThat(allocatedAt(forwardId)).isZero();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM wave_order WHERE order_id = ?").param(orderId).query(Long.class)
			.single()).isZero();
	}

	@Test
	void releasingMakesTheWaveAndItsOrdersReleased() throws Exception {
		receive(forwardId, 20);
		long orderId = order("A", 3, 6);
		long wave = planAndGetWave();

		mockMvc.perform(command("/api/v1/waves/" + wave + "/release"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RELEASED"))
			.andExpect(jsonPath("$.orders[0].externalRef").value(prefix + "-A"))
			.andExpect(jsonPath("$.tasks.ready").value(1));
		assertThat(orderStatus(orderId)).isEqualTo("RELEASED");

		mockMvc.perform(command("/api/v1/waves/" + wave + "/release"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void cancellingReturnsTheStockAndTheOrders() throws Exception {
		receive(forwardId, 20);
		long orderId = order("A", 3, 6);
		long wave = planAndGetWave();

		mockMvc.perform(command("/api/v1/waves/" + wave + "/cancel"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.tasks.cancelled").value(1));

		assertThat(orderStatus(orderId)).isEqualTo("RECEIVED");
		assertThat(allocatedOnLine(orderId)).isZero();
		assertThat(allocatedAt(forwardId)).isZero();
		// The order is free to be planned again.
		planWave().andExpect(jsonPath("$.orders").value(1));
	}

	@Test
	void concurrentPlanningNeverPromisesTheSameStockTwice() throws Exception {
		receive(forwardId, 10);
		order("A", 3, 6);
		order("B", 3, 6);

		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
			for (int i = 0; i < 4; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return planWave().andReturn().getResponse().getContentAsString();
				}));
			}
			start.countDown();
			int allocated = 0;
			for (Future<String> result : results) {
				String body = result.get(60, TimeUnit.SECONDS);
				assertThat(body).as("plan response").contains("unitsAllocated");
				allocated += (Integer) JsonPath.read(body, "$.unitsAllocated");
			}
			assertThat(allocated).isEqualTo(10);
		}
		assertThat(allocatedAt(forwardId)).isEqualTo(10);
		// Each order ended up in exactly one wave.
		assertThat(jdbc.sql("SELECT COUNT(*) FROM wave_order wo JOIN orders o ON o.id = wo.order_id "
				+ "WHERE o.external_ref LIKE ?").param(prefix + "-%").query(Long.class).single()).isEqualTo(2);
	}

	private ResultActions planWave() throws Exception {
		return mockMvc.perform(planRequest());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder planRequest() {
		return post("/api/v1/waves/plan").header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"maxOrders\": 50, \"carrier\": \"%s\"}".formatted(carrier));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(String path) {
		return post(path).header("Idempotency-Key", UUID.randomUUID().toString());
	}

	private long planAndGetWave() throws Exception {
		String body = planWave().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.waveNumber")).longValue();
	}

	private void receive(long locationId, int quantity) {
		inventory.record(InventoryMovement.receipt(skuId, locationId, quantity, null, SYSTEM));
	}

	private long order(String suffix, int priority, int quantity) {
		long orderId = insert("""
				INSERT INTO orders (external_ref, customer, priority, carrier, carrier_cutoff_at)
				VALUES (?, 'Acme', ?, ?, ?)""", prefix + "-" + suffix, priority, carrier,
				Timestamp.from(Instant.now().plusSeconds(3600)));
		insert("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, ?)", orderId, skuId,
				quantity);
		return orderId;
	}

	private String orderStatus(long orderId) {
		return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
	}

	private int allocatedOnLine(long orderId) {
		return jdbc.sql("SELECT COALESCE(SUM(qty_allocated), 0) FROM order_line WHERE order_id = ?")
			.param(orderId)
			.query(Integer.class)
			.single();
	}

	private int allocatedAt(long locationId) {
		return jdbc.sql("SELECT COALESCE(SUM(allocated), 0) FROM inventory_balance WHERE location_id = ?")
			.param(locationId)
			.query(Integer.class)
			.single();
	}

	private List<TaskRow> tasks(long waveId) {
		return jdbc.sql("""
				SELECT id, type, status, from_location_id, to_location_id, quantity, depends_on_task_id,
				       required_equipment
				FROM task WHERE wave_id = ? ORDER BY id""")
			.param(waveId)
			.query((rs, n) -> new TaskRow(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
					rs.getObject("from_location_id", Long.class), rs.getObject("to_location_id", Long.class),
					rs.getInt("quantity"), rs.getObject("depends_on_task_id", Long.class),
					rs.getString("required_equipment")))
			.list();
	}

	private long insert(String sql, Object... params) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

	record TaskRow(long id, String type, String status, Long fromLocationId, Long toLocationId, int quantity,
			Long dependsOnTaskId, String requiredEquipment) {
	}

}
