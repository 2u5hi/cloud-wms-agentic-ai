package com.cloudwms.core.waves.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.cloudwms.core.waves.domain.WaveDiagnosis.Blocker;
import com.cloudwms.core.waves.domain.WaveDiagnosis.BlockerKind;
import com.cloudwms.core.waves.domain.WaveDiagnosis.PendingReplenishment;
import com.cloudwms.core.waves.domain.WaveDiagnosis.RootCause;
import com.cloudwms.core.waves.domain.WaveDiagnosis.ShortLine;
import org.junit.jupiter.api.Test;

class WaveDiagnosisTest {

	static PendingReplenishment replenishment(String equipment, String worker, int waitingPicks) {
		return new PendingReplenishment(8812, "SKU-10035", worker == null ? "READY" : "ASSIGNED", "C-03-04-C",
				"C-01-09-A", 33, equipment, worker, 41, waitingPicks, 9);
	}

	@Test
	void nobodyCertifiedAndAvailableIsTheRootCause() {
		List<Blocker> blockers = WaveDiagnosis.diagnose(List.of(replenishment("REACH_TRUCK", null, 27)), List.of(),
				Map.of("", 6));

		assertThat(blockers).singleElement().satisfies(blocker -> {
			assertThat(blocker.kind()).isEqualTo(BlockerKind.WAITING_ON_REPLENISHMENT);
			assertThat(blocker.rootCause()).isEqualTo(RootCause.NO_ELIGIBLE_WORKER_AVAILABLE);
			assertThat(blocker.affectedPicks()).isEqualTo(27);
			assertThat(blocker.affectedOrders()).isEqualTo(9);
			assertThat(blocker.detail())
				.isEqualTo("27 picks wait on replenishment 8812: 33 units of SKU-10035 from C-03-04-C to C-01-09-A, "
						+ "unclaimed for 41 min because no available worker is certified for REACH_TRUCK");
		});
	}

	@Test
	void withCertifiedWorkersAvailableItIsSimplyUnclaimed() {
		List<Blocker> blockers = WaveDiagnosis.diagnose(List.of(replenishment("REACH_TRUCK", null, 27)), List.of(),
				Map.of("REACH_TRUCK", 2, "", 6));

		assertThat(blockers.get(0).rootCause()).isEqualTo(RootCause.NOT_PICKED_UP);
		assertThat(blockers.get(0).detail()).endsWith("unclaimed for 41 min");
	}

	@Test
	void anAssignedReplenishmentIsInProgress() {
		List<Blocker> blockers = WaveDiagnosis.diagnose(List.of(replenishment("REACH_TRUCK", "priya", 27)), List.of(),
				Map.of("REACH_TRUCK", 0, "", 6));

		assertThat(blockers.get(0).rootCause()).isEqualTo(RootCause.IN_PROGRESS);
		assertThat(blockers.get(0).detail()).endsWith("being done by priya");
	}

	@Test
	void replenishmentWithoutEquipmentNeedsAnyAvailableWorker() {
		assertThat(WaveDiagnosis.diagnose(List.of(replenishment(null, null, 3)), List.of(), Map.of("", 0))).first()
			.satisfies(blocker -> assertThat(blocker.rootCause()).isEqualTo(RootCause.NO_ELIGIBLE_WORKER_AVAILABLE));
		assertThat(WaveDiagnosis.diagnose(List.of(replenishment(null, null, 3)), List.of(), Map.of("", 4))).first()
			.satisfies(blocker -> assertThat(blocker.rootCause()).isEqualTo(RootCause.NOT_PICKED_UP));
	}

	@Test
	void shortLinesAreGroupedPerSku() {
		List<Blocker> blockers = WaveDiagnosis.diagnose(List.of(),
				List.of(new ShortLine("SO-2", "SKU-1", 4), new ShortLine("SO-1", "SKU-1", 6)), Map.of("", 3));

		assertThat(blockers).singleElement().satisfies(blocker -> {
			assertThat(blocker.kind()).isEqualTo(BlockerKind.SHORT_ALLOCATED);
			assertThat(blocker.rootCause()).isEqualTo(RootCause.NO_STOCK_AVAILABLE);
			assertThat(blocker.quantity()).isEqualTo(10);
			assertThat(blocker.orders()).containsExactly("SO-1", "SO-2");
			assertThat(blocker.detail()).isEqualTo("10 units of SKU-1 could not be covered by any stock");
		});
	}

	@Test
	void theBiggestBlockerComesFirst() {
		List<Blocker> blockers = WaveDiagnosis.diagnose(
				List.of(replenishment("REACH_TRUCK", null, 2), replenishment(null, null, 27)),
				List.of(new ShortLine("SO-1", "SKU-9", 5)), Map.of("", 0));

		assertThat(blockers).extracting(Blocker::affectedPicks).containsExactly(27, 2, 0);
	}

	@Test
	void aHealthyWaveHasNoBlockers() {
		assertThat(WaveDiagnosis.diagnose(List.of(), List.of(), Map.of("", 5))).isEmpty();
	}

}
