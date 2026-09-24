package com.cloudwms.core.devdata;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.cloudwms.core.IntegrationTest;
import com.cloudwms.core.TestCredentials;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The deployed demo's reset, under the {@code demo} profile. A different profile means a different Spring context
 * and so its own MySQL container: the reset wipes the schema, which must never touch the database the other
 * integration tests share.
 */
@IntegrationTest
@ActiveProfiles("demo")
class DemoResetTest {

	static final String BLOCKED = "$.blockers[?(@.rootCause == 'NO_ELIGIBLE_WORKER_AVAILABLE')]";

	@Autowired
	MockMvc mockMvc;

	@Test
	void resetBuildsTheBlockedDemoWaveAndUndoesWhateverVisitorsDid() throws Exception {
		long wave = reset();
		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave)).andExpect(jsonPath(BLOCKED, hasSize(2)));

		// A visitor fixes the demo: the reach-truck driver takes both replenishments.
		String diagnosis = mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave))
			.andReturn()
			.getResponse()
			.getContentAsString();
		for (Integer task : JsonPath.<java.util.List<Integer>>read(diagnosis, BLOCKED + ".replenishment.taskId")) {
			mockMvc.perform(post("/api/v1/tasks/{task}/reassign", task)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker\": \"" + DemoScenario.REACH_TRUCK_DRIVER + "\"}")).andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", wave)).andExpect(jsonPath(BLOCKED, hasSize(0)));

		// The next visitor gets the blocked wave back.
		long again = reset();
		mockMvc.perform(get("/api/v1/waves/{wave}/diagnosis", again)).andExpect(jsonPath(BLOCKED, hasSize(2)));
		mockMvc.perform(get("/api/v1/proposals")).andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void onlyASupervisorCanReset() throws Exception {
		mockMvc.perform(post("/demo/reset").header("Authorization", TestCredentials.AGENT))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/demo/reset").header("Authorization", "Bearer not-the-passcode"))
			.andExpect(status().isUnauthorized());
	}

	private long reset() throws Exception {
		String body = mockMvc.perform(post("/demo/reset"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.wave")).longValue();
	}

}
