package it.polito.measurestream.metrology.common

import it.polito.measurestream.metrology.security.Roles
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/** Chi sta chiamando, letto dal token: identita', ruoli Keycloak e il token stesso da inoltrare. */
data class Caller(
	val sub: String,
	val username: String?,
	val displayName: String?,
	val email: String?,
	val authorities: Set<String>,
	val token: String?,
) {
	val isAdmin: Boolean get() = Roles.PREFIX + Roles.ADMIN in authorities
	val isLab: Boolean get() = Roles.PREFIX + Roles.LAB in authorities
	val isEngine: Boolean get() = Roles.PREFIX + Roles.CALIBRATION_ENGINE in authorities
}

@Component
class CurrentUser {
	fun get(): Caller {
		val auth = SecurityContextHolder.getContext().authentication
			?: throw Forbidden("Nessuna autenticazione")
		val jwt = (auth as? JwtAuthenticationToken)?.token
		return Caller(
			sub = jwt?.subject ?: auth.name,
			username = jwt?.getClaimAsString("preferred_username"),
			displayName = listOfNotNull(jwt?.getClaimAsString("given_name"), jwt?.getClaimAsString("family_name"))
				.joinToString(" ").ifBlank { null },
			email = jwt?.getClaimAsString("email"),
			authorities = auth.authorities.mapNotNull { it.authority }.toSet(),
			token = jwt?.tokenValue,
		)
	}

	/** Il `sub` di chi chiama, o null fuori da una richiesta (un consumer Kafka). */
	fun subOrNull(): String? = runCatching { get().sub }.getOrNull()
}
