-- Passo 3.5 del piano metrologico: as-found e as-left come serie separate [S §6.2].
--
-- Un risultato dice da quale job viene. SESSION_FIT porta la funzione nuova e l'as-found
-- calcolato con la funzione in vigore, e chiede la decisione di regolazione; VERIFICATION porta
-- l'as-left misurato su punti indipendenti con la funzione rimasta in vigore dopo la decisione,
-- e non chiede decisioni; CERT_FIT la funzione ricavata dai punti di un certificato esterno.
--
-- Il default vale per le righe gia' scritte, che venivano tutte da SESSION_FIT.

ALTER TABLE metrology.calibration_result
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'SESSION_FIT'
        CHECK (kind IN ('SESSION_FIT', 'VERIFICATION', 'CERT_FIT'));

CREATE INDEX idx_calibration_result_session_kind ON metrology.calibration_result (session_id, kind);
