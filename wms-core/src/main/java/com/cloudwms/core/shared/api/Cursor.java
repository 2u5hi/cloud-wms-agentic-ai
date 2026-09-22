package com.cloudwms.core.shared.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;

/**
 * Keyset pagination over an ascending numeric id. The cursor is the last id of the previous page,
 * base64-encoded so clients treat it as opaque and don't build them by hand.
 */
public final class Cursor {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 500;

	private Cursor() {
	}

	/** The id to continue after; 0 for the first page. */
	public static long decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return 0;
		}
		try {
			long id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
			if (id < 0) {
				throw new NumberFormatException();
			}
			return id;
		}
		catch (IllegalArgumentException ex) {
			throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid cursor", Map.of("cursor", cursor));
		}
	}

	/**
	 * Builds a page from rows fetched with {@code LIMIT limit + 1}: the extra row, if present, only signals
	 * that another page exists.
	 */
	public static <R, T> Page<T> page(List<R> rows, int limit, Function<R, Long> id, Function<R, T> view) {
		boolean hasMore = rows.size() > limit;
		List<R> pageRows = hasMore ? rows.subList(0, limit) : rows;
		String next = hasMore ? encode(id.apply(pageRows.getLast())) : null;
		return new Page<>(pageRows.stream().map(view).toList(), next);
	}

	static String encode(long id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
	}

}
