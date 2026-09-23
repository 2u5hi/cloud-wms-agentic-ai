package com.cloudwms.core.tasks.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TaskViews {

	private TaskViews() {
	}

	public record TaskView(long id, String type, String status, int priority, String sku,
			@Schema(nullable = true) String fromLocation, @Schema(nullable = true) String toLocation, int quantity,
			@Schema(nullable = true) Long wave,
			@Schema(nullable = true, description = "The task this one waits for") Long waitingOnTask,
			@Schema(nullable = true) String requiredEquipment, @Schema(nullable = true) String assignedWorker) {
	}

	public record WorkerView(String code, String name, @Schema(nullable = true) String homeZone, String status,
			List<String> equipment) {
	}

	public record CreateWorkerRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 100) String name,
			@Schema(nullable = true) @Size(max = 20) String homeZone,
			@Schema(nullable = true, description = "Equipment certifications, e.g. REACH_TRUCK") List<String> equipment) {
	}

	public record ReassignRequest(@NotBlank @Size(max = 30) String worker) {
	}

}
