package it.polito.measurestream.metrology.jobs.contract

import java.time.Instant
import java.util.UUID

enum class JobStatus { DONE, FAILED }

enum class Phase { AS_FOUND, AS_LEFT }

enum class Verdict { CONFORM, GUARD_ZONE, NON_CONFORM }

/** Rispetto a che cosa: le specifiche del modello (template) o l'uso (profilo). */
enum class ConformityTarget { SPECIFICATION, PROFILE }

enum class SuggestedDecision { NONE, ADJUST, INVESTIGATE }

enum class DocumentKind { DCC_XML, CERTIFICATE_PDF, CHART, RUN_LOG, BLOCK_A }

data class CalibratedRange(
	val min: Double,
	val max: Double,
	val dsi: String? = null,
)

/** Una riga dei risultati: la stessa forma di `certificate_point`. */
data class CalibrationPoint(
	val phase: Phase,
	val pointIndex: Int,
	val nominal: Double? = null,
	val referenceValue: Double,
	val indicatedValue: Double,
	val error: Double? = null,
	val correction: Double? = null,
	val expandedU: Double? = null,
	val k: Double? = null,
	val dsi: String? = null,
)

/** Il documento del registro da cui viene la tolleranza: template del sensore o profilo. */
data class SourceRef(
	val kind: String,
	val templateId: Int,
	val version: String,
)

data class ConformityResult(
	val against: ConformityTarget,
	val source: SourceRef,
	val decisionRule: String,
	val tolerance: Double,
	val pfa: Double? = null,
	val verdict: Verdict,
)

/** Il risultato per un esemplare: una MU e un indice di sensore. */
data class ItemResult(
	val calibratedItemId: Long,
	val muExtendedId: Long,
	val sensorIndex: Int,
	val function: CalibrationFunction? = null,
	val covariance: List<List<Double>>? = null,
	val calibratedRange: CalibratedRange? = null,
	val adjustmentApplied: Boolean = false,
	val points: List<CalibrationPoint> = emptyList(),
	val budget: Map<String, Any?> = emptyMap(),
	val conformity: List<ConformityResult> = emptyList(),
	val suggestedDecision: SuggestedDecision = SuggestedDecision.NONE,
) {
	init {
		if (covariance != null && function != null) {
			val n = function.c.size
			require(covariance.size == n && covariance.all { it.size == n }) {
				"la covarianza deve essere ${n}x$n, come i coefficienti"
			}
		}
	}
}

/** Un documento gia' caricato su metrology-service: nel messaggio viaggiano solo id e impronta. */
data class DocumentRef(
	val documentId: Long,
	val kind: DocumentKind,
	val sha256: String,
	/** L'esemplare a cui appartiene, quando la sessione ne tara piu' d'uno. Assente se vale per tutti. */
	@field:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
	@get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
	val calibratedItemId: Long? = null,
) {
	init {
		require(SHA256.matches(sha256)) { "impronta SHA-256 non valida: $sha256" }
	}

	private companion object {
		val SHA256 = Regex("^[0-9a-f]{64}$")
	}
}

data class JobError(
	val code: String,
	val message: String,
)

/** L'esito di un job. Idempotente per [jobId]: consegnato due volte, produce un solo risultato. */
data class CalibrationResult(
	val schemaVersion: Int = SCHEMA_VERSION,
	val jobId: UUID,
	val kind: JobKind,
	val status: JobStatus,
	val engineVersion: String,
	val startedAt: Instant,
	val finishedAt: Instant,
	val error: JobError? = null,
	val items: List<ItemResult> = emptyList(),
	val documents: List<DocumentRef> = emptyList(),
) {
	init {
		require((status == JobStatus.FAILED) == (error != null)) {
			"un job FAILED porta un errore, e solo un job FAILED lo porta"
		}
	}
}
