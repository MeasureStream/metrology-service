package it.polito.measurestream.metrology.jobs.contract

import java.time.Instant
import java.util.UUID

/**
 * Il contratto con calibration-engine.
 *
 * Le classi gemelle in Python stanno in `calibration-engine/src/calibration_engine/contracts.py`.
 * Gli esempi in `contracts/` sono identici nei due repository e i test di entrambi li rileggono:
 * chi cambia un campo da una parte sola se ne accorge prima del deploy.
 *
 * Regola per i deploy incrementali: un campo nuovo ha sempre un default, cosi' le due parti si
 * possono aggiornare in tempi diversi; un cambiamento che non si puo' fare cosi' incrementa
 * [SCHEMA_VERSION].
 */
object CalibrationTopics {
	/** metrology-service → motore, chiave = jobId. */
	const val JOBS = "calibration-jobs"

	/** Motore → metrology-service, chiave = jobId. */
	const val RESULTS = "calibration-results"

	/**
	 * Campione viaggiante → metrology-service: i gradini di una sessione, un messaggio per
	 * gradino e per sensore. Lo stesso JSON si puo' mandare via HTTPS a
	 * `POST /API/metrology/sessions/{id}/steps` (Docs/Campione_viaggiante.md).
	 */
	const val STEPS = "calibrations"
}

const val SCHEMA_VERSION = 1

enum class JobKind { SESSION_FIT, VERIFICATION, CERT_FIT, DOCUMENTS }

/** Un documento del registro citato per versione esatta, come lo cita un certificato. */
data class TemplateVersionRef(
	val templateId: Int,
	val version: String,
)

/** Un lavoro per il motore. Piccolo: i dati grossi il motore li legge via REST. */
data class CalibrationJob(
	val schemaVersion: Int = SCHEMA_VERSION,
	val jobId: UUID,
	val kind: JobKind,
	val sessionId: Long? = null,
	val certificateId: Long? = null,
	val procedure: TemplateVersionRef? = null,
	val referenceStandardId: Long? = null,
	val referenceCertificateId: Long? = null,
	val params: Map<String, Any?> = emptyMap(),
	val requestedBy: String,
	val requestedAt: Instant,
)
