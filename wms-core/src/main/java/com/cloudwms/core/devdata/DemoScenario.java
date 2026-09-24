package com.cloudwms.core.devdata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cloudwms.core.orders.OrderService;
import com.cloudwms.core.orders.domain.Order;
import com.cloudwms.core.orders.domain.OrderLine;
import com.cloudwms.core.waves.WavePlanningService;
import com.cloudwms.core.waves.WavePlanningService.PlanRequest;
import com.cloudwms.core.waves.WavePlanningService.PlannedWave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The story the demo tells: a released wave that is not finishing because the pick face is empty, the
 * stock to refill it sits on a high shelf, and the only worker certified to drive a reach truck is busy.
 *
 * <p>Everything that has a service goes through it: orders are received by {@link OrderService}, the wave
 * is planned and released by {@link WavePlanningService}, so the blocker comes from the real planner and
 * the real diagnosis, not from hand-written task rows. Workers are master data and inserted directly,
 * like zones and locations ({@link DevDataSeeder}).
 */
class DemoScenario {

	static final String ORDER_PREFIX = "SO-DEMO-";
	static final String REACH_TRUCK_DRIVER = "W-014";

	private static final Logger log = LoggerFactory.getLogger(DemoScenario.class);
	private static final String[] CUSTOMERS = { "Northwind Outfitters", "Summit Supply Co.", "Harbor & Pine",
			"Trailhead Goods", "Blue Ridge Athletics", "Cedar Lane Retail" };

	private final JdbcClient jdbc;
	private final OrderService orders;
	private final WavePlanningService waves;
	private final TransactionTemplate transaction;

	DemoScenario(JdbcClient jdbc, OrderService orders, WavePlanningService waves,
			PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.waves = waves;
		this.transaction = new TransactionTemplate(transactions);
	}

	/** Returns the blocked wave's number, or empty if the scenario already exists. */
	Optional<Long> create(Instant now) {
		if (jdbc.sql("SELECT COUNT(*) FROM orders WHERE external_ref LIKE ?").param(ORDER_PREFIX + "%")
			.query(Long.class)
			.single() > 0) {
			log.info("Demo scenario already present; skipping");
			return Optional.empty();
		}
		Long wave = transaction.execute(status -> {
			createWorkers();
			receiveOrders(now);
			// Only the demo carrier's orders, so leftovers in a long-lived dev database stay out of the story.
			PlannedWave planned = waves.plan(new PlanRequest(50, "UPS", null), false);
			waves.release(planned.waveNumber());
			return planned.waveNumber();
		});
		log.info("Demo scenario ready: wave {} is blocked on a reach-truck replenishment", wave);
		return Optional.of(wave);
	}

	/**
	 * Two pickers on the floor and one reach-truck driver who is busy elsewhere. The pickers can take every
	 * floor-level pick; nobody available can bring stock down from the high shelves.
	 */
	private void createWorkers() {
		long zoneC = jdbc.sql("SELECT id FROM zone WHERE code = 'C'").query(Long.class).single();
		worker("W-011", "Jordan Reyes", "AVAILABLE", null, null);
		worker("W-012", "Priya Nair", "AVAILABLE", null, null);
		worker(REACH_TRUCK_DRIVER, "Ana Flores", "BUSY", zoneC, "REACH_TRUCK");
	}

	private void worker(String code, String name, String status, Long homeZone, String equipment) {
		jdbc.sql("INSERT INTO worker (code, name, status, home_zone_id) VALUES (?, ?, ?, ?)")
			.params(code, name, status, homeZone)
			.update();
		if (equipment != null) {
			jdbc.sql("INSERT INTO worker_equipment (worker_id, equipment) SELECT id, ? FROM worker WHERE code = ?")
				.params(equipment, code)
				.update();
		}
	}

	/**
	 * Eight orders. Most are for SKUs whose pick face already holds enough; two need more than the pick
	 * face has, for SKUs whose only reserve stock is on a reach-truck shelf. Cutoffs are a few hours out,
	 * so the stuck orders also show up as at risk.
	 */
	private void receiveOrders(Instant now) {
		List<StockedSku> high = skusOnlyInHighReserve(2);
		Set<Long> highIds = high.stream().map(StockedSku::id).collect(Collectors.toSet());
		List<StockedSku> easy = skusWithForwardStockAtLeast(6, 10).stream()
			.filter(sku -> !highIds.contains(sku.id()))
			.toList();
		if (easy.size() < 6 || high.size() < 2) {
			throw new IllegalStateException("The dev warehouse does not have the stock the demo scenario needs");
		}
		List<Order> demo = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			StockedSku sku = easy.get(i);
			demo.add(order(demo.size(), now.plus(Duration.ofHours(6 + i)), 3,
					List.of(OrderLine.of(1, sku.id(), Math.min(4, sku.forward())))));
		}
		for (int i = 0; i < high.size(); i++) {
			StockedSku sku = high.get(i);
			// More than the pick face holds, but no more than the one high pile, so exactly one replenishment
			// from a reach-truck location covers it. Cutoffs two to three hours out: inside the diagnosis's
			// at-risk window, with time left to fix it.
			int quantity = sku.forward() + Math.min(12, sku.largestReserve());
			demo.add(order(demo.size(), now.plus(Duration.ofMinutes(120 + 30L * i)), 1,
					List.of(OrderLine.of(1, sku.id(), quantity))));
		}
		demo.forEach(orders::receive);
	}

	private Order order(int index, Instant cutoff, int priority, List<OrderLine> lines) {
		return Order.received(ORDER_PREFIX + (1001 + index), CUSTOMERS[index % CUSTOMERS.length], priority, "UPS",
				cutoff, lines);
	}

	private List<StockedSku> skusWithForwardStockAtLeast(int minimum, int limit) {
		return jdbc.sql("""
				SELECT s.id, b.on_hand AS forward, 0 AS largest_reserve
				FROM sku s
				JOIN pick_slot ps ON ps.sku_id = s.id
				JOIN inventory_balance b ON b.location_id = ps.location_id AND b.sku_id = s.id
				WHERE s.code LIKE 'SKU-%' AND b.on_hand - b.allocated >= ?
				ORDER BY s.code
				LIMIT ?""")
			.params(minimum, limit)
			.query((rs, n) -> new StockedSku(rs.getLong("id"), rs.getInt("forward"), rs.getInt("largest_reserve")))
			.list();
	}

	/** SKUs with some stock at the pick face and every reserve unit on a shelf that needs a reach truck. */
	private List<StockedSku> skusOnlyInHighReserve(int limit) {
		return jdbc.sql("""
				SELECT s.id,
				       COALESCE(fb.on_hand - fb.allocated, 0) AS forward,
				       MAX(rb.on_hand - rb.allocated) AS largest_reserve
				FROM sku s
				JOIN pick_slot ps ON ps.sku_id = s.id
				LEFT JOIN inventory_balance fb ON fb.location_id = ps.location_id AND fb.sku_id = s.id
				JOIN inventory_balance rb ON rb.sku_id = s.id
				JOIN location rl ON rl.id = rb.location_id AND rl.type = 'RESERVE'
				WHERE s.code LIKE 'SKU-%'
				GROUP BY s.id, fb.on_hand, fb.allocated
				HAVING SUM(rl.required_equipment IS NULL) = 0 AND MAX(rb.on_hand - rb.allocated) >= 12
				   AND forward BETWEEN 1 AND 20
				ORDER BY s.id
				LIMIT ?""")
			.param(limit)
			.query((rs, n) -> new StockedSku(rs.getLong("id"), rs.getInt("forward"), rs.getInt("largest_reserve")))
			.list();
	}

	private record StockedSku(long id, int forward, int largestReserve) {
	}

}
