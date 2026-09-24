package com.cloudwms.core.shared.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may do what (ADR 0027). The rules are ordered: the proposal rule must come before the catch-all for
 * commands, because it is the one command the agent is allowed.
 *
 * <pre>
 * GET anything under /api, health, API docs     anyone
 * POST /api/v1/proposals                        AGENT or SUPERVISOR
 * POST anything else under /api                 SUPERVISOR   (includes approve and reject)
 * POST /demo/reset (dev and demo profiles only)  SUPERVISOR
 * everything else                               denied
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http, AuthProperties properties, JsonMapper json) throws Exception {
		ProblemResponses problems = new ProblemResponses(json);
		http
			// Stateless bearer credentials, no cookies: there is no session for a cross-site request to ride on.
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.addFilterBefore(new BearerTokenFilter(properties, problems), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.GET, "/api/**", "/actuator/health/**", "/actuator/info", "/v3/api-docs*", "/v3/api-docs/**",
						"/swagger-ui/**", "/swagger-ui.html")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/proposals")
				.hasAnyRole(Role.AGENT.name(), Role.SUPERVISOR.name())
				.requestMatchers(HttpMethod.POST, "/api/**", "/demo/reset")
				.hasRole(Role.SUPERVISOR.name())
				.requestMatchers("/error")
				.permitAll()
				.anyRequest()
				.denyAll())
			.exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems));
		return http.build();
	}

}
