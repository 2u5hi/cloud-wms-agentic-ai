package com.cloudwms.core.devdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.inventory.domain.Reference;
import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds a deterministic demo warehouse. Master data is inserted directly; all stock goes through
 * {@link InventoryService} as receipts into reserve and moves into forward-pick, so every unit has a
 * ledger history and balances reconcile exactly like real operations.
 *
 * <p>Layout: aisle zones A-D, each 5 aisles x 15 bays x 4 levels. Level A is forward-pick (floor),
 * B is reserve, C and D are high reserve reachable only with a reach truck. Zone S holds staging, pack
 * stations, and dock doors.
 */
class DevDataSeeder {

	static final String[] AISLE_ZONES = { "A", "B", "C", "D" };
	static final int AISLES = 5;
	static final int BAYS = 15;
	static final int SKU_COUNT = 300;
	static final long RANDOM_SEED = 42;

	private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
	private static final Actor SEEDER = new Actor(ActorType.SYSTEM, "dev-seed");
	private static final String[] PRODUCTS = { "Crew T-Shirt", "V-Neck T-Shirt", "Hoodie", "Quarter-Zip", "Joggers",
			"Chino Pant", "Denim Jacket", "Rain Shell", "Running Short", "Training Tee", "Beanie", "Ball Cap",
			"Crew Sock 3-Pack", "Ankle Sock 6-Pack", "Backpack", "Duffel Bag", "Water Bottle", "Yoga Mat",
			"Resistance Band", "Gym Towel" };
	private static final String[] COLORS = { "Black", "White", "Navy", "Heather Grey", "Olive", "Red", "Royal Blue",
			"Sand" };
	private static final String[] SIZES = { "S", "M", "L", "XL" };

	private final JdbcClient jdbc;
	private final InventoryService inventory;
	private final TransactionTemplate transaction;
	private final Random random = new Random(RANDOM_SEED);

	DevDataSeeder(JdbcClient jdbc, InventoryService inventory, PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.inventory = inventory;
		this.transaction = new TransactionTemplate(transactions);
	}

	/**
	 * Returns false (and changes nothing) if the warehouse has already been seeded. Runs as one
	 * transaction: every inventory operation joins it, so the seed is all-or-nothing and commits once.
	 */
	boolean seed() {
		if (jdbc.sql("SELECT COUNT(*) FROM zone WHERE code = 'A'").query(Long.class).single() > 0) {
			log.info("Dev data already present; skipping seed");
			return false;
		}
		long started = System.currentTimeMillis();
		int skuCount = transaction.execute(status -> {
			Map<String, List<Slot>> forwardByZone = new HashMap<>();
			Map<String, List<Long>> reserveByZone = new HashMap<>();
			createLayout(forwardByZone, reserveByZone);
			List<SeedSku> skus = createSkus();
			slotAndStock(skus, forwardByZone, reserveByZone);
			return skus.size();
		});
		log.info("Seeded dev warehouse: {} SKUs in {} ms", skuCount, System.currentTimeMillis() - started);
		return true;
	}

	private void createLayout(Map<String, List<Slot>> forwardByZone, Map<String, List<Long>> reserveByZone) {
		for (int z = 0; z < AISLE_ZONES.length; z++) {
			String zoneCode = AISLE_ZONES[z];
			long zoneId = insert("INSERT INTO zone (code, name) VALUES (?, ?)", zoneCode, "Aisles " + zoneCode);
			List<Slot> forward = new ArrayList<>();
			List<Long> reserve = new ArrayList<>();
			for (int aisle = 1; aisle <= AISLES; aisle++) {
				for (int bay = 1; bay <= BAYS; bay++) {
					// Serpentine pick path: up odd aisles, back down even ones.
					int walkBay = aisle % 2 == 1 ? bay : BAYS + 1 - bay;
					int sequence = (z + 1) * 10_000 + aisle * 100 + walkBay;
					for (char level : new char[] { 'A', 'B', 'C', 'D' }) {
						String code = "%s-%02d-%02d-%c".formatted(zoneCode, aisle, bay, level);
						if (level == 'A') {
							long id = insert("INSERT INTO location (code, zone_id, type, pick_sequence, capacity_units) "
									+ "VALUES (?, ?, 'FORWARD_PICK', ?, 100)", code, zoneId, sequence);
							forward.add(new Slot(id, sequence));
						}
						else {
							String equipment = level == 'B' ? null : "REACH_TRUCK";
							reserve.add(insert("INSERT INTO location (code, zone_id, type, capacity_units, "
									+ "required_equipment) VALUES (?, ?, 'RESERVE', 400, ?)", code, zoneId, equipment));
						}
					}
				}
			}
			forward.sort((a, b) -> Integer.compare(a.sequence(), b.sequence()));
			Collections.shuffle(reserve, random);
			forwardByZone.put(zoneCode, forward);
			reserveByZone.put(zoneCode, reserve);
		}
		long shipping = insert("INSERT INTO zone (code, name) VALUES ('S', 'Shipping')");
		for (int i = 1; i <= 10; i++) {
			insert("INSERT INTO location (code, zone_id, type) VALUES (?, ?, 'STAGING')", "S-STG-%02d".formatted(i),
					shipping);
		}
		for (int i = 1; i <= 8; i++) {
			insert("INSERT INTO location (code, zone_id, type) VALUES (?, ?, 'PACK')", "S-PCK-%02d".formatted(i),
					shipping);
		}
		for (int i = 1; i <= 6; i++) {
			insert("INSERT INTO location (code, zone_id, type) VALUES (?, ?, 'DOCK')", "S-DCK-%02d".formatted(i),
					shipping);
		}
	}

	/** 20% A (fast), 30% B, 50% C (slow) movers. */
	private List<SeedSku> createSkus() {
		List<SeedSku> skus = new ArrayList<>();
		for (int i = 0; i < SKU_COUNT; i++) {
			String velocity = i < SKU_COUNT * 0.2 ? "A" : i < SKU_COUNT * 0.5 ? "B" : "C";
			String description = "%s, %s, %s".formatted(PRODUCTS[i % PRODUCTS.length],
					COLORS[(i / PRODUCTS.length) % COLORS.length], SIZES[(i / 7) % SIZES.length]);
			String code = "SKU-%05d".formatted(10_001 + i);
			long id = insert("INSERT INTO sku (code, description, velocity_class) VALUES (?, ?, ?)", code, description,
					velocity);
			skus.add(new SeedSku(id, velocity));
		}
		return skus;
	}

	/**
	 * Spreads SKUs evenly across zones. Within a zone, fast movers take the forward slots earliest on the
	 * pick path. Each SKU is received into one or two reserve locations and partly moved to its slot.
	 */
	private void slotAndStock(List<SeedSku> skus, Map<String, List<Slot>> forwardByZone,
			Map<String, List<Long>> reserveByZone) {
		Map<String, Integer> nextSlot = new HashMap<>();
		Map<String, Integer> nextReserve = new HashMap<>();
		int receiptNumber = 0;
		for (int i = 0; i < skus.size(); i++) {
			SeedSku sku = skus.get(i);
			String zone = AISLE_ZONES[i % AISLE_ZONES.length];
			Slot slot = forwardByZone.get(zone).get(nextSlot.merge(zone, 1, Integer::sum) - 1);
			int caseSize = switch (sku.velocity()) {
				case "A" -> 24;
				case "B" -> 12;
				default -> 6;
			};
			int min = caseSize;
			int max = caseSize * 4;
			jdbc.sql("INSERT INTO pick_slot (location_id, sku_id, min_qty, max_qty) VALUES (?, ?, ?, ?)")
				.params(slot.id(), sku.id(), min, max)
				.update();

			int reserveLocations = 1 + random.nextInt(2);
			List<Long> sources = new ArrayList<>();
			for (int r = 0; r < reserveLocations; r++) {
				long reserve = reserveByZone.get(zone).get(nextReserve.merge(zone, 1, Integer::sum) - 1);
				int quantity = caseSize * (3 + random.nextInt(6));
				Reference asn = new Reference("ASN", "ASN-DEV-%04d".formatted(++receiptNumber));
				inventory.record(InventoryMovement.receipt(sku.id(), reserve, quantity, asn, SEEDER));
				sources.add(reserve);
			}

			// About 5% of SKUs start below their forward-pick minimum, so there is something to replenish.
			boolean belowMin = random.nextInt(100) < 5;
			int target = belowMin ? random.nextInt(min) : (int) Math.round(max * (0.4 + random.nextDouble() * 0.6));
			if (target > 0) {
				long source = sources.getFirst();
				int available = jdbc.sql("SELECT on_hand FROM inventory_balance WHERE location_id = ? AND sku_id = ?")
					.params(source, sku.id())
					.query(Integer.class)
					.single();
				inventory.record(InventoryMovement.move(sku.id(), source, slot.id(), Math.min(target, available),
						new Reference("REPLENISHMENT", "DEV-SEED"), SEEDER));
			}
		}
	}

	private long insert(String sql, Object... params) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(keys);
		return keys.getKey().longValue();
	}

	private record Slot(long id, int sequence) {
	}

	private record SeedSku(long id, String velocity) {
	}

}
