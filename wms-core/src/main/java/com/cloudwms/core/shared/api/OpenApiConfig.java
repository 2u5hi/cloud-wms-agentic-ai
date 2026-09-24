package com.cloudwms.core.shared.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;

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
			.servers(List.of(new Server().url("http://localhost:8080").description("Local development")))
			.components(new Components().addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.description("The supervisor passcode, or the agent's service token (ADR 0027). Reads need none.")));
	}

	static final String BEARER = "bearer";

	/** Every command needs a credential; reads are public. Which role a command needs is in its description. */
	@Bean
	OperationCustomizer commandsNeedACredential() {
		return (operation, handlerMethod) -> {
			if (handlerMethod.hasMethodAnnotation(PostMapping.class)) {
				operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
			}
			return operation;
		};
	}

	/**
	 * Contract-wide nullability rule: a property is required unless it is marked nullable. Generated
	 * clients then type {@code code: string} instead of {@code code?: string}, and nullable fields as
	 * {@code T | null}. Nullable references are rewritten to {@code oneOf: [ref, null]}, the OpenAPI 3.1
	 * form, because springdoc emits {@code type: 'null'} next to the {@code $ref}, which drops the null.
	 */
	@Bean
	OpenApiCustomizer requiredUnlessNullable() {
		return openApi -> {
			if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
				return;
			}
			for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
				@SuppressWarnings("rawtypes")
				Map<String, Schema> properties = schema.getProperties();
				if (properties == null) {
					continue;
				}
				Set<String> required = new TreeSet<>(schema.getRequired() == null ? List.of() : schema.getRequired());
				properties.replaceAll((name, property) -> {
					if (!isNullable(property)) {
						required.add(name);
						return property;
					}
					return property.get$ref() == null ? property : nullableReference(property);
				});
				schema.setRequired(required.isEmpty() ? null : new ArrayList<>(required));
			}
		};
	}

	private static boolean isNullable(Schema<?> property) {
		return Boolean.TRUE.equals(property.getNullable()) || "null".equals(property.getType())
				|| (property.getTypes() != null && property.getTypes().contains("null"));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Schema nullableReference(Schema<?> property) {
		Schema reference = new Schema().$ref(property.get$ref());
		Schema nullType = new Schema().types(Set.of("null"));
		return new Schema().oneOf(List.of(reference, nullType)).description(property.getDescription());
	}

}
