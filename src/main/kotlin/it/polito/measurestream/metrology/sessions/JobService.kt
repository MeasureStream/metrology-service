package it.polito.measurestream.metrology.sessions

import it.polito.measurestream.metrology.activation.Outbox
import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateKind
import it.polito.measurestream.metrology.certificates.CertificatePoint
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateService
import it.polito.measurestream.metrology.certificates.CertificateSource
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.Conformity
import it.polito.measurestream.metrology.certificates.HubProgressSource
import it.polito.measurestream.metrology.certificates.PointPhase
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.control.NonconformityService
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.jobs.contract.CalibrationJob
import it.polito.measurestream.metrology.jobs.contract.CalibrationResult
import it.polito.measurestream.metrology.jobs.contract.CalibrationTopics
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import it.polito.measurestream.metrology.jobs.contract.DocumentKind
import it.polito.measurestream.metrology.jobs.contract.JobKind
import it.polito.measurestream.metrology.jobs.contract.JobStatus
import it.polito.measurestream.metrology.jobs.contract.TemplateVersionRef
import it.polito.measurestream.metrology.jobs.contract.Verdict
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Year
import java.time.ZoneOffset
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class DecisionRequest(val decision: String, val reason: String? = null)

/**
 * La coda verso il motore e il ritorno dei risultati (PIANO_METROLOGIA 3.3).
 *
 * Il job parte dall'outbox, nella transazione che lo decide; il risultato si applica una volta
 * sola: un job gia' chiuso ignora una seconda consegna dello stesso messaggio.
 */
@Service
class JobService(
	private val jobs: CalibrationJobRepository,
	private val results: CalibrationResultRepository,
	private val sessions: CalibrationSessionRepository,
	private val certificates: CertificateRepository,
	private val certificateService: CertificateService,
	private val items: CalibratedItemRepository,
	private val labs: LabCompanyRepository,
	private val outbox: Outbox,
	private val policy: AccessPolicy,
	private val audit: AuditService,
	private val nonconformities: NonconformityService,
) {
	private val log = LoggerFactory.getLogger(JobService::class.java)

	fun enqueue(kind: JobKind, session: CalibrationSession, requestedBy: String, params: Map<String, Any?> = emptyMap()): CalibrationJobRecord {
		val message =
			CalibrationJob(
				jobId = UUID.randomUUID(),
				kind = kind,
				sessionId = session.id,
				procedure = TemplateVersionRef(session.procedureTemplateId, session.procedureVersion),
				referenceStandardId = session.referenceStandardId,
				referenceCertificateId = session.referenceCertificateId,
				params = params,
				requestedBy = requestedBy,
				requestedAt = Instant.now(),
			)
		val json = ContractJson.write(message)
		@Suppress("UNCHECKED_CAST")
		val record =
			jobs.save(
				CalibrationJobRecord(
					id = message.jobId,
					kind = kind.name,
					sessionId = session.id,
					request = ContractJson.mapper.readValue(json, Map::class.java) as Map<String, Any?>,
					requestedBy = requestedBy,
				)
			)
		outbox.enqueue(CalibrationTopics.JOBS, message.jobId.toString(), json)
		audit.record("JOB_QUEUED", "calibration_job", null, detail = mapOf("jobId" to message.jobId.toString(), "kind" to kind.name, "sessionId" to session.id), userSub = requestedBy)
		return record
	}

	/** Un job su un certificato, senza sessione: CERT_FIT (passo 5.3). */
	fun enqueueForCertificate(kind: JobKind, certificateId: Long, requestedBy: String): CalibrationJobRecord {
		val message =
			CalibrationJob(jobId = UUID.randomUUID(), kind = kind, certificateId = certificateId, requestedBy = requestedBy, requestedAt = Instant.now())
		val json = ContractJson.write(message)
		@Suppress("UNCHECKED_CAST")
		val record =
			jobs.save(
				CalibrationJobRecord(
					id = message.jobId,
					kind = kind.name,
					certificateId = certificateId,
					request = ContractJson.mapper.readValue(json, Map::class.java) as Map<String, Any?>,
					requestedBy = requestedBy,
				)
			)
		outbox.enqueue(CalibrationTopics.JOBS, message.jobId.toString(), json)
		audit.record("JOB_QUEUED", "calibration_job", null, detail = mapOf("jobId" to message.jobId.toString(), "kind" to kind.name, "certificateId" to certificateId), userSub = requestedBy)
		return record
	}

	/** Applica un risultato del motore. Idempotente sull'id del job. */
	@Transactional
	fun apply(result: CalibrationResult) {
		val job = jobs.findById(result.jobId).orElse(null)
		if (job == null) {
			log.warn("Risultato per il job {} che questo servizio non ha mai chiesto: ignorato", result.jobId)
			return
		}
		if (job.status == JobRecordStatus.DONE || job.status == JobRecordStatus.FAILED) {
			log.info("Risultato del job {} gia' applicato: seconda consegna ignorata", result.jobId)
			return
		}

		@Suppress("UNCHECKED_CAST")
		job.response = ContractJson.mapper.readValue(ContractJson.write(result), Map::class.java) as Map<String, Any?>
		job.status = if (result.status == JobStatus.DONE) JobRecordStatus.DONE else JobRecordStatus.FAILED
		job.startedAt = result.startedAt.atOffset(ZoneOffset.UTC)
		job.finishedAt = result.finishedAt.atOffset(ZoneOffset.UTC)
		job.engineVersion = result.engineVersion.take(64)
		job.error = result.error?.let { "${it.code}: ${it.message}" }
		jobs.save(job)

		if (result.status == JobStatus.DONE) {
			when (result.kind) {
				JobKind.SESSION_FIT, JobKind.VERIFICATION, JobKind.CERT_FIT -> storeItemResults(job, result)
				JobKind.DOCUMENTS -> createDraftCertificates(job, result)
			}
		}
		audit.record(
			"JOB_" + job.status.name,
			"calibration_job",
			null,
			detail = mapOf("jobId" to job.id.toString(), "kind" to job.kind, "error" to job.error),
			userSub = "calibration-engine",
		)
	}

	private fun storeItemResults(job: CalibrationJobRecord, result: CalibrationResult) {
		for (item in result.items) {
			if (results.findByJobIdAndCalibratedItemId(job.id, item.calibratedItemId) != null) continue
			@Suppress("UNCHECKED_CAST")
			fun <T> asJson(value: Any?, type: Class<T>): T? = value?.let { ContractJson.mapper.readValue(ContractJson.write(it), type) }
			@Suppress("UNCHECKED_CAST")
			results.save(
				CalibrationResultRecord(
					jobId = job.id,
					kind = result.kind,
					sessionId = job.sessionId,
					calibratedItemId = item.calibratedItemId,
					function = item.function?.let { ContractJson.plain(it.toWire()) },
					covariance = item.covariance,
					calibratedMin = item.calibratedRange?.min,
					calibratedMax = item.calibratedRange?.max,
					calibratedDsi = item.calibratedRange?.dsi,
					points = asJson(item.points, List::class.java) as List<Map<String, Any?>>?,
					budget = item.budget,
					conformity = asJson(item.conformity, List::class.java) as List<Map<String, Any?>>?,
					suggestedDecision = item.suggestedDecision.name,
					adjustmentApplied = item.adjustmentApplied,
				)
			).also { saved ->
				if (result.kind == JobKind.SESSION_FIT) instrumentNonconformity(job, saved)
				if (result.kind == JobKind.CERT_FIT) applyCertFit(job, saved)
			}
		}
	}

	/**
	 * La funzione ricavata dai punti di un certificato esterno entra nel certificato, se e' ancora
	 * modificabile (bozza o trascritto) e non ne ha una: dopo la verifica il contenuto e' bloccato,
	 * e la funzione resta nel risultato, da riportare con una revisione.
	 */
	private fun applyCertFit(job: CalibrationJobRecord, record: CalibrationResultRecord) {
		val certificate = job.certificateId?.let { certificates.findById(it).orElse(null) } ?: return
		if (certificate.status !in setOf(CertificateStatus.DRAFT, CertificateStatus.ENTERED) || certificate.function != null) {
			log.info("CERT_FIT del certificato {}: il certificato e' {}, la funzione resta nel risultato {}", certificate.id, certificate.status, record.id)
			return
		}
		certificate.function = record.function
		certificate.covariance = record.covariance
		certificate.calibratedMin = record.calibratedMin
		certificate.calibratedMax = record.calibratedMax
		certificate.calibratedDsi = record.calibratedDsi
		certificate.calibrationResultId = record.id
		certificate.fingerprint = certificateService.fingerprint(certificate)
		certificates.save(certificate)
		audit.record("CERTIFICATE_FUNCTION_FITTED", "certificate", certificate.id, detail = mapOf("resultId" to record.id, "jobId" to job.id.toString()), userSub = "calibration-engine")
		// Un DCC firmato da un laboratorio riconosciuto aspettava solo la funzione (passo 5.4).
		certificate.autoVerifyReason?.let { reason ->
			certificate.status = CertificateStatus.VERIFIED
			certificate.verifiedBy = "dcc-signature"
			certificate.verifiedAt = OffsetDateTime.now()
			certificates.save(certificate)
			audit.record("CERTIFICATE_VERIFIED_BY_SIGNATURE", "certificate", certificate.id, detail = mapOf("reason" to reason), userSub = "calibration-engine")
		}
	}

	/**
	 * Un as-found non conforme alle specifiche del modello apre una non conformita' di strumento
	 * (PIANO_METROLOGIA 4.4): i dati misurati dall'esemplare dall'ultima taratura sono sospetti, e
	 * la decisione su regolare, riparare o ritirare la prende il laboratorio.
	 */
	private fun instrumentNonconformity(job: CalibrationJobRecord, record: CalibrationResultRecord) {
		val conformity = record.conformity.orEmpty()
		if (conformity.none { it["verdict"] == Verdict.NON_CONFORM.name }) return
		val item = items.findById(record.calibratedItemId).orElse(null) ?: return
		val found = record.points.orEmpty().filter { it["phase"] == PointPhase.AS_FOUND.name }
		val worst = found.mapNotNull { (it["error"] as? Number)?.toDouble() }.maxByOrNull { kotlin.math.abs(it) }
		val tolerance = conformity.firstNotNullOfOrNull { (it["tolerance"] as? Number)?.toDouble() }
		val since = certificates.findAllBySubjectTypeAndSubjectIdAndStatus(SubjectType.ITEM, item.id, CertificateStatus.EFFECTIVE).firstOrNull()?.calibrationDate
		nonconformities.openInstrument(
			item,
			mapOf(
				"resultId" to record.id,
				"jobId" to job.id.toString(),
				"sessionId" to record.sessionId,
				"muExtendedId" to item.muExtendedId,
				"sensorIndex" to item.sensorIndex,
				"worstError" to worst,
				"tolerance" to tolerance,
				// Testo ISO: il JSON delle colonne jsonb non conosce le date di java.time.
				"suspectSince" to since?.toString(),
				"points" to found,
			),
			"As-found fuori tolleranza: MU 0x%08X sensore %d (errore %s, tolleranza %s)".format(
				item.muExtendedId, item.sensorIndex, worst?.let { "%.3g".format(it) } ?: "?", tolerance?.let { "%.3g".format(it) } ?: "?"
			),
			record.createdAt,
		)
	}

	/**
	 * DCC e PDF sono pronti: per ogni esemplare nasce il certificato interno in bozza, con il
	 * tecnico della sessione come autore, cosi' l'approvatore sara' per forza un'altra persona.
	 *
	 * Che cosa ci va dentro dipende dalla decisione presa sull'ultimo fit (passo 3.5):
	 * - ADJUST: la funzione nuova, che diventera' quella dell'esemplare quando il certificato
	 *   entra in vigore; punti as-found dal fit e as-left dalla verifica sui punti indipendenti;
	 *   la conformita' e' quella dell'as-left, cioe' dello stato in cui lo strumento esce;
	 * - NO_ADJUSTMENT: nessuna funzione nuova. Il certificato riporta quella gia' in vigore (se
	 *   c'e'), cosi' all'attivazione l'esemplare continua a convertire come prima; la
	 *   conformita' e' quella dell'as-left se e' stato misurato, altrimenti dell'as-found;
	 * - REPAIR e RETIRE: nessun certificato. Lo strumento va riparato o tolto dal servizio.
	 */
	private fun createDraftCertificates(job: CalibrationJobRecord, result: CalibrationResult) {
		val session = job.sessionId?.let { sessions.findById(it).orElse(null) } ?: return
		val lab = labs.findFirstByInternalTrue()
		val all = results.findAllBySessionIdOrderByCreatedAtDesc(session.id)
		val fits = all.latestByItem(JobKind.SESSION_FIT)
		val verifications = all.latestByItem(JobKind.VERIFICATION)
		val year = Year.now().value

		for (item in session.items) {
			val fit = fits[item.id] ?: continue
			if (fit.decision == "REPAIR" || fit.decision == "RETIRE") {
				audit.record("CERTIFICATE_NOT_DRAFTED", "calibrated_item", item.id, detail = mapOf("sessionId" to session.id, "decision" to fit.decision), userSub = "calibration-engine")
				continue
			}
			val adjusted = fit.decision == "ADJUST"
			val verification = verifications[item.id]
			val current = certificates.findAllBySubjectTypeAndSubjectIdAndStatus(SubjectType.ITEM, item.id, CertificateStatus.EFFECTIVE).firstOrNull()
			// Senza regolazione il certificato attesta la funzione che resta in vigore: quella del
			// certificato precedente, o la formula nominale del modello come l'ha valutata il motore.
			@Suppress("UNCHECKED_CAST")
			val inForce = current?.function ?: verification?.function ?: (fit.budget?.get("functionInForce") as? Map<String, Any?>)
			val docs = result.documents.filter { it.calibratedItemId == null || it.calibratedItemId == item.id }
			val sequence = certificates.countByKind(CertificateKind.INTERNAL) + 1
			val verdicts = (verification?.conformity ?: fit.conformity).orEmpty()
			val nonConform = verdicts.any { it["verdict"] == Verdict.NON_CONFORM.name }
			val certificate =
				Certificate(
					kind = CertificateKind.INTERNAL,
					subjectType = SubjectType.ITEM,
					subjectId = item.id,
					number = "LAB-%d-%04d".format(year, sequence),
					labCompanyId = lab?.id,
					issuer = lab?.name,
					issuerAccreditation = lab?.accreditation(),
					calibrationDate = session.startedAt.toLocalDate(),
					issueDate = OffsetDateTime.now().toLocalDate(),
					validUntil = (session.endedAt ?: OffsetDateTime.now()).plusDays(item.intervalOverrideDays?.toLong() ?: 365),
					status = CertificateStatus.DRAFT,
					source = CertificateSource.ENGINE,
					function = if (adjusted) fit.function else inForce,
					covariance = if (adjusted) fit.covariance else current?.covariance,
					calibratedMin = if (adjusted) fit.calibratedMin else current?.calibratedMin,
					calibratedMax = if (adjusted) fit.calibratedMax else current?.calibratedMax,
					calibratedDsi = if (adjusted) fit.calibratedDsi else current?.calibratedDsi,
					conformity = if (verdicts.isEmpty()) Conformity.NOT_STATED else if (nonConform) Conformity.NON_CONFORM else Conformity.CONFORM,
					decisionRule = verdicts.firstOrNull()?.get("decisionRule") as? String,
					ambient = session.ambient,
					calibrationResultId = fit.id,
					xmlDocumentId = docs.firstOrNull { it.kind == DocumentKind.DCC_XML }?.documentId,
					pdfDocumentId = docs.firstOrNull { it.kind == DocumentKind.CERTIFICATE_PDF }?.documentId,
					createdBy = session.operatorSub,
				)
			val points = fit.points.orEmpty().filter { it["phase"] == PointPhase.AS_FOUND.name } +
				verification?.points.orEmpty().filter { it["phase"] == PointPhase.AS_LEFT.name }
			points.forEach { p ->
				certificate.points.add(
					CertificatePoint(
						certificate = certificate,
						phase = runCatching { PointPhase.valueOf(p["phase"] as String) }.getOrDefault(PointPhase.AS_LEFT),
						pointIndex = (p["pointIndex"] as? Number)?.toInt() ?: 0,
						nominal = (p["nominal"] as? Number)?.toDouble(),
						referenceValue = (p["referenceValue"] as? Number)?.toDouble(),
						indicatedValue = (p["indicatedValue"] as? Number)?.toDouble(),
						error = (p["error"] as? Number)?.toDouble(),
						correction = (p["correction"] as? Number)?.toDouble(),
						expandedU = (p["expandedU"] as? Number)?.toDouble(),
						k = (p["k"] as? Number)?.toDouble(),
						dsi = p["dsi"] as? String,
					)
				)
			}
			certificate.fingerprint = certificateService.fingerprint(certificate)
			val saved = certificates.save(certificate)
			audit.record(
				"CERTIFICATE_DRAFTED",
				"certificate",
				saved.id,
				detail = mapOf("jobId" to job.id.toString(), "sessionId" to session.id, "decision" to fit.decision, "asLeftResultId" to verification?.id),
				userSub = "calibration-engine",
			)
		}
	}

	/** La decisione del tecnico: regolare o no, con il motivo [S §5.3 punto 4]. */
	@Transactional
	fun decide(resultId: Long, request: DecisionRequest): Map<String, Any?> {
		val caller = policy.caller()
		policy.requireRole(caller, MetrologyRoleType.TECHNICIAN, MetrologyRoleType.APPROVER, what = "Decidere la regolazione")
		val result = results.findById(resultId).orElseThrow { NotFound("Risultato $resultId non trovato") }
		val decision = request.decision.uppercase()
		if (decision !in setOf("ADJUST", "NO_ADJUSTMENT", "REPAIR", "RETIRE")) throw BadRequest("Decisione non prevista: ${request.decision}")
		if (decision != "ADJUST" && result.suggestedDecision == "ADJUST" && request.reason.isNullOrBlank()) {
			throw BadRequest("Il motore suggeriva di regolare: per non farlo serve il motivo")
		}
		if (result.decision != null) throw Conflict("La decisione e' gia' stata presa il ${result.decidedAt}")
		result.decision = decision
		result.decisionReason = request.reason
		result.decidedBy = caller.sub
		result.decidedAt = OffsetDateTime.now()
		result.adjustmentApplied = decision == "ADJUST"
		nonconformities.recordInstrumentDecision(result.calibratedItemId, result.id, decision, request.reason, caller.sub)
		result.sessionId?.let { id ->
			sessions.findById(id).ifPresent {
				if (decision == "ADJUST") it.status = SessionStatus.ADJUSTED
				it.adjustmentApplied = (it.adjustmentApplied ?: false) || decision == "ADJUST"
			}
		}
		audit.record("ADJUSTMENT_DECIDED", "calibration_result", resultId, detail = mapOf("decision" to decision, "reason" to request.reason))
		return results.save(result).toMap()
	}

	@Transactional(readOnly = true)
	fun pendingDecisions(): List<Map<String, Any?>> {
		val caller = policy.caller()
		if (!policy.has(caller, MetrologyRoleType.TECHNICIAN, MetrologyRoleType.APPROVER)) return emptyList()
		return results.findAllByDecisionIsNullAndKind(JobKind.SESSION_FIT).map { it.toMap() }
	}
}

/** Il consumer di `calibration-results`. */
@Component
class CalibrationResultsListener(private val service: JobService) {
	private val log = LoggerFactory.getLogger(CalibrationResultsListener::class.java)

	@KafkaListener(topics = [CalibrationTopics.RESULTS], groupId = "metrology-service-results")
	fun consume(message: String) {
		val result =
			try {
				ContractJson.read(message, CalibrationResult::class.java)
			} catch (e: Exception) {
				log.error("Risultato del motore non leggibile: {}", e.message)
				return
			}
		service.apply(result)
	}
}

/** Sessioni e job visti dall'Hub, senza che l'Hub conosca il loro modulo. */
@Component
class SessionProgress(
	private val sessions: CalibrationSessionRepository,
	private val jobs: CalibrationJobRepository,
	private val results: CalibrationResultRepository,
	private val references: ReferenceStandardRepository,
) : HubProgressSource {
	private val open = listOf(SessionStatus.OPEN, SessionStatus.AS_FOUND_DONE, SessionStatus.ADJUSTED, SessionStatus.AS_LEFT_DONE)

	override fun inCalibration(itemIds: Collection<Long>): Pair<Int, OffsetDateTime?> {
		val active = sessions.findAllByStatusIn(open).filter { s -> s.items.any { it.id in itemIds } }
		return active.flatMap { s -> s.items.filter { it.id in itemIds }.map { it.id } }.distinct().size to active.minOfOrNull { it.startedAt }
	}

	override fun openSessions(): Int = sessions.findAllByStatusIn(open).size

	override fun queuedJobs(): Int = jobs.countByStatusIn(listOf(JobRecordStatus.QUEUED, JobRecordStatus.RUNNING)).toInt()

	override fun referenceLabel(certificate: Certificate): String? {
		val result = certificate.calibrationResultId?.let { results.findById(it).orElse(null) } ?: return null
		val session = result.sessionId?.let { sessions.findById(it).orElse(null) } ?: return null
		return references.findById(session.referenceStandardId).orElse(null)?.serialNumber
	}
}

/** Le sessioni dall'interfaccia e dal Raspberry. */
@RestController
@RequestMapping("/API/metrology")
class SessionController(
	private val service: SessionService,
	private val jobs: JobService,
) {
	@PostMapping("/sessions")
	fun open(@RequestBody request: SessionOpenRequest) = service.open(request)

	@GetMapping("/sessions")
	fun list(@RequestParam(required = false) muExtendedId: Long?) = service.list(muExtendedId)

	@GetMapping("/sessions/{id}")
	fun detail(@PathVariable id: Long) = service.detail(id)

	@PostMapping("/sessions/{id}/close-phase")
	fun closePhase(@PathVariable id: Long, @RequestBody request: ClosePhaseRequest) = service.closePhase(id, request.phase)

	@PostMapping("/sessions/{id}/documents")
	fun documents(@PathVariable id: Long) = service.requestDocuments(id)

	@PostMapping("/sessions/{id}/close")
	fun close(@PathVariable id: Long) = service.close(id, abort = false)

	@PostMapping("/sessions/{id}/abort")
	fun abort(@PathVariable id: Long) = service.close(id, abort = true)

	@PostMapping("/results/{id}/decision")
	fun decide(@PathVariable id: Long, @RequestBody request: DecisionRequest) = jobs.decide(id, request)

	@GetMapping("/results/pending")
	fun pending() = jobs.pendingDecisions()
}
