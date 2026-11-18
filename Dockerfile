# Immagine multi-architettura (amd64 per lo sviluppo, arm64 per la Pi): il jar si costruisce
# fuori con `./gradlew bootJar`, qui si copia e basta.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Il servizio non ha bisogno di root. La chiave di firma e il trust store arriveranno come file
# montati in sola lettura (passo 1.10), mai copiati nell'immagine.
RUN groupadd --system metrology && useradd --system --gid metrology --no-create-home metrology

COPY build/libs/*.jar app.jar

USER metrology
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
