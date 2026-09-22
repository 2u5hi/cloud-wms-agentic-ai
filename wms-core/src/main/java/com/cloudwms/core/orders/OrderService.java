package com.cloudwms.core.orders;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.cloudwms.core.orders.domain.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	private final OrderRepository repository;

	OrderService(OrderRepository repository) {
		this.repository = repository;
	}

	/**
	 * Stores a newly received order. Runs as a nested transaction (a savepoint inside the request's
	 * transaction), so if it fails, only this order rolls back and the rest of an import batch continues.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException if an order with the same external reference
	 * already exists, e.g. imported concurrently by another request
	 */
	@Transactional(propagation = Propagation.NESTED)
	public long receive(Order order) {
		return repository.insert(order);
	}

	/** SKU code to id, for the codes that exist. */
	public Map<String, Long> skuIds(Collection<String> codes) {
		return repository.skuIds(codes);
	}

	/** Which of these external references have already been received. */
	public Set<String> existingRefs(Collection<String> externalRefs) {
		return repository.existingRefs(externalRefs);
	}

}
