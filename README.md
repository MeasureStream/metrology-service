# metrology-service

Il sistema qualità di MeasureStream: esemplari tarati, campioni di riferimento, sessioni di
taratura, certificati interni ed esterni, firma e verifica, punti di controllo, conformità,
scadenze, documenti e report.

sensor-manager resta lo strumento (decodifica, converte, dà a ogni valore la sua incertezza);
questo servizio tiene ciò che serve a dire che quello strumento è tarato. I due si parlano per
eventi Kafka e per REST, mai attraverso le tabelle dell'altro.

Il disegno completo sta nella «Specifica delle modifiche server per taratura, incertezza,
certificati e report» (17 novembre 2026); l'ordine dei lavori in `PIANO_METROLOGIA.md`.

## Stack

Kotlin 2.3, Spring Boot 4.1, Java 21, PostgreSQL 17 con schema proprio, Flyway, Kafka.

## Database

Stessa istanza di sensor-manager, ma **schema `metrology` e utente `metrology` propri**: il
servizio non ha alcun privilegio sulle tabelle di `public`. Lo schema e l'utente si creano una
volta sola con [`db/setup/01_schema_e_utente.sql`](db/setup/01_schema_e_utente.sql); da lì in
poi lo modificano solo le migrazioni Flyway in `src/main/resources/db/migration`
(`ddl-auto: validate`).

## Configurazione

| Variabile | Contenuto |
|---|---|
| `OS` | Profilo: `default`, `dev` o `raspberrypi` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres/<database>` |
| `SPRING_DATASOURCE_USERNAME` | `metrology` |
| `SPRING_DATASOURCE_PASSWORD` | La password scelta nello script di setup |
| `SENSOR_MANAGER_URL` | sensor-manager, a cui si chiede la MU con il token di chi chiama |
| `SIGNING_KEYSTORE`, `SIGNING_PASSWORD_FILE` | Keystore PKCS#12 della chiave di firma e file con la sua password |
| `SIGNING_DEV_SELF_SIGNED` | `true` solo per prova: chiave generata all'avvio |
| `SIGNING_TRUST_ANCHORS` | Cartella delle CA fidate nella verifica (`.crt`, `.pem`, `.cer`) |

La chiave di firma si prepara come in `Docs/Firma_Certificati_Metrology.md` (cartella dei
repository).

Nessun segreto sta nel repository, che è pubblico: chiavi di firma, trust store e credenziali
arrivano come variabili d'ambiente o file montati in sola lettura.

## Che cosa c'è

| Modulo | Endpoint principali |
|---|---|
| Esemplari | `GET, POST /API/metrology/items`, `GET, PUT /API/metrology/items/{id}` |
| Campioni | `GET, POST /API/metrology/references`, `PUT /API/metrology/references/{id}` |
| Documenti | `POST /API/metrology/documents`, `GET /API/metrology/documents/{id}` |
| Certificati esterni | `POST /API/dcc/external`, `PUT /API/dcc/external/{id}/data`, `POST /API/dcc/external/{id}/verify`, `…/reject` |
| Ciclo di vita | `GET /API/metrology/certificates/{id}`, `POST …/{id}/approve · sign · effective · invalidate · revise` |
| Hub | `GET /API/dcc/summary · certificates · clients`, `POST /API/dcc/certificates/{azione}`, `GET /API/dcc/certificates/{id}/download` |
| Verifica | `POST /API/dcc/verify` |
| Sessioni e motore | `POST /API/metrology/sessions`, `…/{id}/close-phase · documents · close · abort`, `POST /API/metrology/results/{id}/decision`, `GET /API/metrology/engine/jobs/{id}/context` |
| Anagrafiche e ruoli | `GET /API/metrology/me`, `…/companies`, `…/labs`, `…/roles` |

Topic: consuma `calibrations` e `calibration-results`; produce, dall'outbox,
`calibration-activated` (compattato) e `calibration-jobs`.

Dove e come è implementato ogni meccanismo: `IMPLEMENTAZIONE_METROLOGIA.md` nella cartella dei
repository.

## Prove

```
./gradlew test
```

I test di schema, certificati, firma e sessioni usano un PostgreSQL incorporato (zonky), quindi
girano anche senza Docker. Solo `MetrologyServiceApplicationTests`, che avvia PostgreSQL e Kafka
con Testcontainers, richiede Docker: dove manca viene saltato, e gira nella CI.

Per verificare la catena di autenticazione da un client vero (gateway o motore):
`GET /API/metrology/whoami` restituisce soggetto e ruoli riconosciuti.
