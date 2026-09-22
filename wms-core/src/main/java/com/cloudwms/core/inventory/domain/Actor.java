package com.cloudwms.core.inventory.domain;

import java.util.Objects;

public record Actor(ActorType type, String id) {

	public Actor {
		Objects.requireNonNull(type, "actor type is required");
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("actor id is required");
		}
	}

}
