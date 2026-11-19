-- Fase 5 del piano metrologico, passo 5.4: import del DCC XML esterno.
--
-- Un DCC firmato da un laboratorio riconosciuto salta la verifica a quattro occhi: non c'e'
-- trascrizione da controllare, e la firma garantisce che i dati sono quelli del laboratorio.
-- Se pero' manca la funzione (la ricava CERT_FIT dai punti), la verifica aspetta il motore:
-- qui resta scritto il motivo per cui, appena la funzione arriva, il certificato diventa
-- verificato da solo.

ALTER TABLE metrology.certificate ADD COLUMN auto_verify_reason varchar(300);
