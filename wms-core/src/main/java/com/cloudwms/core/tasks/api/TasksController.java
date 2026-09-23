package com.cloudwms.core.tasks.api;

import java.util.Map;
import java.util.Optional;

import com.cloudwms.core.shared.actor.CurrentActor;
import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.tasks.TaskExecutionService;
import com.cloudwms.core.tasks.api.TaskViews.CreateWorkerRequest;
import com.cloudwms.core.tasks.api.TaskViews.ReassignRequest;
import com.cloudwms.core.tasks.api.TaskViews.TaskView;
import com.cloudwms.core.tasks.api.TaskViews.WorkerView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tasks", description = "Work on the floor: picks, replenishments, and who does them")
class TasksController {

	private final TaskExecutionService execution;
	private final TaskQueries queries;
	private final CurrentActor actor;

	TasksController(TaskExecutionService execution, TaskQueries queries, CurrentActor actor) {
		this.execution = execution;
		this.queries = queries;
		this.actor = actor;
	}

	@PostMapping("/workers/{code}/next-task")
	@Operation(operationId = "claimNextTask", summary = "Claim the next task for a worker",
			description = "Returns the task now assigned to them, or 204 when there is nothing they can work on. "
					+ "Only tasks of released waves count, and only those whose equipment the worker is certified for. "
					+ "Pass zone to take work only from that zone.")
	ResponseEntity<TaskView> nextTask(@PathVariable String code, @RequestParam(required = false) String zone) {
		Long zoneId = zone == null ? null
				: queries.zoneId(zone)
					.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Zone %s does not exist".formatted(zone),
							Map.of("zone", zone)));
		Optional<Long> taskId = execution.claimNext(code, zoneId);
		return taskId.map(id -> ResponseEntity.ok(task(id))).orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/tasks/{id}/complete")
	@Operation(operationId = "completeTask", summary = "Complete a task",
			description = "A replenishment moves the promised stock to its slot and frees the picks waiting on it. "
					+ "A pick takes the stock off the shelf and advances its order.")
	TaskView complete(@PathVariable long id) {
		execution.complete(id, actor.get());
		return task(id);
	}

	@PostMapping("/tasks/{id}/reassign")
	@Operation(operationId = "reassignTask", summary = "Give a task to another worker")
	TaskView reassign(@PathVariable long id, @Valid @RequestBody ReassignRequest request) {
		execution.reassign(id, request.worker());
		return task(id);
	}

	@GetMapping("/tasks")
	@Operation(operationId = "listTasks", summary = "List tasks")
	Page<TaskView> tasks(@RequestParam(required = false) Long wave, @RequestParam(required = false) String status,
			@RequestParam(required = false) String type, @RequestParam(required = false) String worker,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.tasks(wave, status, type, worker, cursor, limit);
	}

	@GetMapping("/tasks/{id}")
	@Operation(operationId = "getTask", summary = "Get a task")
	TaskView task(@PathVariable long id) {
		return queries.task(id)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Task %d does not exist".formatted(id),
					Map.of("task", id)));
	}

	@GetMapping("/workers")
	@Operation(operationId = "listWorkers", summary = "List workers with their certifications")
	Page<WorkerView> workers(@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.workers(cursor, limit);
	}

	@PostMapping("/workers")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "createWorker", summary = "Add a worker",
			description = "Demo setup; workers would normally come from a labour management system.")
	ResponseEntity<WorkerView> createWorker(@Valid @RequestBody CreateWorkerRequest request) {
		WorkerView worker = queries.create(request);
		return ResponseEntity.created(java.net.URI.create("/api/v1/workers/" + worker.code())).body(worker);
	}

	@GetMapping("/workers/{code}")
	@Operation(operationId = "getWorker", summary = "Get a worker")
	WorkerView worker(@PathVariable String code) {
		return queries.worker(code)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Worker %s does not exist".formatted(code),
					Map.of("worker", code)));
	}

}
