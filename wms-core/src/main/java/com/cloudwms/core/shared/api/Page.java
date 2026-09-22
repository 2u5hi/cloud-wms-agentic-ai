package com.cloudwms.core.shared.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of a list. Pass {@code nextCursor} back as {@code cursor} to get the following page;
 * it is null on the last page.
 */
public record Page<T>(List<T> items,
		@Schema(nullable = true, description = "Opaque cursor for the next page; null on the last page") String nextCursor) {

}
