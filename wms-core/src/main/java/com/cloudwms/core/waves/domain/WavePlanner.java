package com.cloudwms.core.waves.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cloudwms.core.inventory.domain.LocationType;

/**
 * Decides what a wave will do, with no database access: which orders it covers, which stock is promised to
 * each line, what has to be replenished first, and what can't be covered at all.
 *
 * <p>Stock is always promised at the location that physically holds it. When a forward-pick slot can't cover
 * a line, the planner takes the remainder from reserve and marks it {@code replenishTo} the SKU's slot: the
 * caller then creates a REPLENISH task and a PICK task that waits on it.
 *
 * <p>Rules are deliberately fixed for now (they become editable configuration later):
 * <ul>
 * <li>orders in priority order (1 is most urgent), then earliest carrier cutoff</li>
 * <li>within a line, forward-pick locations first in pick-path order, then reserve, largest quantity first
 * (fewer touches)</li>
 * <li>replenish only what the line needs, not up to the slot maximum</li>
 * </ul>
 */
public final class WavePlanner {

	private WavePlanner() {
	}

	/**
	 * @param lines every open line of every candidate order
	 * @param stock available quantity per location, for the SKUs on those lines
	 * @param slots the forward-pick slot for a SKU, if it has one
	 * @param maxOrders how many orders the wave may cover
	 */
	public static WavePlan plan(List<PlannableLine> lines, List<StockAtLocation> stock, Map<Long, ForwardSlot> slots,
			int maxOrders) {
		Map<Long, Integer> available = new HashMap<>();
		Map<Long, StockAtLocation> byLocation = new HashMap<>();
		for (StockAtLocation location : stock) {
			available.merge(location.key(), location.available(), Integer::sum);
			byLocation.putIfAbsent(location.key(), location);
		}
		Map<Long, List<StockAtLocation>> stockBySku = new HashMap<>();
		for (StockAtLocation location : stock) {
			stockBySku.computeIfAbsent(location.skuId(), sku -> new ArrayList<>()).add(location);
		}
		stockBySku.values().forEach(locations -> locations.sort(SOURCE_ORDER));

		List<PlannableLine> ordered = new ArrayList<>(lines);
		ordered.sort(ORDER_SELECTION);

		Set<Long> plannedOrders = new LinkedHashSet<>();
		List<PlannedSource> sources = new ArrayList<>();
		List<Shortage> shortages = new ArrayList<>();
		for (PlannableLine line : ordered) {
			if (!plannedOrders.contains(line.orderId()) && plannedOrders.size() >= maxOrders) {
				continue;
			}
			int remaining = line.quantity();
			for (StockAtLocation source : stockBySku.getOrDefault(line.skuId(), List.of())) {
				if (remaining == 0) {
					break;
				}
				int free = available.getOrDefault(source.key(), 0);
				if (free <= 0) {
					continue;
				}
				ForwardSlot slot = slots.get(line.skuId());
				// Stock in reserve is picked from the forward slot once replenished; without a slot, it is
				// picked straight from reserve.
				Long replenishTo = source.type() == LocationType.RESERVE && slot != null
						&& slot.locationId() != source.locationId() ? slot.locationId() : null;
				int taken = Math.min(remaining, free);
				sources.add(new PlannedSource(line.orderId(), line.orderLineId(), line.skuId(), source.locationId(),
						taken, replenishTo));
				available.put(source.key(), free - taken);
				remaining -= taken;
			}
			if (remaining > 0) {
				shortages.add(new Shortage(line.orderId(), line.orderLineId(), line.skuId(), remaining));
			}
			if (remaining < line.quantity()) {
				plannedOrders.add(line.orderId());
			}
		}
		// Lines of orders that ended up with nothing allocated are not part of the wave.
		shortages.removeIf(shortage -> !plannedOrders.contains(shortage.orderId()));
		return new WavePlan(List.copyOf(plannedOrders), sources, shortages);
	}

	/** Forward-pick first in pick-path order, then reserve with the largest quantity first. */
	private static final Comparator<StockAtLocation> SOURCE_ORDER = Comparator
		.comparingInt((StockAtLocation location) -> location.type() == LocationType.FORWARD_PICK ? 0 : 1)
		.thenComparing(location -> location.type() == LocationType.FORWARD_PICK
				? (location.pickSequence() == null ? Integer.MAX_VALUE : location.pickSequence())
				: -location.available())
		.thenComparingLong(StockAtLocation::locationId);

	private static final Comparator<PlannableLine> ORDER_SELECTION = Comparator.comparingInt(PlannableLine::priority)
		.thenComparing(PlannableLine::carrierCutoffAt)
		.thenComparingLong(PlannableLine::orderId)
		.thenComparingLong(PlannableLine::orderLineId);

	public record PlannableLine(long orderId, int priority, Instant carrierCutoffAt, long orderLineId, long skuId,
			int quantity) {
	}

	public record StockAtLocation(long locationId, long skuId, LocationType type, int available, Integer pickSequence,
			long zoneId, String requiredEquipment) {

		/** Stock is per (location, SKU); locations hold one SKU each in this layout, so the id identifies it. */
		long key() {
			return locationId;
		}

	}

	public record ForwardSlot(long skuId, long locationId, long zoneId, Integer pickSequence) {
	}

	/** Stock promised to one line from one location. {@code replenishTo} is set when it must be moved first. */
	public record PlannedSource(long orderId, long orderLineId, long skuId, long stockLocationId, int quantity,
			Long replenishTo) {

		public boolean needsReplenishment() {
			return replenishTo != null;
		}

		/** Where the picker will take it from. */
		public long pickLocationId() {
			return replenishTo == null ? stockLocationId : replenishTo;
		}

	}

	public record Shortage(long orderId, long orderLineId, long skuId, int quantity) {
	}

	public record WavePlan(List<Long> orderIds, List<PlannedSource> sources, List<Shortage> shortages) {

		public boolean isEmpty() {
			return sources.isEmpty();
		}

		public int unitsAllocated() {
			return sources.stream().mapToInt(PlannedSource::quantity).sum();
		}

		public int unitsShort() {
			return shortages.stream().mapToInt(Shortage::quantity).sum();
		}

	}

}
