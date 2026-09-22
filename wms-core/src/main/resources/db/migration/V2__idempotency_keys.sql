-- Idempotency keys for POST commands. A row is inserted at the start of the request and completed with the
-- response in the same transaction as the command's own writes, so a key is recorded if and only if its
-- command committed. Keys are scoped to the caller (principal).
CREATE TABLE idempotency_key (
    principal              VARCHAR(64)  NOT NULL,
    idem_key               VARCHAR(100) NOT NULL,
    request_hash           CHAR(64)     NOT NULL,
    response_status        SMALLINT     NULL,
    response_content_type  VARCHAR(100) NULL,
    response_location      VARCHAR(500) NULL,
    response_body          MEDIUMBLOB   NULL,
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (principal, idem_key)
);

-- For purging old keys.
CREATE INDEX ix_idempotency_key_created ON idempotency_key (created_at);
