package com.cloudwms.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Server and connection settings the application depends on (ADRs 0003 and 0016). */
@IntegrationTest
class DatabaseSettingsTest {

	@Autowired
	JdbcClient jdbc;

	@Test
	@Transactional
	void pooledConnectionsUseReadCommitted() {
		assertThat(jdbc.sql("SELECT @@transaction_isolation").query(String.class).single()).isEqualTo("READ-COMMITTED");
	}

	@Test
	void serverAllowsTriggersWithBinaryLogging() {
		assertThat(jdbc.sql("SELECT @@log_bin_trust_function_creators").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void testsRunOutsideUtcSoTimeZoneBugsShowUp() {
		assertThat(TimeZone.getDefault().getID()).isEqualTo("America/New_York");
	}

	@Test
	void databaseGeneratedTimestampsReadAsTheCorrectInstant() {
		Instant serverNow = jdbc.sql("SELECT CURRENT_TIMESTAMP(6)")
			.query((rs, n) -> rs.getTimestamp(1).toInstant())
			.single();
		assertThat(Duration.between(serverNow, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
	}

	@Test
	void instantsAreStoredInUtc() {
		Instant cutoff = Instant.parse("2026-09-22T21:00:00Z");
		String stored = jdbc.sql("SELECT DATE_FORMAT(?, '%Y-%m-%d %H:%i')")
			.param(Timestamp.from(cutoff))
			.query(String.class)
			.single();
		assertThat(stored).isEqualTo("2026-09-22 21:00");
	}

	@Test
	void serverRunsInUtc() {
		assertThat(jdbc.sql("SELECT @@global.time_zone").query(String.class).single()).isEqualTo("+00:00");
	}

}
