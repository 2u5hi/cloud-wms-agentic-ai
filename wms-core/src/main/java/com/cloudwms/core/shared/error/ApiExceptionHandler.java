package com.cloudwms.core.shared.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as RFC 9457 application/problem+json with a stable {@code code} property.
 * Framework errors (404, 405, malformed JSON, ...) are handled by the base class and get a code
 * derived from their HTTP status.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
		ProblemDetail problem = problem(ex.code(), ex.getMessage(), request.getRequestURI());
		ex.properties().forEach(problem::setProperty);
		return ResponseEntity.status(ex.code().status()).body(problem);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
		ProblemDetail problem = problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.",
				request.getRequestURI());
		return ResponseEntity.internalServerError().body(problem);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setType(ErrorCode.VALIDATION_FAILED.type());
		problem.setTitle(ErrorCode.VALIDATION_FAILED.title());
		problem.setDetail("One or more fields are invalid.");
		problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message",
					Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
			.toList();
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setDetail("No endpoint " + ex.getHttpMethod() + " /" + ex.getResourcePath());
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem) {
			if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
				problem.setProperty("code", codeFor(statusCode));
			}
			if (problem.getInstance() == null && request instanceof ServletWebRequest servletRequest) {
				problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
			}
		}
		return response;
	}

	private static ProblemDetail problem(ErrorCode code, String detail, String path) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setType(code.type());
		problem.setTitle(code.title());
		problem.setInstance(URI.create(path));
		problem.setProperty("code", code.name());
		return problem;
	}

	private static String codeFor(HttpStatusCode statusCode) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		return status != null ? status.name() : "HTTP_" + statusCode.value();
	}

}
