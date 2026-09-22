package com.cloudwms.core.inventory.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.cloudwms.core.inventory.api.InventoryViews.ActorView;
import com.cloudwms.core.inventory.api.InventoryViews.BalanceView;
import com.cloudwms.core.inventory.api.InventoryViews.LocationTypeAvailability;
import com.cloudwms.core.inventory.api.InventoryViews.Quantities;
import com.cloudwms.core.inventory.api.InventoryViews.ReferenceView;
import com.cloudwms.core.inventory.api.InventoryViews.SkuAvailabilityView;
import com.cloudwms.core.inventory.api.InventoryViews.TransactionView;
import com.cloudwms.core.inventory.domain.ActorType;
import com.cloudwms.core.inventory.domain.InventoryTxnType;
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

	private static final String TRANSACTION_SELECT = """
			SELECT t.id, t.type, s.code AS sku, lf.code AS from_location, lt.code AS to_location, t.quantity,
			       t.reason, t.reference_type, t.reference_id, t.actor_type, t.actor_id, t.occurred_at
			FROM inventory_txn t
			JOIN sku s ON s.id = t.sku_id
			LEFT JOIN location lf ON lf.id = t.from_location_id
			LEFT JOIN location lt ON lt.id = t.to_location_id
			""";

	/** Ledger entries newest first. The cursor is the last id seen; the next page continues below it. */
	Page<TransactionView> transactions(TransactionFilter filter, String cursor, int limit) {
		long before = Cursor.decode(cursor);
		List<TransactionView> rows = jdbc.sql(TRANSACTION_SELECT + """
				WHERE (:before = 0 OR t.id < :before)
				  AND (:sku IS NULL OR s.code = :sku)
				  AND (:location IS NULL OR lf.code = :location OR lt.code = :location)
				  AND (:type IS NULL OR t.type = :type)
				ORDER BY t.id DESC
				LIMIT :limit""")
			.param("before", before)
			.param("sku", filter.sku())
			.param("location", filter.location())
			.param("type", filter.type() == null ? null : filter.type().name())
			.param("limit", limit + 1)
			.query((rs, n) -> transaction(rs))
			.list();
		return Cursor.page(rows, limit, TransactionView::id, view -> view);
	}

	Optional<TransactionView> transaction(long id) {
		return jdbc.sql(TRANSACTION_SELECT + "WHERE t.id = ?").param(id).query((rs, n) -> transaction(rs)).optional();
	}

	/** Current balances for the given (location id, SKU id) pairs, in the order given. */
	List<BalanceView> balancesAt(List<long[]> keys) {
		return keys.stream()
			.map(key -> jdbc.sql("""
					SELECT l.code AS location, z.code AS zone, l.type AS location_type, s.code AS sku, b.on_hand,
					       b.allocated
					FROM inventory_balance b
					JOIN location l ON l.id = b.location_id
					JOIN zone z ON z.id = l.zone_id
					JOIN sku s ON s.id = b.sku_id
					WHERE b.location_id = ? AND b.sku_id = ?""")
				.params(key[0], key[1])
				.query((rs, n) -> new BalanceView(rs.getString("location"), rs.getString("zone"),
						LocationType.valueOf(rs.getString("location_type")), rs.getString("sku"), rs.getInt("on_hand"),
						rs.getInt("allocated"), rs.getInt("on_hand") - rs.getInt("allocated")))
				.single())
			.toList();
	}

	private static TransactionView transaction(ResultSet rs) throws SQLException {
		String referenceType = rs.getString("reference_type");
		ReferenceView reference = referenceType == null ? null
				: new ReferenceView(referenceType, rs.getString("reference_id"));
		return new TransactionView(rs.getLong("id"), InventoryTxnType.valueOf(rs.getString("type")),
				rs.getString("sku"), rs.getString("from_location"), rs.getString("to_location"), rs.getInt("quantity"),
				rs.getString("reason"), reference,
				new ActorView(ActorType.valueOf(rs.getString("actor_type")), rs.getString("actor_id")),
				rs.getTimestamp("occurred_at").toInstant());
	}

	record TransactionFilter(String sku, String location, InventoryTxnType type) {
	}

	record BalanceFilter(String sku, String location, String zone, LocationType type, boolean includeEmpty) {
	}

	private record Row(long locationId, long skuId, BalanceView view) {
	}

}
