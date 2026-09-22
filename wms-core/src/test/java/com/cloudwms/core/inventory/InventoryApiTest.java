package com.cloudwms.core.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.inventory.domain.Actor;
import com.cloudwms.core.inventory.domain.ActorType;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class InventoryApiTest {

	static final Actor CLERK = new Actor(ActorType.HUMAN, "clerk-1");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryService inventory;

	InventoryFixtures fixtures;
	long zone;
	String zoneCode;

	@BeforeEach
	void setUp() {
		fixtures = new InventoryFixtures(jdbc);
		zone = fixtures.zone();
		zoneCode = fixtures.code("zone", zone);
	}

	@Test
	void listsBalancesWithAvailable() throws Exception {
		long forward = fixtures.location(zone, "FORWARD_PICK");
		long sku = fixtures.sku();
		receive(sku, forward, 12);
		allocate(forward, sku, 5);

		mockMvc.perform(get("/api/v1/inventory").param("sku", fixtures.code("sku", sku)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].location").value(fixtures.code("location", forward)))
			.andExpect(jsonPath("$.items[0].zone").value(zoneCode))
			.andExpect(jsonPath("$.items[0].locationType").value("FORWARD_PICK"))
			.andExpect(jsonPath("$.items[0].onHand").value(12))
			.andExpect(jsonPath("$.items[0].allocated").value(5))
			.andExpect(jsonPath("$.items[0].available").value(7));
	}

	@Test
	void emptyBalancesAreHiddenUnlessRequested() throws Exception {
		long forward = fixtures.location(zone, "FORWARD_PICK");
		long sku = fixtures.sku();
		receive(sku, forward, 3);
		inventory.record(InventoryMovement.pick(sku, forward, 3, null, CLERK));
		String skuCode = fixtures.code("sku", sku);

		mockMvc.perform(get("/api/v1/inventory").param("sku", skuCode))
			.andExpect(jsonPath("$.items", empty()));
		mockMvc.perform(get("/api/v1/inventory").param("sku", skuCode).param("includeEmpty", "true"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].onHand").value(0));
	}

	@Test
	void filtersByLocationType() throws Exception {
		long forward = fixtures.location(zone, "FORWARD_PICK");
		long reserve = fixtures.location(zone, "RESERVE");
		long sku = fixtures.sku();
		receive(sku, forward, 5);
		receive(sku, reserve, 40);

		mockMvc.perform(get("/api/v1/inventory").param("zone", zoneCode).param("type", "RESERVE"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].location").value(fixtures.code("location", reserve)));
	}

	@Test
	void paginatesOverTheCompositeKey() throws Exception {
		// 5 balances: two SKUs in each of two locations, plus one more location.
		long a = fixtures.location(zone, "RESERVE");
		long b = fixtures.location(zone, "RESERVE");
		long c = fixtures.location(zone, "RESERVE");
		long sku1 = fixtures.sku();
		long sku2 = fixtures.sku();
		receive(sku1, a, 1);
		receive(sku2, a, 1);
		receive(sku1, b, 1);
		receive(sku2, b, 1);
		receive(sku1, c, 1);

		List<String> seen = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		String cursor = null;
		do {
			var request = get("/api/v1/inventory").param("zone", zoneCode).param("limit", "2");
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
			List<String> locations = JsonPath.read(body, "$.items[*].location");
			List<String> skus = JsonPath.read(body, "$.items[*].sku");
			for (int i = 0; i < locations.size(); i++) {
				seen.add(locations.get(i) + "/" + skus.get(i));
			}
			sizes.add(locations.size());
			cursor = JsonPath.read(body, "$.nextCursor");
		}
		while (cursor != null);

		String la = fixtures.code("location", a), lb = fixtures.code("location", b), lc = fixtures.code("location", c);
		String s1 = fixtures.code("sku", sku1), s2 = fixtures.code("sku", sku2);
		assertThat(sizes).containsExactly(2, 2, 1);
		assertThat(seen).containsExactly(la + "/" + s1, la + "/" + s2, lb + "/" + s1, lb + "/" + s2, lc + "/" + s1);
	}

	@Test
	void availabilityIsSplitByLocationType() throws Exception {
		long forward = fixtures.location(zone, "FORWARD_PICK");
		long reserve1 = fixtures.location(zone, "RESERVE");
		long reserve2 = fixtures.location(zone, "RESERVE");
		long sku = fixtures.sku();
		receive(sku, forward, 12);
		allocate(forward, sku, 5);
		receive(sku, reserve1, 30);
		receive(sku, reserve2, 12);

		mockMvc.perform(get("/api/v1/skus/{code}/availability", fixtures.code("sku", sku)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total.onHand").value(54))
			.andExpect(jsonPath("$.total.allocated").value(5))
			.andExpect(jsonPath("$.total.available").value(49))
			.andExpect(jsonPath("$.byLocationType.length()").value(2))
			.andExpect(jsonPath("$.byLocationType[0].locationType").value("FORWARD_PICK"))
			.andExpect(jsonPath("$.byLocationType[0].locations").value(1))
			.andExpect(jsonPath("$.byLocationType[0].available").value(7))
			.andExpect(jsonPath("$.byLocationType[1].locationType").value("RESERVE"))
			.andExpect(jsonPath("$.byLocationType[1].locations").value(2))
			.andExpect(jsonPath("$.byLocationType[1].onHand").value(42));
	}

	@Test
	void availabilityOfASkuWithNoStockIsZero() throws Exception {
		mockMvc.perform(get("/api/v1/skus/{code}/availability", fixtures.code("sku", fixtures.sku())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total.onHand").value(0))
			.andExpect(jsonPath("$.byLocationType", empty()));
		mockMvc.perform(get("/api/v1/skus/{code}/availability", "NO-SUCH-SKU"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void cursorFromAnotherListIsRejected() throws Exception {
		// A single-id cursor (as issued by /locations) is not valid for the composite-key /inventory list.
		mockMvc.perform(get("/api/v1/inventory").param("cursor", "MTIz"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private void receive(long sku, long location, int quantity) {
		inventory.record(InventoryMovement.receipt(sku, location, quantity, null, CLERK));
	}

	/** Allocation has no API until wave planning exists; set it directly. */
	private void allocate(long location, long sku, int quantity) {
		jdbc.sql("UPDATE inventory_balance SET allocated = ? WHERE location_id = ? AND sku_id = ?")
			.params(quantity, location, sku)
			.update();
	}

}
