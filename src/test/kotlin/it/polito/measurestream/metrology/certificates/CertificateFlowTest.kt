package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.activation.ActivationEvent
import it.polito.measurestream.metrology.activation.ActivationService
import it.polito.measurestream.metrology.activation.Outbox
import it.polito.measurestream.metrology.activation.OutboxEventRepository
import it.polito.measurestream.metrology.common.AuditLogRepository
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.CurrentUser
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.documents.DocumentRepository
import it.polito.measurestream.metrology.documents.DocumentService
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.items.RemoteMeasurementUnit
import it.polito.measurestream.metrology.items.RemoteSensor
import it.polito.measurestream.metrology.items.RemoteTemplateRef
import it.polito.measurestream.metrology.items.SensorManagerClient
import it.polito.measurestream.metrology.jobs.contract.CalibrationFunction
import it.polito.measurestream.metrology.jobs.contract.Coefficient
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import it.polito.measurestream.metrology.references.ReferenceService
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRole
import it.polito.measurestream.metrology.security.MetrologyRoleRepository
import it.polito.measurestream.metrology.security.MetrologyRoleType
import it.polito.measurestream.metrology.security.Roles
import it.polito.measurestream.metrology.signing.SigningKeys
import it.polito.measurestream.metrology.signing.SigningService
import it.polito.measurestream.metrology.support.EmbeddedDatabase
import it.polito.measurestream.metrology.support.login
import jakarta.persistence.EntityManager
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * La prova di accettazione di I1 lato metrology-service: un certificato esterno caricato,
 * trascritto, verificato da un secondo utente e messo in vigore produce l'attivazione per
 * sensor-manager; un certificato verificato non si modifica piu', nemmeno nel database.
 */
@DataJpaTest(properties = ["metrology.signing.dev-self-signed=true", "metrology.signing.trust-anchors-path=/non/esiste"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
	CurrentUser::class,
	AccessPolicy::class,
	AuditService::class,
	DocumentService::class,
	ItemService::class,
	Outbox::class,
	ActivationService::class,
	CertificateService::class,
	ReferenceService::class,
	SigningKeys::class,
	SigningService::class,
)
class CertificateFlowTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)
	}

	@MockitoBean lateinit var sensorManager: SensorManagerClient

	@Autowired lateinit var service: CertificateService
	@Autowired lateinit var roles: MetrologyRoleRepository
	@Autowired lateinit var outbox: OutboxEventRepository
	@Autowired lateinit var certificates: CertificateRepository
	@Autowired lateinit var documents: DocumentRepository
	@Autowired lateinit var auditLog: AuditLogRepository
	@Autowired lateinit var jdbc: JdbcTemplate
	@Autowired lateinit var em: EntityManager

	private val euid = 439041101L

	@BeforeEach
	fun setUp() {
		roles.save(MetrologyRole(userSub = "anna", role = MetrologyRoleType.TECHNICIAN, validFrom = LocalDate.now().minusYears(1), grantedBy = "admin"))
		roles.save(MetrologyRole(userSub = "anna", role = MetrologyRoleType.APPROVER, validFrom = LocalDate.now().minusYears(1), grantedBy = "admin"))
		roles.save(MetrologyRole(userSub = "bruno", role = MetrologyRoleType.APPROVER, validFrom = LocalDate.now().minusYears(1), grantedBy = "admin"))
		// Un approvatore il cui ruolo e' finito ieri: oggi non approva piu'.
		roles.save(MetrologyRole(userSub = "carla", role = MetrologyRoleType.APPROVER, validFrom = LocalDate.now().minusYears(2), validTo = LocalDate.now().minusDays(1), grantedBy = "admin"))
		Mockito.`when`(sensorManager.measurementUnit(anyLong())).thenReturn(
			RemoteMeasurementUnit(
				id = 7,
				extendedId = euid,
				sensors = listOf(RemoteSensor(id = 30, modelName = "HumiditySensorHTU21D", sensorIndex = 2, template = RemoteTemplateRef(202, 1))),
				ownerSub = "cliente",
			)
		)
	}

	private fun data(number: String = "LAT-123/2026") =
		CertificateDataRequest(
			number = number,
			issuer = "Laboratorio Esterno S.r.l.",
			issuerAccreditation = "ACCREDIA LAT n. 123",
			calibrationDate = LocalDate.of(2026, 11, 10),
			issueDate = LocalDate.of(2026, 11, 12),
			function = CalibrationFunction("linear", "c[0] + c[1] * x", listOf("x"), listOf(Coefficient(0, -5.0), Coefficient(1, 0.002))),
			covariance = listOf(listOf(0.01, 0.0), listOf(0.0, 1e-12)),
			calibratedMin = 10.0,
			calibratedMax = 90.0,
			calibratedDsi = "\\percent",
			conformity = Conformity.CONFORM,
			points =
				listOf(
					PointRequest(PointPhase.AS_LEFT, 0, nominal = 20.0, referenceValue = 20.1, indicatedValue = 20.3, expandedU = 1.2),
					PointRequest(PointPhase.AS_LEFT, 1, nominal = 80.0, referenceValue = 79.8, indicatedValue = 80.4, expandedU = 1.5),
				),
		)

	private fun uploadAndEnter(number: String = "LAT-123/2026"): CertificateDetailDTO {
		login("anna", Roles.USER)
		val draft = service.createExternal(SubjectRef(muExtendedId = euid, sensorIndex = 2), "%PDF-1.7 certificato".toByteArray(), "application/pdf", "lat.pdf")
		assertEquals(CertificateStatus.DRAFT, draft.status)
		assertNotNull(draft.originalDocumentId)
		return service.enterData(draft.id, data(number))
	}

	@Test
	fun `quattro occhi, poi in vigore con l'attivazione per sensor-manager`() {
		val entered = uploadAndEnter()
		assertEquals(CertificateStatus.ENTERED, entered.status)
		assertEquals(LocalDate.of(2027, 11, 10), entered.validUntil?.toLocalDate(), "la validita' la decide il sistema: un anno")
		assertEquals(0.2, entered.points[0].error!!, 1e-9)

		// Chi ha trascritto non verifica.
		assertThrows<Conflict> { service.verify(entered.id) }

		// Un ruolo scaduto ieri non vale oggi.
		login("carla", Roles.USER)
		assertThrows<Forbidden> { service.verify(entered.id) }

		login("bruno", Roles.USER)
		val verified = service.verify(entered.id)
		assertEquals(CertificateStatus.VERIFIED, verified.status)
		assertEquals("bruno", verified.verifiedBy)

		val effective = service.makeEffective(entered.id)
		assertEquals(CertificateStatus.EFFECTIVE, effective.status)

		val event = outbox.findTop100BySentAtIsNullOrderByIdAsc().single()
		assertEquals(ActivationEvent.TOPIC, event.topic)
		assertEquals("$euid:2", event.eventKey)
		val payload = ContractJson.mapper.readTree(event.payload!!)
		assertEquals("linear", payload.path("function").path("type").asString())
		assertEquals("c[0] + c[1] * x", payload.path("function").path("linear").asString())
		assertTrue(payload.path("validFrom").isString, "le date viaggiano in ISO 8601, come le legge sensor-manager")
		assertEquals(90.0, payload.path("calibratedRange").path("max").asDouble())
		assertEquals("LAT-123/2026", payload.path("certificateNumber").asString())

		assertTrue(auditLog.findAll().map { it.action }.containsAll(listOf("CERTIFICATE_ENTERED", "CERTIFICATE_VERIFIED", "CERTIFICATE_EFFECTIVE", "CALIBRATION_ACTIVATED")))
	}

	@Test
	fun `un certificato verificato non cambia piu', nemmeno nel database`() {
		val entered = uploadAndEnter()
		login("bruno", Roles.USER)
		service.verify(entered.id)
		em.flush()

		val failure = runCatching { jdbc.update("update metrology.certificate set number = 'ALTRO' where id = ?", entered.id) }.exceptionOrNull()
		assertNotNull(failure, "il trigger deve rifiutare la modifica")
		assertTrue(failure.message!!.contains("revisione"), failure.message)
	}

	@Test
	fun `un documento non si modifica nel database`() {
		val entered = uploadAndEnter()
		em.flush()
		val failure = runCatching { jdbc.update("update metrology.document set filename = 'x' where id = ?", entered.originalDocumentId) }.exceptionOrNull()
		assertNotNull(failure)
	}

	@Test
	fun `il secondo certificato sostituisce il primo, l'invalidazione revoca`() {
		val first = uploadAndEnter("LAT-1")
		login("bruno", Roles.USER)
		service.verify(first.id)
		service.makeEffective(first.id)

		val second = uploadAndEnter("LAT-2")
		login("bruno", Roles.USER)
		service.verify(second.id)
		service.makeEffective(second.id)
		em.flush()
		em.clear()
		assertEquals(CertificateStatus.SUPERSEDED, certificates.findById(first.id).get().status)

		service.invalidate(second.id, "numero trascritto male")
		val messages = outbox.findTop100BySentAtIsNullOrderByIdAsc()
		assertEquals(3, messages.size)
		assertNull(messages.last().payload, "l'invalidazione di un certificato in vigore e' un tombstone")
		assertEquals("$euid:2", messages.last().eventKey)
	}

	@Test
	fun `un utente estraneo non vede il certificato, il proprietario si'`() {
		val entered = uploadAndEnter()
		login("estraneo", Roles.USER)
		assertThrows<NotFound> { service.detail(entered.id) }

		login("cliente", Roles.USER)
		val seen = service.detail(entered.id)
		assertTrue("VERIFY" !in seen.actions.allowed, "il cliente vede, ma non verifica")
	}

	@Test
	fun `un cliente non carica certificati, li chiede`() {
		login("cliente", Roles.USER)
		assertThrows<Forbidden> {
			service.createExternal(SubjectRef(muExtendedId = euid, sensorIndex = 2), "%PDF".toByteArray(), "application/pdf", "x.pdf")
		}
	}

	@Test
	fun `la covarianza deve avere la forma dei coefficienti`() {
		login("anna", Roles.USER)
		val draft = service.createExternal(SubjectRef(muExtendedId = euid, sensorIndex = 2), null, null, null)
		assertThrows<it.polito.measurestream.metrology.common.BadRequest> {
			service.enterData(draft.id, data().copy(covariance = listOf(listOf(1.0))))
		}
	}

	@Test
	fun `lo stesso file caricato due volte e' lo stesso documento`() {
		login("anna", Roles.USER)
		val a = service.createExternal(SubjectRef(muExtendedId = euid, sensorIndex = 2), "%PDF-1.7 uguale".toByteArray(), "application/pdf", "a.pdf")
		val b = service.createExternal(SubjectRef(muExtendedId = euid, sensorIndex = 2), "%PDF-1.7 uguale".toByteArray(), "application/pdf", "b.pdf")
		assertEquals(a.originalDocumentId, b.originalDocumentId)
		assertEquals(64, documents.findById(a.originalDocumentId!!).get().sha256.length)
	}
}
