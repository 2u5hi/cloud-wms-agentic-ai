package com.cloudwms.core.shared.idempotency;

import com.cloudwms.core.shared.actor.CurrentActor;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class IdempotencyConfig {

	/** Registered explicitly for /api/* so web-slice tests, which don't load this config, aren't affected. */
	@Bean
	FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(JdbcClient jdbc, PlatformTransactionManager transactions,
			CurrentActor actor, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
		IdempotencyFilter filter = new IdempotencyFilter(new IdempotencyStore(jdbc), new TransactionTemplate(transactions),
				actor, errors);
		FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns("/api/*");
		return registration;
	}

	/** Documents the required header on every POST operation, so generated clients must send it. */
	@Bean
	OperationCustomizer idempotencyKeyHeader() {
		return (operation, handlerMethod) -> {
			if (handlerMethod.hasMethodAnnotation(PostMapping.class)) {
				operation.addParametersItem(new HeaderParameter().name(IdempotencyFilter.KEY_HEADER)
					.required(true)
					.description("Unique per logical request. Retrying with the same key returns the original "
							+ "response instead of repeating the command.")
					.schema(new StringSchema().maxLength(100).pattern("^[A-Za-z0-9_.:\\-]{1,100}$")));
			}
			return operation;
		};
	}

}
