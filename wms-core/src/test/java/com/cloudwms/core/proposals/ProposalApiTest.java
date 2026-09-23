package com.cloudwms.core.proposals;

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

/**
 * The agent's only write path: propose, a human approves, and the WMS runs the command. Built on the
 * same blocked wave the diagnosis returns, so the fix can be checked against the blocker it clears.
 */
@IntegrationTest
class ProposalApiTest {

	static final Actor SYSTEM = new Actor(ActorType.SYSTEM, "proposal-test");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	String prefix;
	long wave;
	long replenishment;

	@BeforeEach
	void blockedWave() throws Exception {
		prefix = "PRO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
		long zoneId = insert("INSERT INTO zone (code, name) VALUES (?, 'Proposal test')", prefix);
		long forward = insert("INSERT INTO location (code, zone_id, type, pick_sequence) VALUES (?, ?, 'FORWARD_PICK', 10)",
				prefix + "-F", zoneId);
		long reserve = insert(
				"INSERT INTO location (code, zone_id, type, required_equipment) VALUES (?, ?, 'RESERVE', 'REACH_TRUCK')",
				prefix + "-R", zoneId);
		long sku = insert("INSERT INTO sku (code, description) VALUES (?, 'Proposal SKU')", prefix + "-SKU");
		insert("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, 10, 100)", forward, sku);
		inventory.record(InventoryMovement.receipt(sku, forward, 2, null, SYSTEM));
		inventory.record(InventoryMovement.receipt(sku, reserve, 20, null, SYSTEM));

		long order = insert("""
				INSERT INTO orders (external_ref, customer, priority, carrier, carrier_cutoff_at)
				VALUES (?, 'Acme', 2, ?, ?)""", prefix + "-SO", prefix, Timestamp.from(Instant.now().plusSeconds(3600)));
		insert("INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered) VALUES (?, 1, ?, 12)", order, sku);

		String planned = mockMvc
			.perform(command("/api/v1/waves/plan").contentType(MediaType.APPLICATION_JSON)
				.content("{\"carrier\": \"%s\"}".formatted(prefix)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		wave = ((Number) JsonPath.read(planned, "$.waveNumber")).longValue();
		mockMvc.perform(command("/api/v1/waves/" + wave + "/release")).andExpect(status().isOk());
		replenishment = jdbc.sql("SELECT id FROM task WHERE wave_id = ? AND type = 'REPLENISH'")
			.param(wave)
			.query(Long.class)
			.single();
		// The one reach-truck driver is on break, so nobody available can do the replenishment.
		worker(prefix + "-DRIVER", "REACH_TRUCK");
		jdbc.sql("UPDATE worker SET status = 'BREAK' WHERE code = ?").param(prefix + "-DRIVER").update();
	}

	@Test
	void approvingAReassignmentUnblocksTheWave() throws Exception {
		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(jsonPath("$.blockers[0].rootCause").value("NO_ELIGIBLE_WORKER_AVAILABLE"));

		long proposal = propose(replenishment, prefix + "-DRIVER");
		mockMvc.perform(get("/api/v1/proposals/{id}", proposal))
			.andExpect(jsonPath("$.status").value("PROPOSED"))
			.andExpect(jsonPath("$.createdBy.type").value("AGENT"))
			.andExpect(jsonPath("$.createdBy.id").value("ops-agent"))
			.andExpect(jsonPath("$.evidence.length()").value(1));
		// Proposing changes nothing on the floor.
		mockMvc.perform(get("/api/v1/tasks/{id}", replenishment)).andExpect(jsonPath("$.status").value("READY"));

		mockMvc
			.perform(command("/api/v1/proposals/" + proposal + "/approve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"agreed, pull them off break\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EXECUTED"))
			.andExpect(jsonPath("$.decidedBy.type").value("HUMAN"))
			.andExpect(jsonPath("$.decisionNote").value("agreed, pull them off break"));

		mockMvc.perform(get("/api/v1/tasks/{id}", replenishment))
			.andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.assignedWorker").value(prefix + "-DRIVER"));
		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(jsonPath("$.blockers[0].rootCause").value("IN_PROGRESS"));
	}

	@Test
	void rejectingLeavesTheFloorAlone() throws Exception {
		long proposal = propose(replenishment, prefix + "-DRIVER");

		mockMvc
			.perform(command("/api/v1/proposals/" + proposal + "/reject").contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"they are off shift\"}"))
			.andExpect(jsonPath("$.status").value("REJECTED"));

		mockMvc.perform(get("/api/v1/tasks/{id}", replenishment)).andExpect(jsonPath("$.status").value("READY"));
	}

	@Test
	void aProposalCanOnlyBeDecidedOnce() throws Exception {
		long proposal = propose(replenishment, prefix + "-DRIVER");
		mockMvc.perform(command("/api/v1/proposals/" + proposal + "/approve")).andExpect(status().isOk());

		mockMvc.perform(command("/api/v1/proposals/" + proposal + "/reject"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void aFailedCommandChangesNothingAndLeavesTheProposalOpen() throws Exception {
		worker(prefix + "-PICKER", null);
		long proposal = propose(replenishment, prefix + "-PICKER");

		mockMvc.perform(command("/api/v1/proposals/" + proposal + "/approve"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NOT_ELIGIBLE"));

		mockMvc.perform(get("/api/v1/proposals/{id}", proposal)).andExpect(jsonPath("$.status").value("PROPOSED"));
		mockMvc.perform(get("/api/v1/tasks/{id}", replenishment))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.assignedWorker").doesNotExist());
	}

	@Test
	void aPayloadWithoutAWorkerIsRejected() throws Exception {
		mockMvc
			.perform(command("/api/v1/proposals").contentType(MediaType.APPLICATION_JSON).content("""
					{"kind": "REASSIGN_TASK", "wave": %d, "payload": {"task": %d},
					 "rationale": "someone should do this", "evidence": ["diagnosis"]}"""
				.formatted(wave, replenishment)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void proposalsCanBeListedByWave() throws Exception {
		propose(replenishment, prefix + "-DRIVER");

		mockMvc.perform(get("/api/v1/proposals").param("wave", String.valueOf(wave)).param("status", "PROPOSED"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("REASSIGN_TASK"))
			.andExpect(jsonPath("$.items[0].payload.worker").value(prefix + "-DRIVER"));
	}

	private long propose(long task, String worker) throws Exception {
		String body = mockMvc
			.perform(command("/api/v1/proposals").header("X-Agent-Id", "ops-agent")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"kind": "REASSIGN_TASK", "wave": %d, "payload": {"task": %d, "worker": "%s"},
						 "rationale": "Nobody available is certified for REACH_TRUCK; %s is, and is on break.",
						 "evidence": ["diagnosis: NO_ELIGIBLE_WORKER_AVAILABLE"]}"""
					.formatted(wave, task, worker, worker)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private void worker(String code, String equipment) throws Exception {
		String certifications = equipment == null ? "" : "\"" + equipment + "\"";
		mockMvc
			.perform(command("/api/v1/workers").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"code": "%s", "name": "Test worker", "homeZone": "%s", "equipment": [%s]}"""
					.formatted(code, prefix, certifications)))
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
