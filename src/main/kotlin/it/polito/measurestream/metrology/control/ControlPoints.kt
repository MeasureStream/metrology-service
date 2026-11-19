package it.polito.measurestream.metrology.control

import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.items.SensorManagerClient
import it.polito.measurestream.metrology.security.AccessPolicy
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.OffsetDateTime
import java.time.Period
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* ------------------------------------------------------------------------------- entita' */

/**
 * Un punto di controllo: il luogo e il processo sorvegliati (una cella, una vetrina), con il
 * profilo d'uso che dice entro quali limiti devono stare (PIANO_METROLOGIA 4.2, [S §5.6]).
 * Il profilo si assegna qui, mai al sensore.
 */
@Entity
@Table(name = "control_point")
class ControlPoint(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "owner_sub", nullable = false, length = 64) var ownerSub: String,
	@Column(name = "company_id") var companyId: Long? = null,
	@Column(nullable = false, length = 120) var name: String,
	@Column(length = 200) var location: String? = null,
	@Column(length = 120) var process: String? = null,
	@Column(name = "profile_template_id", nullable = false) var profileTemplateId: Int,
	@Column(name = "profile_version", nullable = false, length = 32) var profileVersion: String,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) var profile: Map<String, Any?>,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var overrides: Map<String, Any?>? = null,
	@Column(name = "limit_low") var limitLow: Double? = null,
	@Column(name = "limit_high") var limitHigh: Double? = null,
	@Column(name = "created_by", nullable = false, length = 64) var createdBy: String,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "archived_at") var archivedAt: OffsetDateTime? = null,
) {
	val spec: ProfileSpec get() = ProfileSpec.of(profile)
}

/** Un esemplare che sorveglia il punto di controllo per un periodo. */
@Entity
@Table(name = "control_point_assignment")
class ControlPointAssignment(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "control_point_id", nullable = false) var controlPointId: Long,
	@Column(name = "calibrated_item_id", nullable = false) var calibratedItemId: Long,
	@Column(nullable = false, length = 16) var metric: String = "mean",
	@Column(name = "from_ts", nullable = false) var fromTs: OffsetDateTime,
	@Column(name = "to_ts") var toTs: OffsetDateTime? = null,
	@Column(name = "created_by", nullable = false, length = 64) var createdBy: String,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
	fun covers(at: OffsetDateTime) = !at.isBefore(fromTs) && (toTs == null || at.isBefore(toTs))
}

interface ControlPointRepository : JpaRepository<ControlPoint, Long> {
	fun findAllByOrderByName(): List<ControlPoint>

	fun findAllByOwnerSubInOrderByName(owners: Collection<String>): List<ControlPoint>
}

interface ControlPointAssignmentRepository : JpaRepository<ControlPointAssignment, Long> {
	fun findAllByControlPointIdOrderByFromTsDesc(controlPointId: Long): List<ControlPointAssignment>

	fun findByControlPointIdAndToTsIsNull(controlPointId: Long): ControlPointAssignment?

	fun findByCalibratedItemIdAndMetricAndToTsIsNull(calibratedItemId: Long, metric: String): ControlPointAssignment?

	@Query(
		"select a from ControlPointAssignment a where a.calibratedItemId = :itemId and a.metric = :metric " +
			"and a.fromTs <= :at and (a.toTs is null or a.toTs > :at)"
	)
	fun findCovering(itemId: Long, metric: String, at: OffsetDateTime): ControlPointAssignment?

	fun findAllByCalibratedItemIdAndToTsIsNull(calibratedItemId: Long): List<ControlPointAssignment>
}

/* ------------------------------------------------------------------------------- profilo */

/** Il clima storico della EN 15757: limiti ricavati dai dati del punto di controllo. */
data class ClimateSpec(
	val movingAverageDays: Int,
	val lowPercentile: Double,
	val highPercentile: Double,
	val minHalfBand: Double,
	val minHistoryDays: Int,
)

/**
 * Un profilo d'uso letto come lo leggono conformita' e scadenzario. Le durate sono ISO 8601:
 * gli intervalli in mesi o anni sono di calendario (`P3M`), le durate brevi in tempo (`PT30M`).
 */
data class ProfileSpec(
	val name: String?,
	val quantity: String?,
	val dsi: String?,
	val low: Double?,
	val high: Double?,
	val climate: ClimateSpec?,
	val guardZoneOpensExcursionAfter: Duration?,
	val calibrationInterval: Period?,
	val intermediateCheckInterval: Period?,
	val instrumentMpe: Double?,
	/** L'incertezza estesa massima (k = 2) del riferimento delle verifiche intermedie. */
	val verificationReferenceUMax: Double? = null,
) {
	companion object {
		fun of(profile: Map<String, Any?>): ProfileSpec {
			val limits = profile["limits"] as? Map<*, *>
			val rule = profile["decisionRule"] as? Map<*, *>
			val intervals = profile["intervals"] as? Map<*, *>
			val climate = (profile["historicClimate"] as? Map<*, *>)?.let { c ->
				val percentiles = (c["percentiles"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }.orEmpty()
				ClimateSpec(
					movingAverageDays = (c["movingAverageDays"] as? Number)?.toInt() ?: 30,
					lowPercentile = percentiles.getOrNull(0) ?: 7.0,
					highPercentile = percentiles.getOrNull(1) ?: 93.0,
					minHalfBand = (c["minHalfBand"] as? Number)?.toDouble() ?: 0.0,
					minHistoryDays = (c["minHistoryDays"] as? Number)?.toInt() ?: 395,
				)
			}
			return ProfileSpec(
				name = profile["name"] as? String,
				quantity = profile["quantity"] as? String,
				dsi = profile["dsi"] as? String,
				low = (limits?.get("low") as? Number)?.toDouble(),
				high = (limits?.get("high") as? Number)?.toDouble(),
				climate = climate,
				guardZoneOpensExcursionAfter = (rule?.get("guardZoneOpensExcursionAfter") as? String)?.let { runCatching { Duration.parse(it) }.getOrNull() },
				calibrationInterval = (intervals?.get("calibration") as? String)?.let { runCatching { Period.parse(it) }.getOrNull() },
				intermediateCheckInterval = (intervals?.get("intermediateCheck") as? String)?.let { runCatching { Period.parse(it) }.getOrNull() },
				instrumentMpe = ((profile["instrument"] as? Map<*, *>)?.get("mpe") as? Number)?.toDouble(),
				verificationReferenceUMax =
					(profile["verificationReference"] as? Map<*, *>)?.let { v ->
						val uMax = (v["uMax"] as? Number)?.toDouble()
						val k = (v["k"] as? Number)?.toDouble() ?: 2.0
						uMax?.let { it * 2.0 / k }
					},
			)
		}
	}
}

/**
 * I limiti in vigore: quelli del profilo, ristretti dalle eccezioni del cliente. Un'eccezione
 * che allarga un limite e' rifiutata: il profilo e' il minimo che l'uso richiede [S §5.6].
 * Aggiungere un limite che il profilo non ha e' una restrizione, ed e' ammesso.
 */
fun effectiveLimits(spec: ProfileSpec, overrides: Map<String, Any?>?): Pair<Double?, Double?> {
	val limits = overrides?.get("limits") as? Map<*, *>
	val low = (limits?.get("low") as? Number)?.toDouble()
	val high = (limits?.get("high") as? Number)?.toDouble()
	if (high != null && spec.high != null && high > spec.high) throw BadRequest("L'eccezione alza il limite superiore oltre il profilo (${spec.high}): puo' solo restringerlo")
	if (low != null && spec.low != null && low < spec.low) throw BadRequest("L'eccezione abbassa il limite inferiore oltre il profilo (${spec.low}): puo' solo restringerlo")
	val effectiveLow = low ?: spec.low
	val effectiveHigh = high ?: spec.high
	if (effectiveLow != null && effectiveHigh != null && effectiveLow >= effectiveHigh) throw BadRequest("Il limite inferiore deve stare sotto quello superiore")
	if (limits != null && (overrides["reason"] as? String).isNullOrBlank()) throw BadRequest("Un'eccezione ai limiti del profilo richiede il motivo")
	return effectiveLow to effectiveHigh
}

/* ------------------------------------------------------------------------------- servizio */

data class ProfileRef(val templateId: Int, val version: String)

data class ControlPointRequest(
	val name: String,
	val location: String? = null,
	val process: String? = null,
	val profile: ProfileRef? = null,
	val overrides: Map<String, Any?>? = null,
	/** Solo il personale crea punti di controllo per conto di un cliente. */
	val ownerSub: String? = null,
)

data class AssignRequest(val muExtendedId: Long, val sensorIndex: Int, val metric: String = "mean", val from: OffsetDateTime? = null)

data class UnassignRequest(val at: OffsetDateTime? = null)

data class AssignmentDTO(
	val id: Long,
	val calibratedItemId: Long,
	val muExtendedId: Long?,
	val sensorIndex: Int?,
	val modelName: String?,
	val metric: String,
	val from: OffsetDateTime,
	val to: OffsetDateTime?,
)

data class ControlPointDTO(
	val id: Long,
	val ownerSub: String,
	val name: String,
	val location: String?,
	val process: String?,
	val profile: ProfileRef,
	val profileName: String?,
	val quantity: String?,
	val dsi: String?,
	val profileLimits: Map<String, Double?>,
	val overrides: Map<String, Any?>?,
	val limitLow: Double?,
	val limitHigh: Double?,
	val historicClimate: Boolean,
	val guardZoneOpensExcursionAfter: String?,
	val createdAt: OffsetDateTime,
	val archivedAt: OffsetDateTime?,
	val current: AssignmentDTO?,
	val assignments: List<AssignmentDTO> = emptyList(),
	val lastVerdict: String? = null,
	val lastValue: Double? = null,
	val lastAt: OffsetDateTime? = null,
	val openExcursions: Int = 0,
	val openNonconformities: Int = 0,
	val climate: Map<String, Any?>? = null,
)

@Service
class ControlPointService(
	private val points: ControlPointRepository,
	private val assignments: ControlPointAssignmentRepository,
	private val items: ItemService,
	private val itemRepository: CalibratedItemRepository,
	private val sensorManager: SensorManagerClient,
	private val states: ConformityStateRepository,
	private val excursions: ExcursionRepository,
	private val nonconformities: NonconformityRepository,
	private val climate: HistoricClimateService,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	@Transactional
	fun create(request: ControlPointRequest): ControlPointDTO {
		val caller = policy.caller()
		if (request.name.isBlank()) throw BadRequest("Il punto di controllo ha bisogno di un nome")
		val ref = request.profile ?: throw BadRequest("Indica il profilo d'uso (templateId e versione)")
		val owner =
			when {
				request.ownerSub.isNullOrBlank() || request.ownerSub == caller.sub -> caller.sub
				policy.isStaff(caller) -> request.ownerSub
				else -> throw Forbidden("Solo il personale del laboratorio crea punti di controllo per altri")
			}
		val entry =
			sensorManager.template("profile", ref.templateId, ref.version)
				?: throw BadRequest("Il profilo ${ref.templateId} ${ref.version} non e' nel registro")
		if (entry["status"] != null && entry["status"] != "PUBLISHED") throw Conflict("Il profilo ${ref.templateId} ${ref.version} non e' pubblicato")
		@Suppress("UNCHECKED_CAST")
		val content = entry["content"] as? Map<String, Any?> ?: throw BadRequest("Il profilo ${ref.templateId} ${ref.version} e' vuoto")
		val spec = ProfileSpec.of(content)
		val (low, high) = effectiveLimits(spec, request.overrides)
		val point =
			points.save(
				ControlPoint(
					ownerSub = owner,
					companyId = policy.companyOf(owner),
					name = request.name.trim(),
					location = request.location,
					process = request.process,
					profileTemplateId = ref.templateId,
					profileVersion = ref.version,
					profile = content,
					overrides = request.overrides,
					limitLow = low,
					limitHigh = high,
					createdBy = caller.sub,
				)
			)
		audit.record("CONTROL_POINT_CREATED", "control_point", point.id, after = request)
		return toDTO(point, detailed = true)
	}

	@Transactional
	fun update(id: Long, request: ControlPointRequest): ControlPointDTO {
		val point = editable(id)
		val before = toDTO(point)
		val (low, high) = effectiveLimits(point.spec, request.overrides)
		point.name = request.name.trim().ifBlank { point.name }
		point.location = request.location
		point.process = request.process
		point.overrides = request.overrides
		point.limitLow = low
		point.limitHigh = high
		audit.record("CONTROL_POINT_UPDATED", "control_point", id, before = before, after = request)
		return toDTO(points.save(point), detailed = true)
	}

	/**
	 * Un esemplare comincia a sorvegliare il punto di controllo. L'assegnazione aperta del punto
	 * (la MU che si sostituisce) e quella dell'esemplare altrove si chiudono allo stesso istante:
	 * la serie del punto resta continua [S §5.6].
	 */
	@Transactional
	fun assign(id: Long, request: AssignRequest): ControlPointDTO {
		val point = editable(id)
		val caller = policy.caller()
		val item = items.resolveOrCreate(request.muExtendedId, request.sensorIndex)
		val from = request.from ?: OffsetDateTime.now()
		listOfNotNull(
			assignments.findByControlPointIdAndToTsIsNull(id),
			assignments.findByCalibratedItemIdAndMetricAndToTsIsNull(item.id, request.metric),
		).distinctBy { it.id }.forEach { open ->
			if (!from.isAfter(open.fromTs)) throw Conflict("La nuova assegnazione deve cominciare dopo quella in corso (${open.fromTs})")
			open.toTs = from
			assignments.saveAndFlush(open)
		}
		val created = assignments.save(ControlPointAssignment(controlPointId = id, calibratedItemId = item.id, metric = request.metric, fromTs = from, createdBy = caller.sub))
		audit.record("CONTROL_POINT_ASSIGNED", "control_point", id, detail = mapOf("assignmentId" to created.id, "itemId" to item.id, "metric" to request.metric, "from" to from))
		return toDTO(point, detailed = true)
	}

	@Transactional
	fun unassign(id: Long, request: UnassignRequest): ControlPointDTO {
		val point = editable(id)
		val open = assignments.findByControlPointIdAndToTsIsNull(id) ?: throw Conflict("Il punto di controllo non ha un esemplare assegnato")
		val at = request.at ?: OffsetDateTime.now()
		if (!at.isAfter(open.fromTs)) throw BadRequest("La fine dell'assegnazione deve seguirne l'inizio")
		open.toTs = at
		assignments.save(open)
		audit.record("CONTROL_POINT_UNASSIGNED", "control_point", id, detail = mapOf("assignmentId" to open.id, "at" to at))
		return toDTO(point, detailed = true)
	}

	@Transactional
	fun archive(id: Long): ControlPointDTO {
		val point = editable(id)
		val now = OffsetDateTime.now()
		assignments.findByControlPointIdAndToTsIsNull(id)?.let { it.toTs = now; assignments.save(it) }
		point.archivedAt = now
		audit.record("CONTROL_POINT_ARCHIVED", "control_point", id)
		return toDTO(points.save(point))
	}

	@Transactional(readOnly = true)
	fun list(): List<ControlPointDTO> {
		val owners = policy.visibleOwners()
		val all = if (owners == null) points.findAllByOrderByName() else points.findAllByOwnerSubInOrderByName(owners)
		return all.map { toDTO(it) }
	}

	@Transactional(readOnly = true)
	fun detail(id: Long): ControlPointDTO = toDTO(visible(id), detailed = true)

	@Transactional
	fun computeClimate(id: Long): ControlPointDTO {
		val point = editable(id)
		if (point.spec.climate == null) throw Conflict("Il profilo di questo punto di controllo ha limiti fissi, non un clima storico")
		climate.compute(point)
		return toDTO(point, detailed = true)
	}

	fun visible(id: Long): ControlPoint {
		val point = points.findById(id).orElseThrow { NotFound("Punto di controllo $id non trovato") }
		if (!policy.canSee(point.ownerSub)) throw NotFound("Punto di controllo $id non trovato")
		return point
	}

	/** Lo modifica il proprietario (o un collega della sua azienda) o il personale del laboratorio. */
	private fun editable(id: Long): ControlPoint {
		val point = visible(id)
		if (point.archivedAt != null) throw Conflict("Il punto di controllo e' archiviato")
		return point
	}

	fun toDTO(point: ControlPoint, detailed: Boolean = false): ControlPointDTO {
		val spec = point.spec
		val history = assignments.findAllByControlPointIdOrderByFromTsDesc(point.id).map { assignmentDTO(it) }
		val state = states.findById(point.id).orElse(null)
		return ControlPointDTO(
			id = point.id,
			ownerSub = point.ownerSub,
			name = point.name,
			location = point.location,
			process = point.process,
			profile = ProfileRef(point.profileTemplateId, point.profileVersion),
			profileName = spec.name,
			quantity = spec.quantity,
			dsi = spec.dsi,
			profileLimits = mapOf("low" to spec.low, "high" to spec.high),
			overrides = point.overrides,
			limitLow = point.limitLow,
			limitHigh = point.limitHigh,
			historicClimate = spec.climate != null,
			guardZoneOpensExcursionAfter = spec.guardZoneOpensExcursionAfter?.toString(),
			createdAt = point.createdAt,
			archivedAt = point.archivedAt,
			current = history.firstOrNull { it.to == null },
			assignments = if (detailed) history else emptyList(),
			lastVerdict = state?.lastVerdict,
			lastValue = state?.lastValue,
			lastAt = state?.lastWindowEnd,
			openExcursions = excursions.countByControlPointIdAndState(point.id, ExcursionState.OPEN).toInt(),
			openNonconformities = nonconformities.countByControlPointIdAndStateNot(point.id, NonconformityState.RESOLVED).toInt(),
			climate = if (detailed && spec.climate != null) climate.status(point) else null,
		)
	}

	private fun assignmentDTO(a: ControlPointAssignment): AssignmentDTO {
		val item = itemRepository.findById(a.calibratedItemId).orElse(null)
		return AssignmentDTO(a.id, a.calibratedItemId, item?.muExtendedId, item?.sensorIndex, item?.modelName, a.metric, a.fromTs, a.toTs)
	}
}

@RestController
@RequestMapping("/API/metrology/control-points")
class ControlPointController(private val service: ControlPointService, private val excursions: ExcursionService) {
	@GetMapping fun list() = service.list()

	@GetMapping("/{id}") fun detail(@PathVariable id: Long) = service.detail(id)

	@PostMapping fun create(@RequestBody request: ControlPointRequest) = service.create(request)

	@PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: ControlPointRequest) = service.update(id, request)

	@PostMapping("/{id}/assign") fun assign(@PathVariable id: Long, @RequestBody request: AssignRequest) = service.assign(id, request)

	@PostMapping("/{id}/unassign") fun unassign(@PathVariable id: Long, @RequestBody(required = false) request: UnassignRequest?) = service.unassign(id, request ?: UnassignRequest())

	@PostMapping("/{id}/archive") fun archive(@PathVariable id: Long) = service.archive(id)

	@PostMapping("/{id}/climate") fun climate(@PathVariable id: Long) = service.computeClimate(id)

	@GetMapping("/{id}/excursions") fun excursions(@PathVariable id: Long) = excursions.forControlPoint(service.visible(id).id)
}
