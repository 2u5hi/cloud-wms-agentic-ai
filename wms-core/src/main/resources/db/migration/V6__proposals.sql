-- Proposals: a fix the agent suggests and a human decides on.
--
-- The agent never changes the warehouse. It writes a proposal here, with the evidence it read and the
-- command it wants run; approving it runs that command through the same service the console uses, in the
-- approving transaction. The row is the audit trail: what was suggested, on what evidence, who decided,
-- and what happened when it ran.

CREATE TABLE proposal (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    wave_id          BIGINT       NULL,
    kind             VARCHAR(40)  NOT NULL,
    payload          JSON         NOT NULL,
    rationale        TEXT         NOT NULL,
    evidence         JSON         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    created_by_type  VARCHAR(20)  NOT NULL,
    created_by_id    VARCHAR(60)  NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_by_type  VARCHAR(20)  NULL,
    decided_by_id    VARCHAR(60)  NULL,
    decided_at       TIMESTAMP(6) NULL,
    decision_note    VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_proposal_wave FOREIGN KEY (wave_id) REFERENCES wave (id),
    CONSTRAINT ck_proposal_kind CHECK (kind IN ('REASSIGN_TASK')),
    CONSTRAINT ck_proposal_status CHECK (status IN ('PROPOSED', 'EXECUTED', 'REJECTED', 'FAILED')),
    -- A decision must record who made it, and an undecided proposal must not claim one.
    CONSTRAINT ck_proposal_decided CHECK (
        (status = 'PROPOSED' AND decided_at IS NULL AND decided_by_id IS NULL)
        OR (status <> 'PROPOSED' AND decided_at IS NOT NULL AND decided_by_id IS NOT NULL))
);

CREATE INDEX ix_proposal_wave ON proposal (wave_id, status);
CREATE INDEX ix_proposal_open ON proposal (status, id);
