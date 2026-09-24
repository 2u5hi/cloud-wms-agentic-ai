package com.cloudwms.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The credentials integration tests run with. Every MockMvc request is a supervisor's unless it says otherwise:
 * most tests are about warehouse behaviour, not auth, and a request that sets its own {@code Authorization}
 * header (the agent's token, a wrong passcode) overrides this default. Tests of anonymous access build their own
 * MockMvc without it ({@code SecurityApiTest}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestCredentials {

	public static final String SUPERVISOR_PASSCODE = "test-supervisor-passcode";
	public static final String AGENT_TOKEN = "test-agent-token";

	public static final String SUPERVISOR = "Bearer " + SUPERVISOR_PASSCODE;
	public static final String AGENT = "Bearer " + AGENT_TOKEN;

	@Bean
	MockMvcBuilderCustomizer supervisorByDefault() {
		return builder -> builder
			.defaultRequest(MockMvcRequestBuilders.get("/").header(HttpHeaders.AUTHORIZATION, SUPERVISOR));
	}

}
