package com.cloudwms.core.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import com.cloudwms.core.shared.actor.Actor;
import com.cloudwms.core.shared.actor.ActorType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer <credential>} into an authenticated {@link Actor} with a {@link Role}. No
 * header means anonymous; a header that matches neither credential is rejected outright, so a mistyped passcode
 * says so instead of quietly falling back to read-only.
 *
 * <p>Deliberately not a Spring bean: a {@code Filter} bean would also be registered with the servlet container
 * and run twice. It lives only inside the security filter chain.
 */
class BearerTokenFilter extends OncePerRequestFilter {

	static final Actor SUPERVISOR = new Actor(ActorType.HUMAN, "demo-supervisor");
	static final Actor AGENT = new Actor(ActorType.AGENT, "ops-agent");
	private static final String PREFIX = "Bearer ";

	private final byte[] supervisorPasscode;
	private final byte[] agentToken;
	private final ProblemResponses problems;

	BearerTokenFilter(AuthProperties properties, ProblemResponses problems) {
		this.supervisorPasscode = properties.supervisorPasscode().getBytes(StandardCharsets.UTF_8);
		this.agentToken = properties.agentToken().getBytes(StandardCharsets.UTF_8);
		this.problems = problems;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header == null) {
			chain.doFilter(request, response);
			return;
		}
		byte[] presented = header.startsWith(PREFIX)
				? header.substring(PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
		// Constant-time comparisons, so response timing doesn't leak how much of a guess was right.
		if (MessageDigest.isEqual(presented, supervisorPasscode)) {
			authenticate(SUPERVISOR, Role.SUPERVISOR);
		}
		else if (MessageDigest.isEqual(presented, agentToken)) {
			authenticate(AGENT, Role.AGENT);
		}
		else {
			SecurityContextHolder.clearContext();
			problems.commence(request, response, new BadCredentialsException("Unrecognised credentials"));
			return;
		}
		chain.doFilter(request, response);
	}

	private static void authenticate(Actor actor, Role role) {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

}
