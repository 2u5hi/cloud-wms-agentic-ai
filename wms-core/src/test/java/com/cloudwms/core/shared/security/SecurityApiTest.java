package com.cloudwms.core.shared.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.TestCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who may do what (ADR 0027), from the outside. Uses its own MockMvc without the supervisor credential the other
 * integration tests send by default, so "no credential" really means none.
 */
@IntegrationTest
class SecurityApiTest {

	@Autowired
	WebApplicationContext context;

	MockMvc mockMvc;

	@BeforeEach
	void withoutDefaultCredentials() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void anyoneCanRead() throws Exception {
		mockMvc.perform(get("/api/v1/waves")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/inventory")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
	}

	@Test
	void anonymousCommandsAreRefusedWithAProblemThatSaysWhatToDo() throws Exception {
		mockMvc.perform(post("/api/v1/waves/plan").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value("Sign in as a supervisor to do this"));
	}

	@Test
	void aWrongCredentialIsRejectedEvenForReads() throws Exception {
		// A mistyped passcode should say so, not quietly drop to read-only.
		mockMvc.perform(get("/api/v1/waves").header("Authorization", "Bearer not-the-passcode"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Those credentials were not recognised"));
		mockMvc.perform(get("/api/v1/waves").header("Authorization", TestCredentials.SUPERVISOR_PASSCODE))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void theAgentCanOnlyPropose() throws Exception {
		mockMvc
			.perform(post("/api/v1/waves/plan").header("Authorization", TestCredentials.AGENT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc
			.perform(post("/api/v1/inventory/adjustments").header("Authorization", TestCredentials.AGENT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/waves").header("Authorization", TestCredentials.AGENT)).andExpect(status().isOk());
	}

	@Test
	void aSupervisorCanRunCommands() throws Exception {
		mockMvc
			.perform(post("/api/v1/waves/plan").header("Authorization", TestCredentials.SUPERVISOR)
				.param("preview", "true")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"carrier\": \"NO-SUCH-CARRIER\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void meSaysWhoTheCredentialBelongsTo() throws Exception {
		mockMvc.perform(get("/api/v1/me"))
			.andExpect(jsonPath("$.type").value("HUMAN"))
			.andExpect(jsonPath("$.id").value("unauthenticated"))
			.andExpect(jsonPath("$.role").doesNotExist());
		mockMvc.perform(get("/api/v1/me").header("Authorization", TestCredentials.SUPERVISOR))
			.andExpect(jsonPath("$.id").value("demo-supervisor"))
			.andExpect(jsonPath("$.role").value("SUPERVISOR"));
		mockMvc.perform(get("/api/v1/me").header("Authorization", TestCredentials.AGENT))
			.andExpect(jsonPath("$.type").value("AGENT"))
			.andExpect(jsonPath("$.role").value("AGENT"));
	}

	@Test
	void anythingNotListedIsDenied() throws Exception {
		mockMvc.perform(delete("/api/v1/waves/1").header("Authorization", TestCredentials.SUPERVISOR))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/actuator/env").header("Authorization", TestCredentials.SUPERVISOR))
			.andExpect(status().isForbidden());
	}

}
