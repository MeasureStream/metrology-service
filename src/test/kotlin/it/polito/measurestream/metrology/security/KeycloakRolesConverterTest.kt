package it.polito.measurestream.metrology.security

import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeycloakRolesConverterTest {

	private fun token(claims: Map<String, Any>): Jwt =
		Jwt.withTokenValue("t")
			.header("alg", "none")
			.subject("utente-1")
			.claims { it.putAll(claims) }
			.build()

	private fun rolesOf(jwt: Jwt) = KeycloakRolesConverter().convert(jwt).map { it.authority }.toSet()

	@Test
	fun `legge i ruoli di realm e quelli del client del gateway`() {
		val jwt = token(
			mapOf(
				"realm_access" to mapOf("roles" to listOf("app-admin", "offline_access")),
				"resource_access" to mapOf("iam1client" to mapOf("roles" to listOf("app-user"))),
			),
		)
		assertEquals(setOf("ROLE_app-admin", "ROLE_offline_access", "ROLE_app-user"), rolesOf(jwt))
	}

	@Test
	fun `ignora i ruoli di altri client`() {
		val jwt = token(
			mapOf("resource_access" to mapOf("account" to mapOf("roles" to listOf("manage-account")))),
		)
		assertTrue(rolesOf(jwt).isEmpty())
	}

	@Test
	fun `un token senza ruoli non ha autorita' e non solleva eccezioni`() {
		assertTrue(rolesOf(token(mapOf("scope" to "openid"))).isEmpty())
	}

	@Test
	fun `un ruolo presente in entrambi i posti compare una volta sola`() {
		val jwt = token(
			mapOf(
				"realm_access" to mapOf("roles" to listOf("app-user")),
				"resource_access" to mapOf("iam1client" to mapOf("roles" to listOf("app-user"))),
			),
		)
		assertEquals(listOf("ROLE_app-user"), KeycloakRolesConverter().convert(jwt).map { it.authority })
	}
}
