plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "it.polito.measurestream"
version = "0.1.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Solo cio' che serve adesso. Gli starter delle fasi successive entrano con la fase che li usa:
// posta con lo scadenzario (fase 4), Thymeleaf e OpenHTMLtoPDF con i report (fase 5).
dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	// Promemoria dello scadenzario e avvisi di non conformita' (fase 4): partono solo se spring.mail.host e' configurato.
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	// Firma e verifica dei certificati (passi 1.10 e 3.8): XMLDSig con Apache Santuario, come in
	// dccverify, e PDF con PDFBox al posto di iText 8, che ha licenza AGPL.
	implementation("org.apache.santuario:xmlsec:4.0.4")
	implementation("org.apache.pdfbox:pdfbox:3.0.8")
	implementation("org.bouncycastle:bcprov-jdk18on:1.87")
	implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-kafka")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// PostgreSQL vero senza Docker: migrazioni, trigger e mappatura JPA si provano anche qui.
	testImplementation("io.zonky.test:embedded-postgres:2.2.2")
	testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.12.0"))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property", "-Xemit-jvm-type-annotations")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

// Un solo jar in build/libs, quello eseguibile: il Dockerfile lo copia senza ambiguita'.
tasks.jar {
	enabled = false
}

tasks.withType<Test> {
	useJUnitPlatform()
	// I test che avviano PostgreSQL e Kafka con Testcontainers richiedono Docker: dove non c'e'
	// si saltano invece di fallire, come dichiara @Testcontainers(disabledWithoutDocker = true).
}
