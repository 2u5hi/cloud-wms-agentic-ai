package com.cloudwms.core.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.cloudwms.core.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Keeps contracts/openapi.yaml in sync with the code. The committed file is what the web and agent
 * clients are generated from, so an API change without a contract update fails the build.
 * Regenerate with: ./mvnw test -Dopenapi.update=true
 */
@IntegrationTest
class OpenApiContractTest {

	private static final Path CONTRACT = Path.of("..", "contracts", "openapi.yaml");

	@Autowired
	MockMvc mockMvc;

	@Test
	void committedContractMatchesGeneratedSpec() throws Exception {
		String generated = mockMvc.perform(get("/v3/api-docs.yaml"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		if (Boolean.getBoolean("openapi.update")) {
			Files.createDirectories(CONTRACT.getParent());
			Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
			return;
		}

		assertThat(CONTRACT).as("contracts/openapi.yaml is missing; run ./mvnw test -Dopenapi.update=true")
			.exists();
		assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8))
			.as("contracts/openapi.yaml is out of date; run ./mvnw test -Dopenapi.update=true")
			.isEqualTo(generated);
	}

}
