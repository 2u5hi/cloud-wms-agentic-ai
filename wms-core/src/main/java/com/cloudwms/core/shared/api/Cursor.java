package com.cloudwms.core.shared.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cloudwms.core.shared.error.DomainException;
import com.cloudwms.core.shared.error.ErrorCode;

/**
 * Keyset pagination. The cursor is the sort key of the last row on the previous page (one id, or
 * several for composite keys), base64-encoded so clients treat it as opaque and don't build it by hand.
 */
public final class Cursor {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 500;

	private Cursor() {
	}

	/** The id to continue after; 0 for the first page. */
	public static long decode(String cursor) {
		return decode(cursor, 1)[0];
	}

	/** The composite key to continue after; all zeros for the first page. */
	public static long[] decode(String cursor, int parts) {
		if (cursor == null || cursor.isBlank()) {
			return new long[parts];
		}
		try {
			String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
			if (values.length != parts) {
				throw new IllegalArgumentException();
			}
			long[] key = Arrays.stream(values).mapToLong(Long::parseLong).toArray();
			if (Arrays.stream(key).anyMatch(value -> value < 0)) {
				throw new IllegalArgumentException();
			}
			return key;
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
		return pageByKey(rows, limit, row -> new long[] { id.apply(row) }, view);
	}

	/** Like {@link #page} for rows ordered by a composite key. */
	public static <R, T> Page<T> pageByKey(List<R> rows, int limit, Function<R, long[]> key, Function<R, T> view) {
		boolean hasMore = rows.size() > limit;
		List<R> pageRows = hasMore ? rows.subList(0, limit) : rows;
		String next = hasMore ? encode(key.apply(pageRows.getLast())) : null;
		return new Page<>(pageRows.stream().map(view).toList(), next);
	}

	static String encode(long... key) {
		String joined = Arrays.stream(key).mapToObj(Long::toString).collect(Collectors.joining(":"));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
	}

}
