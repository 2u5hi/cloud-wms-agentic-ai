package com.cloudwms.core.devdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.orders.OrderService;
import com.cloudwms.core.waves.WavePlanningService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevDataSeederTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	OrderService orders;

	@Autowired
	WavePlanningService waves;

	@Autowired
	MockMvc mockMvc;

	boolean seededHere;
	Optional<Long> demoWave;

	@BeforeAll
	void seedOnce() {
		seededHere = new DevDataSeeder(jdbc, inventory, transactions).seed();
		demoWave = new DemoScenario(jdbc, orders, waves, transactions).create(Instant.now());
	}

	/** The whole demo in one test, because each step depends on the one before. */
	@Test
	void theDemoWaveBlocksOnTheReachTruckAndTheProposedFixClearsIt() throws Exception {
		assertThat(demoWave).isPresent();
		long wave = demoWave.get();
		String blocked = "$.blockers[?(@.rootCause == 'NO_ELIGIBLE_WORKER_AVAILABLE')]";

		// Two replenishments from high reserve, nobody available to drive the truck, and the two orders
		// that need them close to their cutoff.
		String diagnosis = mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RELEASED"))
			.andExpect(jsonPath("$.picks.waiting").value(2))
			.andExpect(jsonPath(blocked, hasSize(2)))
			.andExpect(jsonPath(blocked + ".replenishment.requiredEquipment", everyItem(is("REACH_TRUCK"))))
			.andExpect(jsonPath("$.atRiskOrders[*].order", hasItems("SO-DEMO-1007", "SO-DEMO-1008")))
			.andReturn()
			.getResponse()
			.getContentAsString();

		// Approve the fix the agent would propose: give each replenishment to the certified driver.
		List<Integer> replenishments = JsonPath.read(diagnosis, blocked + ".replenishment.taskId");
		for (int task : replenishments) {
			String proposal = mockMvc
				.perform(command("/api/v1/proposals").header("X-Agent-Id", "ops-agent")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"kind": "REASSIGN_TASK", "wave": %d, "payload": {"task": %d, "worker": "%s"},
							 "rationale": "the only certified driver", "evidence": ["diagnosis"]}"""
						.formatted(wave, task, DemoScenario.REACH_TRUCK_DRIVER)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
			mockMvc.perform(command("/api/v1/proposals/" + JsonPath.read(proposal, "$.id") + "/approve"))
				.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andExpect(jsonPath(blocked, hasSize(0)))
			.andExpect(jsonPath("$.blockers[?(@.rootCause == 'IN_PROGRESS')]", hasSize(2)));
	}

	@Test
	void theDemoScenarioIsCreatedOnce() {
		assertThat(new DemoScenario(jdbc, orders, waves, transactions).create(Instant.now())).isEmpty();
		assertThat(count("SELECT COUNT(*) FROM orders WHERE external_ref LIKE 'SO-DEMO-%'")).isEqualTo(8);
	}

	private MockHttpServletRequestBuilder command(String path) {
		return post(path).header("Idempotency-Key", UUID.randomUUID().toString());
	}

	@Test
	void seedsTheLayout() {
		assertThat(seededHere).isTrue();
		assertThat(count("SELECT COUNT(*) FROM location l JOIN zone z ON z.id = l.zone_id WHERE z.code IN ('A','B','C','D')"))
			.isEqualTo(4 * 5 * 15 * 4);
		assertThat(count("SELECT COUNT(*) FROM location l JOIN zone z ON z.id = l.zone_id WHERE z.code = 'S'"))
			.isEqualTo(24);
		assertThat(count("SELECT COUNT(*) FROM location WHERE code LIKE '_-__-__-A' AND type = 'FORWARD_PICK'"))
			.isEqualTo(300);
	}

	@Test
	void highReserveNeedsAReachTruck() {
		assertThat(count("""
				SELECT COUNT(*) FROM location
				WHERE code REGEXP '^[A-D]-[0-9]{2}-[0-9]{2}-[CD]$' AND (required_equipment IS NULL OR required_equipment <> 'REACH_TRUCK')"""))
			.isZero();
		assertThat(count("SELECT COUNT(*) FROM location WHERE code REGEXP '^[A-D]-[0-9]{2}-[0-9]{2}-B$' AND required_equipment IS NOT NULL"))
			.isZero();
	}

	@Test
	void everyForwardSlotHoldsOneSku() {
		assertThat(count("SELECT COUNT(*) FROM sku WHERE code LIKE 'SKU-%'")).isEqualTo(300);
		assertThat(count("""
				SELECT COUNT(*) FROM pick_slot ps JOIN sku s ON s.id = ps.sku_id WHERE s.code LIKE 'SKU-%'""")).isEqualTo(300);
	}

	@Test
	void fastMoversAreSlottedEarlierOnThePickPath() {
		double fast = average("A");
		double slow = average("C");
		assertThat(fast).isLessThan(slow);
	}

	@Test
	void someSlotsStartBelowTheirMinimum() {
		long belowMin = count("""
				SELECT COUNT(*) FROM pick_slot ps
				JOIN sku s ON s.id = ps.sku_id
				LEFT JOIN inventory_balance b ON b.location_id = ps.location_id AND b.sku_id = ps.sku_id
				WHERE s.code LIKE 'SKU-%' AND COALESCE(b.on_hand, 0) < ps.min_qty""");
		assertThat(belowMin).isBetween(1L, 30L);
	}

	@Test
	void everyBalanceInTheDatabaseReconcilesWithTheLedger() {
		long mismatches = count("""
				SELECT COUNT(*) FROM inventory_balance b
				WHERE b.on_hand <> (
				    SELECT COALESCE(SUM(CASE WHEN t.to_location_id = b.location_id THEN t.quantity ELSE -t.quantity END), 0)
				    FROM inventory_txn t
				    WHERE t.sku_id = b.sku_id AND (t.to_location_id = b.location_id OR t.from_location_id = b.location_id))""");
		assertThat(mismatches).isZero();
		assertThat(count("SELECT COUNT(*) FROM inventory_txn WHERE actor_id = 'dev-seed'")).isGreaterThan(300);
	}

	@Test
	void seedingTwiceDoesNothing() {
		long locations = count("SELECT COUNT(*) FROM location");
		assertThat(new DevDataSeeder(jdbc, inventory, transactions).seed()).isFalse();
		assertThat(count("SELECT COUNT(*) FROM location")).isEqualTo(locations);
	}

	private double average(String velocity) {
		return jdbc.sql("""
				SELECT AVG(l.pick_sequence) FROM pick_slot ps
				JOIN sku s ON s.id = ps.sku_id JOIN location l ON l.id = ps.location_id
				WHERE s.velocity_class = ? AND s.code LIKE 'SKU-%'""").param(velocity).query(Double.class).single();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
