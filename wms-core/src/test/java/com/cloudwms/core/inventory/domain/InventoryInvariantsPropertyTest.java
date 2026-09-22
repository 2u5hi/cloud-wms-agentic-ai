package com.cloudwms.core.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * Property tests: random sequences of inventory operations must never break the inventory invariants.
 *
 * <p>Each seed produces a deterministic sequence, so any failure message names the seed and step to
 * replay. Reproduce one seed with {@code -Dinventory.seed=N}.
 */
class InventoryInvariantsPropertyTest {

	static final int SEEDS = 1_000;
	static final int STEPS_PER_SEED = 200;
	static final long[] LOCATIONS = { 1, 2, 3, 4 };
	static final long[] SKUS = { 10, 11, 12 };
	static final Actor ACTOR = new Actor(ActorType.SYSTEM, "property-test");

	@Test
	void randomOperationSequencesPreserveInvariants() {
		String single = System.getProperty("inventory.seed");
		if (single != null) {
			new Run(Long.parseLong(single)).execute();
			return;
		}
		long accepted = 0;
		long rejected = 0;
		for (long seed = 0; seed < SEEDS; seed++) {
			Run run = new Run(seed);
			run.execute();
			accepted += run.accepted;
			rejected += run.rejected;
		}
		// The generator must exercise both paths, or the invariants are only checked on the easy one.
		assertThat(accepted).as("accepted operations").isGreaterThan(SEEDS * STEPS_PER_SEED / 4);
		assertThat(rejected).as("rejected operations").isGreaterThan(SEEDS * STEPS_PER_SEED / 20);
	}

	/** One deterministic sequence of operations against an in-memory warehouse. */
	static final class Run {

		final long seed;
		final Random random;
		final Map<StockKey, InventoryBalance> balances = new HashMap<>();
		final List<InventoryMovement> ledger = new ArrayList<>();
		/** Running ledger net per location, maintained separately from the balances it is checked against. */
		final Map<StockKey, Long> ledgerNet = new HashMap<>();
		long stockIn;
		long stockOut;
		int accepted;
		int rejected;

		Run(long seed) {
			this.seed = seed;
			this.random = new Random(seed);
		}

		void execute() {
			for (int step = 0; step < STEPS_PER_SEED; step++) {
				Map<StockKey, InventoryBalance> before = Map.copyOf(balances);
				Operation operation = nextOperation();
				try {
					operation.apply();
					accepted++;
					check(step, operation, !operation.exceedsAvailable(before),
							"accepted although it needed more stock than was available");
				}
				catch (DomainException ex) {
					rejected++;
					check(step, operation, ex.code() == ErrorCode.INSUFFICIENT_INVENTORY,
							"unexpected error code " + ex.code());
					check(step, operation, operation.exceedsAvailable(before),
							"rejected although enough stock was available");
					check(step, operation, balances.equals(before), "a rejected operation changed balances");
				}
				verifyInvariants(step, operation);
			}
			// Full recomputation from the ledger, independent of the running totals used per step.
			for (InventoryBalance balance : balances.values()) {
				long fromScratch = ledger.stream()
					.flatMap(movement -> movement.effects().stream())
					.filter(change -> change.key().equals(balance.key()))
					.mapToLong(StockDelta::delta)
					.sum();
				check(STEPS_PER_SEED, null, balance.onHand() == fromScratch,
						"end-of-run ledger recomputation differs for " + balance.key());
			}
		}

		Operation nextOperation() {
			long sku = SKUS[random.nextInt(SKUS.length)];
			long location = LOCATIONS[random.nextInt(LOCATIONS.length)];
			int quantity = 1 + random.nextInt(30);
			return switch (random.nextInt(7)) {
				case 0, 1 -> movement(InventoryMovement.receipt(sku, location, quantity, null, ACTOR));
				case 2 -> movement(InventoryMovement.pick(sku, location, quantity, null, ACTOR));
				case 3 -> movement(InventoryMovement.move(sku, location, otherLocation(location), quantity, null, ACTOR));
				case 4 -> movement(InventoryMovement.adjustment(sku, location,
						random.nextBoolean() ? quantity : -quantity, "property test", ACTOR));
				case 5 -> allocation(new StockKey(location, sku), quantity);
				default -> deallocation(new StockKey(location, sku));
			};
		}

		Operation movement(InventoryMovement movement) {
			return new Operation(movement.toString()) {
				@Override
				void apply() {
					List<InventoryBalance> updated = movement.applyTo(Run.this::balanceAt);
					updated.forEach(balance -> balances.put(balance.key(), balance));
					ledger.add(movement);
					movement.effects().forEach(change -> ledgerNet.merge(change.key(), (long) change.delta(), Long::sum));
					if (movement.fromLocationId() == null) {
						stockIn += movement.quantity();
					}
					if (movement.toLocationId() == null) {
						stockOut += movement.quantity();
					}
				}

				@Override
				boolean exceedsAvailable(Map<StockKey, InventoryBalance> before) {
					return movement.effects()
						.stream()
						.anyMatch(change -> change.delta() < 0 && -change.delta() > available(before, change.key()));
				}
			};
		}

		Operation allocation(StockKey key, int quantity) {
			return new Operation("allocate " + quantity + " at " + key) {
				@Override
				void apply() {
					balances.put(key, balanceAt(key).allocate(quantity));
				}

				@Override
				boolean exceedsAvailable(Map<StockKey, InventoryBalance> before) {
					return quantity > available(before, key);
				}
			};
		}

		Operation deallocation(StockKey key) {
			return new Operation("deallocate all at " + key) {
				@Override
				void apply() {
					InventoryBalance balance = balanceAt(key);
					if (balance.allocated() > 0) {
						balances.put(key, balance.deallocate(balance.allocated()));
					}
				}

				@Override
				boolean exceedsAvailable(Map<StockKey, InventoryBalance> before) {
					return false;
				}
			};
		}

		void verifyInvariants(int step, Operation operation) {
			long onHandTotal = 0;
			for (InventoryBalance balance : balances.values()) {
				check(step, operation, balance.allocated() >= 0 && balance.allocated() <= balance.onHand(),
						"allocation invariant broken: " + balance);
				long net = ledgerNet.getOrDefault(balance.key(), 0L);
				check(step, operation, balance.onHand() == net,
						"ledger does not reconcile for " + balance.key() + ": balance " + balance.onHand() + ", ledger "
								+ net);
				onHandTotal += balance.onHand();
			}
			check(step, operation, onHandTotal == stockIn - stockOut,
					"stock not conserved: on hand " + onHandTotal + ", in - out " + (stockIn - stockOut));
		}

		InventoryBalance balanceAt(StockKey key) {
			return balances.getOrDefault(key, InventoryBalance.empty(key));
		}

		long otherLocation(long location) {
			long other;
			do {
				other = LOCATIONS[random.nextInt(LOCATIONS.length)];
			}
			while (other == location);
			return other;
		}

		static int available(Map<StockKey, InventoryBalance> balances, StockKey key) {
			InventoryBalance balance = balances.get(key);
			return balance == null ? 0 : balance.available();
		}

		void check(int step, Operation operation, boolean condition, String message) {
			if (!condition) {
				String what = operation == null ? "end of run" : operation.description;
				fail("seed %d, step %d (%s): %s".formatted(seed, step, what, message));
			}
		}

	}

	abstract static class Operation {

		final String description;

		Operation(String description) {
			this.description = description;
		}

		abstract void apply();

		/** Whether, given the balances before the operation, it asks for more stock than is available. */
		abstract boolean exceedsAvailable(Map<StockKey, InventoryBalance> before);

	}

}
