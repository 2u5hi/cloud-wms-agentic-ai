package com.cloudwms.core.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import(ApiExceptionHandlerTest.ErrorTriggerController.class)
class ApiExceptionHandlerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void domainExceptionBecomesProblemWithCodeAndProperties() throws Exception {
		mockMvc.perform(get("/test/insufficient-inventory"))
			.andExpect(status().isConflict())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:wms:problem:insufficient-inventory"))
			.andExpect(jsonPath("$.title").value("Insufficient inventory"))
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.detail").value("Only 12 of 40 units available"))
			.andExpect(jsonPath("$.instance").value("/test/insufficient-inventory"))
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"))
			.andExpect(jsonPath("$.sku").value("NK-AM-001"))
			.andExpect(jsonPath("$.available").value(12));
	}

	@Test
	void validationFailureListsFieldErrors() throws Exception {
		mockMvc.perform(post("/test/adjustments").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sku\": \"\", \"quantity\": -5}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(2))
			.andExpect(jsonPath("$.errors[?(@.field == 'sku')]").exists())
			.andExpect(jsonPath("$.errors[?(@.field == 'quantity')]").exists());
	}

	@Test
	void malformedJsonIsBadRequest() throws Exception {
		mockMvc.perform(post("/test/adjustments").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void unknownRouteIsNotFound() throws Exception {
		mockMvc.perform(get("/test/does-not-exist"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.detail").value("No endpoint GET /test/does-not-exist"))
			.andExpect(jsonPath("$.instance").value("/test/does-not-exist"));
	}

	@Test
	void wrongMethodIsMethodNotAllowed() throws Exception {
		mockMvc.perform(post("/test/insufficient-inventory"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unexpectedExceptionIsGeneric500WithoutLeakingDetails() throws Exception {
		mockMvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
	}

	record AdjustmentRequest(@NotBlank String sku, @Positive int quantity) {
	}

	@RestController
	static class ErrorTriggerController {

		@GetMapping("/test/insufficient-inventory")
		void insufficientInventory() {
			throw new DomainException(ErrorCode.INSUFFICIENT_INVENTORY, "Only 12 of 40 units available",
					Map.of("sku", "NK-AM-001", "available", 12));
		}

		@PostMapping("/test/adjustments")
		void adjust(@Valid @RequestBody AdjustmentRequest request) {
		}

		@GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("secret internal detail");
		}

	}

}
