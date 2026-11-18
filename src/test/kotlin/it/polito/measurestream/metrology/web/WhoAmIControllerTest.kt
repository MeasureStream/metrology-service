package it.polito.measurestream.metrology.web

import it.polito.measurestream.metrology.security.KeycloakRolesConverter
import it.polito.measurestream.metrology.security.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(WhoAmIController::class)
@Import(SecurityConfig::class)
class WhoAmIControllerTest {

	@Autowired
	lateinit var mvc: MockMvc

	// Il decoder vero si costruisce dall'issuer di Keycloak, che qui non c'e'. I token di prova
	// arrivano gia' decodificati da jwt(), quindi basta che il bean esista.
	@MockitoBean
	lateinit var jwtDecoder: JwtDecoder

	@Test
	fun `senza token risponde 401`() {
		mvc.get("/API/metrology/whoami").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `con un token riconosce soggetto e ruoli di realm`() {
		val token = jwt()
			.jwt {
				it.subject("utente-1")
					.claim("preferred_username", "marco")
					.claim("realm_access", mapOf("roles" to listOf("app-admin")))
			}
			.authorities(KeycloakRolesConverter())

		mvc.get("/API/metrology/whoami") { with(token) }
			.andExpect {
				status { isOk() }
				jsonPath("$.subject") { value("utente-1") }
				jsonPath("$.username") { value("marco") }
				jsonPath("$.roles[0]") { value("app-admin") }
				jsonPath("$.service") { value("metrology-service") }
			}
	}

	@Test
	fun `il service account del motore porta il suo ruolo`() {
		val token = jwt()
			.jwt {
				it.subject("service-account-calibration-engine")
					.claim("realm_access", mapOf("roles" to listOf("CALIBRATION_ENGINE")))
			}
			.authorities(KeycloakRolesConverter())

		mvc.get("/API/metrology/whoami") { with(token) }
			.andExpect {
				status { isOk() }
				jsonPath("$.roles[0]") { value("CALIBRATION_ENGINE") }
			}
	}
}
