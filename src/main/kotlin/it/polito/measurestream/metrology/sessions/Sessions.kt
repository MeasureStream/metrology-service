package it.polito.measurestream.metrology.sessions

import it.polito.measurestream.metrology.items.CalibratedItem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository

enum class SessionStatus { OPEN, AS_FOUND_DONE, ADJUSTED, AS_LEFT_DONE, CLOSED, ABORTED }

enum class StepPhase { AS_FOUND, AS_LEFT }

/** Una sessione di taratura con il campione e la procedura che la rendono riferibile (M4). */
@Entity
@Table(name = "calibration_session")
class CalibrationSession(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "mu_extended_id", nullable = false) var muExtendedId: Long,
	@Column(name = "reference_standard_id", nullable = false) var referenceStandardId: Long,
	@Column(name = "reference_certificate_id") var referenceCertificateId: Long? = null,
	@Column(name = "procedure_template_id", nullable = false) var procedureTemplateId: Int,
	@Column(name = "procedure_version", nullable = false, length = 16) var procedureVersion: String,
	@Column(name = "operator_sub", nullable = false, length = 64) var operatorSub: String,
	@Column(name = "started_at", nullable = false) var startedAt: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "ended_at") var endedAt: OffsetDateTime? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var ambient: Map<String, Any?>? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: SessionStatus = SessionStatus.OPEN,
	@Column(name = "adjustment_applied") var adjustmentApplied: Boolean? = null,
	@Column(columnDefinition = "text") var notes: String? = null,
	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(
		name = "session_item",
		joinColumns = [JoinColumn(name = "session_id")],
		inverseJoinColumns = [JoinColumn(name = "calibrated_item_id")],
	)
	var items: MutableSet<CalibratedItem> = mutableSetOf(),
) {
	val active: Boolean get() = status !in setOf(SessionStatus.CLOSED, SessionStatus.ABORTED)
}

/** Un gradino della procedura, per un sensore e una fase, con le letture e i campioni grezzi. */
@Entity
@Table(name = "session_step")
class SessionStep(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "session_id", nullable = false) var sessionId: Long,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var phase: StepPhase,
	@Column(name = "step_index", nullable = false) var stepIndex: Int,
	@Column(name = "sensor_index", nullable = false) var sensorIndex: Int,
	var setpoint: Double? = null,
	var minutes: Int? = null,
	@Column(name = "total_steps") var totalSteps: Int? = null,
	@Column(name = "started_at") var startedAt: OffsetDateTime? = null,
	@Column(name = "dwell_started_at") var dwellStartedAt: OffsetDateTime? = null,
	@Column(name = "sensor_sampling_freq") var sensorSamplingFreq: Int? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(name = "reference_readings", columnDefinition = "jsonb", nullable = false) var referenceReadings: List<Double>,
	@JdbcTypeCode(SqlTypes.JSON) @Column(name = "sensor_samples", columnDefinition = "jsonb", nullable = false) var sensorSamples: List<Int>,
	var stable: Boolean? = null,
	@Column(name = "received_at", nullable = false) var receivedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface CalibrationSessionRepository : JpaRepository<CalibrationSession, Long> {
	fun findAllByOrderByStartedAtDesc(): List<CalibrationSession>

	fun findAllByMuExtendedIdOrderByStartedAtDesc(muExtendedId: Long): List<CalibrationSession>

	fun findAllByStatusIn(statuses: Collection<SessionStatus>): List<CalibrationSession>
}

interface SessionStepRepository : JpaRepository<SessionStep, Long> {
	fun findAllBySessionIdOrderByPhaseAscSensorIndexAscStepIndexAsc(sessionId: Long): List<SessionStep>

	fun existsBySessionIdAndPhaseAndStepIndexAndSensorIndex(sessionId: Long, phase: StepPhase, stepIndex: Int, sensorIndex: Int): Boolean

	fun countBySessionIdAndPhase(sessionId: Long, phase: StepPhase): Long
}

enum class JobRecordStatus { QUEUED, RUNNING, DONE, FAILED }

/** Un job per il motore, con il messaggio inviato e quello ricevuto. */
@Entity
@Table(name = "calibration_job")
class CalibrationJobRecord(
	@Id var id: UUID,
	@Column(nullable = false, length = 16) var kind: String,
	@Column(name = "session_id") var sessionId: Long? = null,
	@Column(name = "certificate_id") var certificateId: Long? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: JobRecordStatus = JobRecordStatus.QUEUED,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) var request: Map<String, Any?>,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var response: Map<String, Any?>? = null,
	@Column(name = "requested_by", nullable = false, length = 64) var requestedBy: String,
	@Column(name = "requested_at", nullable = false) var requestedAt: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "started_at") var startedAt: OffsetDateTime? = null,
	@Column(name = "finished_at") var finishedAt: OffsetDateTime? = null,
	@Column(name = "engine_version", length = 64) var engineVersion: String? = null,
	@Column(columnDefinition = "text") var error: String? = null,
	@Column(name = "sent_at") var sentAt: OffsetDateTime? = null,
)

interface CalibrationJobRepository : JpaRepository<CalibrationJobRecord, UUID> {
	fun findAllBySessionIdOrderByRequestedAtDesc(sessionId: Long): List<CalibrationJobRecord>

	fun countByStatusIn(statuses: Collection<JobRecordStatus>): Long
}

/** Il risultato del motore per un esemplare, e la decisione di regolazione del tecnico. */
@Entity
@Table(name = "calibration_result")
class CalibrationResultRecord(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "job_id", nullable = false) var jobId: UUID,
	@Column(name = "session_id") var sessionId: Long? = null,
	@Column(name = "calibrated_item_id", nullable = false) var calibratedItemId: Long,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var function: Map<String, Any?>? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var covariance: List<List<Double>>? = null,
	@Column(name = "calibrated_min") var calibratedMin: Double? = null,
	@Column(name = "calibrated_max") var calibratedMax: Double? = null,
	@Column(name = "calibrated_dsi", length = 80) var calibratedDsi: String? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var points: List<Map<String, Any?>>? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var budget: Map<String, Any?>? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var conformity: List<Map<String, Any?>>? = null,
	@Column(name = "suggested_decision", length = 24) var suggestedDecision: String? = null,
	@Column(length = 24) var decision: String? = null,
	@Column(name = "decision_reason", columnDefinition = "text") var decisionReason: String? = null,
	@Column(name = "decided_by", length = 64) var decidedBy: String? = null,
	@Column(name = "decided_at") var decidedAt: OffsetDateTime? = null,
	@Column(name = "adjustment_applied") var adjustmentApplied: Boolean? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface CalibrationResultRepository : JpaRepository<CalibrationResultRecord, Long> {
	fun findAllBySessionIdOrderByCreatedAtDesc(sessionId: Long): List<CalibrationResultRecord>

	fun findByJobIdAndCalibratedItemId(jobId: UUID, calibratedItemId: Long): CalibrationResultRecord?

	fun findAllByDecisionIsNull(): List<CalibrationResultRecord>
}
