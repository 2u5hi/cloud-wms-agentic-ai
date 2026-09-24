package com.cloudwms.core.devdata;

import java.time.Instant;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operating the demo environment, not part of the WMS API: outside {@code /api}, so it is not in the contract
 * and not wrapped in an idempotency transaction (whose table the reset drops). Supervisor only (ADR 0027).
 */
@RestController
@Profile({ "dev", "demo" })
class DemoController {

	private final DemoData demo;

	DemoController(DemoData demo) {
		this.demo = demo;
	}

	@PostMapping("/demo/reset")
	Map<String, Object> reset() {
		return Map.of("wave", demo.reset(Instant.now()));
	}

}
