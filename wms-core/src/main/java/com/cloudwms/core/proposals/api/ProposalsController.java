package com.cloudwms.core.proposals.api;

import java.net.URI;
import java.util.Map;

import com.cloudwms.core.proposals.ProposalRow;
import com.cloudwms.core.proposals.ProposalService;
import com.cloudwms.core.proposals.ProposalService.NewProposal;
import com.cloudwms.core.proposals.api.ProposalViews.CreateProposalRequest;
import com.cloudwms.core.proposals.api.ProposalViews.DecisionRequest;
import com.cloudwms.core.proposals.api.ProposalViews.ProposalView;
import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import com.cloudwms.core.shared.actor.CurrentActor;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/proposals")
@Tag(name = "Proposals", description = "Fixes the agent suggests and a human approves")
class ProposalsController {

	private final ProposalService proposals;
	private final CurrentActor actor;

	ProposalsController(ProposalService proposals, CurrentActor actor) {
		this.proposals = proposals;
		this.actor = actor;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "createProposal", summary = "Suggest a fix",
			description = "Records what should be done and why. Nothing changes in the warehouse until "
					+ "somebody approves it.")
	ResponseEntity<ProposalView> create(@Valid @RequestBody CreateProposalRequest request,
			@RequestHeader(name = "X-Agent-Id", required = false) String agentId) {
		long id = proposals.propose(
				new NewProposal(request.kind(), request.wave(), request.payload(), request.rationale(),
						request.evidence()),
				agentId == null ? actor.get() : new Actor(ActorType.AGENT, agentId));
		return ResponseEntity.created(URI.create("/api/v1/proposals/" + id)).body(ProposalView.of(row(id)));
	}

	@PostMapping("/{id}/approve")
	@Operation(operationId = "approveProposal", summary = "Approve and run a proposal",
			description = "Runs the proposed command as the approver. If the command fails nothing is changed "
					+ "and the proposal stays open.")
	ProposalView approve(@PathVariable long id, @Valid @RequestBody(required = false) DecisionRequest request) {
		return ProposalView.of(proposals.approve(id, actor.get(), note(request)));
	}

	@PostMapping("/{id}/reject")
	@Operation(operationId = "rejectProposal", summary = "Reject a proposal")
	ProposalView reject(@PathVariable long id, @Valid @RequestBody(required = false) DecisionRequest request) {
		return ProposalView.of(proposals.reject(id, actor.get(), note(request)));
	}

	@GetMapping
	@Operation(operationId = "listProposals", summary = "List proposals, oldest first")
	Page<ProposalView> list(@RequestParam(required = false) Long wave, @RequestParam(required = false) String status,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return Cursor.page(proposals.list(wave, status, Cursor.decode(cursor), limit + 1), limit, ProposalRow::id,
				ProposalView::of);
	}

	@GetMapping("/{id}")
	@Operation(operationId = "getProposal", summary = "Get a proposal")
	ProposalView get(@PathVariable long id) {
		return ProposalView.of(row(id));
	}

	private ProposalRow row(long id) {
		return proposals.find(id)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Proposal %d does not exist".formatted(id),
					Map.of("proposal", id)));
	}

	private String note(DecisionRequest request) {
		return request == null ? null : request.note();
	}

}
