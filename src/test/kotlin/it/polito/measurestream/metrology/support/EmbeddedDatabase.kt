package it.polito.measurestream.metrology.support

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import it.polito.measurestream.metrology.security.KeycloakRolesConverter
import java.io.File
import java.time.Instant
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * Un PostgreSQL vero per tutta la JVM dei test, senza Docker: un processo incorporato. Lo
 * schema `metrology` lo crea Flyway (in produzione lo crea lo script di setup).
 */
object EmbeddedDatabase {
	private val postgres: EmbeddedPostgres by lazy {
		EmbeddedPostgres.builder()
			.apply {
				// initdb non regge gli spazi nel percorso, e la cartella temporanea dell'utente
				// su questa macchina ne ha.
				if (System.getProperty("java.io.tmpdir").contains(' ')) {
					setOverrideWorkingDirectory(File("C:/Users/Public/embedded-pg-metrology").apply { mkdirs() })
				}
			}
			.start()
			.also { pg -> Runtime.getRuntime().addShutdownHook(Thread { pg.close() }) }
	}

	fun register(registry: DynamicPropertyRegistry) {
		registry.add("spring.datasource.url") { postgres.getJdbcUrl("postgres", "postgres") }
		registry.add("spring.datasource.username") { "postgres" }
		registry.add("spring.datasource.password") { "" }
		registry.add("spring.flyway.create-schemas") { "true" }
	}
}

/** Mette nel contesto di sicurezza un utente con un token Keycloak e i suoi ruoli di realm. */
fun login(sub: String, vararg realmRoles: String, name: String = sub) {
	val jwt =
		Jwt.withTokenValue("token-$sub")
			.header("alg", "none")
			.subject(sub)
			.claim("preferred_username", name)
			.claim("realm_access", mapOf("roles" to realmRoles.toList()))
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300))
			.build()
	SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, KeycloakRolesConverter().convert(jwt))
}
