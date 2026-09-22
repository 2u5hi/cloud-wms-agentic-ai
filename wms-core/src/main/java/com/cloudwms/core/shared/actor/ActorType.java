package com.cloudwms.core.shared.actor;

/** Who caused a change. Recorded on every ledger row so the audit trail separates people, the agent, and automation. */
public enum ActorType {

	HUMAN, AGENT, SYSTEM, INTEGRATION

}
