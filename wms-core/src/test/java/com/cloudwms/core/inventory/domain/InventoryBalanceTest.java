package com.cloudwms.core.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

class InventoryBalanceTest {

	static final StockKey KEY = new StockKey(10, 20);

	@Test
	void availableIsOnHandMinusAllocated() {
		assertThat(new InventoryBalance(KEY, 40, 15).available()).isEqualTo(25);
	}

	@Test
	void increaseAddsStock() {
		assertThat(InventoryBalance.empty(KEY).increase(12)).isEqualTo(new InventoryBalance(KEY, 12, 0));
	}

	@Test
	void decreaseRemovesAvailableStock() {
		assertThat(new InventoryBalance(KEY, 40, 15).decrease(25)).isEqualTo(new InventoryBalance(KEY, 15, 15));
	}

	@Test
	void decreaseCannotTakeAllocatedStock() {
		InventoryBalance balance = new InventoryBalance(KEY, 40, 15);

		assertThatThrownBy(() -> balance.decrease(26)).isInstanceOfSatisfying(DomainException.class, ex -> {
			assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY);
			assertThat(ex.getMessage()).isEqualTo("Only 25 of 26 units available");
			assertThat(ex.properties()).containsEntry("available", 25).containsEntry("requested", 26);
		});
	}

	@Test
	void allocateReservesAvailableStock() {
		assertThat(new InventoryBalance(KEY, 40, 15).allocate(25)).isEqualTo(new InventoryBalance(KEY, 40, 40));
	}

	@Test
	void cannotAllocateMoreThanAvailable() {
		assertThatThrownBy(() -> new InventoryBalance(KEY, 40, 15).allocate(26))
			.isInstanceOfSatisfying(DomainException.class,
					ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY));
	}

	@Test
	void deallocateReleasesAllocation() {
		assertThat(new InventoryBalance(KEY, 40, 15).deallocate(15)).isEqualTo(new InventoryBalance(KEY, 40, 0));
	}

	@Test
	void cannotDeallocateMoreThanAllocated() {
		assertThatThrownBy(() -> new InventoryBalance(KEY, 40, 15).deallocate(16))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void quantitiesMustBePositive() {
		InventoryBalance balance = new InventoryBalance(KEY, 10, 0);
		assertThatThrownBy(() -> balance.increase(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> balance.decrease(-3)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> balance.allocate(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidBalancesCannotBeConstructed() {
		assertThatThrownBy(() -> new InventoryBalance(KEY, -1, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InventoryBalance(KEY, 5, 6)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InventoryBalance(KEY, 5, -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void applyUsesTheDeltaDirection() {
		InventoryBalance balance = new InventoryBalance(KEY, 10, 0);
		assertThat(balance.apply(new StockDelta(KEY, 5)).onHand()).isEqualTo(15);
		assertThat(balance.apply(new StockDelta(KEY, -4)).onHand()).isEqualTo(6);
	}

	@Test
	void applyRejectsADeltaForAnotherLocation() {
		assertThatThrownBy(() -> InventoryBalance.empty(KEY).apply(new StockDelta(new StockKey(99, 20), 5)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
