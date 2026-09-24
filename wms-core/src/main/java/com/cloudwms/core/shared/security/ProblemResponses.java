package com.cloudwms.core.shared.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.cloudwms.core.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 as the same RFC 9457 problem+json every other error uses, with a stable {@code code}, so the
 * console and the agent handle them like any other failure. The messages say what to do, not just what failed.
 */
class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper json;

	ProblemResponses(JsonMapper json) {
		this.json = json;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		String detail = request.getHeader("Authorization") == null
				? "Sign in as a supervisor to do this"
				: "Those credentials were not recognised";
		write(request, response, ErrorCode.UNAUTHENTICATED, HttpServletResponse.SC_UNAUTHORIZED, detail);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		boolean agent = SecurityContextHolder.getContext().getAuthentication() != null
				&& SecurityContextHolder.getContext()
					.getAuthentication()
					.getAuthorities()
					.stream()
					.anyMatch(authority -> authority.getAuthority().equals("ROLE_" + Role.AGENT.name()));
		String detail = agent && HttpMethod.POST.matches(request.getMethod())
				? "The agent can propose fixes but not run them; a supervisor approves"
				: "Your role cannot do this";
		write(request, response, ErrorCode.FORBIDDEN, HttpServletResponse.SC_FORBIDDEN, detail);
	}

	private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, int status,
			String detail) throws IOException {
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", code.type().toString());
		problem.put("title", code.title());
		problem.put("status", status);
		problem.put("detail", detail);
		problem.put("instance", request.getRequestURI());
		problem.put("code", code.name());
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), problem);
	}

}
