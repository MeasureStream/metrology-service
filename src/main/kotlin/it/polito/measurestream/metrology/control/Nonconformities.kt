package it.polito.measurestream.metrology.control

import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleType
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/* ------------------------------------------------------------------------------- entita' */

enum class NonconformityType { PROCESS, INSTRUMENT }

enum class NonconformityState { OPEN, ACKNOWLEDGED, RESOLVED }

enum class NonconformityDecision { ACCEPT_AS_IS, ADJUST, REPAIR, RETIRE, CORRECTIVE_ACTION }

enum class Severity { MINOR, MAJOR }

enum class ExcursionKind { LIMIT, GUARD_ZONE }

enum class ExcursionState { OPEN, CLOSED }

/**
 * Una non conformita' (criticita' M7, PIANO_METROLOGIA 4.4). Di PROCESSO se nasce da
 * un'escursione del punto di controllo: decide il cliente, con un'azione correttiva. Di
 * STRUMENTO se nasce da un as-found fuori tolleranza: decide il laboratorio.
 */
@Entity
@Table(name = "nonconformity")
class Nonconformity(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var type: NonconformityType,
	@Column(name = "control_point_id") var controlPointId: Long? = null,
	@Column(name = "calibrated_item_id") var calibratedItemId: Long? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) var subject: Map<String, Any?>,
	@Column(nullable = false, length = 200) var title: String,
	@Column(name = "detected_at", nullable = false) var detectedAt: OffsetDateTime,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) var severity: Severity,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var state: NonconformityState = NonconformityState.OPEN,
	@Column(name = "owner_sub", length = 64) var ownerSub: String? = null,
	@Column(name = "assignee_sub", length = 64) var assigneeSub: String? = null,
	@Column(name = "acknowledged_by", length = 64) var acknowledgedBy: String? = null,
	@Column(name = "acknowledged_at") var acknowledgedAt: OffsetDateTime? = null,
	@Enumerated(EnumType.STRING) @Column(length = 24) var decision: NonconformityDecision? = null,
	@Column(name = "decision_reason", columnDefinition = "text") var decisionReason: String? = null,
	@Column(name = "decided_by", length = 64) var decidedBy: String? = null,
	@Column(name = "decided_at") var decidedAt: OffsetDateTime? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/** Valori non conformi consecutivi di un punto di controllo [S §5.6]. */
@Entity
@Table(name = "excursion")
class Excursion(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "control_point_id", nullable = false) var controlPointId: Long,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var kind: ExcursionKind,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) var side: LimitSide,
	@Column(name = "limit_value", nullable = false) var limitValue: Double,
	@Column(name = "started_at", nullable = false) var startedAt: OffsetDateTime,
	@Column(name = "ended_at") var endedAt: OffsetDateTime? = null,
	@Column(nullable = false) var peak: Double,
	@Column(name = "peak_at", nullable = false) var peakAt: OffsetDateTime,
	@Column(name = "degree_minutes", nullable = false) var degreeMinutes: Double = 0.0,
	@Column(nullable = false) var samples: Int = 1,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) var state: ExcursionState = ExcursionState.OPEN,
	@Column(name = "nonconformity_id") var nonconformityId: Long? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface NonconformityRepository : JpaRepository<Nonconformity, Long> {
	fun findAllByOrderByDetectedAtDesc(): List<Nonconformity>

	fun findAllByOwnerSubInOrderByDetectedAtDesc(owners: Collection<String>): List<Nonconformity>

	fun countByControlPointIdAndStateNot(controlPointId: Long, state: NonconformityState): Long

	fun findAllByCalibratedItemIdAndTypeAndStateNot(calibratedItemId: Long, type: NonconformityType, state: NonconformityState): List<Nonconformity>
}

interface ExcursionRepository : JpaRepository<Excursion, Long> {
	fun findByControlPointIdAndState(controlPointId: Long, state: ExcursionState): Excursion?

	fun findAllByControlPointIdOrderByStartedAtDesc(controlPointId: Long): List<Excursion>

	fun countByControlPointIdAndState(controlPointId: Long, state: ExcursionState): Long
}

/* ------------------------------------------------------------------------------ escursioni */

/**
 * La macchina a stati delle escursioni di un punto di controllo.
 *
 * - Un valore NON CONFORME apre un'escursione LIMIT (o promuove a LIMIT quella di zona di
 *   guardia in corso) e con essa una non conformita' di processo MAJOR.
 * - Una zona di guardia che dura piu' di `guardZoneOpensExcursionAfter` del profilo apre
 *   un'escursione GUARD_ZONE e una non conformita' MINOR; senza quel campo la zona di guardia
 *   si marca soltanto.
 * - L'escursione resta aperta finche' i valori non tornano conformi: picco e grado-minuti oltre
 *   il limite si aggiornano a ogni valore, e il primo valore conforme la chiude.
 */
@Service
class ExcursionService(
	private val excursions: ExcursionRepository,
	private val nonconformities: NonconformityService,
) {
	fun track(point: ControlPoint, state: ConformityState, judgement: Judgement, value: Double, start: OffsetDateTime, end: OffsetDateTime) {
		val open = excursions.findByControlPointIdAndState(point.id, ExcursionState.OPEN)
		when (judgement.verdict) {
			SampleVerdict.NON_CONFORM -> {
				if (state.guardSince == null) state.guardSince = start
				val excursion = open ?: openExcursion(point, ExcursionKind.LIMIT, judgement, value, state.guardSince ?: start, end)
				if (excursion.kind == ExcursionKind.GUARD_ZONE) {
					excursion.kind = ExcursionKind.LIMIT
					excursion.nonconformityId?.let { nonconformities.escalate(it) }
				}
				update(excursion, judgement, value, start, end, counted = open != null)
			}
			SampleVerdict.GUARD_ZONE -> {
				if (state.guardSince == null) state.guardSince = start
				if (open != null) {
					update(open, judgement, value, start, end, counted = true)
				} else {
					val after = point.spec.guardZoneOpensExcursionAfter
					if (after != null && Duration.between(state.guardSince, end) >= after) {
						val excursion = openExcursion(point, ExcursionKind.GUARD_ZONE, judgement, value, state.guardSince!!, end)
						update(excursion, judgement, value, start, end, counted = false)
					}
				}
			}
			SampleVerdict.CONFORM, SampleVerdict.NOT_EVALUABLE -> {
				state.guardSince = null
				if (open != null) {
					open.state = ExcursionState.CLOSED
					open.endedAt = start
					excursions.save(open)
				}
			}
		}
	}

	private fun openExcursion(point: ControlPoint, kind: ExcursionKind, judgement: Judgement, value: Double, start: OffsetDateTime, at: OffsetDateTime): Excursion {
		val side = judgement.side ?: LimitSide.HIGH
		val limit = judgement.limit ?: (if (side == LimitSide.HIGH) point.limitHigh else point.limitLow) ?: 0.0
		val excursion = excursions.save(Excursion(controlPointId = point.id, kind = kind, side = side, limitValue = limit, startedAt = start, peak = value, peakAt = at))
		val nc = nonconformities.openProcess(point, excursion)
		excursion.nonconformityId = nc.id
		return excursions.save(excursion)
	}

	private fun update(excursion: Excursion, judgement: Judgement, value: Double, start: OffsetDateTime, end: OffsetDateTime, counted: Boolean) {
		val side = excursion.side
		val worse = if (side == LimitSide.HIGH) value > excursion.peak else value < excursion.peak
		if (worse) {
			excursion.peak = value
			excursion.peakAt = end
		}
		val minutes = Duration.between(start, end).toMillis() / 60_000.0
		excursion.degreeMinutes += Conformity.excess(value, side, excursion.limitValue) * minutes.coerceAtLeast(0.0)
		if (counted) excursion.samples += 1
		judgement.limit?.let { excursion.limitValue = it }
		excursions.save(excursion)
	}

	fun forControlPoint(controlPointId: Long): List<Excursion> = excursions.findAllByControlPointIdOrderByStartedAtDesc(controlPointId)
}

/* ---------------------------------------------------------------------- non conformita' */

data class NonconformityDecisionRequest(val decision: NonconformityDecision, val reason: String? = null)

@Service
class NonconformityService(
	private val repository: NonconformityRepository,
	private val notifier: Notifier,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	fun openProcess(point: ControlPoint, excursion: Excursion): Nonconformity {
		val severity = if (excursion.kind == ExcursionKind.LIMIT) Severity.MAJOR else Severity.MINOR
		val what = if (excursion.side == LimitSide.HIGH) "sopra" else "sotto"
		val nc =
			repository.save(
				Nonconformity(
					type = NonconformityType.PROCESS,
					controlPointId = point.id,
					subject =
						mapOf(
							"controlPointId" to point.id,
							"controlPointName" to point.name,
							"excursionId" to excursion.id,
							"kind" to excursion.kind.name,
							"side" to excursion.side.name,
							"limit" to excursion.limitValue,
							"profile" to mapOf("templateId" to point.profileTemplateId, "version" to point.profileVersion),
						),
					title =
						if (excursion.kind == ExcursionKind.LIMIT) "Escursione $what il limite ${excursion.limitValue} a ${point.name}"
						else "Zona di guardia prolungata $what il limite ${excursion.limitValue} a ${point.name}",
					detectedAt = excursion.startedAt,
					severity = severity,
					ownerSub = point.ownerSub,
				)
			)
		audit.record("NONCONFORMITY_OPENED", "nonconformity", nc.id, detail = mapOf("type" to nc.type.name, "controlPointId" to point.id, "excursionId" to excursion.id), userSub = "conformity")
		notifier.nonconformity(nc)
		return nc
	}

	/** Una zona di guardia diventata superamento del limite: la non conformita' diventa maggiore. */
	fun escalate(id: Long) {
		val nc = repository.findById(id).orElse(null) ?: return
		if (nc.severity == Severity.MAJOR) return
		nc.severity = Severity.MAJOR
		nc.title = nc.title.replace("Zona di guardia prolungata", "Escursione")
		repository.save(nc)
		audit.record("NONCONFORMITY_ESCALATED", "nonconformity", id, userSub = "conformity")
	}

	/**
	 * La non conformita' di strumento da un as-found fuori tolleranza (passo 4.4). I dati misurati
	 * dall'esemplare dall'ultima taratura vanno rivalutati: e' scritto nel soggetto, e la
	 * riconversione della fase 5 parte da qui.
	 */
	fun openInstrument(item: CalibratedItem, subject: Map<String, Any?>, title: String, detectedAt: OffsetDateTime): Nonconformity {
		val nc =
			repository.save(
				Nonconformity(
					type = NonconformityType.INSTRUMENT,
					calibratedItemId = item.id,
					subject = subject,
					title = title,
					detectedAt = detectedAt,
					severity = Severity.MAJOR,
					ownerSub = item.ownerSub,
				)
			)
		audit.record("NONCONFORMITY_OPENED", "nonconformity", nc.id, detail = mapOf("type" to nc.type.name, "itemId" to item.id), userSub = "calibration-engine")
		notifier.nonconformity(nc)
		return nc
	}

	/** La decisione presa sul risultato di taratura chiude il cerchio sulla non conformita' di strumento. */
	fun recordInstrumentDecision(itemId: Long, resultId: Long, decision: String, reason: String?, by: String) {
		val mapped =
			when (decision) {
				"ADJUST" -> NonconformityDecision.ADJUST
				"REPAIR" -> NonconformityDecision.REPAIR
				"RETIRE" -> NonconformityDecision.RETIRE
				else -> NonconformityDecision.ACCEPT_AS_IS
			}
		repository.findAllByCalibratedItemIdAndTypeAndStateNot(itemId, NonconformityType.INSTRUMENT, NonconformityState.RESOLVED)
			.filter { (it.subject["resultId"] as? Number)?.toLong() == resultId }
			.forEach { nc ->
				nc.decision = mapped
				nc.decisionReason = reason
				nc.decidedBy = by
				nc.decidedAt = OffsetDateTime.now()
				// Resta da valutare l'impatto sui dati misurati: si chiude a mano, dopo.
				if (nc.state == NonconformityState.OPEN) {
					nc.state = NonconformityState.ACKNOWLEDGED
					nc.acknowledgedBy = by
					nc.acknowledgedAt = nc.decidedAt
				}
				repository.save(nc)
				audit.record("NONCONFORMITY_DECIDED", "nonconformity", nc.id, detail = mapOf("decision" to mapped.name, "resultId" to resultId))
			}
	}

	@Transactional(readOnly = true)
	fun list(state: NonconformityState?): List<Nonconformity> {
		val owners = policy.visibleOwners()
		val all = if (owners == null) repository.findAllByOrderByDetectedAtDesc() else repository.findAllByOwnerSubInOrderByDetectedAtDesc(owners)
		return all.filter { state == null || it.state == state }
	}

	@Transactional(readOnly = true)
	fun get(id: Long): Nonconformity {
		val nc = repository.findById(id).orElseThrow { NotFound("Non conformita' $id non trovata") }
		if (!policy.canSee(nc.ownerSub)) throw NotFound("Non conformita' $id non trovata")
		return nc
	}

	@Transactional
	fun acknowledge(id: Long): Nonconformity {
		val nc = get(id)
		requireCanHandle(nc)
		if (nc.state != NonconformityState.OPEN) throw Conflict("La non conformita' e' gia' ${nc.state}")
		val caller = policy.caller()
		nc.state = NonconformityState.ACKNOWLEDGED
		nc.acknowledgedBy = caller.sub
		nc.acknowledgedAt = OffsetDateTime.now()
		nc.assigneeSub = nc.assigneeSub ?: caller.sub
		audit.record("NONCONFORMITY_ACKNOWLEDGED", "nonconformity", id)
		return repository.save(nc)
	}

	@Transactional
	fun decide(id: Long, request: NonconformityDecisionRequest): Nonconformity {
		val nc = get(id)
		requireCanHandle(nc)
		if (nc.state == NonconformityState.RESOLVED) throw Conflict("La non conformita' e' gia' chiusa")
		if (request.reason.isNullOrBlank()) throw BadRequest("Una non conformita' si chiude con il motivo della decisione")
		val allowed =
			when (nc.type) {
				NonconformityType.PROCESS -> setOf(NonconformityDecision.CORRECTIVE_ACTION, NonconformityDecision.ACCEPT_AS_IS)
				NonconformityType.INSTRUMENT -> setOf(NonconformityDecision.ACCEPT_AS_IS, NonconformityDecision.ADJUST, NonconformityDecision.REPAIR, NonconformityDecision.RETIRE)
			}
		if (request.decision !in allowed) throw BadRequest("Per una non conformita' ${nc.type} le decisioni sono ${allowed.joinToString()}")
		val caller = policy.caller()
		nc.decision = request.decision
		nc.decisionReason = request.reason
		nc.decidedBy = caller.sub
		nc.decidedAt = OffsetDateTime.now()
		nc.state = NonconformityState.RESOLVED
		audit.record("NONCONFORMITY_RESOLVED", "nonconformity", id, detail = mapOf("decision" to request.decision.name, "reason" to request.reason))
		return repository.save(nc)
	}

	/**
	 * Chi gestisce che cosa [S §9]: la non conformita' di processo il cliente (il proprietario o
	 * un collega della sua azienda) o il personale; quella di strumento il laboratorio, con il
	 * ruolo di tecnico o di approvatore.
	 */
	private fun requireCanHandle(nc: Nonconformity) {
		val caller = policy.caller()
		when (nc.type) {
			NonconformityType.PROCESS -> if (!policy.canSee(nc.ownerSub, caller = caller)) throw Forbidden("La non conformita' appartiene a un altro cliente")
			NonconformityType.INSTRUMENT ->
				if (!policy.has(caller, MetrologyRoleType.TECHNICIAN, MetrologyRoleType.APPROVER)) {
					throw Forbidden("Una non conformita' di strumento la gestisce il laboratorio (tecnico o approvatore)")
				}
		}
	}
}

@RestController
@RequestMapping("/API/metrology/nonconformities")
class NonconformityController(private val service: NonconformityService) {
	@GetMapping fun list(@RequestParam(required = false) state: NonconformityState?) = service.list(state)

	@GetMapping("/{id}") fun one(@PathVariable id: Long) = service.get(id)

	@PostMapping("/{id}/acknowledge") fun acknowledge(@PathVariable id: Long) = service.acknowledge(id)

	@PostMapping("/{id}/decision") fun decide(@PathVariable id: Long, @RequestBody request: NonconformityDecisionRequest) = service.decide(id, request)
}
