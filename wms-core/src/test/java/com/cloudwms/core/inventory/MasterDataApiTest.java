package com.cloudwms.core.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.cloudwms.core.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class MasterDataApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	InventoryFixtures fixtures;
	long zone;
	String zoneCode;

	@BeforeEach
	void setUp() {
		fixtures = new InventoryFixtures(jdbc);
		zone = fixtures.zone();
		zoneCode = fixtures.code("zone", zone);
	}

	@Test
	void getsAForwardPickLocationWithItsSlot() throws Exception {
		long location = fixtures.location(zone, "FORWARD_PICK", 120);
		long sku = fixtures.sku();
		fixtures.pickSlot(location, sku, 10, 60);
		String code = fixtures.code("location", location);

		mockMvc.perform(get("/api/v1/locations/{code}", code))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.zone").value(zoneCode))
			.andExpect(jsonPath("$.type").value("FORWARD_PICK"))
			.andExpect(jsonPath("$.pickSequence").value(120))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.pickSlot.sku").value(fixtures.code("sku", sku)))
			.andExpect(jsonPath("$.pickSlot.minQty").value(10))
			.andExpect(jsonPath("$.pickSlot.maxQty").value(60));
	}

	@Test
	void reserveLocationHasNoSlot() throws Exception {
		String code = fixtures.code("location", fixtures.location(zone, "RESERVE"));

		mockMvc.perform(get("/api/v1/locations/{code}", code))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pickSlot").doesNotExist());
	}

	@Test
	void filtersLocationsByZoneAndType() throws Exception {
		fixtures.location(zone, "FORWARD_PICK");
		fixtures.location(zone, "RESERVE");
		fixtures.location(zone, "RESERVE");

		mockMvc.perform(get("/api/v1/locations").param("zone", zoneCode).param("type", "RESERVE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[*].type").value(org.hamcrest.Matchers.everyItem(
					org.hamcrest.Matchers.is("RESERVE"))))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void paginatesWithoutGapsOrDuplicates() throws Exception {
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(fixtures.code("location", fixtures.location(zone, "RESERVE")));
		}

		List<String> seen = new ArrayList<>();
		List<Integer> pageSizes = new ArrayList<>();
		String cursor = null;
		do {
			var request = get("/api/v1/locations").param("zone", zoneCode).param("limit", "2");
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			List<String> codes = JsonPath.read(body, "$.items[*].code");
			seen.addAll(codes);
			pageSizes.add(codes.size());
			cursor = JsonPath.read(body, "$.nextCursor");
		}
		while (cursor != null);

		assertThat(pageSizes).containsExactly(2, 2, 1);
		assertThat(seen).containsExactlyElementsOf(created);
	}

	@Test
	void getsASku() throws Exception {
		String code = fixtures.code("sku", fixtures.sku());

		mockMvc.perform(get("/api/v1/skus/{code}", code))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.uom").value("EA"));
	}

	@Test
	void unknownCodesAreNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/skus/{code}", "NO-SUCH-SKU"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.detail").value("SKU NO-SUCH-SKU does not exist"));
		mockMvc.perform(get("/api/v1/locations/{code}", "NO-SUCH-LOCATION"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Location NO-SUCH-LOCATION does not exist"));
	}

	@Test
	void invalidQueryParametersAreValidationErrors() throws Exception {
		mockMvc.perform(get("/api/v1/locations").param("limit", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("limit"));
		mockMvc.perform(get("/api/v1/locations").param("limit", "501"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mockMvc.perform(get("/api/v1/locations").param("type", "ROOF"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("type"));
		mockMvc.perform(get("/api/v1/locations").param("cursor", "not-a-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

}
