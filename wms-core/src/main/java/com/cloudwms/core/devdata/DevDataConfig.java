package com.cloudwms.core.devdata;

import java.time.Instant;

import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.orders.OrderService;
import com.cloudwms.core.waves.WavePlanningService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Seeds the demo warehouse and its blocked-wave scenario on startup. {@code dev} is local development (with local
 * credentials, see application-dev.yml); {@code demo} is the deployed demo, which seeds but takes its credentials
 * from secrets.
 */
@Configuration(proxyBeanMethods = false)
@Profile({ "dev", "demo" })
class DevDataConfig {

	@Bean
	ApplicationRunner devDataSeed(JdbcClient jdbc, InventoryService inventory, OrderService orders,
			WavePlanningService waves, PlatformTransactionManager transactions) {
		return args -> {
			new DevDataSeeder(jdbc, inventory, transactions).seed();
			new DemoScenario(jdbc, orders, waves, transactions).create(Instant.now());
		};
	}

}
