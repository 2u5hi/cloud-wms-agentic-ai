package com.cloudwms.core.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.cloudwms.core.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
class InventoryCommandsApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	InventoryFixtures fixtures;
	String reserve;
	String forward;
	String sku;

	@BeforeEach
	void setUp() {
		fixtures = new InventoryFixtures(jdbc);
		long zone = fixtures.zone();
		reserve = fixtures.code("location", fixtures.location(zone, "RESERVE"));
		forward = fixtures.code("location", fixtures.location(zone, "FORWARD_PICK"));
		sku = fixtures.code("sku", fixtures.sku());
	}

	@Test
	void receiptReturnsTheLedgerEntryAndNewBalance() throws Exception {
		String body = post("/api/v1/inventory/receipts", """
				{"sku": "%s", "location": "%s", "quantity": 48, "reference": {"type": "PO", "id": "PO-1001"}}"""
			.formatted(sku, reserve))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/v1/inventory/transactions/")))
			.andExpect(jsonPath("$.transaction.type").value("RECEIPT"))
			.andExpect(jsonPath("$.transaction.sku").value(sku))
			.andExpect(jsonPath("$.transaction.toLocation").value(reserve))
			.andExpect(jsonPath("$.transaction.fromLocation").isEmpty())
			.andExpect(jsonPath("$.transaction.quantity").value(48))
			.andExpect(jsonPath("$.transaction.reference.type").value("PO"))
			.andExpect(jsonPath("$.transaction.reference.id").value("PO-1001"))
			.andExpect(jsonPath("$.transaction.actor.type").value("HUMAN"))
			.andExpect(jsonPath("$.transaction.actor.id").value("unauthenticated"))
			.andExpect(jsonPath("$.transaction.occurredAt").isNotEmpty())
			.andExpect(jsonPath("$.balances.length()").value(1))
			.andExpect(jsonPath("$.balances[0].location").value(reserve))
			.andExpect(jsonPath("$.balances[0].onHand").value(48))
			.andReturn()
			.getResponse()
			.getContentAsString();

		// The Location header points at the same ledger entry.
		Integer id = JsonPath.read(body, "$.transaction.id");
		mockMvc.perform(get("/api/v1/inventory/transactions/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quantity").value(48));
	}

	@Test
	void moveReturnsBothBalances() throws Exception {
		receive(reserve, 42);

		post("/api/v1/inventory/moves", """
				{"sku": "%s", "fromLocation": "%s", "toLocation": "%s", "quantity": 30}""".formatted(sku, reserve, forward))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.transaction.type").value("MOVE"))
			.andExpect(jsonPath("$.balances.length()").value(2))
			.andExpect(jsonPath("$.balances[0].location").value(reserve))
			.andExpect(jsonPath("$.balances[0].onHand").value(12))
			.andExpect(jsonPath("$.balances[1].location").value(forward))
			.andExpect(jsonPath("$.balances[1].onHand").value(30));
	}

	@Test
	void moveWithoutEnoughStockIsAConflict() throws Exception {
		receive(reserve, 10);

		post("/api/v1/inventory/moves", """
				{"sku": "%s", "fromLocation": "%s", "toLocation": "%s", "quantity": 11}""".formatted(sku, reserve, forward))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"))
			.andExpect(jsonPath("$.detail").value("Only 10 of 11 units available"))
			.andExpect(jsonPath("$.available").value(10))
			.andExpect(jsonPath("$.requested").value(11));
	}

	@Test
	void moveToTheSameLocationIsInvalid() throws Exception {
		post("/api/v1/inventory/moves", """
				{"sku": "%s", "fromLocation": "%s", "toLocation": "%s", "quantity": 1}""".formatted(sku, reserve, reserve))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("toLocation"));
	}

	@Test
	void negativeAdjustmentRemovesStockWithAReason() throws Exception {
		receive(forward, 20);

		post("/api/v1/inventory/adjustments", """
				{"sku": "%s", "location": "%s", "quantity": -4, "reason": "damaged"}""".formatted(sku, forward))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.transaction.type").value("ADJUST"))
			.andExpect(jsonPath("$.transaction.fromLocation").value(forward))
			.andExpect(jsonPath("$.transaction.quantity").value(4))
			.andExpect(jsonPath("$.transaction.reason").value("damaged"))
			.andExpect(jsonPath("$.balances[0].onHand").value(16));
	}

	@Test
	void invalidAdjustmentsAreRejected() throws Exception {
		post("/api/v1/inventory/adjustments", """
				{"sku": "%s", "location": "%s", "quantity": 0, "reason": "none"}""".formatted(sku, forward))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("quantity"));
		post("/api/v1/inventory/adjustments", """
				{"sku": "%s", "location": "%s", "quantity": 5}""".formatted(sku, forward))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("reason"));
	}

	@Test
	void invalidReceiptsAreRejected() throws Exception {
		post("/api/v1/inventory/receipts", """
				{"sku": "%s", "location": "%s", "quantity": 0}""".formatted(sku, reserve))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("quantity"));
		post("/api/v1/inventory/receipts", """
				{"sku": "NO-SUCH-SKU", "location": "%s", "quantity": 1}""".formatted(reserve))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("SKU NO-SUCH-SKU does not exist"));
		assertThat(fixtures.onHand(fixtures.locationIdByCode(reserve), fixtures.skuIdByCode(sku))).isZero();
	}

	@Test
	void ledgerIsListedNewestFirstWithPagination() throws Exception {
		receive(reserve, 1);
		receive(reserve, 2);
		receive(reserve, 3);

		String first = mockMvc.perform(get("/api/v1/inventory/transactions").param("sku", sku).param("limit", "2"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Integer> firstQuantities = JsonPath.read(first, "$.items[*].quantity");
		String cursor = JsonPath.read(first, "$.nextCursor");

		String second = mockMvc
			.perform(get("/api/v1/inventory/transactions").param("sku", sku).param("limit", "2").param("cursor", cursor))
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Integer> secondQuantities = JsonPath.read(second, "$.items[*].quantity");

		assertThat(firstQuantities).containsExactly(3, 2);
		assertThat(secondQuantities).containsExactly(1);
		assertThat((String) JsonPath.read(second, "$.nextCursor")).isNull();
	}

	@Test
	void unknownTransactionIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/inventory/transactions/{id}", 999_999_999))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private void receive(String location, int quantity) throws Exception {
		post("/api/v1/inventory/receipts", """
				{"sku": "%s", "location": "%s", "quantity": %d}""".formatted(sku, location, quantity))
			.andExpect(status().isCreated());
	}

	private ResultActions post(String path, String json) throws Exception {
		return mockMvc
			.perform(MockMvcRequestBuilders.post(path)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

}
