package com.cloudwms.core.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

	static final Instant CUTOFF = Instant.parse("2026-09-22T19:00:00Z");

	static Order received() {
		return Order.received("SO-10442", "Acme Retail", 3, "UPS", CUTOFF,
				List.of(OrderLine.of(1, 101, 6), OrderLine.of(2, 102, 4)));
	}

	@Test
	void newOrderIsReceivedAndPlannable() {
		Order order = received();
		assertThat(order.status()).isEqualTo(OrderStatus.RECEIVED);
		assertThat(order.canBePlanned()).isTrue();
		assertThat(order.shortQuantity()).isEqualTo(10);
	}

	@Test
	void allocationCanBePartial() {
		Order order = received().allocate(Map.of(1, 6, 2, 1));

		assertThat(order.status()).isEqualTo(OrderStatus.ALLOCATED);
		assertThat(order.lines()).extracting(OrderLine::allocated).containsExactly(6, 1);
		assertThat(order.shortQuantity()).isEqualTo(3);
		assertThat(order.canBePlanned()).isFalse();
	}

	@Test
	void linesLeftOutOfTheAllocationGetNothing() {
		assertThat(received().allocate(Map.of(1, 6)).lines()).extracting(OrderLine::allocated).containsExactly(6, 0);
	}

	@Test
	void allocationMustAllocateSomethingAndStayWithinTheOrder() {
		assertThatThrownBy(() -> received().allocate(Map.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> received().allocate(Map.of(1, 7))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> received().allocate(Map.of(9, 1))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fulfilmentMovesForward() {
		Order released = received().allocate(Map.of(1, 6, 2, 4)).release();
		assertThat(released.status()).isEqualTo(OrderStatus.RELEASED);
	}

	@Test
	void cancellingAWaveReturnsTheOrderToReceived() {
		Order order = received().allocate(Map.of(1, 6, 2, 4)).unallocate();

		assertThat(order.status()).isEqualTo(OrderStatus.RECEIVED);
		assertThat(order.lines()).extracting(OrderLine::allocated).containsOnly(0);
		assertThat(order.canBePlanned()).isTrue();
	}

	@Test
	void anOrderCannotBeAllocatedTwiceOrReleasedBeforeAllocation() {
		Order allocated = received().allocate(Map.of(1, 6));

		assertInvalid(() -> allocated.allocate(Map.of(1, 6)), "Cannot allocate order SO-10442: a ALLOCATED order cannot move to ALLOCATED");
		assertInvalid(() -> received().release(), "Cannot release order SO-10442: a RECEIVED order cannot move to RELEASED");
	}

	@Test
	void holdIsAFlagThatKeepsTheStatus() {
		Order held = received().allocate(Map.of(1, 6)).hold("credit check");

		assertThat(held.onHold()).isTrue();
		assertThat(held.holdReason()).isEqualTo("credit check");
		assertThat(held.status()).isEqualTo(OrderStatus.ALLOCATED);

		Order resumed = held.releaseHold();
		assertThat(resumed.onHold()).isFalse();
		assertThat(resumed.holdReason()).isNull();
		assertThat(resumed.status()).isEqualTo(OrderStatus.ALLOCATED);
	}

	@Test
	void aHeldOrderCannotBePlannedOrReleased() {
		Order held = received().hold("address check");
		assertThat(held.canBePlanned()).isFalse();
		assertInvalid(() -> held.allocate(Map.of(1, 6)), "the order is on hold: address check");

		Order heldAfterAllocation = received().allocate(Map.of(1, 6)).hold("address check");
		assertInvalid(heldAfterAllocation::release, "the order is on hold: address check");
	}

	@Test
	void aHeldOrderCanStillBeCancelled() {
		assertThat(received().hold("fraud review").cancel().status()).isEqualTo(OrderStatus.CANCELLED);
	}

	@Test
	void holdsNeedAReasonAndCannotBeDoubled() {
		assertThatThrownBy(() -> received().hold(" ")).isInstanceOf(IllegalArgumentException.class);
		assertInvalid(() -> received().hold("a").hold("b"), "already on hold");
		assertInvalid(() -> received().releaseHold(), "not on hold");
	}

	@Test
	void cancellingClearsAllocations() {
		Order cancelled = received().allocate(Map.of(1, 6, 2, 4)).cancel();
		assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.lines()).extracting(OrderLine::allocated).containsOnly(0);
		assertInvalid(cancelled::cancel, "a CANCELLED order cannot move to CANCELLED");
	}

	@Test
	void releasedOrdersCannotBeCancelled() {
		assertInvalid(() -> received().allocate(Map.of(1, 6)).release().cancel(), "a RELEASED order cannot move to CANCELLED");
	}

	@Test
	void priorityCanChangeUntilTheOrderIsDone() {
		assertThat(received().withPriority(1).priority()).isEqualTo(1);
		assertInvalid(() -> received().cancel().withPriority(1), "Cannot reprioritize");
	}

	@Test
	void invalidOrdersCannotBeConstructed() {
		assertThatThrownBy(() -> Order.received("SO-1", "C", 0, "UPS", CUTOFF, List.of(OrderLine.of(1, 1, 1))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Order.received("SO-1", "C", 3, "UPS", CUTOFF, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Order.received("SO-1", "C", 3, "UPS", CUTOFF,
				List.of(OrderLine.of(1, 1, 1), OrderLine.of(1, 2, 1))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OrderLine(1, 1, 5, 3, 4, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@EnumSource(OrderStatus.class)
	void terminalStatesHaveNoWayOut(OrderStatus status) {
		assertThat(status.isTerminal()).isEqualTo(EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED).contains(status));
		assertThat(status.canMoveTo(status)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = OrderStatus.class, names = { "PACKED", "SHIPPED", "CANCELLED" })
	void ordersPastPickingCannotBeHeld(OrderStatus status) {
		assertThat(status.canBeHeld()).isFalse();
	}

	private static void assertInvalid(Runnable action, String messagePart) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, ex -> {
			assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
			assertThat(ex.getMessage()).contains(messagePart);
		});
	}

}
