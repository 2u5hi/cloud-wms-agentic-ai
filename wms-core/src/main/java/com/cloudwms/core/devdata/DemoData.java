package com.cloudwms.core.devdata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.orders.OrderService;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.waves.WavePlanningService;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The demo warehouse and its blocked wave (ADR 0025), built on demand. Only exists under {@code dev} and
 * {@code demo}; the schema-wiping reset is never available anywhere else.
 */
@Component
@Profile({ "dev", "demo" })
class DemoData {

	private static final Logger log = LoggerFactory.getLogger(DemoData.class);
	private static final String RESET_LOCK = "wms-demo-reset";

	private final JdbcClient jdbc;
	private final DataSource dataSource;
	private final Flyway flyway;
	private final InventoryService inventory;
	private final OrderService orders;
	private final WavePlanningService waves;
	private final PlatformTransactionManager transactions;

	DemoData(JdbcClient jdbc, DataSource dataSource, Flyway flyway, InventoryService inventory, OrderService orders,
			WavePlanningService waves, PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.flyway = flyway;
		this.inventory = inventory;
		this.orders = orders;
		this.waves = waves;
		this.transactions = transactions;
	}

	/** Seeds the warehouse and scenario if they are not there yet; a no-op on a database that has them. */
	Optional<Long> seedIfEmpty(Instant now) {
		new DevDataSeeder(jdbc, inventory, transactions).seed();
		return new DemoScenario(jdbc, orders, waves, transactions).create(now);
	}

	/**
	 * Wipes the schema and builds the demo again from nothing: migrations, warehouse, scenario. This is what makes
	 * a public demo repeatable — the first visitor to approve the fix would otherwise "solve" it for everyone, and
	 * cutoffs would age into "missed".
	 *
	 * <p>Only one reset runs at a time across every instance: a MySQL named lock, held on its own connection, since
	 * the schema it would otherwise live in is about to be dropped.
	 */
	long reset(Instant now) {
		try (Connection lock = dataSource.getConnection()) {
			if (!acquire(lock)) {
				throw new DomainException(ErrorCode.CONCURRENCY_CONFLICT, "A demo reset is already running",
						Map.of());
			}
			try {
				long started = System.currentTimeMillis();
				flyway.clean();
				flyway.migrate();
				long wave = seedIfEmpty(now).orElseThrow();
				log.info("Demo reset in {} ms: wave {} is blocked again", System.currentTimeMillis() - started, wave);
				return wave;
			}
			finally {
				release(lock);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not take the demo reset lock", ex);
		}
	}

	private static boolean acquire(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
			statement.setString(1, RESET_LOCK);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getInt(1) == 1;
			}
		}
	}

	private static void release(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			statement.setString(1, RESET_LOCK);
			statement.execute();
		}
	}

}
