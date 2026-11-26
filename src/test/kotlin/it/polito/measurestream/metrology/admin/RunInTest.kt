package it.polito.measurestream.metrology.admin

import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateKind
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateSource
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditLogRepository
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.CurrentUser
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.references.ReferenceRequest
import it.polito.measurestream.metrology.references.ReferenceService
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleRepository
import it.polito.measurestream.metrology.security.MetrologyRoleType
import it.polito.measurestream.metrology.security.Roles
import it.polito.measurestream.metrology.signing.SigningService
import it.polito.measurestream.metrology.support.EmbeddedDatabase
import it.polito.measurestream.metrology.support.login
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * La modalita' rodaggio: l'amministratore corregge e cancella cio' che in esercizio resta
 * immutabile. E, rodaggio o no, cancella cio' che e' stato creato per errore e non e' mai stato
 * usato. Il caso con il rodaggio spento sta in [RunInOffTest].
 */
@DataJpaTest(properties = ["metrology.run-in=true"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CurrentUser::class, AccessPolicy::class, AuditService::class, AdminController::class, ReferenceService::class)
class RunInTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)
	}

	@MockitoBean lateinit var signing: SigningService

	@Autowired lateinit var admin: AdminController
	@Autowired lateinit var references: ReferenceService
	@Autowired lateinit var roles: MetrologyRoleRepository
	@Autowired lateinit var labs: LabCompanyRepository
	@Autowired lateinit var certificates: CertificateRepository
	@Autowired lateinit var auditLog: AuditLogRepository

	@BeforeEach
	fun setUp() = login("amministratore", Roles.ADMIN)

	@Test
	fun `il profilo dice all'interfaccia che il rodaggio e' acceso`() {
		assertTrue(admin.me().runIn)
	}

	@Test
	fun `in rodaggio un ruolo si corregge e si cancella, e il registro lo dice`() {
		val role = admin.grant(RoleRequest(userSub = "tecnico-sbagliato", role = MetrologyRoleType.TECHNICIAN))
		val corrected =
			admin.correctRole(
				role.id,
				RoleRequest(userSub = "tecnico-giusto", displayName = "Tecnico", role = MetrologyRoleType.APPROVER, validFrom = LocalDate.of(2026, 11, 1)),
			)
		assertEquals("tecnico-giusto", corrected.userSub)
		assertEquals(MetrologyRoleType.APPROVER, corrected.role)
		assertEquals(LocalDate.of(2026, 11, 1), corrected.validFrom)

		admin.deleteRole(role.id)
		assertFalse(roles.existsById(role.id))
		val actions = auditLog.findAll().filter { it.entityId == role.id && it.entity == "metrology_role" }.map { it.action }
		assertTrue(actions.containsAll(listOf("ROLE_CORRECTED", "ROLE_DELETED")), actions.toString())
	}

	@Test
	fun `solo l'amministratore corregge un ruolo, anche in rodaggio`() {
		val role = admin.grant(RoleRequest(userSub = "tecnico", role = MetrologyRoleType.TECHNICIAN))
		login("tecnico", Roles.USER)
		assertThrows<Forbidden> { admin.deleteRole(role.id) }
	}

	@Test
	fun `in rodaggio il numero di serie di un campione si corregge`() {
		val reference = references.create(ReferenceRequest(name = "Pozzetto", serialNumber = "SN-ERRATO"))
		val fixed = references.update(reference.id, ReferenceRequest(name = "Pozzetto", serialNumber = "SN-GIUSTO"))
		assertEquals("SN-GIUSTO", fixed.serialNumber)
	}

	@Test
	fun `un campione mai usato si cancella, uno con un certificato si ritira`() {
		val trial = references.create(ReferenceRequest(name = "Prova", serialNumber = "a"))
		references.delete(trial.id)
		assertThrows<NotFound> { references.get(trial.id) }

		val used = references.create(ReferenceRequest(name = "Pozzetto", serialNumber = "SN-1"))
		certificates.save(
			Certificate(
				kind = CertificateKind.EXTERNAL,
				subjectType = SubjectType.REFERENCE,
				subjectId = used.id,
				number = "LAT-999-0001",
				calibrationDate = LocalDate.of(2026, 10, 1),
				status = CertificateStatus.ENTERED,
				source = CertificateSource.PDF_MANUAL,
				createdBy = "amministratore",
			)
		)
		val refused = assertThrows<Conflict> { references.delete(used.id) }
		assertTrue(refused.message!!.contains("si ritira"), refused.message)
	}

	@Test
	fun `un'azienda con membri non si cancella, vuota si`() {
		val company = admin.createCompany(CompanyRequest(name = "Cliente di prova"))
		admin.addMember(company.id, MemberRequest(userSub = "cliente"))
		assertThrows<Conflict> { admin.deleteCompany(company.id) }
		admin.removeMember(company.id, "cliente")
		admin.deleteCompany(company.id)
		assertTrue(admin.companies().none { it.id == company.id })
	}

	@Test
	fun `il laboratorio interno non si cancella`() {
		val internal = labs.findAll().first { it.internal }
		assertThrows<Conflict> { admin.deleteLab(internal.id) }
	}

	@Test
	fun `un laboratorio usato da un ruolo non si cancella`() {
		val lab = admin.createLab(LabRequest(name = "Laboratorio esterno"))
		admin.grant(RoleRequest(userSub = "tecnico-esterno", role = MetrologyRoleType.TECHNICIAN, labCompanyId = lab.id))
		// Il vincolo del database ferma la cancellazione e il messaggio lo dice in chiaro. Dopo
		// l'errore la transazione di PostgreSQL non accetta altri comandi: il test finisce qui.
		val refused = assertThrows<Conflict> { admin.deleteLab(lab.id) }
		assertTrue(refused.message!!.contains("gia' usato"), refused.message)
	}
}

/** Con il rodaggio spento, come in esercizio: le correzioni no, la cancellazione dell'inutilizzato si'. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CurrentUser::class, AccessPolicy::class, AuditService::class, AdminController::class, ReferenceService::class)
class RunInOffTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)
	}

	@MockitoBean lateinit var signing: SigningService

	@Autowired lateinit var admin: AdminController
	@Autowired lateinit var references: ReferenceService

	@BeforeEach
	fun setUp() = login("amministratore", Roles.ADMIN)

	@Test
	fun `fuori dal rodaggio un ruolo non si corregge ne' si cancella`() {
		assertFalse(admin.me().runIn)
		val role = admin.grant(RoleRequest(userSub = "tecnico", role = MetrologyRoleType.TECHNICIAN))
		assertThrows<Conflict> { admin.correctRole(role.id, RoleRequest(userSub = "altro", role = MetrologyRoleType.APPROVER)) }
		assertThrows<Conflict> { admin.deleteRole(role.id) }
	}

	@Test
	fun `fuori dal rodaggio il numero di serie resta, ma un campione mai usato si cancella`() {
		val reference = references.create(ReferenceRequest(name = "Prova", serialNumber = "b"))
		assertThrows<Conflict> { references.update(reference.id, ReferenceRequest(name = "Prova", serialNumber = "c")) }
		references.delete(reference.id)
		assertThrows<NotFound> { references.get(reference.id) }
	}
}
