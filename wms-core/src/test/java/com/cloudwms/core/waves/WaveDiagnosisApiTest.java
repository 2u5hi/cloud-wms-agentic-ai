package com.cloudwms.core.waves;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The blocked-wave scenario the agent will be asked to explain. */
@IntegrationTest
class WaveDiagnosisApiTest {

	static final Actor SYSTEM = new Actor(ActorType.SYSTEM, "diagnosis-test");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	String prefix;
	long wave;

	@BeforeEach
	void blockedWave() throws Exception {
		prefix = "DIA" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		long zoneId = insert("INSERT INTO zone (code, name) VALUES (?, 'Diagnosis test')", prefix);
		long forward = insert("INSERT INTO location (code, zone_id, type, pick_sequence) VALUES (?, ?, 'FORWARD_PICK', 10)",
				prefix + "-F", zoneId);
		long reserve = insert(
				"INSERT INTO location (code, zone_id, type, required_equipment) VALUES (?, ?, 'RESERVE', 'REACH_TRUCK')",
				prefix + "-R", zoneId);
		long sku = insert("INSERT INTO sku (code, description) VALUES (?, 'Diagnosis SKU')", prefix + "-SKU");
		insert("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, 10, 100)", forward, sku);
		// 2 units at the pick face, the rest high up in reserve, and one line nothing can cover.
		inventory.record(InventoryMovement.receipt(sku, forward, 2, null, SYSTEM));
		inventory.record(InventoryMovement.receipt(sku, reserve, 20, null, SYSTEM));
		long shortSku = insert("INSERT INTO sku (code, description) VALUES (?, 'No stock SKU')", prefix + "-NOSTOCK");

		long order = insert("""
				INSERT INTO orders (external_ref, customer, priority, carrier, carrier_cutoff_at)
				VALUES (?, 'Acme', 2, ?, ?)""", prefix + "-SO", prefix, Timestamp.from(Instant.now().plusSeconds(3600)));
		insert("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 12)", order, sku);
		insert("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 2, ?, 5)", order, shortSku);

		String body = mockMvc
			.perform(command("/api/v1/waves/plan").contentType(MediaType.APPLICATION_JSON)
				.content("{\"carrier\": \"%s\"}".formatted(prefix)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		wave = ((Number) JsonPath.read(body, "$.waveNumber")).longValue();
		mockMvc.perform(command("/api/v1/waves/" + wave + "/release")).andExpect(status().isOk());
	}

	@Test
	void withNoCertifiedWorkerTheReplenishmentIsTheRootCause() throws Exception {
		worker("PICKER", "");

		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RELEASED"))
			.andExpect(jsonPath("$.orders").value(1))
			.andExpect(jsonPath("$.picks.total").value(2))
			.andExpect(jsonPath("$.picks.waiting").value(1))
			.andExpect(jsonPath("$.picks.ready").value(1))
			.andExpect(jsonPath("$.blockers.length()").value(2))
			.andExpect(jsonPath("$.blockers[0].kind").value("WAITING_ON_REPLENISHMENT"))
			.andExpect(jsonPath("$.blockers[0].rootCause").value("NO_ELIGIBLE_WORKER_AVAILABLE"))
			.andExpect(jsonPath("$.blockers[0].sku").value(prefix + "-SKU"))
			.andExpect(jsonPath("$.blockers[0].affectedPicks").value(1))
			.andExpect(jsonPath("$.blockers[0].affectedOrders").value(1))
			.andExpect(jsonPath("$.blockers[0].quantity").value(10))
			.andExpect(jsonPath("$.blockers[0].replenishment.requiredEquipment").value("REACH_TRUCK"))
			.andExpect(jsonPath("$.blockers[0].replenishment.fromLocation").value(prefix + "-R"))
			.andExpect(jsonPath("$.blockers[0].detail")
				.value(org.hamcrest.Matchers.containsString("no available worker is certified for REACH_TRUCK")))
			// The line nothing could cover is its own blocker.
			.andExpect(jsonPath("$.blockers[1].kind").value("SHORT_ALLOCATED"))
			.andExpect(jsonPath("$.blockers[1].sku").value(prefix + "-NOSTOCK"))
			.andExpect(jsonPath("$.blockers[1].quantity").value(5))
			.andExpect(jsonPath("$.blockers[1].orders[0]").value(prefix + "-SO"))
			// The order's cutoff is within the hour and it still has work to do.
			.andExpect(jsonPath("$.atRiskOrders[0].order").value(prefix + "-SO"))
			.andExpect(jsonPath("$.atRiskOrders[0].remainingPicks").value(2));
	}

	@Test
	void withACertifiedWorkerAvailableItIsJustUnclaimed() throws Exception {
		worker("DRIVER", "REACH_TRUCK");

		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(jsonPath("$.blockers[0].rootCause").value("NOT_PICKED_UP"));
	}

	@Test
	void onceClaimedTheBlockerShowsWhoIsOnIt() throws Exception {
		worker("DRIVER", "REACH_TRUCK");
		mockMvc.perform(command("/api/v1/workers/" + prefix + "-DRIVER/next-task").param("zone", prefix))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("REPLENISH"));

		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(jsonPath("$.blockers[0].rootCause").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.blockers[0].replenishment.assignedWorker").value(prefix + "-DRIVER"))
			.andExpect(jsonPath("$.blockers[0].detail")
				.value(org.hamcrest.Matchers.containsString("being done by " + prefix + "-DRIVER")));
	}

	@Test
	void completingTheReplenishmentClearsTheBlocker() throws Exception {
		worker("DRIVER", "REACH_TRUCK");
		String claimed = mockMvc
			.perform(command("/api/v1/workers/" + prefix + "-DRIVER/next-task").param("zone", prefix))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long task = ((Number) JsonPath.read(claimed, "$.id")).longValue();
		mockMvc.perform(command("/api/v1/tasks/" + task + "/complete")).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(jsonPath("$.picks.waiting").value(0))
			.andExpect(jsonPath("$.picks.ready").value(2))
			// Only the line that no stock could cover is left.
			.andExpect(jsonPath("$.blockers.length()").value(1))
			.andExpect(jsonPath("$.blockers[0].kind").value("SHORT_ALLOCATED"));
	}

	@Test
	void anUnknownWaveIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", 999_999)).andExpect(status().isNotFound());
	}

	private void worker(String name, String equipment) throws Exception {
		String certifications = equipment.isBlank() ? "" : "\"" + equipment + "\"";
		mockMvc
			.perform(command("/api/v1/workers").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code": "%s", "name": "%s", "homeZone": "%s", "equipment": [%s]}"""
					.formatted(prefix + "-" + name, name, prefix, certifications)))
			.andExpect(status().isCreated());
	}

	private MockHttpServletRequestBuilder command(String path) {
		return post(path).header("Idempotency-Key", UUID.randomUUID().toString());
	}

	private long insert(String sql, Object... params) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

}
