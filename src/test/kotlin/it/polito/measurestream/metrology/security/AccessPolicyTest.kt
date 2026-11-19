package it.polito.measurestream.metrology.security

import it.polito.measurestream.metrology.common.CurrentUser
import it.polito.measurestream.metrology.companies.LabCompany
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.support.EmbeddedDatabase
import it.polito.measurestream.metrology.support.login
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Chi e' personale del laboratorio e chi no [S §9]. Un utente di un laboratorio esterno si lega al
 * suo laboratorio con un ruolo che porta `labCompanyId`: quel ruolo non deve farne personale
 * interno, altrimenti vedrebbe gli oggetti di tutti i clienti e, da approvatore, approverebbe.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CurrentUser::class, AccessPolicy::class)
class AccessPolicyTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)
	}

	@Autowired lateinit var policy: AccessPolicy
	@Autowired lateinit var roles: MetrologyRoleRepository
	@Autowired lateinit var labs: LabCompanyRepository

	private val since = LocalDate.now().minusDays(1)

	private fun lab(name: String): Long = labs.save(LabCompany(name = name)).id

	@Test
	fun `il tecnico di un laboratorio esterno vede solo gli esemplari affidati al suo laboratorio`() {
		val lab = lab("Laboratorio Esterno")
		val other = lab("Altro Laboratorio")
		roles.save(MetrologyRole(userSub = "lucia", role = MetrologyRoleType.TECHNICIAN, validFrom = since, labCompanyId = lab, grantedBy = "admin"))
		login("lucia", Roles.LAB)
		val caller = policy.caller()

		assertFalse(policy.isStaff(caller), "non e' personale interno")
		assertFalse(policy.has(caller, MetrologyRoleType.TECHNICIAN), "non apre sessioni e non trascrive per tutti")
		assertEquals(emptySet(), policy.metrologyRoles("lucia"))
		assertEquals(lab, policy.labOf("lucia"))
		assertTrue(policy.canSee("cliente", lab, caller), "l'esemplare affidato al suo laboratorio")
		assertFalse(policy.canSee("cliente", null, caller), "un esemplare non affidato")
		assertFalse(policy.canSee("cliente", other, caller), "un esemplare affidato a un altro laboratorio")
	}

	@Test
	fun `l'approvatore di un laboratorio esterno non approva`() {
		val lab = lab("Laboratorio Esterno")
		roles.save(MetrologyRole(userSub = "marta", role = MetrologyRoleType.APPROVER, validFrom = since, labCompanyId = lab, grantedBy = "admin"))
		login("marta", Roles.LAB)
		assertFalse(policy.has(policy.caller(), MetrologyRoleType.APPROVER))
	}

	@Test
	fun `un ruolo interno dato per errore a un utente app-lab non ne fa personale`() {
		roles.save(MetrologyRole(userSub = "nico", role = MetrologyRoleType.TECHNICAL_MANAGER, validFrom = since, grantedBy = "admin"))
		login("nico", Roles.LAB)
		val caller = policy.caller()
		assertFalse(policy.isStaff(caller))
		assertFalse(policy.has(caller, MetrologyRoleType.APPROVER))
		assertFalse(policy.canSee("cliente", null, caller))
	}

	@Test
	fun `al tecnico senza app-staff si dice quale ruolo manca per vedere la MU`() {
		roles.save(MetrologyRole(userSub = "anna", role = MetrologyRoleType.TECHNICIAN, validFrom = since, grantedBy = "admin"))
		login("anna", Roles.USER)
		assertTrue(Roles.STAFF in policy.invisibleMu(0x10001, policy.caller()), "il tecnico senza il ruolo di accesso")

		login("anna", Roles.USER, Roles.STAFF)
		assertFalse(Roles.STAFF in policy.invisibleMu(0x10001, policy.caller()), "con il ruolo la MU manca davvero")

		login("cliente", Roles.USER)
		assertFalse(Roles.STAFF in policy.invisibleMu(0x10001, policy.caller()), "a un cliente il ruolo del personale non serve")
	}

	@Test
	fun `il personale interno resta personale`() {
		roles.save(MetrologyRole(userSub = "anna", role = MetrologyRoleType.TECHNICIAN, validFrom = since, grantedBy = "admin"))
		login("anna", Roles.USER)
		val caller = policy.caller()
		assertTrue(policy.isStaff(caller))
		assertTrue(policy.has(caller, MetrologyRoleType.TECHNICIAN))
		assertFalse(policy.has(caller, MetrologyRoleType.APPROVER))
		assertTrue(policy.canSee("cliente", null, caller))
	}
}
