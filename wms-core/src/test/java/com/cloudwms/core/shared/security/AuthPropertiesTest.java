package com.cloudwms.core.shared.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The service refuses to start in a state where writes would be open or the agent could approve. */
class AuthPropertiesTest {

	@Test
	void bothCredentialsAreRequired() {
		assertThatThrownBy(() -> new AuthProperties("", "agent-token-1")).hasMessageContaining("DEMO_PASSCODE");
		assertThatThrownBy(() -> new AuthProperties("supervisor-1", null)).hasMessageContaining("AGENT_TOKEN");
	}

	@Test
	void theAgentCannotShareTheSupervisorsCredential() {
		assertThatThrownBy(() -> new AuthProperties("same-secret", "same-secret"))
			.hasMessageContaining("the agent could approve");
	}

	@Test
	void shortCredentialsAreRefused() {
		assertThatThrownBy(() -> new AuthProperties("short", "agent-token-1")).hasMessageContaining("at least 8");
	}

	@Test
	void distinctCredentialsOfReasonableLengthAreAccepted() {
		assertThatCode(() -> new AuthProperties("supervisor-1", "agent-token-1")).doesNotThrowAnyException();
	}

}
