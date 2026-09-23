package com.cloudwms.core.waves;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.inventory.domain.StockKey;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.waves.WaveRepository.ActiveAllocation;
import com.cloudwms.core.waves.WaveRepository.NewTask;
import com.cloudwms.core.waves.domain.WavePlanner;
import com.cloudwms.core.waves.domain.WavePlanner.PlannableLine;
import com.cloudwms.core.waves.domain.WavePlanner.PlannedSource;
import com.cloudwms.core.waves.domain.WavePlanner.WavePlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns open orders into a wave: promises stock, and creates the work to fulfil it. Planning locks the
 * inventory rows it allocates, so two waves can never promise the same stock.
 */
@Service
public class WavePlanningService {

	/** Defaults until wave templates become editable configuration. */
	public static final int DEFAULT_MAX_ORDERS = 50;


	private final WaveRepository repository;
	private final InventoryService inventory;

	WavePlanningService(WaveRepository repository, InventoryService inventory) {
		this.repository = repository;
		this.inventory = inventory;
	}

	/**
	 * Plans a wave. With {@code preview} nothing is written and no stock is locked: the same planner runs
	 * over current data and reports what a real plan would do. It is therefore advisory — the real plan
	 * recomputes under locks and can differ if stock moved in between.
	 */
	@Transactional
	public PlannedWave plan(PlanRequest request, boolean preview) {
		if (preview) {
			return previewOnly(request);
		}
		// Held until this transaction commits, so a waiting planner sees the stock this one promised.
		repository.lockForPlanning();
		return planLocked(request);
	}

	private PlannedWave previewOnly(PlanRequest request) {
		WavePlan plan = computePlan(request).plan();
		if (plan.isEmpty()) {
			return PlannedWave.nothingToPlan(true);
		}
		int replenishments = (int) plan.sources().stream().filter(PlannedSource::needsReplenishment).count();
		return new PlannedWave(null, true, plan.orderIds().size(), plan.unitsAllocated(), plan.unitsShort(),
				plan.sources().size(), replenishments, plan.shortages());
	}

	/** Reads the current orders and stock and runs the planner over them. */
	private Computed computePlan(PlanRequest request) {
		List<PlannableLine> lines = repository.plannableLines(request.carrier(), request.cutoffBefore(),
				request.maxOrders());
		Set<Long> skuIds = lines.stream().map(PlannableLine::skuId).collect(Collectors.toSet());
		return new Computed(lines, WavePlanner.plan(lines, repository.availableStock(skuIds),
				repository.forwardSlots(skuIds), request.maxOrders()));
	}

	private record Computed(List<PlannableLine> lines, WavePlan plan) {
	}

	private PlannedWave planLocked(PlanRequest request) {
		Computed computed = computePlan(request);
		List<PlannableLine> lines = computed.lines();
		WavePlan plan = computed.plan();
		if (plan.isEmpty()) {
			return PlannedWave.nothingToPlan(false);
		}

		// Promise the stock first: this locks the balance rows and fails if another wave got there first.
		Map<StockKey, Integer> toAllocate = new HashMap<>();
		plan.sources()
			.forEach(source -> toAllocate.merge(new StockKey(source.stockLocationId(), source.skuId()),
					source.quantity(), Integer::sum));
		inventory.allocate(toAllocate);

		long waveId = repository.insertWave();
		plan.orderIds().forEach(orderId -> repository.addOrderToWave(waveId, orderId));
		repository.setOrderStatus(plan.orderIds(), "ALLOCATED");

		int pickTasks = 0;
		int replenishmentTasks = 0;
		Map<Long, Integer> priorityByOrder = lines.stream()
			.collect(Collectors.toMap(PlannableLine::orderId, PlannableLine::priority, Math::min));
		for (PlannedSource source : plan.sources()) {
			repository.allocateLine(source.orderLineId(), source.quantity());
			long allocationId = repository.insertAllocation(waveId, source.orderLineId(), source.skuId(),
					source.stockLocationId(), source.quantity());
			int priority = priorityByOrder.getOrDefault(source.orderId(), 3);
			Long replenishTaskId = null;
			if (source.needsReplenishment()) {
				replenishTaskId = repository.insertTask(new NewTask("REPLENISH", "READY", priority, waveId, null, null,
						source.skuId(), source.stockLocationId(), source.replenishTo(), source.quantity(), null, null,
						repository.requiredEquipment(source.stockLocationId())));
				replenishmentTasks++;
			}
			// A pick that waits on replenishment is not workable until that stock arrives.
			repository.insertTask(new NewTask("PICK", replenishTaskId == null ? "READY" : "WAITING", priority, waveId,
					allocationId, replenishTaskId, source.skuId(), source.pickLocationId(), null, source.quantity(),
					null, null, null));
			pickTasks++;
		}

		return new PlannedWave(waveId, false, plan.orderIds().size(), plan.unitsAllocated(), plan.unitsShort(),
				pickTasks, replenishmentTasks, plan.shortages());
	}

	/** Makes the wave's work available to the floor. */
	@Transactional
	public void release(long waveId) {
		requireStatus(waveId, "PLANNED", "release");
		repository.setWaveStatus(waveId, "RELEASED", "released_at");
		List<Long> orderIds = repository.waveOrderIds(waveId);
		if (!orderIds.isEmpty()) {
			repository.setOrderStatus(orderIds, "RELEASED");
		}
	}

	/** Undoes a planned wave: work is cancelled, stock is released, and its orders can be planned again. */
	@Transactional
	public void cancel(long waveId) {
		requireStatus(waveId, "PLANNED", "cancel");
		Map<StockKey, Integer> toRelease = new LinkedHashMap<>();
		for (ActiveAllocation allocation : repository.activeAllocations(waveId)) {
			toRelease.merge(new StockKey(allocation.locationId(), allocation.skuId()), allocation.quantity(),
					Integer::sum);
			repository.deallocateLine(allocation.orderLineId(), allocation.quantity());
		}
		List<Long> orderIds = repository.waveOrderIds(waveId);
		repository.cancelWaveWork(waveId);
		repository.setWaveStatus(waveId, "CANCELLED", null);
		if (!orderIds.isEmpty()) {
			repository.setOrderStatus(orderIds, "RECEIVED");
		}
		inventory.releaseAllocation(toRelease);
	}

	private void requireStatus(long waveId, String expected, String action) {
		String status = repository.waveStatus(waveId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Wave %d does not exist".formatted(waveId),
					Map.of("wave", waveId)));
		if (!status.equals(expected)) {
			throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
					"Cannot %s wave %d: it is %s".formatted(action, waveId, status),
					Map.of("wave", waveId, "status", status));
		}
	}

	public record PlanRequest(int maxOrders, String carrier, Instant cutoffBefore) {
	}

	public record PlannedWave(Long waveNumber, boolean preview, int orders, int unitsAllocated, int unitsShort,
			int pickTasks, int replenishmentTasks, List<WavePlanner.Shortage> shortages) {

		static PlannedWave nothingToPlan(boolean preview) {
			return new PlannedWave(null, preview, 0, 0, 0, 0, 0, List.of());
		}

	}

}
