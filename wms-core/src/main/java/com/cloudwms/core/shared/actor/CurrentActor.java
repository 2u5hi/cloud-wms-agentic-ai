package com.cloudwms.core.shared.actor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is making the current request, taken from the credential the caller authenticated with (ADR 0027). This is
 * the one place that knows how; when Cognito replaces the Phase 1 tokens, only this and the security filter change.
 */
@Component
public class CurrentActor {

	/** Anonymous callers can only read, so this only ever appears on reads and in tests of the security rules. */
	public static final Actor UNAUTHENTICATED = new Actor(ActorType.HUMAN, "unauthenticated");

	public Actor get() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Actor actor) {
			return actor;
		}
		return UNAUTHENTICATED;
	}

}
