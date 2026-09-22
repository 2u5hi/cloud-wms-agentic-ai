package com.cloudwms.core.inventory.domain;

import java.util.Objects;

/** What a ledger row belongs to, for example a pick task or a cycle count. */
public record Reference(String type, String id) {

	public Reference {
		Objects.requireNonNull(type, "reference type is required");
		Objects.requireNonNull(id, "reference id is required");
	}

}
