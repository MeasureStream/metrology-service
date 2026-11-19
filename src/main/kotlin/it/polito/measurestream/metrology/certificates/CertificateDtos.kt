package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.jobs.contract.CalibrationFunction
import java.time.LocalDate
import java.time.OffsetDateTime

/** Un punto come lo inserisce chi trascrive un certificato. */
data class PointRequest(
	val phase: PointPhase = PointPhase.AS_LEFT,
	val pointIndex: Int,
	val nominal: Double? = null,
	val referenceValue: Double? = null,
	val indicatedValue: Double? = null,
	val error: Double? = null,
	val correction: Double? = null,
	val expandedU: Double? = null,
	val k: Double? = 2.0,
	val dsi: String? = null,
)

/**
 * I dati di un certificato esterno [S §5.5]. In I1 comprendono la funzione dell'esemplare,
 * copiata dall'uscita della pipeline: e' la scorciatoia dichiarata finche' il job CERT_FIT
 * (fase 5) non la ricava dai punti.
 */
data class CertificateDataRequest(
	val number: String,
	val labCompanyId: Long? = null,
	val issuer: String? = null,
	val issuerAccreditation: String? = null,
	val calibrationDate: LocalDate,
	val issueDate: LocalDate? = null,
	/** Facoltativa: se manca la decide il sistema (intervallo dell'esemplare o predefinito). */
	val validUntil: OffsetDateTime? = null,
	val function: CalibrationFunction? = null,
	val covariance: List<List<Double>>? = null,
	val calibratedMin: Double? = null,
	val calibratedMax: Double? = null,
	val calibratedDsi: String? = null,
	val points: List<PointRequest> = emptyList(),
	val conformity: Conformity = Conformity.NOT_STATED,
	val conformityStatement: String? = null,
	val decisionRule: String? = null,
	val ambient: Map<String, Any?>? = null,
)

data class PointDTO(
	val phase: PointPhase,
	val pointIndex: Int,
	val nominal: Double?,
	val referenceValue: Double?,
	val indicatedValue: Double?,
	val error: Double?,
	val correction: Double?,
	val expandedU: Double?,
	val k: Double?,
	val dsi: String?,
)

/** Le azioni che l'interfaccia puo' offrire a chi guarda, con il motivo di quelle negate. */
data class CertificateActions(
	val allowed: List<String>,
	val blocked: Map<String, String> = emptyMap(),
)

data class CertificateDetailDTO(
	val id: Long,
	val kind: CertificateKind,
	val subjectType: SubjectType,
	val subjectId: Long,
	val subjectLabel: String,
	val muExtendedId: Long?,
	val sensorIndex: Int?,
	val number: String,
	val revision: Int,
	val supersedesId: Long?,
	val amendmentReason: String?,
	val labCompanyId: Long?,
	val issuer: String?,
	val issuerAccreditation: String?,
	val calibrationDate: LocalDate?,
	val issueDate: LocalDate?,
	val validUntil: OffsetDateTime?,
	val status: CertificateStatus,
	val source: CertificateSource,
	val function: Map<String, Any?>?,
	val covariance: List<List<Double>>?,
	val calibratedMin: Double?,
	val calibratedMax: Double?,
	val calibratedDsi: String?,
	val conformity: Conformity,
	val conformityStatement: String?,
	val decisionRule: String?,
	val ambient: Map<String, Any?>?,
	val fingerprint: String?,
	val originalDocumentId: Long?,
	val pdfDocumentId: Long?,
	val xmlDocumentId: Long?,
	val calibrationResultId: Long?,
	val createdBy: String,
	val createdAt: OffsetDateTime,
	val enteredBy: String?,
	val enteredAt: OffsetDateTime?,
	val approvedBy: String?,
	val approvedAt: OffsetDateTime?,
	val signedAt: OffsetDateTime?,
	val verifiedBy: String?,
	val verifiedAt: OffsetDateTime?,
	val effectiveFrom: OffsetDateTime?,
	val invalidatedBy: String?,
	val invalidatedAt: OffsetDateTime?,
	val invalidationReason: String?,
	val points: List<PointDTO>,
	val actions: CertificateActions,
)

fun CertificatePoint.toDTO() = PointDTO(phase, pointIndex, nominal, referenceValue, indicatedValue, error, correction, expandedU, k, dsi)

/** Il corpo delle azioni di massa dell'Hub (contratto di `hubApi.ts`). */
data class IdsRequest(val ids: List<Long>, val reason: String? = null)

data class ReasonRequest(val reason: String)
