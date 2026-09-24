package com.cloudwms.core.shared.security;

/** What a caller may do. Anonymous callers have no role and can only read. */
public enum Role {

	/** A person running the floor: every command, including approving the agent's proposals. */
	SUPERVISOR,
	/** ops-agent: reads, and files proposals. Never runs a command. */
	AGENT

}
