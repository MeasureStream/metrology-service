# Immagine multi-architettura (amd64 per lo sviluppo, arm64 per la Pi): il jar si costruisce
# fuori con `./gradlew bootJar`, qui si copia e basta.
FROM eclipse-temurin:21-jre

WORKDIR /app

# curl serve all'healthcheck del compose; non si da' per scontato che l'immagine base lo porti.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Il servizio non ha bisogno di root. UID e GID sono fissi perche' la chiave di firma e la sua
# password arrivano come secret del compose, cioe' come file della macchina montati cosi' come
# sono: sulla Pi si assegnano a 10002 con permessi 600, e solo questo utente li legge.
RUN groupadd --system --gid 10002 metrology \
    && useradd --system --uid 10002 --gid metrology --no-create-home metrology \
    && mkdir -p /app/trust && chown metrology:metrology /app/trust

COPY build/libs/*.jar app.jar

USER 10002:10002
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
