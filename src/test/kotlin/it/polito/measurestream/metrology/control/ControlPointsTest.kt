package it.polito.measurestream.metrology.control

import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateKind
import it.polito.measurestream.metrology.certificates.CertificatePoint
import it.polito.measurestream.metrology.certificates.PointPhase
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateSource
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.CurrentUser
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.items.RemoteMeasurementUnit
import it.polito.measurestream.metrology.items.RemoteSensor
import it.polito.measurestream.metrology.items.RemoteTemplateRef
import it.polito.measurestream.metrology.items.SensorManagerClient
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.Roles
import it.polito.measurestream.metrology.support.EmbeddedDatabase
import it.polito.measurestream.metrology.support.login
import jakarta.persistence.EntityManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * La fase 4 del piano: punti di controllo, conformita' con la banda di guardia, escursioni e
 * non conformita', scadenzario. Ogni caso porta il numero del passo nel nome.
 */
@DataJpaTest(properties = ["metrology.notifications.lab-email=laboratorio@example.test", "metrology.run-in=true"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
	CurrentUser::class,
	AccessPolicy::class,
	AuditService::class,
	ItemService::class,
	ControlPointService::class,
	ConformityService::class,
	HistoricClimateService::class,
	ExcursionService::class,
	NonconformityService::class,
	Notifier::class,
	ScheduleService::class,
	CheckService::class,
	ControlChartService::class,
	IntervalService::class,
)
class ControlPointsTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)

		/** Il profilo 501 come lo pubblica il registro (la parte che conta). */
		val PROFILE_501 =
			mapOf(
				"kind" to "profile",
				"templateId" to 501,
				"templateVersion" to "1.0.0",
				"name" to "Conservazione di surgelati, EN 12830 classe 0,5",
				"quantity" to "temperature",
				"dsi" to "\\degreeCelsius",
				"limits" to mapOf("high" to -18.0),
				"intervals" to mapOf("calibration" to "P1Y", "intermediateCheck" to "P3M"),
				"decisionRule" to mapOf("type" to "guarded", "guardBand" to "U", "guardZoneOpensExcursionAfter" to "PT30M"),
				"instrument" to mapOf("class" to "0,5", "mpe" to 0.5),
				"verificationReference" to mapOf("uMax" to 0.25, "k" to 2),
			)
	}

	@MockitoBean lateinit var sensorManager: SensorManagerClient
	@MockitoBean lateinit var mail: JavaMailSender

	@Autowired lateinit var service: ControlPointService
	@Autowired lateinit var conformity: ConformityService
	@Autowired lateinit var excursions: ExcursionRepository
	@Autowired lateinit var nonconformities: NonconformityRepository
	@Autowired lateinit var assignments: ControlPointAssignmentRepository
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var scheduleItems: ScheduleItemRepository
	@Autowired lateinit var certificates: CertificateRepository
	@Autowired lateinit var items: ItemService
	@Autowired lateinit var itemRepository: it.polito.measurestream.metrology.items.CalibratedItemRepository
	@Autowired lateinit var roles: it.polito.measurestream.metrology.security.MetrologyRoleRepository
	@Autowired lateinit var em: EntityManager
	@Autowired lateinit var checks: CheckService
	@Autowired lateinit var charts: ControlChartService
	@Autowired lateinit var intervals: IntervalService
	@Autowired lateinit var proposals: IntervalProposalRepository

	private val cellA = 0x1A2B3C4DL
	private val cellB = 0x1A2B3C4EL
	private val t0: OffsetDateTime = OffsetDateTime.of(2026, 12, 1, 9, 0, 0, 0, ZoneOffset.UTC)

	private fun mu(extendedId: Long) =
		RemoteMeasurementUnit(
			id = extendedId,
			extendedId = extendedId,
			sensors = listOf(RemoteSensor(id = 1, modelName = "TemperatureSensorNTC", sensorIndex = 0, template = RemoteTemplateRef(201, 2, type = "temperature"))),
			ownerSub = "cliente",
		)

	@BeforeEach
	fun setUp() {
		login("cliente", Roles.USER)
		Mockito.`when`(sensorManager.measurementUnit(anyLong())).thenAnswer { mu(it.getArgument(0)) }
		Mockito.`when`(sensorManager.template(anyString(), anyInt(), anyString())).thenReturn(mapOf("status" to "PUBLISHED", "content" to PROFILE_501))
	}

	private fun cell(overrides: Map<String, Any?>? = null) =
		service.create(ControlPointRequest(name = "Cella surgelati 2", location = "Magazzino", profile = ProfileRef(501, "1.0.0"), overrides = overrides))

	/** Un report di sensor-manager con un valore medio sul sensore 0 della MU. */
	private fun report(mu: Long, end: OffsetDateTime, value: Double, uStd: Double = 0.05, minutes: Long = 10): String =
		"""{"schemaVersion":1,"frameId":${end.toEpochSecond()},"windowStart":"${end.minusMinutes(minutes)}","windowEnd":"$end",
		   "samples":[{"muExtendedId":$mu,"sensorIndex":0,"metric":"mean","value":$value,"converted":true,"uStd":$uStd,"qualityFlags":1}]}"""

	private fun feed(message: String) = conformity.process(conformity.parse(message))

	/* ------------------------------------------------------------------------- 4.2 */

	@Test
	fun `4_2 sostituendo la MU la serie del punto di controllo resta continua`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		service.assign(point.id, AssignRequest(cellB, 0, from = t0.plusDays(10)))

		val history = assignments.findAllByControlPointIdOrderByFromTsDesc(point.id)
		assertEquals(2, history.size)
		assertEquals(history[0].fromTs.toInstant(), history[1].toTs?.toInstant(), "la nuova assegnazione comincia dove finisce la vecchia")

		// Valori della MU A prima del cambio e della MU B dopo: tutti dello stesso punto di controllo.
		assertEquals(point.id, feed(report(cellA, t0.plusDays(1), -20.0)).single().first)
		assertTrue(feed(report(cellA, t0.plusDays(11), -20.0)).isEmpty(), "la MU A non sorveglia piu' la cella")
		assertEquals(point.id, feed(report(cellB, t0.plusDays(11), -20.0)).single().first)
	}

	@Test
	fun `4_2 le eccezioni del cliente possono solo restringere i limiti`() {
		val point = cell(mapOf("limits" to mapOf("high" to -20.0), "reason" to "Piano HACCP aziendale, rev. 4"))
		assertEquals(-20.0, point.limitHigh)
		assertThrows<BadRequest> { cell(mapOf("limits" to mapOf("high" to -15.0), "reason" to "comodo")) }
		assertThrows<BadRequest> { cell(mapOf("limits" to mapOf("high" to -21.0))) }
	}

	/* ------------------------------------------------------------------------- 4.3 */

	@Test
	fun `4_3 conforme, zona di guardia, non conforme con U = k·u`() {
		val limits = Limits(null, -18.0)
		assertEquals(SampleVerdict.CONFORM, Conformity.judge(-18.1, 0.1, limits).verdict, "y = H - U e' ancora conforme")
		assertEquals(SampleVerdict.GUARD_ZONE, Conformity.judge(-18.05, 0.1, limits).verdict)
		assertEquals(SampleVerdict.GUARD_ZONE, Conformity.judge(-17.9, 0.1, limits).verdict, "y = H + U e' ancora zona di guardia")
		assertEquals(SampleVerdict.NON_CONFORM, Conformity.judge(-17.8, 0.1, limits).verdict)
		assertEquals(SampleVerdict.NON_CONFORM, Conformity.judge(1.0, 0.1, Limits(2.0, 8.0)).verdict, "a specchio sul limite inferiore")
		assertEquals(SampleVerdict.NOT_EVALUABLE, Conformity.judge(1.0, 0.1, Limits(null, null)).verdict)
	}

	@Test
	fun `4_3 un valore nella zona di guardia viene marcato come tale`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		val (_, judgement) = feed(report(cellA, t0.plusHours(1), -18.05, uStd = 0.05)).single()
		assertEquals(SampleVerdict.GUARD_ZONE, judgement.verdict)
		assertEquals("GUARD_ZONE", service.detail(point.id).lastVerdict)
		assertTrue(excursions.findAllByControlPointIdOrderByStartedAtDesc(point.id).isEmpty(), "una zona di guardia breve si marca soltanto")
	}

	/* ------------------------------------------------------------------------- 4.4 */

	@Test
	fun `4_4 un valore oltre il limite apre un'escursione e una non conformita' di processo`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		feed(report(cellA, t0.plusHours(1), -20.0))
		feed(report(cellA, t0.plusHours(1).plusMinutes(10), -17.5, uStd = 0.05))
		feed(report(cellA, t0.plusHours(1).plusMinutes(20), -17.0, uStd = 0.05))

		val excursion = excursions.findByControlPointIdAndState(point.id, ExcursionState.OPEN)
		assertNotNull(excursion)
		assertEquals(ExcursionKind.LIMIT, excursion.kind)
		assertEquals(-17.0, excursion.peak)
		assertEquals(0.5 * 10 + 1.0 * 10, excursion.degreeMinutes, 1e-9, "grado-minuti sopra -18")
		val nc = nonconformities.findById(excursion.nonconformityId!!).orElseThrow()
		assertEquals(NonconformityType.PROCESS, nc.type)
		assertEquals(Severity.MAJOR, nc.severity)

		// Il primo valore conforme chiude l'escursione; la non conformita' resta da gestire.
		feed(report(cellA, t0.plusHours(1).plusMinutes(30), -19.5))
		assertNull(excursions.findByControlPointIdAndState(point.id, ExcursionState.OPEN))
		assertEquals(NonconformityState.OPEN, nonconformities.findById(nc.id).orElseThrow().state)
		Mockito.verify(mail, Mockito.atLeastOnce()).send(any(SimpleMailMessage::class.java))
	}

	@Test
	fun `4_4 la zona di guardia prolungata apre un'escursione minore, che un superamento rende maggiore`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		for (minutes in listOf(10L, 20L, 30L, 40L)) feed(report(cellA, t0.plusMinutes(minutes), -18.02, uStd = 0.05))

		val excursion = excursions.findByControlPointIdAndState(point.id, ExcursionState.OPEN)
		assertNotNull(excursion, "oltre 30 minuti di zona di guardia")
		assertEquals(ExcursionKind.GUARD_ZONE, excursion.kind)
		assertEquals(Severity.MINOR, nonconformities.findById(excursion.nonconformityId!!).orElseThrow().severity)

		feed(report(cellA, t0.plusMinutes(50), -17.0))
		val escalated = excursions.findById(excursion.id).orElseThrow()
		assertEquals(ExcursionKind.LIMIT, escalated.kind)
		assertEquals(Severity.MAJOR, nonconformities.findById(excursion.nonconformityId!!).orElseThrow().severity)
	}

	@Test
	fun `4_4 un evento ripetuto non riapre ne' conta due volte`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		val message = report(cellA, t0.plusHours(2), -17.0)
		feed(message)
		feed(message)
		val all = excursions.findAllByControlPointIdOrderByStartedAtDesc(point.id)
		assertEquals(1, all.size)
		assertEquals(1, all.single().samples)
	}

	/* ------------------------------------------------------------------------- 4.5 */

	@Test
	fun `4_5 una scadenza a 30 giorni produce la mail e il badge`() {
		val item = items.resolveOrCreate(cellA, 0)
		val today = LocalDate.of(2027, 3, 1)
		certificates.save(
			Certificate(
				kind = CertificateKind.EXTERNAL,
				subjectType = SubjectType.ITEM,
				subjectId = item.id,
				number = "LAT-042-2026-0001",
				calibrationDate = today.minusDays(340),
				validUntil = today.plusDays(25).atStartOfDay().atOffset(ZoneOffset.UTC),
				status = CertificateStatus.EFFECTIVE,
				source = CertificateSource.PDF_MANUAL,
				createdBy = "cliente",
			)
		)
		em.flush()

		assertEquals(1, schedule.recomputeAll(today), "un promemoria, quello dei 30 giorni")
		val row = scheduleItems.findBySubjectTypeAndSubjectIdAndKind(SubjectType.ITEM, item.id, ScheduleKind.CALIBRATION)
		assertNotNull(row)
		assertEquals(listOf(90, 30), row.notifiedLevels, "il livello dei 90 giorni e' gia' superato: non parte da solo")
		Mockito.verify(mail).send(Mockito.argThat<SimpleMailMessage> { it.subject!!.contains("Fra 25 giorni") && it.to!!.contains("laboratorio@example.test") })

		val entry = schedule.entries(today).single { it.subjectId == item.id }
		assertEquals(DueLevel.WITHIN_30, entry.level)
		assertEquals(0, schedule.remind(today), "una volta sola per livello")
	}

	/* ------------------------------------------------------------------------- 5.2 */

	@Test
	fun `5_2 una verifica intermedia - En, esito, e la scadenza che riparte`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		val ok = checks.record(CheckRequest(cellA, 0, referenceValue = -18.40, referenceU = 0.1, indicatedValue = -18.30, checkedAt = t0.plusDays(10)))
		assertEquals(CheckVerdict.PASS, ok.verdict)
		assertEquals(2 * 0.5 / kotlin.math.sqrt(3.0), ok.indicatedU, 1e-12, "senza incertezza dell'indicazione: quella di targa dal profilo")
		val row = scheduleItems.findBySubjectTypeAndSubjectIdAndKind(SubjectType.ITEM, ok.calibratedItemId, ScheduleKind.INTERMEDIATE_CHECK)
		assertNotNull(row)
		assertEquals(t0.plusDays(10).plusMonths(3).toInstant(), row.dueAt!!.toInstant(), "la prossima verifica a tre mesi dall'ultima (P3M)")
	}

	@Test
	fun `5_2 una verifica fallita apre una non conformita' di strumento`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		val failed = checks.record(CheckRequest(cellA, 0, referenceValue = -18.4, referenceU = 0.1, indicatedValue = -17.7, indicatedU = 0.1))
		assertEquals(CheckVerdict.FAIL, failed.verdict)
		val nc = nonconformities.findById(failed.nonconformityId!!).orElseThrow()
		assertEquals(NonconformityType.INSTRUMENT, nc.type)
	}

	@Test
	fun `rodaggio - l'amministratore cancella una verifica scritta male, la non conformita' resta`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		val failed = checks.record(CheckRequest(cellA, 0, referenceValue = -18.4, referenceU = 0.1, indicatedValue = -17.7, indicatedU = 0.1))
		assertThrows<it.polito.measurestream.metrology.common.Forbidden> { checks.delete(failed.id) }

		login("amministratore", Roles.ADMIN)
		checks.delete(failed.id)
		assertTrue(checks.list(failed.calibratedItemId).none { it.id == failed.id })
		assertTrue(nonconformities.findById(failed.nonconformityId!!).isPresent, "la non conformita' la chiude chi la gestisce")
	}

	@Test
	fun `5_2 un riferimento troppo incerto per il profilo non vale come verifica`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		assertThrows<BadRequest> { checks.record(CheckRequest(cellA, 0, referenceValue = -18.4, referenceU = 0.4, indicatedValue = -18.3)) }
	}

	@Test
	fun `5_2 la carta di controllo dalle verifiche`() {
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		listOf(0.05, 0.07, 0.04, 0.06).forEachIndexed { i, e ->
			checks.record(CheckRequest(cellA, 0, referenceValue = -18.4, referenceU = 0.1, indicatedValue = -18.4 + e, indicatedU = 0.2, checkedAt = t0.plusMonths(i.toLong())))
		}
		val item = items.resolveOrCreate(cellA, 0)
		val chart = charts.chart(item.id)
		assertEquals(4, chart.points.size)
		assertEquals(0.055, chart.center!!, 1e-9)
		assertNotNull(chart.ucl)
	}

	@Test
	fun `5_2 OIML D 10 a gradini - trovato bene, l'intervallo si propone piu' lungo e chi ha il ruolo lo accetta`() {
		val item = items.resolveOrCreate(cellA, 0)
		val certificate =
			certificates.save(
				Certificate(
					kind = CertificateKind.EXTERNAL,
					subjectType = SubjectType.ITEM,
					subjectId = item.id,
					number = "LAT-042-2026-0002",
					calibrationDate = LocalDate.of(2026, 3, 1),
					validUntil = OffsetDateTime.of(2027, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC),
					status = CertificateStatus.ENTERED,
					source = CertificateSource.PDF_MANUAL,
					createdBy = "cliente",
				).also { c ->
					c.points.add(CertificatePoint(certificate = c, phase = PointPhase.AS_FOUND, pointIndex = 0, referenceValue = -18.0, indicatedValue = -17.9, error = 0.1))
				}
			)
		em.flush()
		// I punti si scrivono prima: dopo l'entrata in vigore il database li blocca.
		certificate.status = CertificateStatus.EFFECTIVE
		certificates.save(certificate)
		val point = cell()
		service.assign(point.id, AssignRequest(cellA, 0, from = t0))
		em.flush()

		val proposal = assertNotNull(intervals.proposeAfter(certificate))
		assertEquals(365, proposal.currentDays, "P1Y del profilo")
		assertEquals(456, proposal.proposedDays, "errore al 20 % della tolleranza: si allunga del 25 %")

		assertThrows<it.polito.measurestream.metrology.common.Forbidden> { intervals.accept(proposal.id, ProposalDecision()) }
		roles.save(it.polito.measurestream.metrology.security.MetrologyRole(userSub = "cliente", role = it.polito.measurestream.metrology.security.MetrologyRoleType.APPROVER, validFrom = LocalDate.now().minusDays(1), grantedBy = "admin"))
		intervals.accept(proposal.id, ProposalDecision("storia stabile"))
		assertEquals(proposal.proposedDays, itemRepository.findById(item.id).orElseThrow().intervalOverrideDays)
	}

	/* ----------------------------------------------------------------- clima storico */

	@Test
	fun `4_1 il clima storico della EN 15757 dai dati del punto di controllo`() {
		val spec = ClimateSpec(movingAverageDays = 30, lowPercentile = 7.0, highPercentile = 93.0, minHalfBand = 10.0, minHistoryDays = 395)
		val start = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
		val hours = (0 until 400 * 24).map { h ->
			val day = h / 24.0
			start.plusHours(h.toLong()) to 50.0 + 10.0 * sin(2 * PI * day / 365.0) + 3.0 * sin(2 * PI * h / 24.0)
		}
		assertNull(HistoricClimateService.computeFrom(hours.take(300 * 24), spec), "meno di 395 giorni: non si calcola")

		val climate = assertNotNull(HistoricClimateService.computeFrom(hours, spec))
		assertEquals(-10.0, climate.bandLow, "fluttuazioni di ±3: la banda si allarga a ±10")
		assertEquals(10.0, climate.bandHigh)
		val doy = start.plusDays(380).dayOfYear
		val expected = 50.0 + 10.0 * sin(2 * PI * 380 / 365.0)
		assertEquals(expected, climate.daily.getValue(doy.toString()), 0.3)
	}
}
