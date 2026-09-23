package com.cloudwms.core.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/** Picking and replenishing against a real wave: claims, completion, and what it does to stock. */
@IntegrationTest
class TaskExecutionTest {

	static final Actor SYSTEM = new Actor(ActorType.SYSTEM, "task-test");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	String prefix;
	long forwardId;
	long reserveId;
	long skuId;
	long orderId;

	@BeforeEach
	void setUp() {
		prefix = "TSK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		long zoneId = insert("INSERT INTO zone (code, name) VALUES (?, 'Task test')", prefix);
		forwardId = insert("INSERT INTO location (code, zone_id, type, pick_sequence) VALUES (?, ?, 'FORWARD_PICK', 10)",
				prefix + "-F", zoneId);
		reserveId = insert(
				"INSERT INTO location (code, zone_id, type, required_equipment) VALUES (?, ?, 'RESERVE', 'REACH_TRUCK')",
				prefix + "-R", zoneId);
		skuId = insert("INSERT INTO sku (code, description) VALUES (?, 'Task test SKU')", prefix + "-SKU");
		insert("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, 10, 100)", forwardId, skuId);
	}

	/** Forward-pick covers 2 of the 10 ordered, so the rest needs a replenishment from reserve. */
	private long plannedAndReleasedWave() throws Exception {
		inventory.record(InventoryMovement.receipt(skuId, forwardId, 2, null, SYSTEM));
		inventory.record(InventoryMovement.receipt(skuId, reserveId, 50, null, SYSTEM));
		orderId = insert("""
				INSERT INTO orders (external_ref, customer, priority, carrier, carrier_cutoff_at)
				VALUES (?, 'Acme', 3, ?, ?)""", prefix + "-SO", prefix, Timestamp.from(Instant.now().plusSeconds(3600)));
		insert("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 10)", orderId, skuId);

		String body = mockMvc
			.perform(command("/api/v1/waves/plan").contentType(MediaType.APPLICATION_JSON)
				.content("{\"carrier\": \"%s\"}".formatted(prefix)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long wave = ((Number) JsonPath.read(body, "$.waveNumber")).longValue();
		mockMvc.perform(command("/api/v1/waves/" + wave + "/release")).andExpect(status().isOk());
		return wave;
	}

	@Test
	void aWorkerOnlyGetsWorkTheyAreCertifiedFor() throws Exception {
		plannedAndReleasedWave();
		worker("PICKER", List.of());

		// The replenishment needs a reach truck, so the picker gets the pick that is ready now.
		claim("PICKER").andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("PICK"))
			.andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.quantity").value(2))
			.andExpect(jsonPath("$.assignedWorker").value(code("PICKER")));

		// Nothing else is workable for them: the other pick is waiting on the replenishment.
		claim("PICKER").andExpect(status().isNoContent());
	}

	@Test
	void completingAReplenishmentMovesTheStockAndFreesTheWaitingPick() throws Exception {
		long wave = plannedAndReleasedWave();
		worker("DRIVER", List.of("REACH_TRUCK"));

		String body = claim("DRIVER").andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("REPLENISH"))
			.andExpect(jsonPath("$.requiredEquipment").value("REACH_TRUCK"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long replenishment = ((Number) JsonPath.read(body, "$.id")).longValue();

		mockMvc.perform(command("/api/v1/tasks/" + replenishment + "/complete"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));

		// The stock and its promise both moved to the pick face.
		assertThat(onHand(forwardId)).isEqualTo(10);
		assertThat(allocated(forwardId)).isEqualTo(10);
		assertThat(onHand(reserveId)).isEqualTo(42);
		assertThat(allocated(reserveId)).isZero();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM allocation WHERE location_id = ? AND status = 'ACTIVE'")
			.param(forwardId)
			.query(Long.class)
			.single()).isEqualTo(2);

		// Both picks are now workable and the wave is in progress.
		assertThat(statusesOf(wave, "PICK")).containsOnly("READY");
		assertThat(waveStatus(wave)).isEqualTo("IN_PROGRESS");
	}

	@Test
	void pickingEverythingCompletesTheOrderAndTheWave() throws Exception {
		long wave = plannedAndReleasedWave();
		worker("DRIVER", List.of("REACH_TRUCK"));
		worker("PICKER", List.of());

		completeNext("DRIVER");
		completeNext("PICKER");
		assertThat(orderStatus()).isEqualTo("PICKING");
		completeNext("PICKER");

		assertThat(orderStatus()).isEqualTo("PICKED");
		assertThat(waveStatus(wave)).isEqualTo("COMPLETED");
		assertThat(onHand(forwardId)).isZero();
		assertThat(allocated(forwardId)).isZero();
		assertThat(jdbc.sql("SELECT qty_picked FROM order_line WHERE order_id = ?")
			.param(orderId)
			.query(Integer.class)
			.single()).isEqualTo(10);
		// Every unit that left is in the ledger.
		assertThat(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM inventory_txn WHERE sku_id = ? AND type = 'PICK'")
			.param(skuId)
			.query(Integer.class)
			.single()).isEqualTo(10);
	}

	@Test
	void concurrentClaimsNeverHandOutTheSameTask() throws Exception {
		// Ten separate picks, ten pickers, all claiming at once.
		inventory.record(InventoryMovement.receipt(skuId, forwardId, 100, null, SYSTEM));
		for (int i = 0; i < 10; i++) {
			long order = insert("""
					INSERT INTO orders (external_ref, customer, priority, carrier, carrier_cutoff_at)
					VALUES (?, 'Acme', 3, ?, ?)""", prefix + "-C" + i, prefix,
					Timestamp.from(Instant.now().plusSeconds(3600)));
			insert("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 5)", order, skuId);
		}
		String body = mockMvc
			.perform(post("/api/v1/waves/plan").header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"carrier\": \"%s\"}".formatted(prefix)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long wave = ((Number) JsonPath.read(body, "$.waveNumber")).longValue();
		mockMvc.perform(command("/api/v1/waves/" + wave + "/release")).andExpect(status().isOk());
		for (int i = 0; i < 10; i++) {
			worker("P" + i, List.of());
		}

		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> claims = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			for (int i = 0; i < 10; i++) {
				String worker = "P" + i;
				claims.add(pool.submit(() -> {
					start.await();
					return claim(worker).andReturn().getResponse().getContentAsString();
				}));
			}
			start.countDown();
			Set<Integer> taskIds = new HashSet<>();
			for (Future<String> claim : claims) {
				String claimed = claim.get(60, TimeUnit.SECONDS);
				if (!claimed.isBlank()) {
					taskIds.add(JsonPath.read(claimed, "$.id"));
				}
			}
			assertThat(taskIds).hasSize(10);
		}
	}

	@Test
	void aTaskCannotBeGivenToAnUncertifiedWorker() throws Exception {
		plannedAndReleasedWave();
		worker("DRIVER", List.of("REACH_TRUCK"));
		worker("PICKER", List.of());
		long replenishment = ((Number) JsonPath
			.read(claim("DRIVER").andReturn().getResponse().getContentAsString(), "$.id")).longValue();

		mockMvc
			.perform(command("/api/v1/tasks/" + replenishment + "/reassign").contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker\": \"%s\"}".formatted(code("PICKER"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"))
			.andExpect(jsonPath("$.detail").value("Worker %s is not certified for REACH_TRUCK".formatted(code("PICKER"))));
	}

	@Test
	void anIdleZoneHasNothingToClaim() throws Exception {
		worker("PICKER", List.of());
		claim("PICKER").andExpect(status().isNoContent());
	}

	private void completeNext(String worker) throws Exception {
		String claimed = claim(worker).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long taskId = ((Number) JsonPath.read(claimed, "$.id")).longValue();
		mockMvc.perform(command("/api/v1/tasks/" + taskId + "/complete")).andExpect(status().isOk());
	}

	private ResultActions claim(String worker) throws Exception {
		return mockMvc.perform(command("/api/v1/workers/" + code(worker) + "/next-task").param("zone", prefix));
	}

	private void worker(String name, List<String> equipment) throws Exception {
		String certifications = equipment.stream().map(item -> "\"" + item + "\"").reduce((a, b) -> a + "," + b)
			.orElse("");
		mockMvc
			.perform(command("/api/v1/workers").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code": "%s", "name": "%s", "homeZone": "%s", "equipment": [%s]}"""
					.formatted(code(name), name, prefix, certifications)))
			.andExpect(status().isCreated());
	}

	private String code(String name) {
		return prefix + "-" + name;
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(String path) {
		return post(path).header("Idempotency-Key", UUID.randomUUID().toString());
	}

	private int onHand(long locationId) {
		return quantity("on_hand", locationId);
	}

	private int allocated(long locationId) {
		return quantity("allocated", locationId);
	}

	private int quantity(String column, long locationId) {
		return jdbc.sql("SELECT COALESCE(SUM(" + column + "), 0) FROM inventory_balance WHERE location_id = ?")
			.param(locationId)
			.query(Integer.class)
			.single();
	}

	private List<String> statusesOf(long waveId, String type) {
		return jdbc.sql("SELECT status FROM task WHERE wave_id = ? AND type = ?")
			.params(waveId, type)
			.query(String.class)
			.list();
	}

	private String waveStatus(long waveId) {
		return jdbc.sql("SELECT status FROM wave WHERE id = ?").param(waveId).query(String.class).single();
	}

	private String orderStatus() {
		return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
	}

	private long insert(String sql, Object... params) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

}
