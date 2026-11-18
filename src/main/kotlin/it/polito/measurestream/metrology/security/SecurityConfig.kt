package it.polito.measurestream.metrology.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Il servizio e' solo un resource server: il login lo fa gateway-iam, che inoltra il token con
 * TokenRelay. Qui si valida il token e se ne leggono i ruoli, niente sessioni e niente cookie.
 *
 * Le regole fini (ruolo metrologico valido alla data, proprieta' dell'esemplare) non stanno
 * qui ma nei servizi, perche' dipendono dal dato e non dall'indirizzo.
 */
@Configuration
class SecurityConfig {
	@Bean
	fun filterChain(http: HttpSecurity): SecurityFilterChain =
		http
			.authorizeHttpRequests {
				// Lo usa l'healthcheck di Docker, che non ha un token.
				it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				it.anyRequest().authenticated()
			}
			.oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) } }
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.csrf { it.disable() }
			.cors { it.disable() }
			.build()

	@Bean
	fun jwtAuthenticationConverter(): JwtAuthenticationConverter =
		JwtAuthenticationConverter().apply {
			setJwtGrantedAuthoritiesConverter(KeycloakRolesConverter())
		}
}
