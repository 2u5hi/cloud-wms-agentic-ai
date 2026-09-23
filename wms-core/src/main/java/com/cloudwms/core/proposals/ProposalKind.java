package com.cloudwms.core.proposals;

/** What a proposal asks the WMS to do. One kind per command the agent is allowed to suggest. */
public enum ProposalKind {

	/** Give a task to a named worker: {@code {"task": 42, "worker": "W-014"}}. */
	REASSIGN_TASK

}
