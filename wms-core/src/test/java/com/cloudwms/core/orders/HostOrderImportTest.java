package com.cloudwms.core.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.cloudwms.core.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class HostOrderImportTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	String prefix;
	String skuA;
	String skuB;

	@BeforeEach
	void setUp() {
		prefix = "SO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		skuA = prefix + "-A";
		skuB = prefix + "-B";
		jdbc.sql("INSERT INTO sku (code, description) VALUES (?, 'A'), (?, 'B')").params(skuA, skuB).update();
	}

	@Test
	void eachOrderInABatchSucceedsOrFailsOnItsOwn() throws Exception {
		String batch = batch(order(prefix + "-1", 2, skuA, 6), """
				{"externalRef": "%s-2", "customer": "Acme", "priority": 9, "carrierCutoffAt": "2026-09-22T19:00:00Z",
				 "lines": [{"lineNo": 1, "sku": "NO-SUCH-SKU", "quantity": 1}]}""".formatted(prefix), order(prefix + "-3", 1, skuB, 2));

		importOrders(batch).andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(2))
			.andExpect(jsonPath("$.rejected").value(1))
			.andExpect(jsonPath("$.duplicates").value(0))
			.andExpect(jsonPath("$.results[0].result").value("ACCEPTED"))
			.andExpect(jsonPath("$.results[1].result").value("REJECTED"))
			.andExpect(jsonPath("$.results[1].externalRef").value(prefix + "-2"))
			.andExpect(jsonPath("$.results[1].errors[*].field").value(org.hamcrest.Matchers.contains("carrier",
					"lines[0].sku", "priority")))
			.andExpect(jsonPath("$.results[2].result").value("ACCEPTED"));

		assertThat(orderExists(prefix + "-2")).isFalse();
		mockMvc.perform(get("/api/v1/orders/{ref}", prefix + "-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RECEIVED"))
			.andExpect(jsonPath("$.priority").value(2))
			.andExpect(jsonPath("$.carrier").value("UPS"))
			.andExpect(jsonPath("$.onHold").value(false))
			.andExpect(jsonPath("$.lines[0].sku").value(skuA))
			.andExpect(jsonPath("$.lines[0].ordered").value(6))
			.andExpect(jsonPath("$.lines[0].shortQuantity").value(6));
	}

	@Test
	void resendingAnOrderIsADuplicateAndChangesNothing() throws Exception {
		importOrders(batch(order(prefix + "-1", 3, skuA, 6))).andExpect(jsonPath("$.accepted").value(1));

		// The host resends the order with a different quantity. It is reported, not applied.
		importOrders(batch(order(prefix + "-1", 3, skuA, 99))).andExpect(status().isOk())
			.andExpect(jsonPath("$.duplicates").value(1))
			.andExpect(jsonPath("$.results[0].result").value("DUPLICATE"));

		mockMvc.perform(get("/api/v1/orders/{ref}", prefix + "-1")).andExpect(jsonPath("$.lines[0].ordered").value(6));
	}

	@Test
	void theSameReferenceTwiceInOneBatchIsAcceptedOnce() throws Exception {
		importOrders(batch(order(prefix + "-1", 3, skuA, 1), order(prefix + "-1", 3, skuA, 1)))
			.andExpect(jsonPath("$.results[0].result").value("ACCEPTED"))
			.andExpect(jsonPath("$.results[1].result").value("DUPLICATE"));
	}

	@Test
	void duplicateLineNumbersAreRejected() throws Exception {
		importOrders(batch("""
				{"externalRef": "%s-1", "customer": "Acme", "carrier": "UPS", "carrierCutoffAt": "2026-09-22T19:00:00Z",
				 "lines": [{"lineNo": 1, "sku": "%s", "quantity": 1}, {"lineNo": 1, "sku": "%s", "quantity": 2}]}"""
			.formatted(prefix, skuA, skuB)))
			.andExpect(jsonPath("$.results[0].result").value("REJECTED"))
			.andExpect(jsonPath("$.results[0].errors[0].field").value("lines[1].lineNo"))
			.andExpect(jsonPath("$.results[0].errors[0].message").value("duplicate line number 1"));
	}

	@Test
	void priorityDefaultsToThree() throws Exception {
		importOrders(batch("""
				{"externalRef": "%s-1", "customer": "Acme", "carrier": "UPS", "carrierCutoffAt": "2026-09-22T19:00:00Z",
				 "lines": [{"lineNo": 1, "sku": "%s", "quantity": 1}]}""".formatted(prefix, skuA)))
			.andExpect(jsonPath("$.accepted").value(1));
		mockMvc.perform(get("/api/v1/orders/{ref}", prefix + "-1")).andExpect(jsonPath("$.priority").value(3));
	}

	@Test
	void aMalformedBatchIsRejectedAsAWhole() throws Exception {
		importOrders("{\"orders\": []}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("orders"));
		String tooMany = IntStream.range(0, 501)
			.mapToObj(i -> order(prefix + "-" + i, 3, skuA, 1))
			.collect(Collectors.joining(","));
		importOrders("{\"orders\": [" + tooMany + "]}").andExpect(status().isBadRequest());
	}

	@Test
	void concurrentImportsOfTheSameOrdersAcceptEachOnce() throws Exception {
		// Five requests (different idempotency keys) race to import the same 20 orders.
		String batch = batch(IntStream.range(0, 20).mapToObj(i -> order(prefix + "-" + i, 3, skuA, 1)).toArray(String[]::new));
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
			for (int i = 0; i < 5; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return importOrders(batch).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
				}));
			}
			start.countDown();
			int accepted = 0;
			int duplicates = 0;
			for (Future<String> future : futures) {
				String body = future.get(60, TimeUnit.SECONDS);
				accepted += (Integer) JsonPath.read(body, "$.accepted");
				duplicates += (Integer) JsonPath.read(body, "$.duplicates");
			}
			assertThat(accepted).isEqualTo(20);
			assertThat(duplicates).isEqualTo(80);
		}
		assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE external_ref LIKE ?").param(prefix + "-%")
			.query(Long.class).single()).isEqualTo(20);
	}

	@Test
	void ordersAreListedAndFilterable() throws Exception {
		importOrders(batch(order(prefix + "-1", 3, skuA, 4), order(prefix + "-2", 3, skuB, 5)));

		String body = mockMvc.perform(get("/api/v1/orders").param("status", "RECEIVED").param("limit", "500"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> refs = JsonPath.read(body, "$.items[*].externalRef");
		assertThat(refs).contains(prefix + "-1", prefix + "-2");
		List<Integer> unitsForOurs = JsonPath.read(body, "$.items[?(@.externalRef == '%s-2')].unitsOrdered".formatted(prefix));
		assertThat(unitsForOurs).containsExactly(5);

		mockMvc.perform(get("/api/v1/orders").param("status", "SHIPPED").param("limit", "500"))
			.andExpect(jsonPath("$.items[?(@.externalRef == '%s-1')]".formatted(prefix)).isEmpty());
		mockMvc.perform(get("/api/v1/orders/{ref}", "NO-SUCH-ORDER")).andExpect(status().isNotFound());
	}

	private String order(String ref, int priority, String sku, int quantity) {
		return """
				{"externalRef": "%s", "customer": "Acme Retail", "priority": %d, "carrier": "UPS",
				 "carrierCutoffAt": "2026-09-22T19:00:00Z", "lines": [{"lineNo": 1, "sku": "%s", "quantity": %d}]}"""
			.formatted(ref, priority, sku, quantity);
	}

	private static String batch(String... orders) {
		return "{\"orders\": [" + String.join(",", orders) + "]}";
	}

	private ResultActions importOrders(String json) throws Exception {
		return mockMvc.perform(post("/api/v1/integrations/host/orders").header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private boolean orderExists(String ref) {
		return jdbc.sql("SELECT COUNT(*) FROM orders WHERE external_ref = ?").param(ref).query(Long.class).single() > 0;
	}

}
