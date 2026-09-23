package com.cloudwms.core.waves.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.cloudwms.core.inventory.domain.LocationType;
import com.cloudwms.core.waves.domain.WavePlanner.ForwardSlot;
import com.cloudwms.core.waves.domain.WavePlanner.PlannableLine;
import com.cloudwms.core.waves.domain.WavePlanner.PlannedSource;
import com.cloudwms.core.waves.domain.WavePlanner.StockAtLocation;
import com.cloudwms.core.waves.domain.WavePlanner.WavePlan;
import org.junit.jupiter.api.Test;

class WavePlannerTest {

	static final Instant EARLY = Instant.parse("2026-09-23T15:00:00Z");
	static final Instant LATE = Instant.parse("2026-09-23T21:00:00Z");
	static final long SKU = 10;
	static final long FORWARD = 1;
	static final long FORWARD_FAR = 2;
	static final long RESERVE_BIG = 3;
	static final long RESERVE_SMALL = 4;

	static StockAtLocation forward(long id, int available, int pickSequence) {
		return new StockAtLocation(id, SKU, LocationType.FORWARD_PICK, available, pickSequence, 1, null);
	}

	static StockAtLocation reserve(long id, int available) {
		return new StockAtLocation(id, SKU, LocationType.RESERVE, available, null, 1, "REACH_TRUCK");
	}

	static PlannableLine line(long orderId, int priority, Instant cutoff, long lineId, int quantity) {
		return new PlannableLine(orderId, priority, cutoff, lineId, SKU, quantity);
	}

	static Map<Long, ForwardSlot> slot() {
		return Map.of(SKU, new ForwardSlot(SKU, FORWARD, 1, 100));
	}

	@Test
	void takesForwardPickStockInPickPathOrder() {
		WavePlan plan = WavePlanner.plan(List.of(line(1, 3, EARLY, 11, 8)),
				List.of(forward(FORWARD_FAR, 5, 900), forward(FORWARD, 5, 100)), slot(), 10);

		assertThat(plan.sources()).extracting(PlannedSource::stockLocationId, PlannedSource::quantity)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(FORWARD, 5),
					org.assertj.core.groups.Tuple.tuple(FORWARD_FAR, 3));
		assertThat(plan.sources()).noneMatch(PlannedSource::needsReplenishment);
		assertThat(plan.shortages()).isEmpty();
	}

	@Test
	void fallsBackToReserveAndAsksForReplenishment() {
		WavePlan plan = WavePlanner.plan(List.of(line(1, 3, EARLY, 11, 30)),
				List.of(forward(FORWARD, 9, 100), reserve(RESERVE_BIG, 100)), slot(), 10);

		assertThat(plan.sources()).hasSize(2);
		PlannedSource fromReserve = plan.sources().get(1);
		assertThat(fromReserve.stockLocationId()).isEqualTo(RESERVE_BIG);
		assertThat(fromReserve.quantity()).isEqualTo(21);
		assertThat(fromReserve.needsReplenishment()).isTrue();
		// The picker takes it from the forward slot, once the replenishment has moved it there.
		assertThat(fromReserve.pickLocationId()).isEqualTo(FORWARD);
		assertThat(plan.unitsAllocated()).isEqualTo(30);
	}

	@Test
	void reserveIsUsedLargestFirstToTouchFewerLocations() {
		WavePlan plan = WavePlanner.plan(List.of(line(1, 3, EARLY, 11, 60)),
				List.of(reserve(RESERVE_SMALL, 20), reserve(RESERVE_BIG, 50)), slot(), 10);

		assertThat(plan.sources()).extracting(PlannedSource::stockLocationId).containsExactly(RESERVE_BIG,
				RESERVE_SMALL);
		assertThat(plan.sources().get(0).quantity()).isEqualTo(50);
		assertThat(plan.sources().get(1).quantity()).isEqualTo(10);
	}

	@Test
	void withoutASlotStockIsPickedStraightFromReserve() {
		WavePlan plan = WavePlanner.plan(List.of(line(1, 3, EARLY, 11, 5)), List.of(reserve(RESERVE_BIG, 50)), Map.of(),
				10);

		assertThat(plan.sources()).singleElement().satisfies(source -> {
			assertThat(source.needsReplenishment()).isFalse();
			assertThat(source.pickLocationId()).isEqualTo(RESERVE_BIG);
		});
	}

	@Test
	void aPartlyCoveredLineIsAllocatedAndTheRestIsShort() {
		WavePlan plan = WavePlanner.plan(List.of(line(1, 3, EARLY, 11, 12)), List.of(forward(FORWARD, 5, 100)), slot(),
				10);

		assertThat(plan.unitsAllocated()).isEqualTo(5);
		assertThat(plan.shortages()).singleElement()
			.satisfies(shortage -> assertThat(shortage.quantity()).isEqualTo(7));
		assertThat(plan.orderIds()).containsExactly(1L);
	}

	@Test
	void anOrderWithNoStockAtAllIsLeftOutOfTheWave() {
		WavePlan plan = WavePlanner.plan(List.of(line(1, 3, EARLY, 11, 4)), List.of(), slot(), 10);

		assertThat(plan.isEmpty()).isTrue();
		assertThat(plan.orderIds()).isEmpty();
		assertThat(plan.shortages()).isEmpty();
	}

	@Test
	void stockIsNotPromisedTwice() {
		WavePlan plan = WavePlanner.plan(
				List.of(line(1, 3, EARLY, 11, 6), line(2, 3, EARLY, 21, 6), line(3, 3, EARLY, 31, 6)),
				List.of(forward(FORWARD, 10, 100)), slot(), 10);

		assertThat(plan.unitsAllocated()).isEqualTo(10);
		// Order 1 takes 6, order 2 takes the last 4 and is 2 short. Order 3 gets nothing, so it stays out of
		// the wave entirely and its units are not counted as a shortage of this wave.
		assertThat(plan.unitsShort()).isEqualTo(2);
		assertThat(plan.orderIds()).containsExactly(1L, 2L);
	}

	@Test
	void urgentOrdersAndEarlierCutoffsGetTheStockFirst() {
		WavePlan plan = WavePlanner.plan(
				List.of(line(1, 5, EARLY, 11, 10), line(2, 1, LATE, 21, 10), line(3, 5, EARLY, 31, 10)),
				List.of(forward(FORWARD, 10, 100)), slot(), 10);

		assertThat(plan.orderIds()).containsExactly(2L);
		assertThat(plan.sources()).singleElement().satisfies(source -> assertThat(source.orderId()).isEqualTo(2L));
	}

	@Test
	void maxOrdersCapsTheWaveButNeverSplitsAnOrder() {
		WavePlan plan = WavePlanner.plan(
				List.of(line(1, 3, EARLY, 11, 2), new PlannableLine(1, 3, EARLY, 12, SKU, 2), line(2, 3, LATE, 21, 2)),
				List.of(forward(FORWARD, 100, 100)), slot(), 1);

		assertThat(plan.orderIds()).containsExactly(1L);
		assertThat(plan.sources()).hasSize(2).allMatch(source -> source.orderId() == 1L);
	}

}
