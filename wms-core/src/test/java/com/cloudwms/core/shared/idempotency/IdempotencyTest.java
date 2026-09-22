package com.cloudwms.core.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.cloudwms.core.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class IdempotencyTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	String sku;
	String reserve;
	String forward;
	long skuId;
	long reserveId;

	@BeforeEach
	void setUp() {
		String prefix = "IDEM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		long zone = insert("INSERT INTO zone (code, name) VALUES (?, 'Idempotency test')", prefix);
		reserve = prefix + "-R";
		forward = prefix + "-F";
		sku = prefix + "-SKU";
		reserveId = insert("INSERT INTO location (code, zone_id, type) VALUES (?, ?, 'RESERVE')", reserve, zone);
		insert("INSERT INTO location (code, zone_id, type) VALUES (?, ?, 'FORWARD_PICK')", forward, zone);
		skuId = insert("INSERT INTO sku (code, description) VALUES (?, 'Test SKU')", sku);
	}

	@Test
	void retryWithTheSameKeyReplaysInsteadOfReceivingTwice() throws Exception {
		String key = UUID.randomUUID().toString();

		MockHttpServletResponse first = receipt(key, 10).andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn()
			.getResponse();
		MockHttpServletResponse retry = receipt(key, 10).andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andReturn()
			.getResponse();

		assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
		assertThat(retry.getHeader("Location")).isEqualTo(first.getHeader("Location"));
		assertThat(onHand()).isEqualTo(10);
		assertThat(ledgerRows()).isEqualTo(1);
	}

	@Test
	void sameKeyWithADifferentRequestIsRejected() throws Exception {
		String key = UUID.randomUUID().toString();
		receipt(key, 10).andExpect(status().isCreated());

		receipt(key, 99).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
			.andExpect(jsonPath("$.idempotencyKey").value(key));

		assertThat(onHand()).isEqualTo(10);
	}

	@Test
	void aFailedRequestDoesNotConsumeItsKey() throws Exception {
		String key = UUID.randomUUID().toString();
		String move = """
				{"sku": "%s", "fromLocation": "%s", "toLocation": "%s", "quantity": 5}""".formatted(sku, reserve, forward);

		// Nothing in reserve yet: the move fails and the key is not recorded.
		command("/api/v1/inventory/moves", key, move).andExpect(status().isConflict());
		assertThat(keyRecorded(key)).isFalse();

		receipt(UUID.randomUUID().toString(), 5).andExpect(status().isCreated());

		// The same key now executes for real rather than replaying the earlier failure.
		command("/api/v1/inventory/moves", key, move).andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Idempotent-Replayed"));
		assertThat(onHand()).isZero();
	}

	@Test
	void concurrentRetriesWithOneKeyApplyOnce() throws Exception {
		String key = UUID.randomUUID().toString();
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			for (int i = 0; i < 10; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return receipt(key, 7).andReturn().getResponse();
				}));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(30, TimeUnit.SECONDS));
			}

			assertThat(responses).allSatisfy(response -> assertThat(response.getStatus()).isEqualTo(201));
			assertThat(responses.stream().filter(r -> r.getHeader("Idempotent-Replayed") == null).count()).isEqualTo(1);
			assertThat(responses.stream().map(this::body).distinct().count()).isEqualTo(1);
		}
		assertThat(onHand()).isEqualTo(7);
		assertThat(ledgerRows()).isEqualTo(1);
	}

	@Test
	void postWithoutAKeyIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/inventory/receipts").contentType(MediaType.APPLICATION_JSON).content(receiptBody(1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
		command("/api/v1/inventory/receipts", "has spaces in it", receiptBody(1)).andExpect(status().isBadRequest());
		assertThat(ledgerRows()).isZero();
	}

	@Test
	void readsDoNotNeedAKey() throws Exception {
		mockMvc.perform(get("/api/v1/inventory").param("sku", sku)).andExpect(status().isOk());
	}

	private ResultActions receipt(String key, int quantity) throws Exception {
		return command("/api/v1/inventory/receipts", key, receiptBody(quantity));
	}

	private String receiptBody(int quantity) {
		return """
				{"sku": "%s", "location": "%s", "quantity": %d}""".formatted(sku, reserve, quantity);
	}

	private ResultActions command(String path, String key, String json) throws Exception {
		return mockMvc
			.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private String body(MockHttpServletResponse response) {
		try {
			return response.getContentAsString();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private int onHand() {
		return jdbc.sql("SELECT COALESCE(SUM(on_hand), 0) FROM inventory_balance WHERE sku_id = ? AND location_id = ?")
			.params(skuId, reserveId)
			.query(Integer.class)
			.single();
	}

	private long ledgerRows() {
		return jdbc.sql("SELECT COUNT(*) FROM inventory_txn WHERE sku_id = ?").param(skuId).query(Long.class).single();
	}

	private boolean keyRecorded(String key) {
		return jdbc.sql("SELECT COUNT(*) FROM idempotency_key WHERE idem_key = ?").param(key).query(Long.class).single() > 0;
	}

	private long insert(String sql, Object... params) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

}
