package com.cloudwms.core.shared.idempotency;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotencyStore {

	private final JdbcClient jdbc;

	IdempotencyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Claims the key inside the current transaction. Returns false if it already exists. If another request
	 * holds the same key in an open transaction, this blocks on its row lock until that request commits
	 * (then returns false) or rolls back (then claims it).
	 */
	boolean claim(String principal, String key, String requestHash) {
		try {
			jdbc.sql("INSERT INTO idempotency_key (principal, idem_key, request_hash) VALUES (?, ?, ?)")
				.params(principal, key, requestHash)
				.update();
			return true;
		}
		catch (DuplicateKeyException ex) {
			return false;
		}
	}

	void complete(String principal, String key, StoredResponse response) {
		jdbc.sql("""
				UPDATE idempotency_key
				SET response_status = ?, response_content_type = ?, response_location = ?, response_body = ?
				WHERE principal = ? AND idem_key = ?""")
			.params(response.status(), response.contentType(), response.location(), response.body(), principal, key)
			.update();
	}

	Optional<StoredKey> find(String principal, String key) {
		return jdbc.sql("""
				SELECT request_hash, response_status, response_content_type, response_location, response_body
				FROM idempotency_key WHERE principal = ? AND idem_key = ?""")
			.params(principal, key)
			.query((rs, n) -> new StoredKey(rs.getString("request_hash"),
					new StoredResponse(rs.getInt("response_status"), rs.getString("response_content_type"),
							rs.getString("response_location"), rs.getBytes("response_body"))))
			.optional();
	}

	record StoredKey(String requestHash, StoredResponse response) {
	}

	record StoredResponse(int status, String contentType, String location, byte[] body) {
	}

}
