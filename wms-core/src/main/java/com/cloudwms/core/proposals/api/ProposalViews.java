package com.cloudwms.core.proposals.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.cloudwms.core.proposals.ProposalKind;
import com.cloudwms.core.proposals.ProposalRow;
import com.cloudwms.core.shared.actor.ActorType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for proposals (ADR 0023: views own the contract). */
public final class ProposalViews {

	private ProposalViews() {
	}

	public record CreateProposalRequest(@NotNull ProposalKind kind,
			@Schema(nullable = true, description = "The wave this is about, when it is about one") Long wave,
			@NotNull @Schema(description = "Arguments for the command, e.g. {\"task\": 42, \"worker\": \"W-014\"}",
					example = "{\"task\": 42, \"worker\": \"W-014\"}") Map<String, Object> payload,
			@NotBlank @Size(max = 4000) @Schema(description = "Why, in operator language") String rationale,
			@NotEmpty @Size(max = 20) @Schema(
					description = "What was read to reach this, one line each") List<@NotBlank @Size(max = 500) String> evidence) {
	}

	public record DecisionRequest(
			@Size(max = 500) @Schema(nullable = true, description = "Why it was approved or rejected") String note) {
	}

	public record ActorView(ActorType type, String id) {
	}

	public record ProposalView(long id, ProposalKind kind, @Schema(nullable = true) Long wave,
			Map<String, Object> payload, String rationale, List<String> evidence,
			@Schema(description = "PROPOSED, EXECUTED, REJECTED or FAILED") String status, ActorView createdBy,
			Instant createdAt, @Schema(nullable = true) ActorView decidedBy, @Schema(nullable = true) Instant decidedAt,
			@Schema(nullable = true) String decisionNote) {

		public static ProposalView of(ProposalRow row) {
			return new ProposalView(row.id(), row.kind(), row.waveId(), row.payload(), row.rationale(), row.evidence(),
					row.status(), new ActorView(row.createdBy().type(), row.createdBy().id()), row.createdAt(),
					row.decidedBy() == null ? null : new ActorView(row.decidedBy().type(), row.decidedBy().id()),
					row.decidedAt(), row.decisionNote());
		}

	}

}
