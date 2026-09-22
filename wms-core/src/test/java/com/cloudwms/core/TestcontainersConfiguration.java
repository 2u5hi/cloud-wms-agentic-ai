package com.cloudwms.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Keep in sync with deploy/compose/docker-compose.yml
	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4.11"));
	}

}
