package com.cloudwms.core.shared.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionSystemException;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
		return ResponseEntity.status(statusOf(ex.code())).body(problem);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
		if (isLockConflict(ex)) {
			return lockConflict(ex, request);
		}
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
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result.getResolvableErrors()
				.stream()
				.map(error -> Map.of("field", Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "?"),
						"message", Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"))))
			.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String field = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
				: Objects.requireNonNullElse(ex.getPropertyName(), "?");
		String message = "has an invalid value '%s'".formatted(ex.getValue());
		return handleExceptionInternal(ex, validationProblem(List.of(Map.of("field", field, "message", message))), headers,
				HttpStatus.BAD_REQUEST, request);
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

	/**
	 * The database chose this transaction as a deadlock victim or a lock wait timed out. The whole
	 * transaction was rolled back, so retrying (with the same Idempotency-Key) is safe.
	 */
	private ResponseEntity<ProblemDetail> lockConflict(Exception ex, HttpServletRequest request) {
		log.warn("Lock conflict on {} {}", request.getMethod(), request.getRequestURI(), ex);
		ProblemDetail problem = problem(ErrorCode.CONCURRENCY_CONFLICT,
				"The request conflicted with a concurrent update and was rolled back. Retry it.", request.getRequestURI());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
	}

	/**
	 * Finds a lock conflict anywhere in the cause chain. When MySQL kills a deadlocked transaction it also
	 * discards open savepoints, so rolling back a nested transaction then fails and Spring reports that
	 * failure, keeping the original deadlock only as the "application exception".
	 */
	static boolean isLockConflict(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof PessimisticLockingFailureException) {
				return true;
			}
			if (current instanceof TransactionSystemException tx && tx.getApplicationException() != null
					&& isLockConflict(tx.getApplicationException())) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
		problem.setType(ErrorCode.VALIDATION_FAILED.type());
		problem.setTitle(ErrorCode.VALIDATION_FAILED.title());
		problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
		problem.setProperty("errors", errors);
		return problem;
	}

	private static ProblemDetail problem(ErrorCode code, String detail, String path) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(code), detail);
		problem.setType(code.type());
		problem.setTitle(code.title());
		problem.setInstance(URI.create(path));
		problem.setProperty("code", code.name());
		return problem;
	}

	/** Exhaustive on purpose: adding an ErrorCode without choosing its HTTP status won't compile. */
	static HttpStatus statusOf(ErrorCode code) {
		return switch (code) {
			case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case INVALID_STATE_TRANSITION, VERSION_CONFLICT, INSUFFICIENT_INVENTORY -> HttpStatus.CONFLICT;
			case PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
			case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
			case CONCURRENCY_CONFLICT -> HttpStatus.SERVICE_UNAVAILABLE;
			case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

	private static String codeFor(HttpStatusCode statusCode) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		return status != null ? status.name() : "HTTP_" + statusCode.value();
	}

}
