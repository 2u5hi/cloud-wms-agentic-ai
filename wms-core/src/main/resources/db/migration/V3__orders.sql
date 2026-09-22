-- Customer orders from the host system (ERP / order management).
--
-- Holds are a flag, not a status: an order can be held at any point before packing, and releasing the
-- hold returns it to exactly where it was. Short allocation is derived from the lines, not stored.

CREATE TABLE orders (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    external_ref       VARCHAR(64)  NOT NULL,
    customer           VARCHAR(100) NOT NULL,
    priority           TINYINT      NOT NULL DEFAULT 3,
    status             VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    carrier            VARCHAR(30)  NOT NULL,
    carrier_cutoff_at  TIMESTAMP(6) NOT NULL,
    on_hold            BOOLEAN      NOT NULL DEFAULT FALSE,
    hold_reason        VARCHAR(200) NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- The host's order number. Re-importing the same order is detected here.
    CONSTRAINT uq_orders_external_ref UNIQUE (external_ref),
    CONSTRAINT ck_orders_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_orders_status CHECK (status IN
        ('RECEIVED', 'ALLOCATED', 'RELEASED', 'PICKING', 'PICKED', 'PACKED', 'SHIPPED', 'CANCELLED')),
    CONSTRAINT ck_orders_hold CHECK ((on_hold AND hold_reason IS NOT NULL) OR (NOT on_hold AND hold_reason IS NULL))
);

-- Wave planning selects open orders by status, earliest cutoff first.
CREATE INDEX ix_orders_status_cutoff ON orders (status, carrier_cutoff_at);

CREATE TABLE order_line (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL,
    line_no         INT          NOT NULL,
    sku_id          BIGINT       NOT NULL,
    qty_ordered     INT          NOT NULL,
    qty_allocated   INT          NOT NULL DEFAULT 0,
    qty_picked      INT          NOT NULL DEFAULT 0,
    qty_shipped     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_line UNIQUE (order_id, line_no),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_line_sku FOREIGN KEY (sku_id) REFERENCES sku (id),
    CONSTRAINT ck_order_line_quantities CHECK (
        qty_ordered > 0
        AND qty_allocated BETWEEN 0 AND qty_ordered
        AND qty_picked BETWEEN 0 AND qty_allocated
        AND qty_shipped BETWEEN 0 AND qty_picked)
);

CREATE INDEX ix_order_line_sku ON order_line (sku_id);
