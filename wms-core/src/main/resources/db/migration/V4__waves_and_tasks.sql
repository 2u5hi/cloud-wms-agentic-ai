-- Waves, allocations, and the tasks they generate.
--
-- Stock is allocated at the location that physically holds it. When forward-pick is short, the plan
-- allocates in reserve and creates a REPLENISH task (reserve -> forward slot) plus a PICK task that waits
-- on it; completing the replenishment moves the allocation along with the stock. This keeps
-- allocated <= on_hand true at every moment (V1 ck_balance_allocated).

-- One row, locked with SELECT ... FOR UPDATE while a wave is being planned. Planning reads the open orders
-- and free stock and then writes; two planners at once could hand the same order to two waves. A row lock
-- is held for exactly the planning transaction and released by its commit, so the next planner always reads
-- the previous one's results. Picking and execution are unaffected.
CREATE TABLE planning_lock (
    id    TINYINT     NOT NULL,
    name  VARCHAR(30) NOT NULL,
    PRIMARY KEY (id)
);

INSERT INTO planning_lock (id, name) VALUES (1, 'wave_planning');

CREATE TABLE wave (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',
    planned_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    released_at   TIMESTAMP(6) NULL,
    completed_at  TIMESTAMP(6) NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_wave_status CHECK (status IN ('PLANNED', 'RELEASED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

-- An order belongs to at most one live wave; cancelling a wave deletes its rows.
CREATE TABLE wave_order (
    wave_id   BIGINT NOT NULL,
    order_id  BIGINT NOT NULL,
    PRIMARY KEY (wave_id, order_id),
    CONSTRAINT uq_wave_order_order UNIQUE (order_id),
    CONSTRAINT fk_wave_order_wave FOREIGN KEY (wave_id) REFERENCES wave (id),
    CONSTRAINT fk_wave_order_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

-- Stock promised to one order line, held at the location that currently has it.
CREATE TABLE allocation (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    order_line_id  BIGINT      NOT NULL,
    wave_id        BIGINT      NOT NULL,
    sku_id         BIGINT      NOT NULL,
    location_id    BIGINT      NOT NULL,
    quantity       INT         NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_allocation_line FOREIGN KEY (order_line_id) REFERENCES order_line (id),
    CONSTRAINT fk_allocation_wave FOREIGN KEY (wave_id) REFERENCES wave (id),
    CONSTRAINT fk_allocation_sku FOREIGN KEY (sku_id) REFERENCES sku (id),
    CONSTRAINT fk_allocation_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT ck_allocation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_allocation_status CHECK (status IN ('ACTIVE', 'PICKED', 'CANCELLED'))
);

CREATE INDEX ix_allocation_wave ON allocation (wave_id);
CREATE INDEX ix_allocation_line ON allocation (order_line_id);

-- Work for the floor. A PICK that needs stock brought forward WAITs on its REPLENISH task.
CREATE TABLE task (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    type                VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'READY',
    priority            TINYINT      NOT NULL DEFAULT 3,
    wave_id             BIGINT       NULL,
    allocation_id       BIGINT       NULL,
    depends_on_task_id  BIGINT       NULL,
    sku_id              BIGINT       NOT NULL,
    from_location_id    BIGINT       NULL,
    to_location_id      BIGINT       NULL,
    quantity            INT          NOT NULL,
    zone_id             BIGINT       NULL,
    sequence            INT          NULL,
    required_equipment  VARCHAR(30)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_task_wave FOREIGN KEY (wave_id) REFERENCES wave (id),
    CONSTRAINT fk_task_allocation FOREIGN KEY (allocation_id) REFERENCES allocation (id),
    CONSTRAINT fk_task_depends_on FOREIGN KEY (depends_on_task_id) REFERENCES task (id),
    CONSTRAINT fk_task_sku FOREIGN KEY (sku_id) REFERENCES sku (id),
    CONSTRAINT fk_task_from_location FOREIGN KEY (from_location_id) REFERENCES location (id),
    CONSTRAINT fk_task_to_location FOREIGN KEY (to_location_id) REFERENCES location (id),
    CONSTRAINT fk_task_zone FOREIGN KEY (zone_id) REFERENCES zone (id),
    CONSTRAINT ck_task_type CHECK (type IN ('PICK', 'REPLENISH', 'COUNT')),
    CONSTRAINT ck_task_status CHECK (status IN
        ('WAITING', 'READY', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_task_quantity CHECK (quantity > 0),
    CONSTRAINT ck_task_locations CHECK (
        (from_location_id IS NOT NULL OR to_location_id IS NOT NULL)
        AND (from_location_id IS NULL OR to_location_id IS NULL OR from_location_id <> to_location_id))
);

-- Workers claim the next ready task in their zone, in pick-path order.
CREATE INDEX ix_task_claim ON task (status, zone_id, priority, sequence);
CREATE INDEX ix_task_wave_status ON task (wave_id, status);
CREATE INDEX ix_task_depends_on ON task (depends_on_task_id);
