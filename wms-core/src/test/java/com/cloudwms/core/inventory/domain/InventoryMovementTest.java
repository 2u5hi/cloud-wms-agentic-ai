package com.cloudwms.core.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import org.junit.jupiter.api.Test;

class InventoryMovementTest {

	static final Actor CLERK = new Actor(ActorType.HUMAN, "clerk-1");
	static final long SKU = 20;
	static final long RESERVE = 1;
	static final long FORWARD = 2;

	@Test
	void receiptAddsStockAtTheDestination() {
		InventoryMovement receipt = InventoryMovement.receipt(SKU, RESERVE, 48, null, CLERK);
		assertThat(receipt.effects()).containsExactly(new StockDelta(new StockKey(RESERVE, SKU), 48));
	}

	@Test
	void pickRemovesStockAtTheSource() {
		InventoryMovement pick = InventoryMovement.pick(SKU, FORWARD, 3, new Reference("PICK_TASK", "T-1"), CLERK);
		assertThat(pick.effects()).containsExactly(new StockDelta(new StockKey(FORWARD, SKU), -3));
	}

	@Test
	void moveRemovesAtSourceAndAddsAtDestination() {
		InventoryMovement move = InventoryMovement.move(SKU, RESERVE, FORWARD, 42, null, CLERK);
		assertThat(move.effects()).containsExactly(new StockDelta(new StockKey(RESERVE, SKU), -42),
				new StockDelta(new StockKey(FORWARD, SKU), 42));
	}

	@Test
	void positiveAdjustmentIsRecordedAsArrival() {
		InventoryMovement adjustment = InventoryMovement.adjustment(SKU, FORWARD, 5, "found on shelf", CLERK);
		assertThat(adjustment.toLocationId()).isEqualTo(FORWARD);
		assertThat(adjustment.fromLocationId()).isNull();
		assertThat(adjustment.quantity()).isEqualTo(5);
	}

	@Test
	void negativeAdjustmentIsRecordedAsDeparture() {
		InventoryMovement adjustment = InventoryMovement.adjustment(SKU, FORWARD, -4, "damaged", CLERK);
		assertThat(adjustment.fromLocationId()).isEqualTo(FORWARD);
		assertThat(adjustment.toLocationId()).isNull();
		assertThat(adjustment.quantity()).isEqualTo(4);
		assertThat(adjustment.effects()).containsExactly(new StockDelta(new StockKey(FORWARD, SKU), -4));
	}

	@Test
	void correctionsNeedAReason() {
		assertThatThrownBy(() -> InventoryMovement.adjustment(SKU, FORWARD, 5, " ", CLERK))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reason");
		assertThatThrownBy(() -> InventoryMovement.countVariance(SKU, FORWARD, -2, null, null, CLERK))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void correctionsMustChangeTheQuantity() {
		assertThatThrownBy(() -> InventoryMovement.adjustment(SKU, FORWARD, 0, "nothing", CLERK))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void moveCannotStartAndEndAtTheSameLocation() {
		assertThatThrownBy(() -> InventoryMovement.move(SKU, FORWARD, FORWARD, 1, null, CLERK))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void typeMustMatchTheLocationsUsed() {
		assertThatThrownBy(() -> new InventoryMovement(InventoryTxnType.RECEIPT, SKU, RESERVE, null, 1, null, null,
				CLERK)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InventoryMovement(InventoryTxnType.PICK, SKU, null, FORWARD, 1, null, null,
				CLERK)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InventoryMovement(InventoryTxnType.MOVE, SKU, RESERVE, null, 1, null, null,
				CLERK)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void quantityMustBePositiveAndActorIsRequired() {
		assertThatThrownBy(() -> InventoryMovement.receipt(SKU, RESERVE, 0, null, CLERK))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InventoryMovement.receipt(SKU, RESERVE, 5, null, null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void applyingAMoveToBothBalancesConservesStock() {
		InventoryBalance reserve = new InventoryBalance(new StockKey(RESERVE, SKU), 42, 0);
		InventoryBalance forward = new InventoryBalance(new StockKey(FORWARD, SKU), 0, 0);
		InventoryMovement move = InventoryMovement.move(SKU, RESERVE, FORWARD, 42, null, CLERK);

		InventoryBalance reserveAfter = reserve.apply(move.effects().get(0));
		InventoryBalance forwardAfter = forward.apply(move.effects().get(1));

		assertThat(reserveAfter.onHand()).isZero();
		assertThat(forwardAfter.onHand()).isEqualTo(42);
		assertThat(reserveAfter.onHand() + forwardAfter.onHand()).isEqualTo(reserve.onHand() + forward.onHand());
	}

}
