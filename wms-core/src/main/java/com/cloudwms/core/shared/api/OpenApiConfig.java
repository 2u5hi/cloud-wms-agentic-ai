package com.cloudwms.core.shared.api;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	/**
	 * A fixed server entry keeps the generated spec deterministic. Without it, springdoc derives the
	 * server URL from each incoming request, and the committed contract would drift between environments.
	 */
	@Bean
	OpenAPI wmsOpenApi() {
		return new OpenAPI()
			.info(new Info().title("WMS Core API")
				.version("v1")
				.description("Warehouse management core: inventory, orders, waves, tasks, and execution."))
			.servers(List.of(new Server().url("http://localhost:8080").description("Local development")));
	}

}
