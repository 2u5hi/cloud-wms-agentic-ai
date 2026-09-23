package com.cloudwms.core.proposals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.cloudwms.core.shared.actor.Actor;

/** A stored proposal, exactly as the table holds it. */
public record ProposalRow(long id, Long waveId, ProposalKind kind, Map<String, Object> payload, String rationale,
		List<String> evidence, String status, Actor createdBy, Instant createdAt, Actor decidedBy, Instant decidedAt,
		String decisionNote) {
}
