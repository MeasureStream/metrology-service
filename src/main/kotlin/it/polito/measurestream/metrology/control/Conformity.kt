package it.polito.measurestream.metrology.control

import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/* ------------------------------------------------------------------------------ verdetto */

/** L'esito di un valore rispetto ai limiti, con la banda di guardia U [S §5.6, ILAC G8]. */
enum class SampleVerdict { CONFORM, GUARD_ZONE, NON_CONFORM, NOT_EVALUABLE }

enum class LimitSide { HIGH, LOW }

data class Limits(val low: Double?, val high: Double?) {
	val evaluable get() = low != null || high != null
}

data class Judgement(val verdict: SampleVerdict, val side: LimitSide? = null, val limit: Double? = null)

object Conformity {
	/**
	 * Il limite superiore H e il valore y con incertezza estesa U = k·u:
	 * conforme se y ≤ H − U, zona di guardia se H − U < y ≤ H + U, non conforme se y > H + U.
	 * Il limite inferiore a specchio. Con entrambi i limiti vince l'esito peggiore.
	 */
	fun judge(y: Double, expandedU: Double, limits: Limits): Judgement {
		if (!limits.evaluable) return Judgement(SampleVerdict.NOT_EVALUABLE)
		val u = if (expandedU.isFinite() && expandedU > 0) expandedU else 0.0
		val candidates = mutableListOf<Judgement>()
		limits.high?.let { h ->
			candidates +=
				when {
					y > h + u -> Judgement(SampleVerdict.NON_CONFORM, LimitSide.HIGH, h)
					y > h - u -> Judgement(SampleVerdict.GUARD_ZONE, LimitSide.HIGH, h)
					else -> Judgement(SampleVerdict.CONFORM)
				}
		}
		limits.low?.let { l ->
			candidates +=
				when {
					y < l - u -> Judgement(SampleVerdict.NON_CONFORM, LimitSide.LOW, l)
					y < l + u -> Judgement(SampleVerdict.GUARD_ZONE, LimitSide.LOW, l)
					else -> Judgement(SampleVerdict.CONFORM)
				}
		}
		return candidates.maxBy { it.verdict.ordinal }
	}

	/** Di quanto il valore sta oltre il limite, dalla parte che conta (zero se dentro). */
	fun excess(y: Double, side: LimitSide, limit: Double): Double =
		when (side) {
			LimitSide.HIGH -> (y - limit).coerceAtLeast(0.0)
			LimitSide.LOW -> (limit - y).coerceAtLeast(0.0)
		}
}

/* ------------------------------------------------------------------------- stato e storia */

@Entity
@Table(name = "conformity_state")
class ConformityState(
	@Id @Column(name = "control_point_id") var controlPointId: Long,
	@Column(name = "last_window_end") var lastWindowEnd: OffsetDateTime? = null,
	@Column(name = "last_value") var lastValue: Double? = null,
	@Column(name = "last_verdict", length = 16) var lastVerdict: String? = null,
	@Column(name = "guard_since") var guardSince: OffsetDateTime? = null,
	@Column(name = "updated_at", nullable = false) var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ConformityStateRepository : JpaRepository<ConformityState, Long>

@Entity
@Table(name = "control_point_climate")
class ControlPointClimate(
	@Id @Column(name = "control_point_id") var controlPointId: Long,
	@Column(name = "computed_at", nullable = false) var computedAt: OffsetDateTime,
	@Column(name = "history_from", nullable = false) var historyFrom: OffsetDateTime,
	@Column(name = "history_to", nullable = false) var historyTo: OffsetDateTime,
	@Column(name = "band_low", nullable = false) var bandLow: Double,
	@Column(name = "band_high", nullable = false) var bandHigh: Double,
	/** La media mobile del ciclo stagionale per giorno dell'anno: chiave "1".."366". */
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) var daily: Map<String, Double>,
)

interface ControlPointClimateRepository : JpaRepository<ControlPointClimate, Long>

/* --------------------------------------------------------------------------- clima storico */

/**
 * Il clima storico della EN 15757 (profilo 502).
 *
 * Il consumer accumula le medie orarie di ogni punto di controllo a clima storico. Con almeno
 * `minHistoryDays` giorni di storia si calcola:
 * - il ciclo stagionale: la media mobile centrata su `movingAverageDays` giorni delle medie
 *   giornaliere, per ogni giorno dell'anno (dell'ultimo anno disponibile);
 * - la banda delle fluttuazioni a breve termine: i percentili (7 e 93) degli scarti delle medie
 *   orarie dalla media mobile, allargata a ±`minHalfBand` se piu' stretta.
 * I valori nuovi del giorno d si confrontano con media(d) + banda. Prima di avere la storia la
 * conformita' e' NOT_EVALUABLE, e l'interfaccia dice quanti giorni mancano.
 */
@Service
class HistoricClimateService(
	private val em: EntityManager,
	private val climates: ControlPointClimateRepository,
) {
	fun accumulate(controlPointId: Long, at: OffsetDateTime, value: Double) {
		em.createNativeQuery(
			"INSERT INTO metrology.control_point_hourly (control_point_id, hour_start, value_sum, samples) VALUES (:cp, :hour, :value, 1) " +
				"ON CONFLICT (control_point_id, hour_start) DO UPDATE SET value_sum = metrology.control_point_hourly.value_sum + EXCLUDED.value_sum, " +
				"samples = metrology.control_point_hourly.samples + 1"
		)
			.setParameter("cp", controlPointId)
			.setParameter("hour", at.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS))
			.setParameter("value", value)
			.executeUpdate()
	}

	/** Le medie orarie, in ordine. */
	fun hourly(controlPointId: Long): List<Pair<OffsetDateTime, Double>> {
		@Suppress("UNCHECKED_CAST")
		val rows =
			em.createNativeQuery(
				"SELECT hour_start, value_sum / samples FROM metrology.control_point_hourly WHERE control_point_id = :cp ORDER BY hour_start"
			).setParameter("cp", controlPointId).resultList as List<Array<Any?>>
		return rows.map { r -> toOffset(r[0]) to (r[1] as Number).toDouble() }
	}

	fun status(point: ControlPoint): Map<String, Any?> {
		val spec = point.spec.climate ?: return emptyMap()
		val hours = hourly(point.id)
		val days = if (hours.size < 2) 0 else Duration.between(hours.first().first, hours.last().first).toDays()
		val computed = climates.findById(point.id).orElse(null)
		return mapOf(
			"historyDays" to days,
			"requiredDays" to spec.minHistoryDays,
			"computedAt" to computed?.computedAt,
			"bandLow" to computed?.bandLow,
			"bandHigh" to computed?.bandHigh,
			"historyFrom" to computed?.historyFrom,
			"historyTo" to computed?.historyTo,
		)
	}

	/** I limiti del giorno [at] dal clima calcolato, o nessun limite se non c'e' ancora. */
	fun limitsAt(point: ControlPoint, at: OffsetDateTime): Limits {
		val climate = climates.findById(point.id).orElse(null) ?: return Limits(null, null)
		val day = at.withOffsetSameInstant(ZoneOffset.UTC).dayOfYear
		val mean = climate.daily[day.toString()] ?: climate.daily[(day - 1).coerceAtLeast(1).toString()] ?: return Limits(null, null)
		return Limits(mean + climate.bandLow, mean + climate.bandHigh)
	}

	@Transactional
	fun compute(point: ControlPoint): ControlPointClimate {
		val spec = point.spec.climate ?: throw IllegalStateException("profilo senza clima storico")
		val result = computeFrom(hourly(point.id), spec) ?: throw it.polito.measurestream.metrology.common.Conflict(
			"Storia insufficiente per il clima storico: servono ${spec.minHistoryDays} giorni di dati"
		)
		val saved =
			climates.save(
				ControlPointClimate(
					controlPointId = point.id,
					computedAt = OffsetDateTime.now(),
					historyFrom = result.from,
					historyTo = result.to,
					bandLow = result.bandLow,
					bandHigh = result.bandHigh,
					daily = result.daily,
				)
			)
		return saved
	}

	data class ClimateResult(val from: OffsetDateTime, val to: OffsetDateTime, val bandLow: Double, val bandHigh: Double, val daily: Map<String, Double>)

	companion object {
		/** Il calcolo puro, provato senza database. */
		fun computeFrom(hours: List<Pair<OffsetDateTime, Double>>, spec: ClimateSpec): ClimateResult? {
			if (hours.size < 2) return null
			val from = hours.first().first
			val to = hours.last().first
			if (Duration.between(from, to).toDays() < spec.minHistoryDays) return null

			val byDay = hours.groupBy { it.first.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate() }.mapValues { (_, v) -> v.map { it.second }.average() }
			val days = byDay.keys.sorted()
			val half = spec.movingAverageDays / 2
			// Media mobile centrata: la finestra [d - half, d + half - 1] deve essere coperta per
			// almeno l'80 % dei giorni, o il valore non si calcola.
			val movingAverage =
				days.mapNotNull { d ->
					val window = (-half until spec.movingAverageDays - half).mapNotNull { byDay[d.plusDays(it.toLong())] }
					if (window.size >= 0.8 * spec.movingAverageDays) d to window.average() else null
				}.toMap()
			if (movingAverage.isEmpty()) return null

			val deviations =
				hours.mapNotNull { (t, v) -> movingAverage[t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()]?.let { v - it } }.sorted()
			if (deviations.size < 10) return null
			val bandLow = minOf(percentile(deviations, spec.lowPercentile), -spec.minHalfBand)
			val bandHigh = maxOf(percentile(deviations, spec.highPercentile), spec.minHalfBand)

			// Il ciclo stagionale per giorno dell'anno, dall'anno piu' recente della storia.
			val lastYear = movingAverage.keys.sorted().takeLastWhile { !it.isBefore(to.toLocalDate().minusDays(365)) }
			val daily = lastYear.associate { it.dayOfYear.toString() to movingAverage.getValue(it) }
			return ClimateResult(from, to, bandLow, bandHigh, daily)
		}

		/** Percentile con interpolazione lineare fra le posizioni (metodo 7 di Hyndman e Fan). */
		fun percentile(sorted: List<Double>, p: Double): Double {
			if (sorted.isEmpty()) return Double.NaN
			val h = (sorted.size - 1) * p / 100.0
			val lo = floor(h).toInt()
			val hi = ceil(h).toInt()
			return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
		}

		private fun toOffset(value: Any?): OffsetDateTime =
			when (value) {
				is OffsetDateTime -> value
				is java.time.Instant -> value.atOffset(ZoneOffset.UTC)
				is java.sql.Timestamp -> value.toInstant().atOffset(ZoneOffset.UTC)
				is java.time.LocalDateTime -> value.atOffset(ZoneOffset.UTC)
				else -> OffsetDateTime.parse(value.toString())
			}
	}
}

/* --------------------------------------------------------------------- consumer e servizio */

/** Un campione di `sample-converted`, come lo scrive ReportQualityService di sensor-manager. */
data class ConvertedSample(
	val muExtendedId: Long?,
	val sensorIndex: Int?,
	val metric: String,
	val value: Double?,
	val converted: Boolean?,
	val uStd: Double?,
	val qualityFlags: Int?,
	val certificateId: Long?,
)

data class SampleConvertedEvent(
	val frameId: Long?,
	val windowStart: OffsetDateTime?,
	val windowEnd: OffsetDateTime,
	val samples: List<ConvertedSample>,
)

/**
 * La conformita' dei punti di controllo, valore per valore (PIANO_METROLOGIA 4.3).
 *
 * Per ogni campione convertito di un esemplare assegnato a un punto di controllo: limiti dal
 * profilo (ristretti dalle eccezioni) o dal clima storico, U = k·u con k = 2, esito con la
 * banda di guardia, e lo stato delle escursioni. Il consumer e' idempotente: un campione non
 * piu' recente dell'ultimo visto per quel punto di controllo si ignora, cosi' un evento ripetuto
 * (o la ripartenza del consumer) non riapre un'escursione. Un valore non convertito (`converted`
 * falso) non si confronta con limiti fisici.
 */
@Service
class ConformityService(
	private val items: CalibratedItemRepository,
	private val assignments: ControlPointAssignmentRepository,
	private val points: ControlPointRepository,
	private val states: ConformityStateRepository,
	private val climate: HistoricClimateService,
	private val excursions: ExcursionService,
) {
	private val log = LoggerFactory.getLogger(ConformityService::class.java)

	companion object {
		/** Fattore di copertura della banda di guardia. */
		const val K = 2.0
	}

	fun parse(message: String): SampleConvertedEvent {
		@Suppress("UNCHECKED_CAST")
		val root = ContractJson.mapper.readValue(message, Map::class.java) as Map<String, Any?>
		fun time(name: String) = (root[name] as? String)?.let { OffsetDateTime.parse(it) }
		@Suppress("UNCHECKED_CAST")
		val samples = (root["samples"] as? List<Map<String, Any?>>).orEmpty()
		return SampleConvertedEvent(
			frameId = (root["frameId"] as? Number)?.toLong(),
			windowStart = time("windowStart"),
			windowEnd = time("windowEnd") ?: throw IllegalArgumentException("windowEnd mancante"),
			samples =
				samples.map { s ->
					ConvertedSample(
						muExtendedId = (s["muExtendedId"] as? Number)?.toLong(),
						sensorIndex = (s["sensorIndex"] as? Number)?.toInt(),
						metric = s["metric"] as? String ?: "mean",
						value = (s["value"] as? Number)?.toDouble(),
						converted = s["converted"] as? Boolean,
						uStd = (s["uStd"] as? Number)?.toDouble(),
						qualityFlags = (s["qualityFlags"] as? Number)?.toInt(),
						certificateId = (s["certificateId"] as? Number)?.toLong(),
					)
				},
		)
	}

	@Transactional
	fun process(event: SampleConvertedEvent): List<Pair<Long, Judgement>> {
		val out = mutableListOf<Pair<Long, Judgement>>()
		for (sample in event.samples) {
			val value = sample.value ?: continue
			if (sample.converted != true) continue
			val mu = sample.muExtendedId ?: continue
			val index = sample.sensorIndex ?: continue
			val item = items.findByMuExtendedIdAndSensorIndex(mu, index) ?: continue
			val assignment = assignments.findCovering(item.id, sample.metric, event.windowEnd) ?: continue
			val point = points.findById(assignment.controlPointId).orElse(null) ?: continue
			if (point.archivedAt != null) continue

			val state = states.findById(point.id).orElse(null) ?: ConformityState(controlPointId = point.id)
			val previous = state.lastWindowEnd
			if (previous != null && !event.windowEnd.isAfter(previous)) continue

			val spec = point.spec
			if (spec.climate != null) climate.accumulate(point.id, event.windowEnd, value)
			val limits = if (spec.climate != null) climate.limitsAt(point, event.windowEnd) else Limits(point.limitLow, point.limitHigh)
			val judgement = Conformity.judge(value, K * (sample.uStd ?: 0.0), limits)
			val start = event.windowStart ?: previous ?: event.windowEnd
			excursions.track(point, state, judgement, value, start, event.windowEnd)

			state.lastWindowEnd = event.windowEnd
			state.lastValue = value
			state.lastVerdict = judgement.verdict.name
			state.updatedAt = OffsetDateTime.now()
			states.save(state)
			out += point.id to judgement
		}
		return out
	}

	fun consume(message: String) {
		val event =
			try {
				parse(message)
			} catch (e: Exception) {
				log.warn("sample-converted non leggibile: {}", e.message)
				return
			}
		process(event)
	}
}

@Component
class SampleConvertedListener(private val service: ConformityService) {
	@KafkaListener(topics = ["sample-converted"], groupId = "metrology-service-conformity")
	fun consume(message: String) = service.consume(message)
}
