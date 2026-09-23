package com.cloudwms.core.waves.api;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class WaveViews {

	private WaveViews() {
	}

	/** Selection criteria. Fixed rules for now; these become wave templates later. */
	public record PlanWaveRequest(
			@Min(1) @Max(500) @Schema(nullable = true, description = "How many orders the wave may cover; default 50") Integer maxOrders,
			@Size(max = 30) @Schema(nullable = true, description = "Only orders for this carrier") String carrier,
			@Min(1) @Max(168) @Schema(nullable = true,
					description = "Only orders whose carrier cutoff is within this many hours") Integer cutoffWithinHours) {
	}

	public record ShortageView(String order, String sku, int quantity) {
	}

	public record PlanResultView(
			@Schema(nullable = true, description = "Null for a preview, or when there was nothing to plan") Long waveNumber,
			boolean preview, int orders, int unitsAllocated,
			@Schema(description = "Units that could not be covered for orders in this wave") int unitsShort,
			int pickTasks, int replenishmentTasks, List<ShortageView> shortages) {
	}

	public record TaskCounts(int total, int waiting, int ready, int assigned, int inProgress, int completed,
			int cancelled) {
	}

	public record WaveSummaryView(long number, String status, Instant plannedAt,
			@Schema(nullable = true) Instant releasedAt, int orders, int unitsAllocated, TaskCounts tasks) {
	}

	public record WaveOrderView(String externalRef, String customer, int priority, String status, Instant carrierCutoffAt,
			int unitsOrdered, int unitsAllocated) {
	}

	public record WaveView(long number, String status, Instant plannedAt, @Schema(nullable = true) Instant releasedAt,
			int unitsAllocated, TaskCounts tasks, List<WaveOrderView> orders) {
	}

}
