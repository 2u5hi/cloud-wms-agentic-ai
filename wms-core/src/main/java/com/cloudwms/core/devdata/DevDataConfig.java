package com.cloudwms.core.devdata;

import java.time.Instant;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Local development seeds the demo warehouse and its blocked wave on startup. The deployed {@code demo} profile
 * does not: seeding takes longer than a Lambda cold start may, so there it happens through the supervisor's
 * reset ({@link DemoController}), which also makes the public demo repeatable.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
class DevDataConfig {

	@Bean
	ApplicationRunner devDataSeed(DemoData demo) {
		return args -> demo.seedIfEmpty(Instant.now());
	}

}
