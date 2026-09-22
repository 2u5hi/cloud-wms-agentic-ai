package com.cloudwms.core.shared.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.cloudwms.core.shared.actor.CurrentActor;
import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;
import com.cloudwms.core.shared.idempotency.IdempotencyStore.StoredKey;
import com.cloudwms.core.shared.idempotency.IdempotencyStore.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes POST commands safe to retry. The request runs inside one transaction that first claims the
 * {@code Idempotency-Key}; the command's own writes join that transaction. On a 2xx response the key and
 * the response are committed together with the command. On any other response everything rolls back,
 * so a failed request does not consume its key.
 *
 * <p>A retry with the same key and the same request replays the stored response (with
 * {@code Idempotent-Replayed: true}) without running the command again. The same key with a different
 * request is rejected with IDEMPOTENCY_KEY_REUSED.
 */
class IdempotencyFilter extends OncePerRequestFilter {

	static final String KEY_HEADER = "Idempotency-Key";
	static final String REPLAYED_HEADER = "Idempotent-Replayed";
	static final int MAX_BODY_BYTES = 1024 * 1024;
	private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{1,100}");

	private final IdempotencyStore store;
	private final TransactionTemplate transaction;
	private final CurrentActor actor;
	private final HandlerExceptionResolver errors;

	IdempotencyFilter(IdempotencyStore store, TransactionTemplate transaction, CurrentActor actor,
			HandlerExceptionResolver errors) {
		this.store = store;
		this.transaction = transaction;
		this.actor = actor;
		this.errors = errors;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String key = request.getHeader(KEY_HEADER);
		if (key == null || !VALID_KEY.matcher(key).matches()) {
			reject(request, response, invalid("is required: 1-100 characters of letters, digits, '_', '-', '.', ':'"));
			return;
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			reject(request, response, new DomainException(ErrorCode.VALIDATION_FAILED, "Request body is too large",
					Map.of("maxBytes", MAX_BODY_BYTES)));
			return;
		}
		String principal = actor.get().id();
		String requestHash = hash(request, body);
		ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);

		Boolean executed = transaction.execute(status -> {
			if (!store.claim(principal, key, requestHash)) {
				status.setRollbackOnly();
				return false;
			}
			try {
				chain.doFilter(new CachedBodyRequest(request, body), captured);
			}
			catch (IOException | ServletException ex) {
				throw new IllegalStateException(ex);
			}
			int responseStatus = captured.getStatus();
			if (responseStatus >= 200 && responseStatus < 300) {
				store.complete(principal, key, new StoredResponse(responseStatus, captured.getContentType(),
						captured.getHeader(HttpHeaders.LOCATION), captured.getContentAsByteArray()));
			}
			else {
				status.setRollbackOnly();
			}
			return true;
		});

		if (Boolean.TRUE.equals(executed)) {
			captured.copyBodyToResponse();
			return;
		}
		StoredKey stored = store.find(principal, key).orElseThrow();
		if (!stored.requestHash().equals(requestHash)) {
			reject(request, response, new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"Idempotency key %s was already used for a different request".formatted(key),
					Map.of("idempotencyKey", key)));
			return;
		}
		replay(stored.response(), response);
	}

	private static void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
		response.setStatus(stored.status());
		if (stored.contentType() != null) {
			response.setContentType(stored.contentType());
		}
		if (stored.location() != null) {
			response.setHeader(HttpHeaders.LOCATION, stored.location());
		}
		response.setHeader(REPLAYED_HEADER, "true");
		response.getOutputStream().write(stored.body());
	}

	/** Renders the error through the normal @RestControllerAdvice, so it is the same problem+json shape. */
	private void reject(HttpServletRequest request, HttpServletResponse response, DomainException error) {
		errors.resolveException(request, response, null, error);
	}

	private static DomainException invalid(String message) {
		return new DomainException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.",
				Map.of("errors", List.of(Map.of("field", KEY_HEADER, "message", message))));
	}

	/** Identifies "the same request": method, path, query string, and body. */
	private static String hash(HttpServletRequest request, byte[] body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String target = request.getMethod() + " " + request.getRequestURI()
					+ (request.getQueryString() == null ? "" : "?" + request.getQueryString()) + "\n";
			digest.update(target.getBytes(StandardCharsets.UTF_8));
			digest.update(body);
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Lets the body be read again downstream after it was read here for hashing. */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream in = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return in.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) {
					return in.read(buffer, offset, length);
				}

				@Override
				public boolean isFinished() {
					return in.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

	}

}
