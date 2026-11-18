-- Punto di partenza dello schema `metrology`.
--
-- Lo schema esiste gia': lo crea una volta sola db/setup/01_schema_e_utente.sql, con l'utente
-- `metrology` come proprietario. Questa migrazione non crea tabelle — le prime arrivano con la
-- fase 1 — ma fissa due regole che devono valere prima di qualunque dato.

-- Nessun altro ruolo entra nello schema per default. Chi deve leggere (per esempio un utente di
-- sola lettura per Grafana) riceve un GRANT esplicito, in una migrazione che dice perche'.
REVOKE ALL ON SCHEMA metrology FROM PUBLIC;

-- La funzione che rende immutabili documenti, registro delle azioni e certificati firmati
-- [S §5.2]. Sta qui perche' la usano tabelle di fasi diverse: ognuna aggiungera' il suo trigger
-- BEFORE UPDATE OR DELETE che la chiama.
CREATE FUNCTION metrology.reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'La tabella %.% e'' immutabile: % non consentito', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON SCHEMA metrology IS
    'Sistema qualita'' di MeasureStream: esemplari, campioni, certificati, conformita''. Gestito da metrology-service.';
