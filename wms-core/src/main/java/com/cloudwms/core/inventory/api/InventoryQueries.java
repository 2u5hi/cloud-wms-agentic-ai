package com.cloudwms.core.inventory.api;

import java.util.List;
import java.util.Optional;

import com.cloudwms.core.inventory.api.InventoryViews.BalanceView;
import com.cloudwms.core.inventory.api.InventoryViews.LocationTypeAvailability;
import com.cloudwms.core.inventory.api.InventoryViews.Quantities;
import com.cloudwms.core.inventory.api.InventoryViews.SkuAvailabilityView;
import com.cloudwms.core.inventory.domain.LocationType;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read side for inventory balances. */
@Component
class InventoryQueries {

	private final JdbcClient jdbc;

	InventoryQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Balances ordered by (location, SKU), the primary key. The keyset condition is spelled out as
	 * {@code loc > ? OR (loc = ? AND sku > ?)} so MySQL can range-scan the primary key.
	 */
	Page<BalanceView> balances(BalanceFilter filter, String cursor, int limit) {
		long[] after = Cursor.decode(cursor, 2);
		List<Row> rows = jdbc.sql("""
				SELECT b.location_id, b.sku_id, l.code AS location, z.code AS zone, l.type AS location_type,
				       s.code AS sku, b.on_hand, b.allocated
				FROM inventory_balance b
				JOIN location l ON l.id = b.location_id
				JOIN zone z ON z.id = l.zone_id
				JOIN sku s ON s.id = b.sku_id
				WHERE (b.location_id > :afterLocation OR (b.location_id = :afterLocation AND b.sku_id > :afterSku))
				  AND (:sku IS NULL OR s.code = :sku)
				  AND (:location IS NULL OR l.code = :location)
				  AND (:zone IS NULL OR z.code = :zone)
				  AND (:type IS NULL OR l.type = :type)
				  AND (:includeEmpty OR b.on_hand > 0 OR b.allocated > 0)
				ORDER BY b.location_id, b.sku_id
				LIMIT :limit""")
			.param("afterLocation", after[0])
			.param("afterSku", after[1])
			.param("sku", filter.sku())
			.param("location", filter.location())
			.param("zone", filter.zone())
			.param("type", filter.type() == null ? null : filter.type().name())
			.param("includeEmpty", filter.includeEmpty())
			.param("limit", limit + 1)
			.query((rs, n) -> {
				int onHand = rs.getInt("on_hand");
				int allocated = rs.getInt("allocated");
				return new Row(rs.getLong("location_id"), rs.getLong("sku_id"),
						new BalanceView(rs.getString("location"), rs.getString("zone"),
								LocationType.valueOf(rs.getString("location_type")), rs.getString("sku"), onHand,
								allocated, onHand - allocated));
			})
			.list();
		return Cursor.pageByKey(rows, limit, row -> new long[] { row.locationId(), row.skuId() }, Row::view);
	}

	/** Empty if the SKU does not exist; a SKU with no stock has zero totals and no location types. */
	Optional<SkuAvailabilityView> availability(String skuCode) {
		if (jdbc.sql("SELECT COUNT(*) FROM sku WHERE code = ?").param(skuCode).query(Long.class).single() == 0) {
			return Optional.empty();
		}
		List<LocationTypeAvailability> byType = jdbc.sql("""
				SELECT l.type, COUNT(*) AS locations, SUM(b.on_hand) AS on_hand, SUM(b.allocated) AS allocated
				FROM inventory_balance b
				JOIN location l ON l.id = b.location_id
				JOIN sku s ON s.id = b.sku_id
				WHERE s.code = ? AND (b.on_hand > 0 OR b.allocated > 0)
				GROUP BY l.type""")
			.param(skuCode)
			.query((rs, n) -> {
				int onHand = rs.getInt("on_hand");
				int allocated = rs.getInt("allocated");
				return new LocationTypeAvailability(LocationType.valueOf(rs.getString("type")), rs.getInt("locations"),
						onHand, allocated, onHand - allocated);
			})
			.list()
			.stream()
			.sorted((a, b) -> a.locationType().compareTo(b.locationType()))
			.toList();
		int onHand = byType.stream().mapToInt(LocationTypeAvailability::onHand).sum();
		int allocated = byType.stream().mapToInt(LocationTypeAvailability::allocated).sum();
		return Optional.of(new SkuAvailabilityView(skuCode, new Quantities(onHand, allocated, onHand - allocated), byType));
	}

	record BalanceFilter(String sku, String location, String zone, LocationType type, boolean includeEmpty) {
	}

	private record Row(long locationId, long skuId, BalanceView view) {
	}

}
