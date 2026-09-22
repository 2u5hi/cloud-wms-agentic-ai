package com.cloudwms.core.shared.actor;

import org.springframework.stereotype.Component;

/**
 * Who is making the current request. Until authentication is added, every API caller is recorded as an
 * unauthenticated human; this is the single place that will read the caller from their OAuth token.
 */
@Component
public class CurrentActor {

	public static final Actor UNAUTHENTICATED = new Actor(ActorType.HUMAN, "unauthenticated");

	public Actor get() {
		return UNAUTHENTICATED;
	}

}
