package com.cloudwms.core.proposals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.tasks.TaskExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A proposal is a command somebody (in practice the agent) wants run, held until a human approves it.
 * Approving runs it here, through the same service the console calls, inside the approving transaction:
 * either the proposal is EXECUTED and the change is made, or the command fails and nothing happened.
 *
 * <p>The agent has no write path of its own. Adding a new proposal kind means adding an executor below
 * and to {@code ck_proposal_kind}, which keeps the list of things it can ask for small and reviewable.
 */
@Service
public class ProposalService {

	private final ProposalRepository repository;
	private final TaskExecutionService tasks;

	ProposalService(ProposalRepository repository, TaskExecutionService tasks) {
		this.repository = repository;
		this.tasks = tasks;
	}

	@Transactional
	public long propose(NewProposal proposal, Actor actor) {
		validate(proposal);
		return repository.insert(proposal, actor);
	}

	/**
	 * Runs the proposal's command. A domain failure (the task is finished, the worker is not certified)
	 * propagates: the whole transaction rolls back, so an approval either works or leaves the proposal
	 * PROPOSED for somebody to try something else.
	 */
	@Transactional
	public ProposalRow approve(long id, Actor actor, String note) {
		ProposalRow proposal = open(id);
		execute(proposal);
		repository.decide(id, "EXECUTED", actor, note);
		return repository.find(id).orElseThrow();
	}

	public Optional<ProposalRow> find(long id) {
		return repository.find(id);
	}

	/** Oldest first, for the console's proposal list. Ask for one more row than the page size. */
	public List<ProposalRow> list(Long waveId, String status, long after, int limit) {
		return repository.list(waveId, status, after, limit);
	}

	@Transactional
	public ProposalRow reject(long id, Actor actor, String note) {
		open(id);
		repository.decide(id, "REJECTED", actor, note);
		return repository.find(id).orElseThrow();
	}

	private void execute(ProposalRow proposal) {
		switch (proposal.kind()) {
			case REASSIGN_TASK -> tasks.reassign(longValue(proposal.payload(), "task"),
					stringValue(proposal.payload(), "worker"));
		}
	}

	private ProposalRow open(long id) {
		ProposalRow proposal = repository.find(id)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Proposal %d does not exist".formatted(id),
					Map.of("proposal", id)));
		if (!proposal.status().equals("PROPOSED")) {
			throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
					"Proposal %d was already %s".formatted(id, proposal.status().toLowerCase()),
					Map.of("proposal", id, "status", proposal.status()));
		}
		return proposal;
	}

	/** Payload shape is checked when the proposal is made, not when somebody approves it. */
	private void validate(NewProposal proposal) {
		switch (proposal.kind()) {
			case REASSIGN_TASK -> {
				longValue(proposal.payload(), "task");
				stringValue(proposal.payload(), "worker");
			}
		}
	}

	private long longValue(Map<String, Object> payload, String field) {
		Object value = payload.get(field);
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new DomainException(ErrorCode.VALIDATION_FAILED,
				"Proposal payload needs a numeric %s".formatted(field), Map.of("field", field));
	}

	private String stringValue(Map<String, Object> payload, String field) {
		Object value = payload.get(field);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw new DomainException(ErrorCode.VALIDATION_FAILED, "Proposal payload needs a %s".formatted(field),
				Map.of("field", field));
	}

	/** What the agent (or a human) asks for. */
	public record NewProposal(ProposalKind kind, Long waveId, Map<String, Object> payload, String rationale,
			List<String> evidence) {
	}

}
