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

Nessun segreto sta nel repository, che è pubblico: chiavi di firma, trust store e credenziali
arrivano come variabili d'ambiente o file montati in sola lettura.

## Prove

```
./gradlew test
```

I test che avviano PostgreSQL e Kafka con Testcontainers richiedono Docker: dove manca vengono
saltati, e girano comunque nella CI.

Per verificare la catena di autenticazione da un client vero (gateway o motore):
`GET /API/metrology/whoami` restituisce soggetto e ruoli riconosciuti.
