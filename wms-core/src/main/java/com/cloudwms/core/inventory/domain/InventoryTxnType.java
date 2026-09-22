package com.cloudwms.core.inventory.domain;

public enum InventoryTxnType {

	/** Stock arrives from outside the warehouse. */
	RECEIPT,
	/** Stock leaves a location to fulfil an order. */
	PICK,
	/** Stock moves between two locations, e.g. replenishment from reserve to forward-pick. */
	MOVE,
	/** A manual correction. */
	ADJUST,
	/** A correction found by counting, e.g. during a short pick or cycle count. */
	COUNT_VARIANCE

}
