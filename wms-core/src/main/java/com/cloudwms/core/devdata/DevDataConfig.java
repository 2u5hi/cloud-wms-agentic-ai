package com.cloudwms.core.devdata;

import com.cloudwms.core.inventory.InventoryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Seeds the demo warehouse on startup, only with the {@code dev} profile. */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
class DevDataConfig {

	@Bean
	ApplicationRunner devDataSeed(JdbcClient jdbc, InventoryService inventory, PlatformTransactionManager transactions) {
		return args -> new DevDataSeeder(jdbc, inventory, transactions).seed();
	}

}
