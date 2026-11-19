package it.polito.measurestream.metrology.control

import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.companies.ClientCompanyMemberRepository
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.ItemStatus
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.references.ReferenceStatus
import it.polito.measurestream.metrology.security.AccessPolicy
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/* ------------------------------------------------------------------------------- avvisi */

/**
 * Posta per non conformita' e promemoria. Parte solo se Spring Mail e' configurato
 * (`spring.mail.host`): senza, l'avviso si scrive nel log e l'interfaccia resta l'unico canale
 * (i badge dello scadenzario, la coda delle non conformita').
 *
 * Destinatari: la casella del laboratorio (`metrology.notifications.lab-email`) e gli indirizzi
 * dei membri dell'azienda cliente proprietaria dell'oggetto.
 */
@Component
class Notifier(
	private val mail: ObjectProvider<JavaMailSender>,
	private val members: ClientCompanyMemberRepository,
	@Value("\${metrology.notifications.lab-email:}") private val labEmail: String,
	@Value("\${metrology.notifications.from:metrology@localhost}") private val from: String,
) {
	private val log = LoggerFactory.getLogger(Notifier::class.java)

	/** Gli indirizzi a cui scrivere per un oggetto di [ownerSub]. */
	fun recipients(ownerSub: String?): List<String> {
		val company = ownerSub?.let { members.findById(it).orElse(null)?.companyId }
		val customer = if (company != null) members.findAllByCompanyId(company).mapNotNull { it.email } else emptyList()
		return (listOfNotNull(labEmail.takeIf { it.isNotBlank() }) + customer).filter { it.isNotBlank() }.distinct()
	}

	/** Vero se la posta e' partita (o se non c'era nessuno a cui mandarla). */
	fun send(subject: String, body: String, recipients: List<String>): Boolean {
		if (recipients.isEmpty()) {
			log.info("Avviso senza destinatari: {}", subject)
			return true
		}
		val sender = mail.ifAvailable
		if (sender == null) {
			log.info("Posta non configurata, avviso solo nel log: {} -> {}", subject, recipients)
			return false
		}
		return try {
			sender.send(SimpleMailMessage().also { m ->
				m.from = from
				m.setTo(*recipients.toTypedArray())
				m.subject = subject
				m.text = body
			})
			true
		} catch (e: Exception) {
			log.warn("Invio della posta fallito ({}): {}", subject, e.message)
			false
		}
	}

	fun nonconformity(nc: Nonconformity) {
		send(
			"[Non conformita' ${nc.type.name.lowercase()}] ${nc.title}",
			"E' stata aperta la non conformita' ${nc.id} (${nc.severity}): ${nc.title}.\nRilevata il ${nc.detectedAt}.\n" +
				"Si gestisce dalla coda delle non conformita' dell'interfaccia.",
			recipients(nc.ownerSub),
		)
	}
}

/* -------------------------------------------------------------------------- scadenzario */

enum class ScheduleKind { CALIBRATION, INTERMEDIATE_CHECK }

/** Il badge di una scadenza: scaduta, oggi, entro 30 o 90 giorni, in regola. */
enum class DueLevel { OVERDUE, DUE_TODAY, WITHIN_30, WITHIN_90, OK, UNKNOWN }

@Entity
@Table(name = "schedule_item")
class ScheduleItem(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Enumerated(EnumType.STRING) @Column(name = "subject_type", nullable = false, length = 16) var subjectType: SubjectType,
	@Column(name = "subject_id", nullable = false) var subjectId: Long,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var kind: ScheduleKind,
	@Column(name = "due_at") var dueAt: OffsetDateTime? = null,
	@Column(name = "interval_days") var intervalDays: Int? = null,
	@Column(name = "last_done_at") var lastDoneAt: OffsetDateTime? = null,
	@Column(length = 200) var source: String? = null,
	@Column(name = "owner_sub", length = 64) var ownerSub: String? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(name = "notified_levels", columnDefinition = "jsonb", nullable = false) var notifiedLevels: List<Int> = emptyList(),
	@Column(name = "updated_at", nullable = false) var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ScheduleItemRepository : JpaRepository<ScheduleItem, Long> {
	fun findBySubjectTypeAndSubjectIdAndKind(subjectType: SubjectType, subjectId: Long, kind: ScheduleKind): ScheduleItem?

	fun findAllBySubjectTypeAndSubjectId(subjectType: SubjectType, subjectId: Long): List<ScheduleItem>
}

data class ScheduleEntryDTO(
	val id: Long,
	val subjectType: SubjectType,
	val subjectId: Long,
	val label: String,
	val kind: ScheduleKind,
	val dueAt: OffsetDateTime?,
	val daysLeft: Long?,
	val level: DueLevel,
	val source: String?,
	val lastDoneAt: OffsetDateTime?,
)

/** Le ultime verifiche intermedie, fornite dal modulo delle verifiche (fase 5.2). */
fun interface LastCheckSource {
	fun lastCheck(itemId: Long): OffsetDateTime?
}

/**
 * Lo scadenzario (PIANO_METROLOGIA 4.5, [S §5.7]): taratura e verifica intermedia di ogni
 * esemplare in uso, taratura di ogni campione. Si ricalcola a ogni attivazione e ogni notte.
 *
 * Da dove viene la scadenza di taratura di un esemplare: la validita' del certificato in vigore,
 * accorciata se il profilo del suo punto di controllo chiede un intervallo piu' breve. Un
 * esemplare che sorveglia un punto di controllo senza essere mai stato tarato e' scaduto dal
 * giorno dell'assegnazione; uno che non sorveglia niente e non ha certificati non ha scadenze.
 */
@Service
class ScheduleService(
	private val schedule: ScheduleItemRepository,
	private val items: CalibratedItemRepository,
	private val references: ReferenceStandardRepository,
	private val certificates: CertificateRepository,
	private val assignments: ControlPointAssignmentRepository,
	private val points: ControlPointRepository,
	private val notifier: Notifier,
	private val checks: ObjectProvider<LastCheckSource>,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	private val log = LoggerFactory.getLogger(ScheduleService::class.java)

	companion object {
		/** I promemoria: giorni prima della scadenza. */
		val LEVELS = listOf(90, 30, 0)

		fun level(daysLeft: Long?): DueLevel =
			when {
				daysLeft == null -> DueLevel.UNKNOWN
				daysLeft < 0 -> DueLevel.OVERDUE
				daysLeft == 0L -> DueLevel.DUE_TODAY
				daysLeft <= 30 -> DueLevel.WITHIN_30
				daysLeft <= 90 -> DueLevel.WITHIN_90
				else -> DueLevel.OK
			}
	}

	@Transactional
	fun recompute(subjectType: SubjectType, subjectId: Long) {
		when (subjectType) {
			SubjectType.ITEM -> items.findById(subjectId).ifPresent { recomputeItem(it) }
			SubjectType.REFERENCE -> references.findById(subjectId).ifPresent { recomputeReference(it) }
		}
	}

	@Transactional
	fun recomputeAll(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int {
		items.findAll().forEach { recomputeItem(it) }
		references.findAll().forEach { recomputeReference(it) }
		return remind(today)
	}

	/** Ogni notte alle 2:30: scadenze ricalcolate e promemoria. */
	@Scheduled(cron = "\${metrology.schedule.cron:0 30 2 * * *}")
	fun nightly() {
		val sent = recomputeAll()
		log.info("Scadenzario ricalcolato, {} promemoria", sent)
	}

	private fun recomputeItem(item: it.polito.measurestream.metrology.items.CalibratedItem) {
		if (item.status == ItemStatus.RETIRED) {
			schedule.findAllBySubjectTypeAndSubjectId(SubjectType.ITEM, item.id).forEach { schedule.delete(it) }
			return
		}
		val certificate = certificates.findAllBySubjectTypeAndSubjectIdAndStatus(SubjectType.ITEM, item.id, CertificateStatus.EFFECTIVE).firstOrNull()
		val assignment = assignments.findAllByCalibratedItemIdAndToTsIsNull(item.id).firstOrNull()
		val point = assignment?.let { points.findById(it.controlPointId).orElse(null) }
		val spec = point?.spec
		val calibratedAt = certificate?.calibrationDate?.atStartOfDay()?.atOffset(ZoneOffset.UTC)

		if (certificate == null && assignment == null) {
			schedule.findAllBySubjectTypeAndSubjectId(SubjectType.ITEM, item.id).forEach { schedule.delete(it) }
			return
		}

		// Taratura.
		val byCertificate = certificate?.validUntil
		val byProfile = calibratedAt?.let { at -> spec?.calibrationInterval?.let { at.plus(it) } }
		val byItem = calibratedAt?.let { at -> item.intervalOverrideDays?.let { at.plusDays(it.toLong()) } }
		val candidates = listOfNotNull(byCertificate, byProfile, byItem)
		val due = candidates.minOrNull() ?: assignment?.fromTs
		val source =
			when (due) {
				null -> null
				byProfile -> "profilo ${point?.profileTemplateId} del punto di controllo «${point?.name}» (${spec?.calibrationInterval})"
				byItem -> "intervallo dell'esemplare (${item.intervalOverrideDays} giorni)"
				byCertificate -> "certificato ${certificate?.number} rev. ${certificate?.revision}"
				else -> "mai tarato: sorveglia «${point?.name}» dal ${assignment?.fromTs?.toLocalDate()}"
			}
		upsert(SubjectType.ITEM, item.id, ScheduleKind.CALIBRATION, due, calibratedAt, source, item.ownerSub)

		// Verifica intermedia, se il profilo la chiede.
		val checkInterval = spec?.intermediateCheckInterval
		if (checkInterval == null) {
			schedule.findBySubjectTypeAndSubjectIdAndKind(SubjectType.ITEM, item.id, ScheduleKind.INTERMEDIATE_CHECK)?.let { schedule.delete(it) }
		} else {
			val lastCheck = checks.ifAvailable?.lastCheck(item.id)
			val base = listOfNotNull(lastCheck, calibratedAt, assignment?.fromTs).maxOrNull()
			upsert(
				SubjectType.ITEM,
				item.id,
				ScheduleKind.INTERMEDIATE_CHECK,
				base?.plus(checkInterval),
				lastCheck,
				"profilo ${point?.profileTemplateId} del punto di controllo «${point?.name}» ($checkInterval)",
				item.ownerSub,
			)
		}
	}

	private fun recomputeReference(reference: it.polito.measurestream.metrology.references.ReferenceStandard) {
		if (reference.status == ReferenceStatus.RETIRED) {
			schedule.findAllBySubjectTypeAndSubjectId(SubjectType.REFERENCE, reference.id).forEach { schedule.delete(it) }
			return
		}
		val certificate = reference.currentCertificateId?.let { certificates.findById(it).orElse(null) }
		upsert(
			SubjectType.REFERENCE,
			reference.id,
			ScheduleKind.CALIBRATION,
			certificate?.validUntil,
			certificate?.calibrationDate?.atStartOfDay()?.atOffset(ZoneOffset.UTC),
			certificate?.let { "certificato ${it.number} rev. ${it.revision}" } ?: "nessun certificato",
			null,
		)
	}

	private fun upsert(type: SubjectType, id: Long, kind: ScheduleKind, due: OffsetDateTime?, lastDone: OffsetDateTime?, source: String?, owner: String?) {
		val row = schedule.findBySubjectTypeAndSubjectIdAndKind(type, id, kind) ?: ScheduleItem(subjectType = type, subjectId = id, kind = kind)
		// Una scadenza nuova (certificato nuovo) ricomincia i promemoria da capo.
		if (row.dueAt?.toInstant() != due?.toInstant()) row.notifiedLevels = emptyList()
		row.dueAt = due
		row.lastDoneAt = lastDone
		row.source = source
		row.ownerSub = owner
		row.updatedAt = OffsetDateTime.now()
		schedule.save(row)
	}

	/**
	 * I promemoria a 90, 30 e 0 giorni, una volta sola per livello. Se il primo calcolo trova una
	 * scadenza gia' a 20 giorni parte solo l'avviso dei 30, e quello dei 90 si segna come superato.
	 */
	@Transactional
	fun remind(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int {
		var sent = 0
		for (row in schedule.findAll()) {
			val due = row.dueAt ?: continue
			val days = ChronoUnit.DAYS.between(today, due.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate())
			val crossed = LEVELS.filter { days <= it && it !in row.notifiedLevels }
			if (crossed.isEmpty()) continue
			val label = label(row)
			val what = if (row.kind == ScheduleKind.CALIBRATION) "taratura" else "verifica intermedia"
			val subject =
				when {
					days < 0 -> "[Scaduta] $what di $label"
					days == 0L -> "[Scade oggi] $what di $label"
					else -> "[Fra $days giorni] $what di $label"
				}
			notifier.send(subject, "$what di $label: scadenza ${due.toLocalDate()} (${row.source ?: "n.d."}).", notifier.recipients(row.ownerSub))
			row.notifiedLevels = (row.notifiedLevels + crossed).distinct().sortedDescending()
			schedule.save(row)
			audit.record("SCHEDULE_REMINDER", "schedule_item", row.id, detail = mapOf("days" to days, "levels" to crossed), userSub = "scheduler")
			sent++
		}
		return sent
	}

	@Transactional(readOnly = true)
	fun entries(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<ScheduleEntryDTO> {
		val caller = policy.caller()
		val owners = policy.visibleOwners(caller)
		return schedule.findAll()
			.filter { row ->
				when (row.subjectType) {
					SubjectType.REFERENCE -> owners == null
					SubjectType.ITEM -> owners == null || (row.ownerSub != null && row.ownerSub in owners)
				}
			}
			.map { row ->
				val days = row.dueAt?.let { ChronoUnit.DAYS.between(today, it.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()) }
				ScheduleEntryDTO(row.id, row.subjectType, row.subjectId, label(row), row.kind, row.dueAt, days, level(days), row.source, row.lastDoneAt)
			}
			.sortedWith(compareBy(nullsLast()) { it.daysLeft })
	}

	private fun label(row: ScheduleItem): String =
		when (row.subjectType) {
			SubjectType.ITEM -> items.findById(row.subjectId).map { "MU 0x%08X sensore %d".format(it.muExtendedId, it.sensorIndex) }.orElse("esemplare ${row.subjectId}")
			SubjectType.REFERENCE -> references.findById(row.subjectId).map { "campione ${it.name} (${it.serialNumber})" }.orElse("campione ${row.subjectId}")
		}
}

@RestController
@RequestMapping("/API/metrology/schedule")
class ScheduleController(private val service: ScheduleService) {
	@GetMapping fun entries() = service.entries()
}
