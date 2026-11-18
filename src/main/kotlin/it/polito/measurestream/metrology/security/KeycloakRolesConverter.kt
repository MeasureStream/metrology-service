package it.polito.measurestream.metrology.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Estrae i ruoli dal token di Keycloak.
 *
 * Legge gli stessi due posti di sensor-manager, perche' lo stesso token deve valere allo stesso
 * modo in entrambi i servizi: `realm_access.roles` e `resource_access.iam1client.roles`. Ogni
 * ruolo diventa un'autorita' con il prefisso `ROLE_`, cosi' `hasRole("app-admin")` funziona.
 */
class KeycloakRolesConverter : Converter<Jwt, Collection<GrantedAuthority>> {
	override fun convert(jwt: Jwt): Collection<GrantedAuthority> {
		val realmRoles = rolesIn(jwt.claims["realm_access"])
		val clientRoles = rolesIn((jwt.claims["resource_access"] as? Map<*, *>)?.get(GATEWAY_CLIENT))

		return (realmRoles + clientRoles)
			.distinct()
			.map { SimpleGrantedAuthority(Roles.PREFIX + it) }
	}

	private fun rolesIn(claim: Any?): List<String> =
		((claim as? Map<*, *>)?.get("roles") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

	private companion object {
		/** Il client con cui il gateway fa entrare gli utenti. */
		const val GATEWAY_CLIENT = "iam1client"
	}
}
