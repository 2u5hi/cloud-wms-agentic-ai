package com.cloudwms.core.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two credentials the Phase 1 demo knows about (ADR 0027). Both are required: the service refuses to start
 * without them rather than run with writes open. The {@code dev} profile supplies local defaults; the deployed
 * demo reads them from secrets.
 *
 * @param supervisorPasscode shared passcode for people approving and running commands
 * @param agentToken service token for ops-agent, which may read and propose but never execute
 */
@ConfigurationProperties("wms.auth")
public record AuthProperties(String supervisorPasscode, String agentToken) {

	static final int MIN_LENGTH = 8;

	public AuthProperties {
		require(supervisorPasscode, "wms.auth.supervisor-passcode (DEMO_PASSCODE)");
		require(agentToken, "wms.auth.agent-token (AGENT_TOKEN)");
		if (supervisorPasscode.equals(agentToken)) {
			throw new IllegalStateException(
					"wms.auth.supervisor-passcode and wms.auth.agent-token must differ, or the agent could approve");
		}
	}

	private static void require(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is not set; the service will not start with writes open");
		}
		if (value.length() < MIN_LENGTH) {
			throw new IllegalStateException(name + " must be at least " + MIN_LENGTH + " characters");
		}
	}

}
