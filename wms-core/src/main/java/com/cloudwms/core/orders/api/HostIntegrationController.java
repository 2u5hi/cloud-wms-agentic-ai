package com.cloudwms.core.orders.api;

import com.cloudwms.core.orders.api.HostOrderMessages.OrderImportRequest;
import com.cloudwms.core.orders.api.HostOrderMessages.OrderImportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/host")
@Tag(name = "Host integration", description = "Inbound interface from the host system (ERP / order management)")
class HostIntegrationController {

	private final HostOrderImporter importer;

	HostIntegrationController(HostOrderImporter importer) {
		this.importer = importer;
	}

	@PostMapping("/orders")
	@Operation(operationId = "importOrders", summary = "Import a batch of orders",
			description = "Each order is processed independently and gets its own result: ACCEPTED, DUPLICATE (an order "
					+ "with this external reference already exists and was not changed), or REJECTED with field errors. "
					+ "Returns 200 whenever the batch itself is well formed, even if every order was rejected.")
	OrderImportResponse importOrders(@Valid @RequestBody OrderImportRequest request) {
		return importer.importBatch(request);
	}

}
