package it.polito.measurestream.metrology.sessions

import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.certificates.toDTO
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.items.toDTO
import it.polito.measurestream.metrology.jobs.contract.JobKind
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import java.util.UUID
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cio' che il motore legge per eseguire un job [S §6.1]: i messaggi Kafka restano piccoli, i
 * dati grossi passano di qui. Una sola chiamata per job, con tutto il contesto: sessione,
 * esemplari, gradini, campione e suo certificato, e la taratura in vigore di ogni esemplare
 * (serve all'as-found con la funzione precedente).
 *
 * Riservato al motore (ruolo CALIBRATION_ENGINE) e al personale, che lo usa per capire cosa il
 * motore ha visto.
 */
@RestController
@RequestMapping("/API/metrology/engine")
class EngineController(
	private val jobs: CalibrationJobRepository,
	private val sessions: CalibrationSessionRepository,
	private val steps: SessionStepRepository,
	private val references: ReferenceStandardRepository,
	private val certificates: CertificateRepository,
	private val results: CalibrationResultRepository,
	private val policy: AccessPolicy,
) {
	@GetMapping("/jobs/{jobId}/context")
	@Transactional(readOnly = true)
	fun context(@PathVariable jobId: UUID): Map<String, Any?> {
		val caller = policy.caller()
		if (!caller.isEngine && !policy.isStaff(caller)) throw Forbidden("Contesto dei job riservato al motore e al personale")
		val job = jobs.findById(jobId).orElseThrow { NotFound("Job $jobId non trovato") }
		val session = job.sessionId?.let { sessions.findById(it).orElse(null) }
		val reference = session?.let { references.findById(it.referenceStandardId).orElse(null) }
		val referenceCertificate = session?.referenceCertificateId?.let { certificates.findById(it).orElse(null) }
		// L'ultimo fit di ogni esemplare con la decisione del tecnico: VERIFICATION verifica la
		// funzione nuova se si e' regolato, quella in vigore se no; DOCUMENTS lo dice nel DCC.
		val fits = session?.let { results.findAllBySessionIdOrderByCreatedAtDesc(it.id).latestByItem(JobKind.SESSION_FIT) }.orEmpty()

		return mapOf(
			"job" to job.request,
			"session" to
				session?.let {
					mapOf(
						"id" to it.id,
						"muExtendedId" to it.muExtendedId,
						"procedure" to mapOf("templateId" to it.procedureTemplateId, "version" to it.procedureVersion),
						"operatorSub" to it.operatorSub,
						"startedAt" to it.startedAt,
						"endedAt" to it.endedAt,
						"ambient" to it.ambient,
						"status" to it.status.name,
					)
				},
			"items" to
				session?.items?.sortedBy { it.sensorIndex }?.map { item ->
					val current =
						certificates.findAllBySubjectTypeAndSubjectIdAndStatus(SubjectType.ITEM, item.id, CertificateStatus.EFFECTIVE).firstOrNull()
					mapOf(
						"item" to item.toDTO(),
						"currentCertificate" to
							current?.let { c ->
								mapOf("id" to c.id, "number" to c.number, "revision" to c.revision, "function" to c.function, "covariance" to c.covariance, "validUntil" to c.validUntil)
							},
						"latestFit" to
							fits[item.id]?.let { r ->
								mapOf(
									"resultId" to r.id,
									"function" to r.function,
									"covariance" to r.covariance,
									"calibratedMin" to r.calibratedMin,
									"calibratedMax" to r.calibratedMax,
									"calibratedDsi" to r.calibratedDsi,
									"decision" to r.decision,
								)
							},
					)
				},
			"steps" to
				session?.let {
					steps.findAllBySessionIdOrderByPhaseAscSensorIndexAscStepIndexAsc(it.id).map { s ->
						mapOf(
							"phase" to s.phase.name,
							"sensorIndex" to s.sensorIndex,
							"stepIndex" to s.stepIndex,
							"setpoint" to s.setpoint,
							"minutes" to s.minutes,
							"startedAt" to s.startedAt,
							"dwellStartedAt" to s.dwellStartedAt,
							"sensorSamplingFreq" to s.sensorSamplingFreq,
							"referenceReadings" to s.referenceReadings,
							"sensorSamples" to s.sensorSamples,
							"stable" to s.stable,
						)
					}
				},
			"reference" to
				reference?.let {
					mapOf(
						"id" to it.id,
						"name" to it.name,
						"serialNumber" to it.serialNumber,
						"manufacturer" to it.manufacturer,
						"model" to it.model,
						"modelTemplateId" to it.modelTemplateId,
						"modelTemplateMajor" to it.modelTemplateMajor,
					)
				},
			"referenceCertificate" to
				referenceCertificate?.let { c ->
					mapOf(
						"id" to c.id,
						"number" to c.number,
						"revision" to c.revision,
						"issuer" to c.issuer,
						"issuerAccreditation" to c.issuerAccreditation,
						"calibrationDate" to c.calibrationDate,
						"validUntil" to c.validUntil,
						"points" to c.points.map { it.toDTO() },
					)
				},
		)
	}
}
