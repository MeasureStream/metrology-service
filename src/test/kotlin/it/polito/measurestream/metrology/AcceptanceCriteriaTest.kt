package it.polito.measurestream.metrology

import it.polito.measurestream.metrology.activation.ActivationEvent
import it.polito.measurestream.metrology.activation.ActivationService
import it.polito.measurestream.metrology.activation.Outbox
import it.polito.measurestream.metrology.activation.OutboxEventRepository
import it.polito.measurestream.metrology.activation.OutboxPublisher
import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateDataRequest
import it.polito.measurestream.metrology.certificates.Conformity
import it.polito.measurestream.metrology.certificates.CertificateKind
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateService
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.HubService
import it.polito.measurestream.metrology.certificates.SubjectRef
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.CurrentUser
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.companies.ClientCompany
import it.polito.measurestream.metrology.companies.ClientCompanyMember
import it.polito.measurestream.metrology.companies.ClientCompanyMemberRepository
import it.polito.measurestream.metrology.companies.ClientCompanyRepository
import it.polito.measurestream.metrology.control.NonconformityDecision
import it.polito.measurestream.metrology.control.NonconformityRepository
import it.polito.measurestream.metrology.control.NonconformityService
import it.polito.measurestream.metrology.control.NonconformityState
import it.polito.measurestream.metrology.control.NonconformityType
import it.polito.measurestream.metrology.control.Notifier
import it.polito.measurestream.metrology.documents.DocumentService
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.items.RemoteMeasurementUnit
import it.polito.measurestream.metrology.items.RemoteSensor
import it.polito.measurestream.metrology.items.RemoteTemplateRef
import it.polito.measurestream.metrology.items.SensorManagerClient
import it.polito.measurestream.metrology.jobs.contract.CalibratedRange
import it.polito.measurestream.metrology.jobs.contract.CalibrationFunction
import it.polito.measurestream.metrology.jobs.contract.CalibrationPoint
import it.polito.measurestream.metrology.jobs.contract.CalibrationResult
import it.polito.measurestream.metrology.jobs.contract.Coefficient
import it.polito.measurestream.metrology.jobs.contract.DocumentKind
import it.polito.measurestream.metrology.jobs.contract.DocumentRef
import it.polito.measurestream.metrology.jobs.contract.ItemResult
import it.polito.measurestream.metrology.jobs.contract.JobKind
import it.polito.measurestream.metrology.jobs.contract.JobStatus
import it.polito.measurestream.metrology.jobs.contract.Phase
import it.polito.measurestream.metrology.jobs.contract.ConformityResult
import it.polito.measurestream.metrology.jobs.contract.ConformityTarget
import it.polito.measurestream.metrology.jobs.contract.SourceRef
import it.polito.measurestream.metrology.jobs.contract.SuggestedDecision
import it.polito.measurestream.metrology.references.ReferenceService
import it.polito.measurestream.metrology.references.ReferenceStandard
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRole
import it.polito.measurestream.metrology.security.MetrologyRoleRepository
import it.polito.measurestream.metrology.security.MetrologyRoleType
import it.polito.measurestream.metrology.security.Roles
import it.polito.measurestream.metrology.sessions.CalibrationResultRepository
import it.polito.measurestream.metrology.sessions.CalibrationSession
import it.polito.measurestream.metrology.sessions.CalibrationSessionRepository
import it.polito.measurestream.metrology.sessions.DecisionRequest
import it.polito.measurestream.metrology.sessions.EngineController
import it.polito.measurestream.metrology.sessions.JobService
import it.polito.measurestream.metrology.sessions.SessionProgress
import it.polito.measurestream.metrology.sessions.SessionService
import it.polito.measurestream.metrology.sessions.SessionStep
import it.polito.measurestream.metrology.sessions.SessionStepRepository
import it.polito.measurestream.metrology.sessions.StepPhase
import it.polito.measurestream.metrology.signing.SigningKeys
import it.polito.measurestream.metrology.signing.SigningService
import it.polito.measurestream.metrology.signing.TrustStore
import it.polito.measurestream.metrology.signing.Verdict
import it.polito.measurestream.metrology.signing.VerificationService
import it.polito.measurestream.metrology.support.EmbeddedDatabase
import it.polito.measurestream.metrology.support.login
import jakarta.persistence.EntityManager
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * I criteri «fatto quando» delle fasi 1 e 3 di PIANO_METROLOGIA che non hanno un test loro
 * altrove: ognuno e' un caso, con il numero del passo nel nome.
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
	OutboxPublisher::class,
	ActivationService::class,
	CertificateService::class,
	ReferenceService::class,
	SigningKeys::class,
	SigningService::class,
	TrustStore::class,
	VerificationService::class,
	HubService::class,
	SessionProgress::class,
	JobService::class,
	SessionService::class,
	EngineController::class,
	NonconformityService::class,
	Notifier::class,
)
class AcceptanceCriteriaTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)
	}

	@MockitoBean lateinit var sensorManager: SensorManagerClient
	@MockitoBean lateinit var kafka: KafkaTemplate<String, String>

	@Autowired lateinit var certificates: CertificateService
	@Autowired lateinit var certificateRepository: CertificateRepository
	@Autowired lateinit var documents: DocumentService
	@Autowired lateinit var references: ReferenceService
	@Autowired lateinit var referenceRepository: ReferenceStandardRepository
	@Autowired lateinit var items: ItemService
	@Autowired lateinit var itemRepository: CalibratedItemRepository
	@Autowired lateinit var outbox: OutboxEventRepository
	@Autowired lateinit var publisher: OutboxPublisher
	@Autowired lateinit var hub: HubService
	@Autowired lateinit var companies: ClientCompanyRepository
	@Autowired lateinit var members: ClientCompanyMemberRepository
	@Autowired lateinit var roles: MetrologyRoleRepository
	@Autowired lateinit var jobs: JobService
	@Autowired lateinit var results: CalibrationResultRepository
	@Autowired lateinit var sessions: CalibrationSessionRepository
	@Autowired lateinit var verification: VerificationService
	@Autowired lateinit var em: EntityManager
	@Autowired lateinit var sessionService: SessionService
	@Autowired lateinit var steps: SessionStepRepository
	@Autowired lateinit var engine: EngineController
	@Autowired lateinit var nonconformityRepository: NonconformityRepository

	private val euid = 439041101L

	private fun mu(extendedId: Long, owner: String) =
		RemoteMeasurementUnit(
			id = extendedId,
			extendedId = extendedId,
			sensors = listOf(RemoteSensor(id = 1, modelName = "TemperatureSensorNTC", sensorIndex = 0, template = RemoteTemplateRef(201, 2, type = "temperature"))),
			ownerSub = owner,
		)

	@BeforeEach
	fun setUp() {
		val since = LocalDate.now().minusYears(1)
		roles.save(MetrologyRole(userSub = "anna", role = MetrologyRoleType.TECHNICIAN, validFrom = since, grantedBy = "admin"))
		roles.save(MetrologyRole(userSub = "bruno", role = MetrologyRoleType.APPROVER, validFrom = since, grantedBy = "admin"))
		Mockito.`when`(sensorManager.measurementUnit(anyLong())).thenAnswer { inv ->
			val id = inv.getArgument<Long>(0)
			if (id == euid) mu(euid, "cliente") else null
		}
		Mockito.`when`(sensorManager.measurementUnits()).thenReturn(listOf(mu(euid, "cliente"), mu(77L, "altro")))
	}

	/* ------------------------------------------------------------------- fase 1 */

	@Test
	fun `1_2 un esemplare per una MU altrui viene rifiutato con 403`() {
		login("cliente", Roles.USER)
		assertThrows<Forbidden> { items.resolveOrCreate(999L, 0) }
		assertNotNull(items.resolveOrCreate(euid, 0), "la propria MU si risolve")
	}

	@Test
	fun `1_4 un utente non scarica i documenti di un altro`() {
		login("cliente", Roles.USER)
		val mine = documents.store("%PDF di cliente".toByteArray(), "application/pdf", "a.pdf", "ATTACHMENT", "cliente", "cliente")
		login("estraneo", Roles.USER)
		assertThrows<Forbidden> { documents.readable(mine.id) }
		login("anna", Roles.USER)
		assertNotNull(documents.readable(mine.id), "il personale del laboratorio lo vede")
	}

	@Test
	fun `1_5 un campione con certificato scaduto non e' utilizzabile alla data`() {
		login("anna", Roles.USER)
		val reference = referenceRepository.save(ReferenceStandard(name = "Fluke 9142", serialNumber = "B12345", createdBy = "anna"))
		val draft = certificates.createExternal(SubjectRef(referenceId = reference.id), "%PDF rif".toByteArray(), "application/pdf", "rif.pdf")
		certificates.enterData(
			draft.id,
			CertificateDataRequest(
				number = "LAT-9/2025",
				issuer = "Laboratorio accreditato",
				calibrationDate = LocalDate.now().minusYears(2),
				validUntil = OffsetDateTime.now().minusDays(1),
			),
		)
		login("bruno", Roles.USER)
		certificates.verify(draft.id)
		certificates.makeEffective(draft.id)
		em.flush()
		em.clear()

		val dto = references.toDTO(referenceRepository.findById(reference.id).get())
		assertEquals(false, dto.usable)
		assertTrue(dto.unusableReason!!.contains("scaduto"), dto.unusableReason)
	}

	@Test
	fun `1_7 con Kafka spento l'attivazione resta in outbox e parte alla ripresa`() {
		val effective = externalEffectiveCertificate("LAT-1")
		assertEquals(CertificateStatus.EFFECTIVE, effective.status)

		Mockito.`when`(kafka.send(Mockito.any<ProducerRecord<String, String>>()))
			.thenReturn(CompletableFuture.failedFuture(RuntimeException("broker non raggiungibile")))
		publisher.publish()
		val waiting = outbox.findTop100BySentAtIsNullOrderByIdAsc().single { it.topic == ActivationEvent.TOPIC }
		assertEquals(1, waiting.attempts)
		assertNull(waiting.sentAt)

		Mockito.`when`(kafka.send(Mockito.any<ProducerRecord<String, String>>()))
			.thenReturn(CompletableFuture.completedFuture(Mockito.mock(SendResult::class.java) as SendResult<String, String>))
		publisher.publish()
		assertTrue(outbox.findTop100BySentAtIsNullOrderByIdAsc().isEmpty(), "consegnata alla ripresa")
	}

	@Test
	fun `1_8 i clienti con il numero di sensori, e ognuno vede solo la sua azienda`() {
		val acme = companies.save(ClientCompany(name = "Acme Surgelati"))
		members.save(ClientCompanyMember("cliente", acme.id, "Cliente Acme", null, "admin"))
		externalEffectiveCertificate("LAT-2")

		login("admin", Roles.ADMIN)
		val clients = hub.clients()
		assertEquals(1, clients.single { it.id == acme.id }.sensors)
		assertEquals(1, clients.single { it.id == HubService.UNASSIGNED }.sensors, "la MU di «altro», che non ha azienda")

		login("cliente", Roles.USER)
		assertEquals(listOf("LAT-2"), hub.certificates(null).map { it.number })
		login("altro", Roles.USER)
		assertTrue(hub.certificates(acme.id).isEmpty(), "chiedere un'altra azienda non serve: il perimetro e' il proprio")
	}

	/* ------------------------------------------------------------------- fase 3 */

	@Test
	fun `3_3 un risultato consegnato due volte produce un solo risultato`() {
		val (session, job) = sessionWithJob(JobKind.SESSION_FIT)
		val result = fitResult(job, session)

		jobs.apply(result)
		jobs.apply(result)
		em.flush()
		assertEquals(1, results.findAllBySessionIdOrderByCreatedAtDesc(session.id).size)
	}

	@Test
	fun `3_7 e 3_8 approvatore diverso dal tecnico, firma, verifica con la revisione corrente`() {
		val (session, fitJob) = sessionWithJob(JobKind.SESSION_FIT)
		jobs.apply(fitResult(fitJob, session))
		val fit = results.findAllBySessionIdOrderByCreatedAtDesc(session.id).single()
		login("anna", Roles.USER)
		jobs.decide(fit.id, DecisionRequest("ADJUST"))

		// Il motore ha prodotto DCC e PDF: nasce la bozza interna, autore il tecnico.
		val xml = documents.store(dcc(), "application/xml", "dcc.xml", "DCC_XML", "cliente", "calibration-engine")
		val pdf = documents.store(pdf(), "application/pdf", "cert.pdf", "CERTIFICATE_PDF", "cliente", "calibration-engine")
		val docsJob = jobs.enqueue(JobKind.DOCUMENTS, session, "anna")
		jobs.apply(
			CalibrationResult(
				jobId = docsJob.id,
				kind = JobKind.DOCUMENTS,
				status = JobStatus.DONE,
				engineVersion = "test",
				startedAt = Instant.now(),
				finishedAt = Instant.now(),
				documents =
					listOf(
						DocumentRef(xml.id, DocumentKind.DCC_XML, xml.sha256),
						DocumentRef(pdf.id, DocumentKind.CERTIFICATE_PDF, pdf.sha256),
					),
			)
		)
		em.flush()
		val draft = certificateRepository.findAll().single { it.kind == CertificateKind.INTERNAL }
		assertEquals("anna", draft.createdBy)

		login("anna", Roles.USER)
		assertThrows<Forbidden> { certificates.approve(draft.id) }
		roles.save(MetrologyRole(userSub = "anna", role = MetrologyRoleType.APPROVER, validFrom = LocalDate.now(), grantedBy = "admin"))
		assertThrows<Conflict> { certificates.approve(draft.id) }

		login("bruno", Roles.USER)
		certificates.approve(draft.id)
		val signed = certificates.sign(draft.id)
		assertEquals(CertificateStatus.SIGNED, signed.status)
		certificates.makeEffective(draft.id)
		em.flush()

		// Il DCC firmato da noi: autentico, ed e' la revisione in vigore.
		val signedXml = documents.readable(signed.xmlDocumentId!!)
		val first = verification.verify(signedXml.content, "dcc.xml")
		assertEquals(Verdict.AUTHENTIC, first.verdict, first.reason)
		assertEquals(draft.id, first.ours?.certificateId)
		assertEquals(false, first.ours?.superseded)

		// Una revisione in vigore lo supera: lo stesso file resta autentico ma non e' piu' quello che vale.
		// La emette il tecnico, la approva un altro.
		login("anna", Roles.USER)
		val revision = certificates.revise(draft.id, "numero di serie trascritto male")
		login("bruno", Roles.USER)
		certificates.approve(revision.id)
		certificates.sign(revision.id)
		certificates.makeEffective(revision.id)
		em.flush()
		val again = verification.verify(signedXml.content, "dcc.xml")
		assertEquals(true, again.ours?.superseded)
		assertEquals(2, again.ours?.currentRevision)
	}

	/* --------------------------------------------- passo 3.5: as-found e as-left separati */

	@Test
	fun `3_5 con la regolazione la bozza porta la funzione nuova, l'as-found e l'as-left`() {
		val (session, fitJob) = sessionWithJob(JobKind.SESSION_FIT)
		jobs.apply(fitResult(fitJob, session))
		val fit = results.findAllBySessionIdOrderByCreatedAtDesc(session.id).single()
		login("anna", Roles.USER)
		jobs.decide(fit.id, DecisionRequest("ADJUST"))

		// Il contesto del motore porta l'ultimo fit con la decisione: VERIFICATION sa che cosa verificare.
		val verificationJob = jobs.enqueue(JobKind.VERIFICATION, session, "anna")
		@Suppress("UNCHECKED_CAST")
		val entry = (engine.context(verificationJob.id)["items"] as List<Map<String, Any?>>).single()
		@Suppress("UNCHECKED_CAST")
		assertEquals("ADJUST", (entry["latestFit"] as Map<String, Any?>)["decision"])

		jobs.apply(verificationResult(verificationJob))
		em.flush()
		assertTrue(jobs.pendingDecisions().none { it["kind"] == JobKind.VERIFICATION.name }, "l'as-left non chiede decisioni")

		val draft = draftAfterDocuments(session)
		assertEquals(fit.function, draft.function)
		assertEquals(setOf("AS_FOUND", "AS_LEFT"), draft.points.map { it.phase.name }.toSet())
		assertEquals(Conformity.CONFORM, draft.conformity, "la conformita' e' quella dell'as-left")
	}

	@Test
	fun `3_5 senza regolazione la bozza attesta la funzione che resta in vigore`() {
		val nominal = mapOf("type" to "steinhart_201", "steinhart_201" to "1.0 / (c[0] + c[1] * ln(4095.0 / x - 1.0)) - 273.15", "input" to listOf("x"), "c" to listOf(mapOf("id" to 0, "value" to 0.0032), mapOf("id" to 1, "value" to 0.00024)))
		val (session, fitJob) = sessionWithJob(JobKind.SESSION_FIT)
		jobs.apply(fitResult(fitJob, session, budget = mapOf("functionInForce" to nominal)))
		val fit = results.findAllBySessionIdOrderByCreatedAtDesc(session.id).single()
		login("anna", Roles.USER)
		jobs.decide(fit.id, DecisionRequest("NO_ADJUSTMENT", "scelta del cliente"))

		val draft = draftAfterDocuments(session)
		assertEquals(nominal, draft.function, "la formula nominale del modello, come la valutava il motore: il certificato puo' entrare in vigore")
		assertTrue(draft.function != fit.function, "non la funzione nuova del fit")
		assertEquals(setOf("AS_FOUND"), draft.points.map { it.phase.name }.toSet())
	}

	@Test
	fun `4_4 un as-found non conforme apre una non conformita' di strumento, la decisione la prende in carico`() {
		val (session, fitJob) = sessionWithJob(JobKind.SESSION_FIT)
		val nonConform =
			ConformityResult(ConformityTarget.SPECIFICATION, SourceRef("sensor", 201, "2.2.0"), "guarded (PFA <= 2 %)", 0.5, 0.9, it.polito.measurestream.metrology.jobs.contract.Verdict.NON_CONFORM)
		jobs.apply(fitResult(fitJob, session, conformity = listOf(nonConform)))
		em.flush()
		val nc = nonconformityRepository.findAll().single()
		assertEquals(NonconformityType.INSTRUMENT, nc.type)
		assertEquals(NonconformityState.OPEN, nc.state)
		assertEquals(0.8, (nc.subject["worstError"] as Number).toDouble())

		val fit = results.findAllBySessionIdOrderByCreatedAtDesc(session.id).single()
		login("anna", Roles.USER)
		jobs.decide(fit.id, DecisionRequest("ADJUST"))
		val taken = nonconformityRepository.findById(nc.id).orElseThrow()
		assertEquals(NonconformityDecision.ADJUST, taken.decision)
		assertEquals(NonconformityState.ACKNOWLEDGED, taken.state, "resta da valutare l'impatto sui dati misurati")
	}

	@Test
	fun `3_5 da riparare o da ritirare non nasce un certificato`() {
		val (session, fitJob) = sessionWithJob(JobKind.SESSION_FIT)
		jobs.apply(fitResult(fitJob, session))
		val fit = results.findAllBySessionIdOrderByCreatedAtDesc(session.id).single()
		login("anna", Roles.USER)
		jobs.decide(fit.id, DecisionRequest("REPAIR", "sonda danneggiata"))
		documentsResult(session)
		em.flush()
		assertTrue(certificateRepository.findAll().none { it.kind == CertificateKind.INTERNAL })
	}

	@Test
	fun `3_5 i documenti dopo una regolazione aspettano l'as-left della procedura`() {
		Mockito.`when`(sensorManager.template("procedure", 401, "1.0.0")).thenReturn(mapOf("content" to mapOf("asLeft" to mapOf("points" to listOf(10.0)))))
		val (session, fitJob) = sessionWithJob(JobKind.SESSION_FIT)
		jobs.apply(fitResult(fitJob, session))
		val fit = results.findAllBySessionIdOrderByCreatedAtDesc(session.id).single()
		login("anna", Roles.USER)

		// Prima della decisione l'as-left non si chiude: non si sa quale funzione verificare.
		steps.save(SessionStep(sessionId = session.id, phase = StepPhase.AS_LEFT, stepIndex = 0, sensorIndex = 0, setpoint = 10.0, referenceReadings = listOf(10.0), sensorSamples = listOf(1000)))
		assertThrows<Conflict> { sessionService.closePhase(session.id, StepPhase.AS_LEFT) }

		jobs.decide(fit.id, DecisionRequest("ADJUST"))
		assertThrows<Conflict> { sessionService.requestDocuments(session.id) }

		sessionService.closePhase(session.id, StepPhase.AS_LEFT)
		val verificationJob = jobs.enqueue(JobKind.VERIFICATION, session, "anna")
		jobs.apply(verificationResult(verificationJob))
		em.flush()
		sessionService.requestDocuments(session.id)
	}

	/* ------------------------------------------------------------------ supporto */

	private fun externalEffectiveCertificate(number: String): it.polito.measurestream.metrology.certificates.CertificateDetailDTO {
		login("anna", Roles.USER)
		val draft = certificates.createExternal(SubjectRef(muExtendedId = euid, sensorIndex = 0), "%PDF $number".toByteArray(), "application/pdf", "$number.pdf")
		certificates.enterData(
			draft.id,
			CertificateDataRequest(
				number = number,
				issuer = "Laboratorio esterno",
				calibrationDate = LocalDate.now().minusDays(3),
				function = CalibrationFunction("linear", "c[0] + c[1] * x", listOf("x"), listOf(Coefficient(0, 0.0), Coefficient(1, 0.01))),
			),
		)
		login("bruno", Roles.USER)
		certificates.verify(draft.id)
		return certificates.makeEffective(draft.id)
	}

	private fun sessionWithJob(kind: JobKind): Pair<CalibrationSession, it.polito.measurestream.metrology.sessions.CalibrationJobRecord> {
		login("anna", Roles.USER)
		val item = items.resolveOrCreate(euid, 0)
		val reference = referenceRepository.save(ReferenceStandard(name = "Fluke 9142", serialNumber = "SN-${UUID.randomUUID()}", createdBy = "anna"))
		val session =
			sessions.save(
				CalibrationSession(
					muExtendedId = euid,
					referenceStandardId = reference.id,
					procedureTemplateId = 401,
					procedureVersion = "1.0.0",
					operatorSub = "anna",
					items = mutableSetOf(item),
				)
			)
		return session to jobs.enqueue(kind, session, "anna")
	}

	private fun fitResult(
		job: it.polito.measurestream.metrology.sessions.CalibrationJobRecord,
		session: CalibrationSession,
		budget: Map<String, Any?> = emptyMap(),
		conformity: List<ConformityResult> = emptyList(),
	): CalibrationResult {
		val item = itemRepository.findByMuExtendedIdAndSensorIndex(euid, 0)!!
		return CalibrationResult(
			jobId = job.id,
			kind = JobKind.SESSION_FIT,
			status = JobStatus.DONE,
			engineVersion = "test",
			startedAt = Instant.now(),
			finishedAt = Instant.now(),
			items =
				listOf(
					ItemResult(
						calibratedItemId = item.id,
						muExtendedId = euid,
						sensorIndex = 0,
						function = CalibrationFunction("linear_lsb", "c[0] + c[1] * x", listOf("x"), listOf(Coefficient(0, -5.0), Coefficient(1, 0.002))),
						covariance = listOf(listOf(0.01, 0.0), listOf(0.0, 1e-12)),
						calibratedRange = CalibratedRange(-20.0, 40.0),
						points = listOf(CalibrationPoint(Phase.AS_FOUND, 0, -20.0, -20.0, -19.2, 0.8)),
						budget = budget,
						conformity = conformity,
						suggestedDecision = SuggestedDecision.ADJUST,
					)
				),
		)
	}

	private fun verificationResult(job: it.polito.measurestream.metrology.sessions.CalibrationJobRecord): CalibrationResult {
		val item = itemRepository.findByMuExtendedIdAndSensorIndex(euid, 0)!!
		return CalibrationResult(
			jobId = job.id,
			kind = JobKind.VERIFICATION,
			status = JobStatus.DONE,
			engineVersion = "test",
			startedAt = Instant.now(),
			finishedAt = Instant.now(),
			items =
				listOf(
					ItemResult(
						calibratedItemId = item.id,
						muExtendedId = euid,
						sensorIndex = 0,
						function = CalibrationFunction("linear_lsb", "c[0] + c[1] * x", listOf("x"), listOf(Coefficient(0, -5.0), Coefficient(1, 0.002))),
						adjustmentApplied = true,
						points = listOf(CalibrationPoint(Phase.AS_LEFT, 0, 10.0, 10.0, 10.05, 0.05)),
						conformity =
							listOf(
								ConformityResult(
									ConformityTarget.SPECIFICATION,
									SourceRef("sensor", 201, "2.2.0"),
									"guarded (PFA <= 2 %)",
									0.3,
									0.001,
									it.polito.measurestream.metrology.jobs.contract.Verdict.CONFORM,
								)
							),
					)
				),
		)
	}

	private fun documentsResult(session: CalibrationSession) {
		val docsJob = jobs.enqueue(JobKind.DOCUMENTS, session, "anna")
		jobs.apply(CalibrationResult(jobId = docsJob.id, kind = JobKind.DOCUMENTS, status = JobStatus.DONE, engineVersion = "test", startedAt = Instant.now(), finishedAt = Instant.now()))
	}

	private fun draftAfterDocuments(session: CalibrationSession): Certificate {
		documentsResult(session)
		em.flush()
		return certificateRepository.findAll().single { it.kind == CertificateKind.INTERNAL }
	}

	private fun dcc() =
		"""<?xml version="1.0" encoding="UTF-8"?><dcc:digitalCalibrationCertificate xmlns:dcc="https://ptb.de/dcc"><dcc:administrativeData/></dcc:digitalCalibrationCertificate>"""
			.toByteArray()

	private fun pdf(): ByteArray = PDDocument().use { d -> d.addPage(PDPage()); ByteArrayOutputStream().also { d.save(it) }.toByteArray() }
}
