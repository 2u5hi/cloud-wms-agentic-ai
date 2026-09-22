package com.cloudwms.core.inventory.api;

import com.cloudwms.core.inventory.domain.Actor;
import com.cloudwms.core.inventory.domain.ActorType;
import org.springframework.stereotype.Component;

/**
 * Who is making the current request. Until authentication is added, every API caller is recorded as an
 * unauthenticated human; this is the single place that will read the caller from their OAuth token.
 */
@Component
class CurrentActor {

	static final Actor UNAUTHENTICATED = new Actor(ActorType.HUMAN, "unauthenticated");

	Actor get() {
		return UNAUTHENTICATED;
	}

}
