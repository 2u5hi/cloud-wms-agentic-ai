package com.cloudwms.core.waves.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Works out why a wave isn't finishing, deterministically. The agent reads this instead of joining tables
 * and doing arithmetic itself, which is where hallucinations and token cost come from.
 */
public final class WaveDiagnosis {

	private WaveDiagnosis() {
	}

	public enum BlockerKind {

		/** Picks can't start because stock hasn't been brought to the pick face yet. */
		WAITING_ON_REPLENISHMENT,
		/** Some ordered units were never covered by stock. */
		SHORT_ALLOCATED

	}

	public enum RootCause {

		/** Nobody who is available is certified for the equipment the replenishment needs. */
		NO_ELIGIBLE_WORKER_AVAILABLE,
		/** Eligible workers exist, but nobody has picked the task up yet. */
		NOT_PICKED_UP,
		/** Somebody is on it. */
		IN_PROGRESS,
		/** There was not enough stock anywhere in the warehouse. */
		NO_STOCK_AVAILABLE

	}

	/** A replenishment that picks are waiting on. */
	public record PendingReplenishment(long taskId, String sku, String status, String fromLocation, String toLocation,
			int quantity, String requiredEquipment, String assignedWorker, long ageMinutes, int waitingPicks,
			int affectedOrders) {
	}

	/** Units of a wave's orders that no stock could cover. */
	public record ShortLine(String order, String sku, int quantity) {
	}

	public record Blocker(BlockerKind kind, RootCause rootCause, String sku, int affectedPicks, int affectedOrders,
			int quantity, String detail, PendingReplenishment replenishment, List<String> orders) {
	}

	/**
	 * @param availableByEquipment how many workers are available per equipment type, and under the empty key,
	 * how many are available at all
	 */
	public static List<Blocker> diagnose(List<PendingReplenishment> replenishments, List<ShortLine> shortLines,
			Map<String, Integer> availableByEquipment) {
		List<Blocker> blockers = new ArrayList<>();
		for (PendingReplenishment replenishment : replenishments) {
			RootCause cause = rootCause(replenishment, availableByEquipment);
			blockers.add(new Blocker(BlockerKind.WAITING_ON_REPLENISHMENT, cause, replenishment.sku(),
					replenishment.waitingPicks(), replenishment.affectedOrders(), replenishment.quantity(),
					describe(replenishment, cause), replenishment, List.of()));
		}
		shortLines.stream()
			.collect(java.util.stream.Collectors.groupingBy(ShortLine::sku))
			.forEach((sku, lines) -> blockers.add(new Blocker(BlockerKind.SHORT_ALLOCATED, RootCause.NO_STOCK_AVAILABLE,
					sku, 0, lines.size(), lines.stream().mapToInt(ShortLine::quantity).sum(),
					"%d units of %s could not be covered by any stock".formatted(
							lines.stream().mapToInt(ShortLine::quantity).sum(), sku),
					null, lines.stream().map(ShortLine::order).sorted().toList())));
		blockers.sort(Comparator.comparingInt(Blocker::affectedPicks).thenComparingInt(Blocker::quantity).reversed());
		return blockers;
	}

	private static RootCause rootCause(PendingReplenishment replenishment, Map<String, Integer> availableByEquipment) {
		if (replenishment.assignedWorker() != null) {
			return RootCause.IN_PROGRESS;
		}
		int eligible = replenishment.requiredEquipment() == null ? availableByEquipment.getOrDefault("", 0)
				: availableByEquipment.getOrDefault(replenishment.requiredEquipment(), 0);
		return eligible == 0 ? RootCause.NO_ELIGIBLE_WORKER_AVAILABLE : RootCause.NOT_PICKED_UP;
	}

	private static String describe(PendingReplenishment replenishment, RootCause cause) {
		String base = "%d picks wait on replenishment %d: %d units of %s from %s to %s".formatted(
				replenishment.waitingPicks(), replenishment.taskId(), replenishment.quantity(), replenishment.sku(),
				replenishment.fromLocation(), replenishment.toLocation());
		return base + switch (cause) {
			case NO_ELIGIBLE_WORKER_AVAILABLE -> ", unclaimed for %d min because no available worker is certified for %s"
				.formatted(replenishment.ageMinutes(), replenishment.requiredEquipment());
			case NOT_PICKED_UP -> ", unclaimed for %d min".formatted(replenishment.ageMinutes());
			case IN_PROGRESS -> ", being done by %s".formatted(replenishment.assignedWorker());
			case NO_STOCK_AVAILABLE -> "";
		};
	}

}
