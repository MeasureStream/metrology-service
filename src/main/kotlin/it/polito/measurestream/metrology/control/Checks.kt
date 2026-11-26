package it.polito.measurestream.metrology.control

import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.PointPhase
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleType
import it.polito.measurestream.metrology.sessions.CalibrationResultRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/* ------------------------------------------------------------------- verifiche intermedie */

enum class CheckVerdict { PASS, FAIL, UNDECIDED }

enum class CheckSource { UI, RASPBERRY }

@Entity
@Table(name = "intermediate_check")
class IntermediateCheck(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "calibrated_item_id", nullable = false) var calibratedItemId: Long,
	@Column(name = "checked_at", nullable = false) var checkedAt: OffsetDateTime,
	@Column(name = "reference_value", nullable = false) var referenceValue: Double,
	@Column(name = "reference_u", nullable = false) var referenceU: Double,
	@Column(name = "reference_k", nullable = false) var referenceK: Double = 2.0,
	@Column(name = "indicated_value", nullable = false) var indicatedValue: Double,
	@Column(name = "indicated_u", nullable = false) var indicatedU: Double,
	@Column(nullable = false) var error: Double,
	@Column(nullable = false) var en: Double,
	var tolerance: Double? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var verdict: CheckVerdict,
	@Column(name = "reference_standard_id") var referenceStandardId: Long? = null,
	@Column(name = "reference_description", length = 200) var referenceDescription: String? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var source: CheckSource = CheckSource.UI,
	@Column(name = "performed_by", nullable = false, length = 64) var performedBy: String,
	@Column(columnDefinition = "text") var notes: String? = null,
	@Column(name = "nonconformity_id") var nonconformityId: Long? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface IntermediateCheckRepository : JpaRepository<IntermediateCheck, Long> {
	fun findAllByCalibratedItemIdOrderByCheckedAtAsc(itemId: Long): List<IntermediateCheck>

	fun findTopByCalibratedItemIdOrderByCheckedAtDesc(itemId: Long): IntermediateCheck?

	fun existsByReferenceStandardId(referenceStandardId: Long): Boolean
}

data class CheckRequest(
	val muExtendedId: Long,
	val sensorIndex: Int,
	val referenceValue: Double,
	/** Incertezza estesa del riferimento, con il suo fattore di copertura. */
	val referenceU: Double,
	val referenceK: Double = 2.0,
	val indicatedValue: Double,
	/** Incertezza estesa dell'indicazione (k = 2); se manca, quella di targa 2·MPE/√3. */
	val indicatedU: Double? = null,
	/** L'MPE per cui decidere; se manca, quello del profilo del punto di controllo. */
	val tolerance: Double? = null,
	val checkedAt: OffsetDateTime? = null,
	val referenceStandardId: Long? = null,
	val referenceDescription: String? = null,
	val source: CheckSource = CheckSource.UI,
	val notes: String? = null,
)

/** I conti di una verifica, separati dal servizio per provarli da soli. */
object Checks {
	/** Il numero En con le incertezze estese (k = 2) di indicazione e riferimento. */
	fun en(error: Double, indicatedU: Double, referenceU: Double): Double {
		val combined = sqrt(indicatedU * indicatedU + referenceU * referenceU)
		return if (combined > 0) error / combined else if (error == 0.0) 0.0 else Double.POSITIVE_INFINITY
	}

	/**
	 * Fuori dall'MPE: FAIL. Dentro l'MPE e compatibile con il riferimento (|En| ≤ 1): PASS.
	 * Dentro l'MPE ma incompatibile con la propria incertezza: UNDECIDED (l'incertezza che lo
	 * strumento dichiara e' troppo ottimista). Senza MPE decide solo En.
	 */
	fun verdict(error: Double, en: Double, tolerance: Double?): CheckVerdict =
		when {
			tolerance != null && abs(error) > tolerance -> CheckVerdict.FAIL
			abs(en) <= 1.0 -> CheckVerdict.PASS
			tolerance == null -> CheckVerdict.FAIL
			else -> CheckVerdict.UNDECIDED
		}
}

/**
 * Le verifiche intermedie (PIANO_METROLOGIA 5.2, [S §5.7]): dall'interfaccia, con il valore del
 * riferimento e la sua incertezza scritti dall'operatore, o dal Raspberry con una procedura breve.
 * Una verifica fallita apre una non conformita' di strumento, come un as-found fuori tolleranza.
 */
@Service
class CheckService(
	private val checks: IntermediateCheckRepository,
	private val items: ItemService,
	private val assignments: ControlPointAssignmentRepository,
	private val points: ControlPointRepository,
	private val nonconformities: NonconformityService,
	private val schedule: ObjectProvider<ScheduleService>,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) : LastCheckSource {
	override fun lastCheck(itemId: Long): OffsetDateTime? = checks.findTopByCalibratedItemIdOrderByCheckedAtDesc(itemId)?.checkedAt

	/** Il profilo del punto di controllo che l'esemplare sorveglia oggi, se ce n'e' uno. */
	private fun profileOf(item: CalibratedItem): ProfileSpec? =
		assignments.findAllByCalibratedItemIdAndToTsIsNull(item.id).firstOrNull()?.let { points.findById(it.controlPointId).orElse(null)?.spec }

	@Transactional
	fun record(request: CheckRequest): IntermediateCheck {
		val caller = policy.caller()
		val item = items.resolveOrCreate(request.muExtendedId, request.sensorIndex)
		if (request.referenceU <= 0 || request.referenceK <= 0) throw BadRequest("Serve l'incertezza del riferimento, con il suo fattore di copertura")
		val profile = profileOf(item)
		val referenceU2 = request.referenceU * 2.0 / request.referenceK
		profile?.let { spec ->
			val uMax = (spec.verificationReferenceUMax)
			if (uMax != null && referenceU2 > uMax) {
				throw BadRequest("Il riferimento ha U = %.3g (k = 2): il profilo ne chiede al massimo %.3g".format(referenceU2, uMax))
			}
		}
		val tolerance = request.tolerance ?: profile?.instrumentMpe
		val indicatedU = request.indicatedU ?: tolerance?.let { 2 * it / sqrt(3.0) }
			?: throw BadRequest("Serve l'incertezza dell'indicazione, o una tolleranza da cui ricavarla")
		val error = request.indicatedValue - request.referenceValue
		val en = Checks.en(error, indicatedU, referenceU2)
		val verdict = Checks.verdict(error, en, tolerance)
		val check =
			checks.save(
				IntermediateCheck(
					calibratedItemId = item.id,
					checkedAt = request.checkedAt ?: OffsetDateTime.now(),
					referenceValue = request.referenceValue,
					referenceU = request.referenceU,
					referenceK = request.referenceK,
					indicatedValue = request.indicatedValue,
					indicatedU = indicatedU,
					error = error,
					en = en,
					tolerance = tolerance,
					verdict = verdict,
					referenceStandardId = request.referenceStandardId,
					referenceDescription = request.referenceDescription,
					source = request.source,
					performedBy = caller.sub,
					notes = request.notes,
				)
			)
		if (verdict == CheckVerdict.FAIL) {
			val nc =
				nonconformities.openInstrument(
					item,
					mapOf(
						"checkId" to check.id,
						"muExtendedId" to item.muExtendedId,
						"sensorIndex" to item.sensorIndex,
						"error" to error,
						"en" to en,
						"tolerance" to tolerance,
						"suspectSince" to checks.findAllByCalibratedItemIdOrderByCheckedAtAsc(item.id).lastOrNull { it.id != check.id && it.verdict == CheckVerdict.PASS }?.checkedAt?.toString(),
					),
					"Verifica intermedia fallita: MU 0x%08X sensore %d (errore %.3g, En %.2f)".format(item.muExtendedId, item.sensorIndex, error, en),
					check.checkedAt,
				)
			check.nonconformityId = nc.id
			checks.save(check)
		}
		audit.record("INTERMEDIATE_CHECK", "calibrated_item", item.id, detail = mapOf("checkId" to check.id, "verdict" to verdict.name, "en" to en))
		schedule.ifAvailable?.recompute(SubjectType.ITEM, item.id)
		return check
	}

	@Transactional(readOnly = true)
	fun list(itemId: Long): List<IntermediateCheck> {
		items.get(itemId)
		return checks.findAllByCalibratedItemIdOrderByCheckedAtAsc(itemId)
	}

	/**
	 * In rodaggio una verifica scritta male si cancella e si riscrive. Se aveva aperto una non
	 * conformita', quella resta: la chiude chi la gestisce, con la sua decisione. In esercizio
	 * una verifica non si cancella: l'errore si corregge con una verifica nuova e una nota.
	 */
	@Transactional
	fun delete(id: Long) {
		policy.requireRunInAdmin("Cancellare una verifica intermedia")
		val check = checks.findById(id).orElseThrow { NotFound("Verifica $id non trovata") }
		audit.record(
			"INTERMEDIATE_CHECK_DELETED",
			"calibrated_item",
			check.calibratedItemId,
			detail =
				mapOf(
					"checkId" to id,
					"checkedAt" to check.checkedAt.toString(),
					"referenceValue" to check.referenceValue,
					"indicatedValue" to check.indicatedValue,
					"verdict" to check.verdict.name,
					"nonconformityId" to check.nonconformityId,
				),
		)
		checks.delete(check)
		checks.flush()
		schedule.ifAvailable?.recompute(SubjectType.ITEM, check.calibratedItemId)
	}
}

/* -------------------------------------------------------------------- carta di controllo */

data class ChartPoint(
	val at: OffsetDateTime,
	val source: String,
	/** La differenza esemplare − riferimento. */
	val value: Double,
	/** L'incertezza estesa della differenza. */
	val expandedU: Double?,
	val label: String,
)

data class ControlChart(
	val calibratedItemId: Long,
	val method: String,
	val points: List<ChartPoint>,
	val center: Double?,
	val sigma: Double?,
	val ucl: Double?,
	val lcl: Double?,
	val uwl: Double?,
	val lwl: Double?,
	val cusumHigh: List<Double>,
	val cusumLow: List<Double>,
	val signals: List<String>,
	val proposal: IntervalProposal? = null,
)

/**
 * La carta di controllo di un esemplare (PIANO_METROLOGIA 5.2): le differenze fra esemplare e
 * riferimento dalle tarature (l'errore as-found medio di ogni certificato) e dalle verifiche,
 * con la carta di Shewhart per valori singoli e la CUSUM tabellare.
 *
 * - Shewhart: centro = media, σ stimata dalla media degli scarti mobili (MR̄ / 1,128), limiti
 *   di controllo a ±3σ e di sorveglianza a ±2σ. Segnali: un punto oltre ±3σ; due su tre oltre
 *   ±2σ dalla stessa parte; sette consecutivi dalla stessa parte del centro.
 * - CUSUM: k = σ/2, h = 5σ: si accumulano gli scostamenti oltre k, e un accumulo oltre h e' una
 *   deriva che i singoli punti ancora non mostrano.
 */
@Service
class ControlChartService(
	private val checks: IntermediateCheckRepository,
	private val certificates: CertificateRepository,
	private val proposals: IntervalProposalRepository,
	private val items: ItemService,
) {
	@Transactional(readOnly = true)
	fun chart(itemId: Long): ControlChart {
		items.get(itemId)
		val fromCertificates =
			listOf(CertificateStatus.EFFECTIVE, CertificateStatus.SUPERSEDED)
				.flatMap { certificates.findAllBySubjectTypeAndSubjectIdAndStatus(SubjectType.ITEM, itemId, it) }
				.mapNotNull { certificatePoint(it) }
		val fromChecks =
			checks.findAllByCalibratedItemIdOrderByCheckedAtAsc(itemId).map {
				ChartPoint(it.checkedAt, "CHECK", it.error, sqrt(it.indicatedU * it.indicatedU + (it.referenceU * 2 / it.referenceK).let { u -> u * u }), "verifica ${it.id}")
			}
		val series = (fromCertificates + fromChecks).sortedBy { it.at }
		return compute(itemId, series).copy(proposal = proposals.findFirstByCalibratedItemIdAndStateOrderByCreatedAtDesc(itemId, ProposalState.PROPOSED))
	}

	private fun certificatePoint(certificate: Certificate): ChartPoint? {
		val found = certificate.points.filter { it.phase == PointPhase.AS_FOUND && it.error != null }
		if (found.isEmpty()) return null
		val at = certificate.calibrationDate?.atStartOfDay()?.atOffset(ZoneOffset.UTC) ?: return null
		return ChartPoint(at, "CALIBRATION", found.mapNotNull { it.error }.average(), found.mapNotNull { it.expandedU }.maxOrNull(), "certificato ${certificate.number} rev. ${certificate.revision}")
	}

	companion object {
		/** d2 per scarti mobili di due valori. */
		const val D2 = 1.128

		fun compute(itemId: Long, series: List<ChartPoint>): ControlChart {
			val values = series.map { it.value }
			if (values.size < 2) {
				return ControlChart(itemId, "SHEWHART_INDIVIDUALS", series, values.firstOrNull(), null, null, null, null, null, emptyList(), emptyList(), emptyList())
			}
			val center = values.average()
			val movingRanges = values.zipWithNext { a, b -> abs(b - a) }
			val sigma = movingRanges.average() / D2
			val signals = mutableListOf<String>()
			if (sigma > 0) {
				values.forEachIndexed { i, v -> if (abs(v - center) > 3 * sigma) signals += "punto ${i + 1} (${series[i].label}) oltre ±3σ" }
				for (i in 2 until values.size) {
					val window = values.subList(i - 2, i + 1)
					if (window.count { it - center > 2 * sigma } >= 2) signals += "due su tre oltre +2σ fino al punto ${i + 1}"
					if (window.count { center - it > 2 * sigma } >= 2) signals += "due su tre oltre −2σ fino al punto ${i + 1}"
				}
			}
			var run = 0
			var side = 0
			values.forEachIndexed { i, v ->
				val s = if (v > center) 1 else if (v < center) -1 else 0
				run = if (s != 0 && s == side) run + 1 else 1
				side = s
				if (run == 7) signals += "sette punti consecutivi dalla stessa parte del centro, fino al punto ${i + 1}"
			}
			val k = sigma / 2
			val h = 5 * sigma
			val high = mutableListOf<Double>()
			val low = mutableListOf<Double>()
			var cHigh = 0.0
			var cLow = 0.0
			values.forEachIndexed { i, v ->
				cHigh = maxOf(0.0, cHigh + (v - center) - k)
				cLow = maxOf(0.0, cLow - (v - center) - k)
				high += cHigh
				low += cLow
				if (sigma > 0 && (cHigh > h || cLow > h)) signals += "CUSUM oltre 5σ al punto ${i + 1}: deriva"
			}
			return ControlChart(
				calibratedItemId = itemId,
				method = "SHEWHART_INDIVIDUALS",
				points = series,
				center = center,
				sigma = sigma,
				ucl = center + 3 * sigma,
				lcl = center - 3 * sigma,
				uwl = center + 2 * sigma,
				lwl = center - 2 * sigma,
				cusumHigh = high,
				cusumLow = low,
				signals = signals.distinct(),
			)
		}
	}
}

/* ------------------------------------------------------------------ proposte d'intervallo */

enum class ProposalState { PROPOSED, ACCEPTED, REJECTED }

@Entity
@Table(name = "interval_proposal")
class IntervalProposal(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "calibrated_item_id", nullable = false) var calibratedItemId: Long,
	@Column(name = "certificate_id") var certificateId: Long? = null,
	@Column(nullable = false, length = 32) var method: String = "OIML_D10_STAIRCASE",
	@Column(name = "current_days", nullable = false) var currentDays: Int,
	@Column(name = "proposed_days", nullable = false) var proposedDays: Int,
	var ratio: Double? = null,
	@Column(nullable = false, columnDefinition = "text") var reason: String,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var state: ProposalState = ProposalState.PROPOSED,
	@Column(name = "decided_by", length = 64) var decidedBy: String? = null,
	@Column(name = "decided_at") var decidedAt: OffsetDateTime? = null,
	@Column(name = "decision_reason", columnDefinition = "text") var decisionReason: String? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface IntervalProposalRepository : JpaRepository<IntervalProposal, Long> {
	fun findAllByCalibratedItemIdOrderByCreatedAtDesc(itemId: Long): List<IntervalProposal>

	fun findFirstByCalibratedItemIdAndStateOrderByCreatedAtDesc(itemId: Long, state: ProposalState): IntervalProposal?
}

data class ProposalDecision(val reason: String? = null)

/**
 * La proposta d'intervallo secondo OIML D 10, metodo «a gradini» (PIANO_METROLOGIA 5.2): dopo
 * ogni taratura, se l'esemplare e' stato trovato entro l'80 % della tolleranza l'intervallo si
 * allunga, se e' stato trovato fuori si accorcia, altrimenti resta. I fattori sono una scelta del
 * laboratorio (configurazione `metrology.intervals.*`); la proposta non cambia niente da sola: la
 * accetta o la respinge chi ha il ruolo di approvatore o di responsabile tecnico.
 */
@Service
class IntervalService(
	private val proposals: IntervalProposalRepository,
	private val results: CalibrationResultRepository,
	private val items: CalibratedItemRepository,
	private val assignments: ControlPointAssignmentRepository,
	private val points: ControlPointRepository,
	private val schedule: ObjectProvider<ScheduleService>,
	private val policy: AccessPolicy,
	private val audit: AuditService,
	@Value("\${metrology.intervals.extend-factor:1.25}") private val extendFactor: Double,
	@Value("\${metrology.intervals.reduce-factor:0.5}") private val reduceFactor: Double,
	@Value("\${metrology.intervals.extend-below-ratio:0.8}") private val extendBelow: Double,
	@Value("\${metrology.intervals.min-days:30}") private val minDays: Int,
	@Value("\${metrology.intervals.max-days:1095}") private val maxDays: Int,
) {
	/** La proposta dopo un certificato entrato in vigore, se i numeri bastano a farne una. */
	@Transactional
	fun proposeAfter(certificate: Certificate): IntervalProposal? {
		if (certificate.subjectType != SubjectType.ITEM) return null
		val item = items.findById(certificate.subjectId).orElse(null) ?: return null
		val result = certificate.calibrationResultId?.let { results.findById(it).orElse(null) }
		val profile = assignments.findAllByCalibratedItemIdAndToTsIsNull(item.id).firstOrNull()?.let { points.findById(it.controlPointId).orElse(null)?.spec }
		val tolerance =
			result?.conformity.orEmpty().mapNotNull { (it["tolerance"] as? Number)?.toDouble() }.minOrNull() ?: profile?.instrumentMpe ?: return null
		val worst =
			(result?.points.orEmpty().filter { it["phase"] == PointPhase.AS_FOUND.name }.mapNotNull { (it["error"] as? Number)?.toDouble() }
				.ifEmpty { certificate.points.filter { it.phase == PointPhase.AS_FOUND }.mapNotNull { it.error } })
				.maxOfOrNull { abs(it) } ?: return null
		val current =
			item.intervalOverrideDays
				?: profile?.calibrationInterval?.let { (it.toTotalMonths() * 30.44 + it.days).roundToInt() }
				?: certificate.validUntil?.let { until -> certificate.calibrationDate?.let { Duration.between(it.atStartOfDay().atOffset(ZoneOffset.UTC), until).toDays().toInt() } }
				?: 365
		val ratio = worst / tolerance
		val (proposed, reason) =
			when {
				ratio <= extendBelow ->
					(current * extendFactor).roundToInt() to "as-found entro il ${(ratio * 100).roundToInt()} % della tolleranza (soglia ${(extendBelow * 100).roundToInt()} %): l'intervallo si allunga"
				ratio > 1.0 -> (current * reduceFactor).roundToInt() to "as-found fuori tolleranza (${(ratio * 100).roundToInt()} %): l'intervallo si accorcia"
				else -> return null
			}
		val bounded = proposed.coerceIn(minDays, maxDays)
		if (bounded == current) return null
		val proposal =
			proposals.save(
				IntervalProposal(calibratedItemId = item.id, certificateId = certificate.id, currentDays = current, proposedDays = bounded, ratio = ratio, reason = reason)
			)
		audit.record("INTERVAL_PROPOSED", "calibrated_item", item.id, detail = mapOf("proposalId" to proposal.id, "from" to current, "to" to bounded, "ratio" to ratio))
		return proposal
	}

	@Transactional
	fun accept(id: Long, decision: ProposalDecision): IntervalProposal {
		val proposal = decidable(id)
		val item = items.findById(proposal.calibratedItemId).orElseThrow { NotFound("Esemplare ${proposal.calibratedItemId} non trovato") }
		item.intervalOverrideDays = proposal.proposedDays
		items.save(item)
		close(proposal, ProposalState.ACCEPTED, decision.reason)
		schedule.ifAvailable?.recompute(SubjectType.ITEM, item.id)
		return proposal
	}

	@Transactional
	fun reject(id: Long, decision: ProposalDecision): IntervalProposal {
		if (decision.reason.isNullOrBlank()) throw BadRequest("Una proposta si respinge con il motivo")
		return close(decidable(id), ProposalState.REJECTED, decision.reason)
	}

	@Transactional(readOnly = true)
	fun list(itemId: Long): List<IntervalProposal> = proposals.findAllByCalibratedItemIdOrderByCreatedAtDesc(itemId)

	private fun decidable(id: Long): IntervalProposal {
		policy.requireRole(policy.caller(), MetrologyRoleType.APPROVER, MetrologyRoleType.TECHNICAL_MANAGER, what = "Decidere l'intervallo di taratura")
		val proposal = proposals.findById(id).orElseThrow { NotFound("Proposta $id non trovata") }
		if (proposal.state != ProposalState.PROPOSED) throw Conflict("La proposta e' gia' ${proposal.state}")
		return proposal
	}

	private fun close(proposal: IntervalProposal, state: ProposalState, reason: String?): IntervalProposal {
		proposal.state = state
		proposal.decidedBy = policy.caller().sub
		proposal.decidedAt = OffsetDateTime.now()
		proposal.decisionReason = reason
		audit.record("INTERVAL_" + state.name, "calibrated_item", proposal.calibratedItemId, detail = mapOf("proposalId" to proposal.id, "days" to proposal.proposedDays, "reason" to reason))
		return proposals.save(proposal)
	}
}

@RestController
@RequestMapping("/API/metrology")
class CheckController(
	private val checks: CheckService,
	private val charts: ControlChartService,
	private val intervals: IntervalService,
	private val items: ItemService,
) {
	@PostMapping("/checks") fun record(@RequestBody request: CheckRequest) = checks.record(request)

	@GetMapping("/checks") fun list(@RequestParam itemId: Long) = checks.list(itemId)

	@DeleteMapping("/checks/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: Long) = checks.delete(id)

	@GetMapping("/items/{id}/control-chart") fun chart(@PathVariable id: Long) = charts.chart(id)

	@GetMapping("/items/{id}/interval-proposals")
	fun proposals(@PathVariable id: Long): List<IntervalProposal> {
		items.get(id)
		return intervals.list(id)
	}

	@PostMapping("/interval-proposals/{id}/accept") fun accept(@PathVariable id: Long, @RequestBody(required = false) decision: ProposalDecision?) = intervals.accept(id, decision ?: ProposalDecision())

	@PostMapping("/interval-proposals/{id}/reject") fun reject(@PathVariable id: Long, @RequestBody decision: ProposalDecision) = intervals.reject(id, decision)
}
