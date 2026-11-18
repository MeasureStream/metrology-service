package it.polito.measurestream.metrology.web

import it.polito.measurestream.metrology.security.Roles
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Chi sta chiamando, visto da questo servizio. */
data class WhoAmIDTO(
	val subject: String,
	val username: String?,
	val roles: List<String>,
	val service: String,
)

/**
 * Diagnostica della catena di autenticazione.
 *
 * Risponde a una domanda sola: il token che arriva dal gateway (o dal motore, con le sue
 * credenziali di servizio) viene riconosciuto, e con quali ruoli? E' la prova che rotta,
 * TokenRelay, issuer e mappatura dei ruoli sono a posto prima che ci sia un dato da proteggere.
 */
@RestController
@RequestMapping("/API/metrology")
class WhoAmIController(
	@Value("\${spring.application.name}") private val serviceName: String,
) {
	@GetMapping("/whoami")
	fun whoAmI(
		@AuthenticationPrincipal jwt: Jwt,
		authentication: JwtAuthenticationToken,
	): WhoAmIDTO =
		WhoAmIDTO(
			// `sub` e' facoltativo per lo standard; Keycloak lo mette sempre, ma se mancasse il
			// nome dell'autenticazione e' il ripiego che Spring stesso userebbe.
			subject = jwt.subject ?: authentication.name,
			username = jwt.getClaimAsString("preferred_username"),
			roles = authentication.authorities
				.mapNotNull { it.authority?.removePrefix(Roles.PREFIX) }
				.sorted(),
			service = serviceName,
		)
}
