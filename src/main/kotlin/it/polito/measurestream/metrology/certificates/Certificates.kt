package it.polito.measurestream.metrology.certificates

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository

enum class CertificateKind { INTERNAL, EXTERNAL }

enum class SubjectType { ITEM, REFERENCE }

enum class CertificateSource { ENGINE, DCC_XML, PDF_MANUAL }

enum class Conformity { CONFORM, NON_CONFORM, NOT_STATED }

enum class PointPhase { AS_FOUND, AS_LEFT }

/**
 * Gli stati di un certificato [S §5.4].
 *
 * Interno: DRAFT → APPROVED → SIGNED → EFFECTIVE. Esterno da PDF: DRAFT (file caricato) →
 * ENTERED (dati inseriti) → VERIFIED (secondo utente) → EFFECTIVE. Da qualunque stato si puo'
 * invalidare; una revisione porta il precedente in SUPERSEDED quando entra in vigore.
 */
enum class CertificateStatus {
	DRAFT,
	ENTERED,
	APPROVED,
	SIGNED,
	VERIFIED,
	EFFECTIVE,
	SUPERSEDED,
	SUPERSEDED_BY_CONFIG,
	INVALIDATED,
	;

	/** Il contenuto e' bloccato anche nel database (trigger `certificate_content_locked`). */
	val locked: Boolean get() = this in setOf(SIGNED, VERIFIED, EFFECTIVE, SUPERSEDED, SUPERSEDED_BY_CONFIG, INVALIDATED)
}

@Entity
@Table(name = "certificate")
class Certificate(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var kind: CertificateKind,
	@Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false, length = 16) var subjectType: SubjectType,
	@Column(name = "subject_id", nullable = false) var subjectId: Long,
	@Column(nullable = false, length = 64) var number: String,
	@Column(nullable = false) var revision: Int = 1,
	@Column(name = "supersedes_id") var supersedesId: Long? = null,
	@Column(name = "amendment_reason", columnDefinition = "text") var amendmentReason: String? = null,
	@Column(name = "lab_company_id") var labCompanyId: Long? = null,
	@Column(length = 200) var issuer: String? = null,
	@Column(name = "issuer_accreditation", length = 120) var issuerAccreditation: String? = null,
	@Column(name = "calibration_date") var calibrationDate: LocalDate? = null,
	@Column(name = "issue_date") var issueDate: LocalDate? = null,
	@Column(name = "valid_until") var validUntil: OffsetDateTime? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var status: CertificateStatus = CertificateStatus.DRAFT,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var source: CertificateSource,
	/** La funzione dell'esemplare, nel formato del blocco `calibration` di un template. */
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var function: Map<String, Any?>? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var covariance: List<List<Double>>? = null,
	@Column(name = "calibrated_min") var calibratedMin: Double? = null,
	@Column(name = "calibrated_max") var calibratedMax: Double? = null,
	@Column(name = "calibrated_dsi", length = 80) var calibratedDsi: String? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var conformity: Conformity = Conformity.NOT_STATED,
	@Column(name = "conformity_statement", columnDefinition = "text") var conformityStatement: String? = null,
	@Column(name = "decision_rule", length = 200) var decisionRule: String? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var ambient: Map<String, Any?>? = null,
	@Column(length = 80) var fingerprint: String? = null,
	@Column(name = "calibration_result_id") var calibrationResultId: Long? = null,
	@Column(name = "xml_document_id") var xmlDocumentId: Long? = null,
	@Column(name = "pdf_document_id") var pdfDocumentId: Long? = null,
	@Column(name = "original_document_id") var originalDocumentId: Long? = null,
	@Column(name = "created_by", nullable = false, length = 64) var createdBy: String,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "entered_by", length = 64) var enteredBy: String? = null,
	@Column(name = "entered_at") var enteredAt: OffsetDateTime? = null,
	@Column(name = "approved_by", length = 64) var approvedBy: String? = null,
	@Column(name = "approved_at") var approvedAt: OffsetDateTime? = null,
	@Column(name = "signed_at") var signedAt: OffsetDateTime? = null,
	@Column(name = "verified_by", length = 64) var verifiedBy: String? = null,
	@Column(name = "verified_at") var verifiedAt: OffsetDateTime? = null,
	@Column(name = "effective_from") var effectiveFrom: OffsetDateTime? = null,
	@Column(name = "invalidated_by", length = 64) var invalidatedBy: String? = null,
	@Column(name = "invalidated_at") var invalidatedAt: OffsetDateTime? = null,
	@Column(name = "invalidation_reason", columnDefinition = "text") var invalidationReason: String? = null,
	@OneToMany(mappedBy = "certificate", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
	@OrderBy("phase ASC, pointIndex ASC")
	var points: MutableList<CertificatePoint> = mutableListOf(),
)

/** Un punto del certificato: riferimento, indicazione, errore o correzione, incertezza. */
@Entity
@Table(name = "certificate_point")
class CertificatePoint(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "certificate_id", nullable = false) var certificate: Certificate,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var phase: PointPhase,
	@Column(name = "point_index", nullable = false) var pointIndex: Int,
	var nominal: Double? = null,
	@Column(name = "reference_value") var referenceValue: Double? = null,
	@Column(name = "indicated_value") var indicatedValue: Double? = null,
	var error: Double? = null,
	var correction: Double? = null,
	@Column(name = "expanded_u") var expandedU: Double? = null,
	var k: Double? = null,
	@Column(length = 80) var dsi: String? = null,
)

interface CertificateRepository : JpaRepository<Certificate, Long> {
	fun findAllBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(subjectType: SubjectType, subjectId: Long): List<Certificate>

	fun findAllBySubjectTypeAndSubjectIdAndStatus(subjectType: SubjectType, subjectId: Long, status: CertificateStatus): List<Certificate>

	fun findAllBySubjectTypeAndSubjectIdIn(subjectType: SubjectType, subjectIds: Collection<Long>): List<Certificate>

	fun findAllByStatusIn(statuses: Collection<CertificateStatus>): List<Certificate>

	fun findAllByXmlDocumentIdInOrPdfDocumentIdInOrOriginalDocumentIdIn(
		xml: Collection<Long>,
		pdf: Collection<Long>,
		original: Collection<Long>,
	): List<Certificate>

	fun findAllByNumberOrderByRevisionDesc(number: String): List<Certificate>

	fun countByKind(kind: CertificateKind): Long
}
