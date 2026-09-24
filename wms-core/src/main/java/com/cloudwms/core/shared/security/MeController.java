package com.cloudwms.core.shared.security;

import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import com.cloudwms.core.shared.actor.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Session", description = "Who the current credential belongs to")
class MeController {

	private final CurrentActor actor;

	MeController(CurrentActor actor) {
		this.actor = actor;
	}

	@GetMapping
	@Operation(operationId = "getMe", summary = "Who am I",
			description = "The actor and role behind the request's credential. Without one, the caller is anonymous "
					+ "and can only read; with an unrecognised one, the request is refused with 401.")
	MeView me() {
		Actor current = actor.get();
		return new MeView(current.type(), current.id(), role());
	}

	/** Anonymous requests carry Spring's ROLE_ANONYMOUS, which is not one of ours, so they get null. */
	private static Role role() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return null;
		}
		for (Role role : Role.values()) {
			if (authentication.getAuthorities()
				.stream()
				.anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role.name()))) {
				return role;
			}
		}
		return null;
	}

	record MeView(ActorType type, String id, @Schema(nullable = true, description = "Null when anonymous") Role role) {
	}

}
