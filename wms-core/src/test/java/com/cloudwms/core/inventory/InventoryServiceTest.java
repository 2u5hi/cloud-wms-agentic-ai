package com.cloudwms.core.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs against real MySQL and commits, so locking and transaction behavior are the real thing. */
@IntegrationTest
class InventoryServiceTest {

	static final Actor CLERK = new Actor(ActorType.HUMAN, "clerk-1");

	@Autowired
	InventoryService inventory;

	@Autowired
	JdbcClient jdbc;

	InventoryFixtures fixtures;
	long reserve;
	long forwardPick;
	long sku;

	@BeforeEach
	void setUp() {
		fixtures = new InventoryFixtures(jdbc);
		long zone = fixtures.zone();
		reserve = fixtures.location(zone, "RESERVE");
		forwardPick = fixtures.location(zone, "FORWARD_PICK");
		sku = fixtures.sku();
	}

	@Test
	void recordsBalancesAndLedgerTogether() {
		inventory.record(InventoryMovement.receipt(sku, reserve, 48, null, CLERK));
		inventory.record(InventoryMovement.move(sku, reserve, forwardPick, 30, null, CLERK));
		var pick = inventory.record(InventoryMovement.pick(sku, forwardPick, 4, null, CLERK));

		assertThat(pick.transactionId()).isPositive();
		assertThat(fixtures.onHand(reserve, sku)).isEqualTo(18);
		assertThat(fixtures.onHand(forwardPick, sku)).isEqualTo(26);
		assertThat(fixtures.ledgerRows(sku)).isEqualTo(3);
		assertThat(fixtures.ledgerNet(reserve, sku)).isEqualTo(18);
		assertThat(fixtures.ledgerNet(forwardPick, sku)).isEqualTo(26);
	}

	@Test
	void insufficientStockChangesNothing() {
		inventory.record(InventoryMovement.receipt(sku, forwardPick, 5, null, CLERK));

		assertThatThrownBy(() -> inventory.record(InventoryMovement.pick(sku, forwardPick, 6, null, CLERK)))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY));

		assertThat(fixtures.onHand(forwardPick, sku)).isEqualTo(5);
		assertThat(fixtures.ledgerRows(sku)).isEqualTo(1);
	}

	@Test
	void failedMoveLeavesBothLocationsUntouched() {
		inventory.record(InventoryMovement.receipt(sku, reserve, 10, null, CLERK));

		assertThatThrownBy(() -> inventory.record(InventoryMovement.move(sku, reserve, forwardPick, 11, null, CLERK)))
			.isInstanceOf(DomainException.class);

		assertThat(fixtures.onHand(reserve, sku)).isEqualTo(10);
		// The empty destination row created while locking was rolled back too.
		assertThat(fixtures.balanceRowExists(forwardPick, sku)).isFalse();
		assertThat(fixtures.ledgerRows(sku)).isEqualTo(1);
	}

	@Test
	void unknownSkuOrLocationIsNotFound() {
		assertThatThrownBy(() -> inventory.record(InventoryMovement.receipt(999_999_999L, reserve, 1, null, CLERK)))
			.isInstanceOfSatisfying(DomainException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
				assertThat(ex.getMessage()).isEqualTo("SKU 999999999 does not exist");
			});
		assertThatThrownBy(() -> inventory.record(InventoryMovement.receipt(sku, 999_999_999L, 1, null, CLERK)))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.getMessage()).isEqualTo("Location 999999999 does not exist"));
	}

	@Test
	void concurrentPicksNeverOversellStock() throws Exception {
		inventory.record(InventoryMovement.receipt(sku, forwardPick, 100, null, CLERK));

		// 50 pickers each take 3 units of the same stock at the same moment. 100 / 3 = 33 can succeed.
		List<Boolean> results = runConcurrently(50,
				i -> attempt(() -> inventory.record(InventoryMovement.pick(sku, forwardPick, 3, null, CLERK))));

		assertThat(results.stream().filter(ok -> ok).count()).isEqualTo(33);
		assertThat(fixtures.onHand(forwardPick, sku)).isEqualTo(1);
		assertThat(fixtures.ledgerNet(forwardPick, sku)).isEqualTo(1);
		assertThat(fixtures.ledgerRows(sku)).isEqualTo(1 + 33);
	}

	@Test
	void oppositeConcurrentMovesDoNotDeadlock() throws Exception {
		inventory.record(InventoryMovement.receipt(sku, reserve, 500, null, CLERK));
		inventory.record(InventoryMovement.receipt(sku, forwardPick, 500, null, CLERK));

		// Half move reserve -> forward, half forward -> reserve. Without a consistent lock order this is the
		// textbook deadlock: each transaction holds one row and waits for the other.
		List<Boolean> results = runConcurrently(60, i -> {
			long from = i % 2 == 0 ? reserve : forwardPick;
			long to = i % 2 == 0 ? forwardPick : reserve;
			return attempt(() -> inventory.record(InventoryMovement.move(sku, from, to, 5, null, CLERK)));
		});

		assertThat(results).allMatch(ok -> ok);
		assertThat(fixtures.onHand(reserve, sku) + fixtures.onHand(forwardPick, sku)).isEqualTo(1000);
		assertThat(fixtures.onHand(reserve, sku)).isEqualTo(fixtures.ledgerNet(reserve, sku));
		assertThat(fixtures.onHand(forwardPick, sku)).isEqualTo(fixtures.ledgerNet(forwardPick, sku));
	}

	/** True if recorded, false if rejected for insufficient stock. Anything else (e.g. a deadlock) fails. */
	private static boolean attempt(Runnable operation) {
		try {
			operation.run();
			return true;
		}
		catch (DomainException ex) {
			if (ex.code() != ErrorCode.INSUFFICIENT_INVENTORY) {
				throw ex;
			}
			return false;
		}
	}

	/** Runs {@code tasks} copies of the task (given their index) on 16 threads, all released at the same instant. */
	private static List<Boolean> runConcurrently(int tasks, IntFunction<Boolean> task) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < tasks; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					start.await();
					return task.apply(index);
				}));
			}
			start.countDown();
			List<Boolean> results = new ArrayList<>();
			for (Future<Boolean> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
	}

}
