package it.polito.measurestream.metrology.sessions

import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.items.ItemDTO
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.items.SensorManagerClient
import it.polito.measurestream.metrology.items.toDTO
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import it.polito.measurestream.metrology.jobs.contract.JobKind
import it.polito.measurestream.metrology.jobs.contract.TemplateVersionRef
import it.polito.measurestream.metrology.references.ReferenceService
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleType
import java.time.OffsetDateTime
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode

data class SessionOpenRequest(
	val muExtendedId: Long,
	/** I sensori da tarare; vuoto vuol dire tutti quelli della MU. */
	val sensorIndexes: List<Int> = emptyList(),
	val procedure: TemplateVersionRef,
	val referenceStandardId: Long? = null,
	val referenceSerialNumber: String? = null,
	val ambient: Map<String, Any?>? = null,
	val notes: String? = null,
)

/** Cio' che serve al Raspberry per partire: l'id da usare come `calib_id` e i punti. */
data class SessionOpenResponse(
	val sessionId: Long,
	val calibId: String,
	val points: List<Double>,
	val asLeftPoints: List<Double>,
	val procedure: Map<String, Any?>,
	val items: List<ItemDTO>,
)

data class StepSummaryDTO(
	val phase: StepPhase,
	val sensorIndex: Int,
	val stepIndex: Int,
	val setpoint: Double?,
	val readings: Int,
	val samples: Int,
	val referenceMean: Double?,
	val receivedAt: OffsetDateTime,
)

data class SessionDTO(
	val id: Long,
	val muExtendedId: Long,
	val referenceStandardId: Long,
	val referenceCertificateId: Long?,
	val procedure: TemplateVersionRef,
	val operatorSub: String,
	val startedAt: OffsetDateTime,
	val endedAt: OffsetDateTime?,
	val status: SessionStatus,
	val adjustmentApplied: Boolean?,
	val notes: String?,
	val items: List<ItemDTO>,
	val steps: List<StepSummaryDTO> = emptyList(),
	val jobs: List<Map<String, Any?>> = emptyList(),
	val results: List<Map<String, Any?>> = emptyList(),
)

data class ClosePhaseRequest(val phase: StepPhase)

/**
 * Le sessioni di taratura (PIANO_METROLOGIA 3.2, specifica § 5.3). Il Raspberry apre la
 * sessione via REST — non inventa piu' il `calib_id` — e il server controlla procedura,
 * campione e ruolo prima di dargli l'id con cui pubblicare i gradini.
 */
@Service
class SessionService(
	private val sessions: CalibrationSessionRepository,
	private val steps: SessionStepRepository,
	private val jobs: CalibrationJobRepository,
	private val results: CalibrationResultRepository,
	private val items: ItemService,
	private val references: ReferenceService,
	private val certificates: CertificateRepository,
	private val sensorManager: SensorManagerClient,
	private val jobService: JobService,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	@Transactional
	fun open(request: SessionOpenRequest): SessionOpenResponse {
		val caller = policy.caller()
		policy.requireRole(caller, MetrologyRoleType.TECHNICIAN, what = "Aprire una sessione di taratura")

		val procedure =
			sensorManager.template("procedure", request.procedure.templateId, request.procedure.version)
				?: throw BadRequest("La procedura ${request.procedure.templateId} ${request.procedure.version} non e' nel registro")
		if (procedure["status"] != "PUBLISHED") throw Conflict("La procedura ${request.procedure.templateId} ${request.procedure.version} non e' pubblicata")
		@Suppress("UNCHECKED_CAST")
		val content = procedure["content"] as? Map<String, Any?> ?: emptyMap()

		val reference =
			when {
				request.referenceStandardId != null -> references.get(request.referenceStandardId)
				!request.referenceSerialNumber.isNullOrBlank() -> references.bySerial(request.referenceSerialNumber)
				else -> throw BadRequest("Indica il campione di riferimento")
			}
		val referenceCertificate = reference.currentCertificateId?.let { certificates.findById(it).orElse(null) }
		references.usability(reference, referenceCertificate)?.let { throw Conflict("Campione ${reference.serialNumber}: $it") }

		val mu = sensorManager.measurementUnit(request.muExtendedId) ?: throw NotFound("MU 0x%08X non trovata".format(request.muExtendedId))
		val indexes = request.sensorIndexes.ifEmpty { mu.sensors.map { it.sensorIndex } }
		if (indexes.isEmpty()) throw BadRequest("La MU non ha sensori da tarare")
		val sessionItems = indexes.distinct().map { items.resolveOrCreate(request.muExtendedId, it) }

		val session =
			sessions.save(
				CalibrationSession(
					muExtendedId = request.muExtendedId,
					referenceStandardId = reference.id,
					referenceCertificateId = referenceCertificate?.id,
					procedureTemplateId = request.procedure.templateId,
					procedureVersion = request.procedure.version,
					operatorSub = caller.sub,
					ambient = request.ambient,
					notes = request.notes,
					items = sessionItems.toMutableSet(),
				)
			)
		audit.record("SESSION_OPENED", "calibration_session", session.id, after = request)
		return SessionOpenResponse(
			sessionId = session.id,
			calibId = session.id.toString(),
			points = numbers(content["points"]),
			asLeftPoints = numbers((content["asLeft"] as? Map<*, *>)?.get("points")),
			procedure = content,
			items = sessionItems.map { it.toDTO() },
		)
	}

	/** Chiude una fase: i gradini ci sono tutti, il motore puo' lavorare. */
	@Transactional
	fun closePhase(id: Long, phase: StepPhase): SessionDTO {
		val caller = policy.caller()
		policy.requireRole(caller, MetrologyRoleType.TECHNICIAN, what = "Chiudere una fase di taratura")
		val session = find(id)
		if (!session.active) throw Conflict("La sessione e' ${session.status}")
		if (steps.countBySessionIdAndPhase(id, phase) == 0L) throw Conflict("Nessun gradino $phase ricevuto: il Raspberry li ha pubblicati?")
		when (phase) {
			StepPhase.AS_FOUND -> {
				session.status = SessionStatus.AS_FOUND_DONE
				jobService.enqueue(JobKind.SESSION_FIT, session, caller.sub)
			}
			StepPhase.AS_LEFT -> {
				// L'as-left verifica la funzione che resta in vigore: senza la decisione non si
				// sa quale sia (passo 3.5).
				val undecided = results.findAllBySessionIdOrderByCreatedAtDesc(id).latestByItem(JobKind.SESSION_FIT).values.filter { it.decision == null }
				if (undecided.isNotEmpty()) throw Conflict("Prima la decisione di regolazione: manca per ${undecided.size} esemplari")
				session.status = SessionStatus.AS_LEFT_DONE
				jobService.enqueue(JobKind.VERIFICATION, session, caller.sub)
			}
		}
		audit.record("SESSION_PHASE_CLOSED", "calibration_session", id, detail = mapOf("phase" to phase.name))
		return toDTO(sessions.save(session), detailed = true)
	}

	/** Chiede DCC e PDF al motore: da li' nascono i certificati interni in bozza. */
	@Transactional
	fun requestDocuments(id: Long): SessionDTO {
		val caller = policy.caller()
		policy.requireRole(caller, MetrologyRoleType.TECHNICIAN, what = "Generare i documenti di una taratura")
		val session = find(id)
		val all = results.findAllBySessionIdOrderByCreatedAtDesc(id)
		val fits = all.latestByItem(JobKind.SESSION_FIT)
		if (fits.isEmpty()) throw Conflict("Il motore non ha ancora restituito un risultato")
		val pending = fits.values.filter { it.decision == null }
		if (pending.isNotEmpty()) throw Conflict("Manca la decisione di regolazione per ${pending.size} esemplari")
		// Dopo una regolazione l'as-left su punti indipendenti e' obbligatorio, se la procedura
		// li prevede: un certificato con la funzione nuova senza la sua verifica non si emette.
		val verified = all.latestByItem(JobKind.VERIFICATION).keys
		val unverified = fits.values.filter { it.decision == "ADJUST" && it.calibratedItemId !in verified }
		if (unverified.isNotEmpty() && asLeftPoints(session).isNotEmpty()) {
			throw Conflict("Dopo la regolazione serve l'as-left sui punti indipendenti della procedura: manca per ${unverified.size} esemplari")
		}
		jobService.enqueue(JobKind.DOCUMENTS, session, caller.sub)
		return toDTO(session, detailed = true)
	}

	@Transactional
	fun close(id: Long, abort: Boolean): SessionDTO {
		val caller = policy.caller()
		policy.requireRole(caller, MetrologyRoleType.TECHNICIAN, what = "Chiudere una sessione")
		val session = find(id)
		if (!session.active) return toDTO(session)
		session.status = if (abort) SessionStatus.ABORTED else SessionStatus.CLOSED
		session.endedAt = OffsetDateTime.now()
		audit.record(if (abort) "SESSION_ABORTED" else "SESSION_CLOSED", "calibration_session", id)
		return toDTO(sessions.save(session))
	}

	@Transactional(readOnly = true)
	fun list(muExtendedId: Long?): List<SessionDTO> {
		val caller = policy.caller()
		val all = if (muExtendedId != null) sessions.findAllByMuExtendedIdOrderByStartedAtDesc(muExtendedId) else sessions.findAllByOrderByStartedAtDesc()
		return all.filter { s -> s.items.any { policy.canSee(it.ownerSub, it.assignedLabId, caller) } }.map { toDTO(it) }
	}

	@Transactional(readOnly = true)
	fun detail(id: Long): SessionDTO {
		val session = find(id)
		if (session.items.none { policy.canSee(it.ownerSub, it.assignedLabId) }) throw NotFound("Sessione $id non trovata")
		return toDTO(session, detailed = true)
	}

	fun find(id: Long): CalibrationSession = sessions.findById(id).orElseThrow { NotFound("Sessione $id non trovata") }

	fun toDTO(s: CalibrationSession, detailed: Boolean = false) =
		SessionDTO(
			id = s.id,
			muExtendedId = s.muExtendedId,
			referenceStandardId = s.referenceStandardId,
			referenceCertificateId = s.referenceCertificateId,
			procedure = TemplateVersionRef(s.procedureTemplateId, s.procedureVersion),
			operatorSub = s.operatorSub,
			startedAt = s.startedAt,
			endedAt = s.endedAt,
			status = s.status,
			adjustmentApplied = s.adjustmentApplied,
			notes = s.notes,
			items = s.items.sortedBy { it.sensorIndex }.map { it.toDTO() },
			steps =
				if (detailed)
					steps.findAllBySessionIdOrderByPhaseAscSensorIndexAscStepIndexAsc(s.id).map {
						StepSummaryDTO(it.phase, it.sensorIndex, it.stepIndex, it.setpoint, it.referenceReadings.size, it.sensorSamples.size, it.referenceReadings.takeIf { r -> r.isNotEmpty() }?.average(), it.receivedAt)
					}
				else emptyList(),
			jobs =
				if (detailed)
					jobs.findAllBySessionIdOrderByRequestedAtDesc(s.id).map {
						mapOf("id" to it.id, "kind" to it.kind, "status" to it.status.name, "requestedAt" to it.requestedAt, "finishedAt" to it.finishedAt, "engineVersion" to it.engineVersion, "error" to it.error)
					}
				else emptyList(),
			results = if (detailed) results.findAllBySessionIdOrderByCreatedAtDesc(s.id).map { it.toMap() } else emptyList(),
		)

	private fun numbers(raw: Any?): List<Double> = (raw as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toDouble() }

	/** I punti as-left della procedura della sessione; vuoto se la procedura non li prevede. */
	private fun asLeftPoints(session: CalibrationSession): List<Double> {
		val procedure = runCatching { sensorManager.template("procedure", session.procedureTemplateId, session.procedureVersion) }.getOrNull()
		@Suppress("UNCHECKED_CAST")
		val content = procedure?.get("content") as? Map<String, Any?> ?: return emptyList()
		return numbers((content["asLeft"] as? Map<*, *>)?.get("points"))
	}
}

fun CalibrationResultRecord.toMap(): Map<String, Any?> =
	mapOf(
		"id" to id,
		"jobId" to jobId,
		"calibratedItemId" to calibratedItemId,
		"function" to function,
		"covariance" to covariance,
		"calibratedMin" to calibratedMin,
		"calibratedMax" to calibratedMax,
		"calibratedDsi" to calibratedDsi,
		"points" to points,
		"budget" to budget,
		"conformity" to conformity,
		"kind" to kind.name,
		"suggestedDecision" to suggestedDecision,
		"adjustmentApplied" to adjustmentApplied,
		"decision" to decision,
		"decisionReason" to decisionReason,
		"decidedBy" to decidedBy,
		"decidedAt" to decidedAt,
		"createdAt" to createdAt,
	)

/**
 * Il consumer del topic `calibrations` (PIANO_METROLOGIA 3.2): la logica di assemblaggio di
 * `KafkaCalibrationConsumer` di Mario, riscritta per sessione.
 *
 * **Il software del Raspberry non e' ancora aggiornato** [S §7]. Finche' non lo e', i messaggi
 * possono arrivare senza `phase` e senza `sensor_index`, e con il vecchio `calib_id` inventato
 * (`calib-{mu}-{sensor}-{ts}`). Le regole sono scritte qui e non indovinate altrove: la fase
 * mancante si deduce dallo stato della sessione (aperta → as-found, as-found chiusa → as-left),
 * l'indice mancante vale solo se la sessione tara un sensore solo, e un `calib_id` che non e'
 * l'id di una sessione si scarta con un avviso, perche' non c'e' modo di sapere a quale
 * sessione appartenga.
 */
@Component
class CalibrationStepsListener(
	private val sessions: CalibrationSessionRepository,
	private val steps: SessionStepRepository,
) {
	private val log = LoggerFactory.getLogger(CalibrationStepsListener::class.java)

	@KafkaListener(topics = ["calibrations"], groupId = "metrology-service-calibrations")
	@Transactional
	fun consume(message: String) {
		val root =
			try {
				ContractJson.mapper.readTree(message)
			} catch (e: Exception) {
				log.warn("Gradino non leggibile come JSON: {}", e.message)
				return
			}
		store(root)
	}

	/** Salva un gradino; restituisce la riga, o null se e' scartato o gia' presente. */
	fun store(root: JsonNode): SessionStep? {
		val calibId = root.path("calib_id").asString("")
		val sessionId = calibId.toLongOrNull()
		if (sessionId == null) {
			log.warn("Gradino con calib_id '{}': non e' l'id di una sessione aperta via REST, scartato", calibId)
			return null
		}
		val session = sessions.findById(sessionId).orElse(null)
		if (session == null || !session.active) {
			log.warn("Gradino per la sessione {}, che non esiste o e' chiusa: scartato", sessionId)
			return null
		}

		val phase =
			root.path("phase").asString("").uppercase().takeIf { it.isNotBlank() }?.let { runCatching { StepPhase.valueOf(it.replace('-', '_')) }.getOrNull() }
				?: if (session.status == SessionStatus.OPEN) StepPhase.AS_FOUND else StepPhase.AS_LEFT
		val sensorIndex =
			if (root.has("sensor_index")) root.path("sensor_index").asInt()
			else session.items.singleOrNull()?.sensorIndex
				?: run {
					log.warn("Sessione {}: gradino senza sensor_index e piu' sensori in taratura, scartato", sessionId)
					return null
				}
		if (session.items.none { it.sensorIndex == sensorIndex }) {
			log.warn("Sessione {}: il sensore {} non fa parte della sessione, gradino scartato", sessionId, sensorIndex)
			return null
		}
		val stepIndex = root.path("step_index").asInt(0)
		if (steps.existsBySessionIdAndPhaseAndStepIndexAndSensorIndex(sessionId, phase, stepIndex, sensorIndex)) {
			log.info("Sessione {}: gradino {} {} del sensore {} gia' ricevuto, duplicato ignorato", sessionId, phase, stepIndex, sensorIndex)
			return null
		}

		val summary = root.path("step_summary")
		val minutes = summary.takeIf { it.isArray && stepIndex < it.size() }?.get(stepIndex)?.path("minutes")?.asInt()
		val readings = elements(root.path("ref_readings")).map { it.asDouble() }
		val samples = decodeSamples(root.path("sensor_b64").asString(""))

		val step =
			steps.save(
				SessionStep(
					sessionId = sessionId,
					phase = phase,
					stepIndex = stepIndex,
					sensorIndex = sensorIndex,
					setpoint = root.path("target").takeIf { it.isNumber }?.asDouble(),
					minutes = minutes,
					totalSteps = summary.takeIf { it.isArray }?.size(),
					startedAt = time(root.path("start_time").asString("")),
					dwellStartedAt = time(root.path("start_time_dwell").asString("")),
					sensorSamplingFreq = root.path("sensor_sampling_freq").takeIf { it.isNumber }?.asInt(),
					referenceReadings = readings,
					sensorSamples = samples,
					stable = root.path("stable").takeIf { it.isBoolean }?.asBoolean(),
				)
			)
		log.info("Sessione {}: gradino {} {} del sensore {} ({} letture, {} campioni)", sessionId, phase, stepIndex, sensorIndex, readings.size, samples.size)
		return step
	}

	/** Gli elementi di un array JSON; vuoto se il nodo non e' un array. */
	private fun elements(node: JsonNode): List<JsonNode> = if (node.isArray) (0 until node.size()).map { node.get(it) } else emptyList()

	private fun time(text: String): OffsetDateTime? =
		text.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() ?: runCatching { java.time.LocalDateTime.parse(it).atOffset(java.time.ZoneOffset.UTC) }.getOrNull() }

	companion object {
		/**
		 * I campioni grezzi del sensore: uint16 **little-endian**, come ha verificato Mario sui
		 * file reali (un NTC a 95 °C da' 3527, non 50957), anche se il formato li dichiarava
		 * big-endian.
		 */
		fun decodeSamples(b64: String): List<Int> {
			if (b64.isBlank()) return emptyList()
			val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return emptyList()
			return (0 until bytes.size / 2).map { i -> (bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8) }
		}
	}
}
