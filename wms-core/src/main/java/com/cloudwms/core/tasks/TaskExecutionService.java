package com.cloudwms.core.tasks;

import java.util.Map;
import java.util.Optional;

import com.cloudwms.core.inventory.InventoryService;
import com.cloudwms.core.inventory.domain.InventoryMovement;
import com.cloudwms.core.inventory.domain.Reference;
import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.tasks.TaskRepository.AllocationRow;
import com.cloudwms.core.tasks.TaskRepository.TaskRow;
import com.cloudwms.core.tasks.TaskRepository.WorkerRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What happens on the floor: workers take the next task, and completing it moves real stock. */
@Service
public class TaskExecutionService {

	private final TaskRepository repository;
	private final InventoryService inventory;

	TaskExecutionService(TaskRepository repository, InventoryService inventory) {
		this.repository = repository;
		this.inventory = inventory;
	}

	/**
	 * Assigns the next workable task to this worker, or empty if there is nothing they can do.
	 *
	 * @param zoneId optional: only take work in this zone
	 */
	@Transactional
	public Optional<Long> claimNext(String workerCode, Long zoneId) {
		WorkerRow worker = worker(workerCode);
		if (!worker.status().equals("AVAILABLE")) {
			throw new DomainException(ErrorCode.NOT_ELIGIBLE,
					"Worker %s is %s".formatted(worker.code(), worker.status().toLowerCase()),
					Map.of("worker", worker.code(), "status", worker.status()));
		}
		return repository.claimNext(worker.id(), worker.homeZoneId(), zoneId);
	}

	/**
	 * Completes a task and applies what it means to stock:
	 * <ul>
	 * <li>REPLENISH moves the promised stock to the forward slot and makes the picks waiting on it workable</li>
	 * <li>PICK consumes the promise and takes the stock off the shelf</li>
	 * </ul>
	 */
	@Transactional
	public void complete(long taskId, Actor actor) {
		TaskRow task = task(taskId);
		if (!task.status().equals("ASSIGNED") && !task.status().equals("IN_PROGRESS")) {
			throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
					"Cannot complete task %d: it is %s".formatted(taskId, task.status()),
					Map.of("task", taskId, "status", task.status()));
		}
		Reference reference = new Reference("TASK", Long.toString(taskId));
		switch (task.type()) {
			case "REPLENISH" -> {
				inventory.moveAllocated(InventoryMovement.move(task.skuId(), task.fromLocationId(),
						task.toLocationId(), task.quantity(), reference, actor), task.quantity());
				repository.moveAllocationsOfDependents(taskId, task.toLocationId());
				repository.releaseDependents(taskId);
			}
			case "PICK" -> {
				inventory.pickAllocated(InventoryMovement.pick(task.skuId(), task.fromLocationId(), task.quantity(),
						reference, actor));
				AllocationRow allocation = repository.findAllocation(task.allocationId())
					.orElseThrow(() -> new IllegalStateException("pick task %d has no allocation".formatted(taskId)));
				repository.completeAllocation(allocation.id(), allocation.quantity(), allocation.orderLineId());
				repository.advanceOrder(allocation.orderId());
			}
			default -> throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
					"Task type %s cannot be completed yet".formatted(task.type()), Map.of("task", taskId));
		}
		repository.setStatus(taskId, "COMPLETED");
		if (task.waveId() != null) {
			repository.advanceWave(task.waveId());
		}
	}

	/** Hands a task to another worker, e.g. when a replenishment is stalled for lack of a certified driver. */
	@Transactional
	public void reassign(long taskId, String workerCode) {
		TaskRow task = task(taskId);
		WorkerRow worker = worker(workerCode);
		requireReassignable(task, worker);
		repository.assign(taskId, worker.id(), "ASSIGNED");
	}

	/**
	 * Everything {@link #reassign} checks, without changing anything, plus that the task belongs to the given wave.
	 * Proposals call this when they are made, so a suggestion that could never run is refused up front instead of
	 * waiting for a supervisor to find out.
	 */
	@Transactional(readOnly = true)
	public void checkReassign(long taskId, String workerCode, Long waveId) {
		TaskRow task = task(taskId);
		if (waveId != null && !waveId.equals(task.waveId())) {
			throw new DomainException(ErrorCode.PRECONDITION_FAILED,
					"Task %d is not part of wave %d".formatted(taskId, waveId), Map.of("task", taskId, "wave", waveId));
		}
		requireReassignable(task, worker(workerCode));
	}

	private void requireReassignable(TaskRow task, WorkerRow worker) {
		if (task.status().equals("COMPLETED") || task.status().equals("CANCELLED")) {
			throw new DomainException(ErrorCode.INVALID_STATE_TRANSITION,
					"Cannot reassign task %d: it is %s".formatted(task.id(), task.status()),
					Map.of("task", task.id(), "status", task.status()));
		}
		if (task.requiredEquipment() != null && !repository.equipment(worker.id()).contains(task.requiredEquipment())) {
			throw new DomainException(ErrorCode.NOT_ELIGIBLE,
					"Worker %s is not certified for %s".formatted(worker.code(), task.requiredEquipment()),
					Map.of("worker", worker.code(), "equipment", task.requiredEquipment()));
		}
	}

	private WorkerRow worker(String code) {
		return repository.findWorker(code)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Worker %s does not exist".formatted(code),
					Map.of("worker", code)));
	}

	private TaskRow task(long taskId) {
		return repository.find(taskId)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Task %d does not exist".formatted(taskId),
					Map.of("task", taskId)));
	}

}
