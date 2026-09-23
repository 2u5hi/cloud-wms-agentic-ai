package com.cloudwms.core.waves.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.cloudwms.core.shared.api.Cursor;
import com.cloudwms.core.shared.api.Page;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.waves.WavePlanningService;
import com.cloudwms.core.waves.WavePlanningService.PlanRequest;
import com.cloudwms.core.waves.WavePlanningService.PlannedWave;
import com.cloudwms.core.waves.api.WaveViews.PlanResultView;
import com.cloudwms.core.waves.api.WaveViews.PlanWaveRequest;
import com.cloudwms.core.waves.api.WaveViews.WaveSummaryView;
import com.cloudwms.core.waves.api.WaveViews.WaveView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waves")
@Tag(name = "Waves", description = "Planning orders into waves of pick and replenishment work")
class WavesController {

	private final WavePlanningService planning;
	private final WaveQueries queries;

	WavesController(WavePlanningService planning, WaveQueries queries) {
		this.planning = planning;
		this.queries = queries;
	}

	@PostMapping("/plan")
	@Operation(operationId = "planWave", summary = "Plan a wave",
			description = "Selects open orders, promises stock, and creates pick tasks plus the replenishment "
					+ "they depend on. With preview=true nothing is written and no stock is locked; the result is advisory.")
	PlanResultView plan(@RequestParam(defaultValue = "false") boolean preview,
			@Valid @RequestBody(required = false) PlanWaveRequest request) {
		PlanWaveRequest criteria = request == null ? new PlanWaveRequest(null, null, null) : request;
		Instant cutoffBefore = criteria.cutoffWithinHours() == null ? null
				: Instant.now().plus(Duration.ofHours(criteria.cutoffWithinHours()));
		int maxOrders = criteria.maxOrders() == null ? WavePlanningService.DEFAULT_MAX_ORDERS : criteria.maxOrders();
		PlannedWave planned = planning.plan(new PlanRequest(maxOrders, criteria.carrier(), cutoffBefore), preview);
		return new PlanResultView(planned.waveNumber(), planned.preview(), planned.orders(), planned.unitsAllocated(),
				planned.unitsShort(), planned.pickTasks(), planned.replenishmentTasks(),
				queries.describe(planned.shortages()));
	}

	@PostMapping("/{number}/release")
	@Operation(operationId = "releaseWave", summary = "Release a wave to the floor")
	WaveView release(@PathVariable long number) {
		planning.release(number);
		return wave(number);
	}

	@PostMapping("/{number}/cancel")
	@Operation(operationId = "cancelWave", summary = "Cancel a planned wave",
			description = "Cancels its work, releases the stock it promised, and returns its orders to RECEIVED.")
	WaveView cancel(@PathVariable long number) {
		planning.cancel(number);
		return wave(number);
	}

	@GetMapping
	@Operation(operationId = "listWaves", summary = "List waves")
	Page<WaveSummaryView> waves(@RequestParam(required = false) String status,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "" + Cursor.DEFAULT_LIMIT) @Min(1) @Max(Cursor.MAX_LIMIT) int limit) {
		return queries.waves(status, cursor, limit);
	}

	@GetMapping("/{number}")
	@Operation(operationId = "getWave", summary = "Get a wave with its orders and task counts")
	WaveView wave(@PathVariable long number) {
		return queries.wave(number)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Wave %d does not exist".formatted(number),
					Map.of("wave", number)));
	}

}
