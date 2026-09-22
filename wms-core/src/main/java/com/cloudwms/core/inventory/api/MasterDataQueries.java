package com.cloudwms.core.inventory.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.cloudwms.core.inventory.api.MasterDataViews.LocationView;
import com.cloudwms.core.inventory.api.MasterDataViews.PickSlotView;
import com.cloudwms.core.inventory.api.MasterDataViews.SkuView;
import com.cloudwms.core.inventory.api.MasterDataViews.ZoneView;
import com.cloudwms.core.inventory.domain.LocationType;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read side for master data. Lists use keyset pagination on the internal id. */
@Component
class MasterDataQueries {

	private static final String LOCATION_SELECT = """
			SELECT l.id, l.code, z.code AS zone, l.type, l.pick_sequence, l.capacity_units, l.required_equipment,
			       l.active, s.code AS slot_sku, ps.min_qty, ps.max_qty
			FROM location l
			JOIN zone z ON z.id = l.zone_id
			LEFT JOIN pick_slot ps ON ps.location_id = l.id
			LEFT JOIN sku s ON s.id = ps.sku_id
			""";

	private final JdbcClient jdbc;

	MasterDataQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Page<ZoneView> zones(String cursor, int limit) {
		List<Row<ZoneView>> rows = jdbc.sql("SELECT id, code, name FROM zone WHERE id > ? ORDER BY id LIMIT ?")
			.params(Cursor.decode(cursor), limit + 1)
			.query((rs, n) -> new Row<>(rs.getLong("id"), new ZoneView(rs.getString("code"), rs.getString("name"))))
			.list();
		return Cursor.page(rows, limit, Row::id, Row::view);
	}

	Page<LocationView> locations(String zone, LocationType type, String cursor, int limit) {
		List<Row<LocationView>> rows = jdbc.sql(LOCATION_SELECT + """
				WHERE l.id > :after
				  AND (:zone IS NULL OR z.code = :zone)
				  AND (:type IS NULL OR l.type = :type)
				ORDER BY l.id
				LIMIT :limit""")
			.param("after", Cursor.decode(cursor))
			.param("zone", zone)
			.param("type", type == null ? null : type.name())
			.param("limit", limit + 1)
			.query((rs, n) -> new Row<>(rs.getLong("id"), location(rs)))
			.list();
		return Cursor.page(rows, limit, Row::id, Row::view);
	}

	Optional<LocationView> location(String code) {
		return jdbc.sql(LOCATION_SELECT + "WHERE l.code = ?").param(code).query((rs, n) -> location(rs)).optional();
	}

	Page<SkuView> skus(String cursor, int limit) {
		List<Row<SkuView>> rows = jdbc
			.sql("SELECT id, code, description, uom, velocity_class, active FROM sku WHERE id > ? ORDER BY id LIMIT ?")
			.params(Cursor.decode(cursor), limit + 1)
			.query((rs, n) -> new Row<>(rs.getLong("id"), sku(rs)))
			.list();
		return Cursor.page(rows, limit, Row::id, Row::view);
	}

	Optional<SkuView> sku(String code) {
		return jdbc.sql("SELECT code, description, uom, velocity_class, active FROM sku WHERE code = ?")
			.param(code)
			.query((rs, n) -> sku(rs))
			.optional();
	}

	private static LocationView location(ResultSet rs) throws SQLException {
		String slotSku = rs.getString("slot_sku");
		PickSlotView slot = slotSku == null ? null
				: new PickSlotView(slotSku, rs.getInt("min_qty"), rs.getInt("max_qty"));
		return new LocationView(rs.getString("code"), rs.getString("zone"), LocationType.valueOf(rs.getString("type")),
				rs.getObject("pick_sequence", Integer.class), rs.getObject("capacity_units", Integer.class),
				rs.getString("required_equipment"), rs.getBoolean("active"), slot);
	}

	private static SkuView sku(ResultSet rs) throws SQLException {
		return new SkuView(rs.getString("code"), rs.getString("description"), rs.getString("uom"),
				rs.getString("velocity_class"), rs.getBoolean("active"));
	}

	/** A view plus the id it is paginated on. */
	private record Row<T>(long id, T view) {
	}

}
