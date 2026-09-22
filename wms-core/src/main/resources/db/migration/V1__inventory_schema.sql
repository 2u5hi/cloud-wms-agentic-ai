-- Master data and inventory.
--
-- Stock is tracked per (location, SKU). inventory_balance is the current state; inventory_txn is the
-- append-only ledger of every change. Both are written in the same transaction, and for any
-- (location, SKU) the ledger nets to the balance's on_hand:
--     on_hand = SUM(quantity WHERE to_location = loc) - SUM(quantity WHERE from_location = loc)

CREATE TABLE zone (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_zone_code UNIQUE (code)
);

-- A zone can mix location types: forward-pick slots at ground level with reserve storage above them.
CREATE TABLE location (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    code                VARCHAR(30)  NOT NULL,
    zone_id             BIGINT       NOT NULL,
    type                VARCHAR(20)  NOT NULL,
    pick_sequence       INT          NULL,
    capacity_units      INT          NULL,
    required_equipment  VARCHAR(30)  NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_location_code UNIQUE (code),
    CONSTRAINT fk_location_zone FOREIGN KEY (zone_id) REFERENCES zone (id),
    CONSTRAINT ck_location_type CHECK (type IN ('FORWARD_PICK', 'RESERVE', 'STAGING', 'PACK', 'DOCK')),
    CONSTRAINT ck_location_capacity CHECK (capacity_units IS NULL OR capacity_units > 0)
);

CREATE INDEX ix_location_zone_type ON location (zone_id, type);

CREATE TABLE sku (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(40)  NOT NULL,
    description     VARCHAR(200) NOT NULL,
    uom             VARCHAR(10)  NOT NULL DEFAULT 'EA',
    velocity_class  CHAR(1)      NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_sku_code UNIQUE (code),
    CONSTRAINT ck_sku_velocity CHECK (velocity_class IS NULL OR velocity_class IN ('A', 'B', 'C'))
);

-- Forward-pick slotting: which SKU lives in a pick location, and the replenishment thresholds.
-- One SKU per slot. That the location is FORWARD_PICK is enforced by the application.
CREATE TABLE pick_slot (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    location_id  BIGINT       NOT NULL,
    sku_id       BIGINT       NOT NULL,
    min_qty      INT          NOT NULL,
    max_qty      INT          NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pick_slot_location UNIQUE (location_id),
    CONSTRAINT fk_pick_slot_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_pick_slot_sku FOREIGN KEY (sku_id) REFERENCES sku (id),
    CONSTRAINT ck_pick_slot_thresholds CHECK (min_qty >= 0 AND max_qty > min_qty)
);

CREATE INDEX ix_pick_slot_sku ON pick_slot (sku_id);

-- Current stock. available = on_hand - allocated. The CHECKs are the last line of defence against
-- negative stock and over-allocation, whatever the application does.
CREATE TABLE inventory_balance (
    location_id  BIGINT       NOT NULL,
    sku_id       BIGINT       NOT NULL,
    on_hand      INT          NOT NULL DEFAULT 0,
    allocated    INT          NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (location_id, sku_id),
    CONSTRAINT fk_balance_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_balance_sku FOREIGN KEY (sku_id) REFERENCES sku (id),
    CONSTRAINT ck_balance_on_hand CHECK (on_hand >= 0),
    CONSTRAINT ck_balance_allocated CHECK (allocated >= 0 AND allocated <= on_hand)
);

CREATE INDEX ix_balance_sku ON inventory_balance (sku_id);

-- Append-only ledger. quantity is always positive; direction comes from the locations:
-- stock leaves from_location and arrives at to_location.
--   RECEIPT: to only        PICK: from only        MOVE: both
--   ADJUST / COUNT_VARIANCE: to for an increase, from for a decrease
CREATE TABLE inventory_txn (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    type              VARCHAR(20)  NOT NULL,
    sku_id            BIGINT       NOT NULL,
    from_location_id  BIGINT       NULL,
    to_location_id    BIGINT       NULL,
    quantity          INT          NOT NULL,
    reason            VARCHAR(200) NULL,
    reference_type    VARCHAR(30)  NULL,
    reference_id      VARCHAR(64)  NULL,
    actor_type        VARCHAR(20)  NOT NULL,
    actor_id          VARCHAR(64)  NOT NULL,
    occurred_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_txn_sku FOREIGN KEY (sku_id) REFERENCES sku (id),
    CONSTRAINT fk_txn_from_location FOREIGN KEY (from_location_id) REFERENCES location (id),
    CONSTRAINT fk_txn_to_location FOREIGN KEY (to_location_id) REFERENCES location (id),
    CONSTRAINT ck_txn_type CHECK (type IN ('RECEIPT', 'PICK', 'MOVE', 'ADJUST', 'COUNT_VARIANCE')),
    CONSTRAINT ck_txn_quantity CHECK (quantity > 0),
    CONSTRAINT ck_txn_locations CHECK (
        (from_location_id IS NOT NULL OR to_location_id IS NOT NULL)
        AND (from_location_id IS NULL OR to_location_id IS NULL OR from_location_id <> to_location_id)),
    CONSTRAINT ck_txn_actor_type CHECK (actor_type IN ('HUMAN', 'AGENT', 'SYSTEM', 'INTEGRATION'))
);

CREATE INDEX ix_txn_sku_time ON inventory_txn (sku_id, occurred_at);
CREATE INDEX ix_txn_from_location ON inventory_txn (from_location_id, sku_id);
CREATE INDEX ix_txn_to_location ON inventory_txn (to_location_id, sku_id);
CREATE INDEX ix_txn_reference ON inventory_txn (reference_type, reference_id);

-- Corrections are new ledger rows, never edits.
CREATE TRIGGER trg_inventory_txn_no_update BEFORE UPDATE ON inventory_txn FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inventory_txn is append-only';

CREATE TRIGGER trg_inventory_txn_no_delete BEFORE DELETE ON inventory_txn FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inventory_txn is append-only';
