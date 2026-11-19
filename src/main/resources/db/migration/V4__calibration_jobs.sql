-- Fase 3 del piano (passo 3.3): la coda verso il motore di taratura [S §3, §6.1].
--
-- Il job e' la promessa di un risultato: nasce QUEUED quando parte su `calibration-jobs`,
-- passa a DONE o FAILED quando arriva la risposta su `calibration-results`. L'id e' quello
-- del messaggio, cosi' una consegna ripetuta del risultato trova il job gia' chiuso e non
-- produce un secondo risultato.

CREATE TABLE metrology.calibration_job (
    id              uuid         PRIMARY KEY,
    kind            varchar(16)  NOT NULL
        CHECK (kind IN ('SESSION_FIT', 'VERIFICATION', 'CERT_FIT', 'DOCUMENTS')),
    session_id      bigint       REFERENCES metrology.calibration_session(id),
    certificate_id  bigint       REFERENCES metrology.certificate(id),
    status          varchar(16)  NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    -- Il messaggio inviato e quello ricevuto, interi: sono la traccia di cosa il motore ha
    -- visto e di cosa ha risposto.
    request         jsonb        NOT NULL,
    response        jsonb,
    requested_by    varchar(64)  NOT NULL,
    requested_at    timestamptz  NOT NULL DEFAULT now(),
    started_at      timestamptz,
    finished_at     timestamptz,
    engine_version  varchar(64),
    error           text,
    sent_at         timestamptz
);
CREATE INDEX idx_calibration_job_session ON metrology.calibration_job (session_id);
CREATE INDEX idx_calibration_job_status ON metrology.calibration_job (status);
