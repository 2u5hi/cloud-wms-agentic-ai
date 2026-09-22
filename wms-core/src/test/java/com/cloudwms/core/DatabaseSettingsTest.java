package com.cloudwms.core;

import static org.assertj.core.api.Assertions.assertThat;

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
	void serverRunsInUtc() {
		assertThat(jdbc.sql("SELECT @@global.time_zone").query(String.class).single()).isEqualTo("+00:00");
	}

}
