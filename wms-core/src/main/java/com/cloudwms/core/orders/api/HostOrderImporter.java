package com.cloudwms.core.orders.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import com.cloudwms.core.orders.OrderService;
import com.cloudwms.core.orders.api.HostOrderMessages.FieldError;
import com.cloudwms.core.orders.api.HostOrderMessages.HostOrder;
import com.cloudwms.core.orders.api.HostOrderMessages.HostOrderLine;
import com.cloudwms.core.orders.api.HostOrderMessages.ImportResult;
import com.cloudwms.core.orders.api.HostOrderMessages.OrderImportRequest;
import com.cloudwms.core.orders.api.HostOrderMessages.OrderImportResponse;
import com.cloudwms.core.orders.api.HostOrderMessages.OrderResult;
import com.cloudwms.core.orders.domain.Order;
import com.cloudwms.core.orders.domain.OrderLine;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Imports a batch of host orders, each independently: every order is validated in full (fields, line
 * numbers, SKUs) before anything is written, and a failure affects only that order.
 */
@Component
class HostOrderImporter {

	static final int DEFAULT_PRIORITY = 3;

	private final OrderService orders;
	private final Validator validator;

	HostOrderImporter(OrderService orders, Validator validator) {
		this.orders = orders;
		this.validator = validator;
	}

	OrderImportResponse importBatch(OrderImportRequest request) {
		List<HostOrder> batch = request.orders();
		Map<String, Long> skuIds = orders.skuIds(batch.stream()
			.filter(Objects::nonNull)
			.filter(order -> order.lines() != null)
			.flatMap(order -> order.lines().stream())
			.filter(line -> line != null && line.sku() != null)
			.map(HostOrderLine::sku)
			.distinct()
			.toList());
		Set<String> existing = orders.existingRefs(batch.stream()
			.filter(order -> order != null && order.externalRef() != null)
			.map(HostOrder::externalRef)
			.distinct()
			.toList());

		// Insert in external-reference order, the order of the unique index. Concurrent imports of overlapping
		// orders then take their locks in the same sequence and cannot deadlock (as with inventory rows, ADR 0005).
		// Results are still reported in batch order.
		Integer[] processingOrder = IntStream.range(0, batch.size()).boxed().toArray(Integer[]::new);
		Arrays.sort(processingOrder, Comparator.comparing((Integer i) -> sortKey(batch.get(i))).thenComparing(i -> i));
		Set<String> acceptedInBatch = new HashSet<>();
		OrderResult[] results = new OrderResult[batch.size()];
		for (int index : processingOrder) {
			results[index] = importOne(batch.get(index), skuIds, existing, acceptedInBatch);
		}
		return toResponse(List.of(results));
	}

	private static String sortKey(HostOrder order) {
		return order == null || order.externalRef() == null ? "" : order.externalRef();
	}

	private static OrderImportResponse toResponse(List<OrderResult> results) {
		return new OrderImportResponse(count(results, ImportResult.ACCEPTED), count(results, ImportResult.DUPLICATE),
				count(results, ImportResult.REJECTED), results);
	}

	private OrderResult importOne(HostOrder order, Map<String, Long> skuIds, Set<String> existing,
			Set<String> acceptedInBatch) {
		if (order == null) {
			return new OrderResult(null, ImportResult.REJECTED, List.of(new FieldError("order", "must not be null")));
		}
		List<FieldError> errors = validate(order, skuIds);
		if (!errors.isEmpty()) {
			return new OrderResult(order.externalRef(), ImportResult.REJECTED, errors);
		}
		if (existing.contains(order.externalRef()) || acceptedInBatch.contains(order.externalRef())) {
			return new OrderResult(order.externalRef(), ImportResult.DUPLICATE, List.of());
		}
		Order received = Order.received(order.externalRef(), order.customer(),
				order.priority() == null ? DEFAULT_PRIORITY : order.priority(), order.carrier(),
				order.carrierCutoffAt(), order.lines()
					.stream()
					.map(line -> OrderLine.of(line.lineNo(), skuIds.get(line.sku()), line.quantity()))
					.toList());
		try {
			orders.receive(received);
		}
		catch (DuplicateKeyException ex) {
			// Imported by a concurrent request since we checked; only this order's savepoint rolled back.
			return new OrderResult(order.externalRef(), ImportResult.DUPLICATE, List.of());
		}
		acceptedInBatch.add(order.externalRef());
		return new OrderResult(order.externalRef(), ImportResult.ACCEPTED, List.of());
	}

	private List<FieldError> validate(HostOrder order, Map<String, Long> skuIds) {
		List<FieldError> errors = new ArrayList<>(validator.validate(order)
			.stream()
			.map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
			.toList());
		if (order.lines() != null) {
			Set<Integer> lineNumbers = new HashSet<>();
			for (int i = 0; i < order.lines().size(); i++) {
				HostOrderLine line = order.lines().get(i);
				if (line == null) {
					errors.add(new FieldError("lines[%d]".formatted(i), "must not be null"));
					continue;
				}
				if (!lineNumbers.add(line.lineNo())) {
					errors.add(new FieldError("lines[%d].lineNo".formatted(i), "duplicate line number " + line.lineNo()));
				}
				if (line.sku() != null && !line.sku().isBlank() && !skuIds.containsKey(line.sku())) {
					errors.add(new FieldError("lines[%d].sku".formatted(i), "unknown SKU " + line.sku()));
				}
			}
		}
		errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::message));
		return errors;
	}

	private static int count(List<OrderResult> results, ImportResult result) {
		return (int) results.stream().filter(r -> r.result() == result).count();
	}

}
