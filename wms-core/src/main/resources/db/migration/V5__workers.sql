-- Workers and what they are certified to drive. A task that needs equipment can only be claimed by a
-- worker who has it, which is what makes "replenishment stalled: nobody available can reach it" possible.

CREATE TABLE worker (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code          VARCHAR(30)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    home_zone_id  BIGINT       NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_worker_code UNIQUE (code),
    CONSTRAINT fk_worker_zone FOREIGN KEY (home_zone_id) REFERENCES zone (id),
    CONSTRAINT ck_worker_status CHECK (status IN ('AVAILABLE', 'BUSY', 'BREAK', 'OFFLINE'))
);

CREATE TABLE worker_equipment (
    worker_id  BIGINT      NOT NULL,
    equipment  VARCHAR(30) NOT NULL,
    PRIMARY KEY (worker_id, equipment),
    CONSTRAINT fk_worker_equipment_worker FOREIGN KEY (worker_id) REFERENCES worker (id)
);

ALTER TABLE task
    ADD COLUMN assigned_worker_id BIGINT NULL AFTER zone_id,
    ADD CONSTRAINT fk_task_worker FOREIGN KEY (assigned_worker_id) REFERENCES worker (id);

CREATE INDEX ix_task_worker ON task (assigned_worker_id, status);
