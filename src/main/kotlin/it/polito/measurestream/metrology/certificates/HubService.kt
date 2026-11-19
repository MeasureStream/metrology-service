package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.common.Caller
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.companies.ClientCompanyMemberRepository
import it.polito.measurestream.metrology.companies.ClientCompanyRepository
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.RemoteMeasurementUnit
import it.polito.measurestream.metrology.items.SensorManagerClient
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleRepository
import java.time.OffsetDateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Una riga dello storico dell'Hub: il contratto di `hubTypes.ts`, piu' i campi del ciclo di vita. */
data class HubCertificateDTO(
	val id: Long,
	val sensorLabel: String,
	val channel: Int,
	val sensorType: String,
	val muLabel: String,
	val cuLabel: String,
	val revision: Int,
	val clientId: Long?,
	val clientName: String,
	val operator: String,
	val labCode: String,
	val reference: String,
	val validFrom: String?,
	val validTo: String?,
	val conformity: String,
	val status: String,
	val number: String,
	val kind: CertificateKind,
	val source: CertificateSource,
	val subjectType: SubjectType,
	val subjectId: Long,
	val muExtendedId: Long?,
	val sensorIndex: Int?,
	val rawStatus: CertificateStatus,
	val originalDocumentId: Long?,
	val pdfDocumentId: Long?,
	val xmlDocumentId: Long?,
)

data class HubClientDTO(val id: Long, val name: String, val sensors: Int)

/**
 * La sezione Metrologia vista dall'alto: lo storico dei certificati e l'intestazione con
 * copertura, scadenze ed eccezioni, per il perimetro di chi guarda (PIANO_METROLOGIA 1.9, 3.9).
 *
 * Il perimetro e' l'azienda cliente (decisione D-C). I sensori che non sono ancora esemplari
 * si contano lo stesso, chiedendo le MU a sensor-manager: un sensore mai tarato e' proprio
 * cio' che l'intestazione deve far vedere.
 */
@Service
class HubService(
	private val certificates: CertificateRepository,
	private val items: CalibratedItemRepository,
	private val references: ReferenceStandardRepository,
	private val companies: ClientCompanyRepository,
	private val members: ClientCompanyMemberRepository,
	private val labs: LabCompanyRepository,
	private val roles: MetrologyRoleRepository,
	private val sensorManager: SensorManagerClient,
	private val certificateService: CertificateService,
	private val policy: AccessPolicy,
	private val progress: HubProgressSource,
	@Value("\${metrology.certificates.expiring-horizon-days:90}") private val horizonDays: Long,
) {
	/** Gli utenti nel perimetro: un'azienda, i non assegnati (0), o tutto cio' che si vede (null). */
	private fun ownersFor(caller: Caller, clientId: Long?): Set<String>? {
		val visible = policy.visibleOwners(caller)
		val chosen =
			when {
				visible != null -> return visible // un cliente vede il suo perimetro, qualunque cosa chieda
				clientId == null -> return null
				clientId == UNASSIGNED -> null
				else -> members.findAllByCompanyId(clientId).map { it.userSub }.toSet()
			}
		return chosen ?: unassignedOwners()
	}

	private fun unassignedOwners(): Set<String> {
		val assigned = members.findAll().map { it.userSub }.toSet()
		return items.findAll().mapNotNull { it.ownerSub }.filter { it !in assigned }.toSet() +
			sensorManager.measurementUnits().mapNotNull { it.ownerSub }.filter { it !in assigned }
	}

	@Transactional(readOnly = true)
	fun certificates(clientId: Long?): List<HubCertificateDTO> {
		val caller = policy.caller()
		val owners = ownersFor(caller, clientId)
		val units = sensorManager.measurementUnits().associateBy { it.extendedId }
		val names = names()
		val companyOf = members.findAll().associate { it.userSub to it.companyId }
		val companyNames = companies.findAll().associate { it.id to it.name }

		val inScope = items.findAll().filter { owners == null || it.ownerSub in owners }.filter { policy.canSee(it.ownerSub, it.assignedLabId, caller) }
		val byItem = inScope.associateBy { it.id }
		val itemCertificates = certificates.findAllBySubjectTypeAndSubjectIdIn(SubjectType.ITEM, byItem.keys)
		val referenceCertificates =
			if (policy.isStaff(caller) && clientId == null) certificates.findAllBySubjectTypeAndSubjectIdIn(SubjectType.REFERENCE, references.findAll().map { it.id })
			else emptyList()

		return (itemCertificates + referenceCertificates)
			.sortedByDescending { it.createdAt }
			.map { c ->
				val item = if (c.subjectType == SubjectType.ITEM) byItem[c.subjectId] else null
				val reference = if (c.subjectType == SubjectType.REFERENCE) references.findById(c.subjectId).orElse(null) else null
				val sensor = item?.let { units[it.muExtendedId]?.sensors?.firstOrNull { s -> s.sensorIndex == it.sensorIndex } }
				val company = item?.ownerSub?.let { companyOf[it] }
				HubCertificateDTO(
					id = c.id,
					sensorLabel = item?.modelName ?: reference?.name ?: "—",
					channel = item?.sensorIndex ?: 0,
					sensorType = sensor?.template?.type ?: if (reference != null) "reference" else item?.modelName ?: "—",
					muLabel = item?.let { "0x%08X".format(it.muExtendedId) } ?: reference?.serialNumber ?: "—",
					cuLabel = item?.let { units[it.muExtendedId]?.controlUnitId }?.let { "CU #$it" } ?: "—",
					revision = c.revision,
					clientId = company,
					clientName = company?.let { companyNames[it] } ?: "—",
					operator = names[c.enteredBy ?: c.createdBy] ?: "—",
					labCode = c.issuerAccreditation ?: c.labCompanyId?.let { labs.findById(it).orElse(null)?.accreditation() } ?: c.issuer ?: "—",
					reference = progress.referenceLabel(c) ?: c.issuer ?: "—",
					validFrom = (c.effectiveFrom ?: c.calibrationDate?.atStartOfDay()?.atOffset(java.time.ZoneOffset.UTC))?.toString(),
					validTo = c.validUntil?.toString(),
					conformity = if (c.conformity == Conformity.NON_CONFORM) "nonConform" else "conform",
					status = hubStatus(c),
					number = c.number,
					kind = c.kind,
					source = c.source,
					subjectType = c.subjectType,
					subjectId = c.subjectId,
					muExtendedId = item?.muExtendedId,
					sensorIndex = item?.sensorIndex,
					rawStatus = c.status,
					originalDocumentId = c.originalDocumentId,
					pdfDocumentId = c.pdfDocumentId,
					xmlDocumentId = c.xmlDocumentId,
				)
			}
	}

	/** Lo stato come lo conosce l'interfaccia: «in vigore» si spezza in vigore, in scadenza, scaduto. */
	fun hubStatus(c: Certificate, now: OffsetDateTime = OffsetDateTime.now()): String =
		when (c.status) {
			CertificateStatus.DRAFT, CertificateStatus.ENTERED -> "draft"
			CertificateStatus.APPROVED -> "approved"
			CertificateStatus.SIGNED -> "signed"
			CertificateStatus.VERIFIED -> "verified"
			CertificateStatus.EFFECTIVE ->
				when {
					c.validUntil != null && !now.isBefore(c.validUntil) -> "expired"
					c.validUntil != null && now.plusDays(horizonDays).isAfter(c.validUntil) -> "expiring"
					else -> "effective"
				}
			CertificateStatus.SUPERSEDED -> "superseded"
			CertificateStatus.SUPERSEDED_BY_CONFIG -> "supersededByConfig"
			CertificateStatus.INVALIDATED -> "invalidated"
		}

	@Transactional(readOnly = true)
	fun clients(): List<HubClientDTO> {
		policy.requireAdmin()
		val units = sensorManager.measurementUnits()
		val sensorsByOwner = units.groupBy { it.ownerSub }.mapValues { (_, mus) -> mus.sumOf { it.sensors.size } }
		val membersByCompany = members.findAll().groupBy { it.companyId }
		val assigned = membersByCompany.values.flatten().map { it.userSub }.toSet()
		val list =
			companies.findAllByArchivedAtIsNullOrderByName().map { company ->
				HubClientDTO(company.id, company.name, membersByCompany[company.id].orEmpty().sumOf { sensorsByOwner[it.userSub] ?: 0 })
			}
		val unassigned = sensorsByOwner.filterKeys { it != null && it !in assigned }.values.sum()
		return if (unassigned > 0) list + HubClientDTO(UNASSIGNED, "", unassigned) else list
	}

	/** L'intestazione della sezione: il contratto `HubSummaryDTO` di `hubTypes.ts`. */
	@Transactional(readOnly = true)
	fun summary(clientId: Long?): Map<String, Any?> {
		val caller = policy.caller()
		val now = OffsetDateTime.now()
		val owners = ownersFor(caller, clientId)
		val units = sensorManager.measurementUnits().filter { owners == null || it.ownerSub in owners }
		val itemsInScope = items.findAll().filter { owners == null || it.ownerSub in owners }.filter { policy.canSee(it.ownerSub, it.assignedLabId, caller) }
		val itemByKey = itemsInScope.associateBy { it.muExtendedId to it.sensorIndex }
		val certs = certificates.findAllBySubjectTypeAndSubjectIdIn(SubjectType.ITEM, itemsInScope.map { it.id }).groupBy { it.subjectId }

		// Ogni sensore nel perimetro: quelli che sensor-manager conosce, piu' gli esemplari di
		// MU che non gli risultano (staccate, o sensor-manager irraggiungibile).
		data class Slot(val key: Pair<Long, Int>, val label: String, val item: CalibratedItem?)
		val slots =
			(units.flatMap { mu -> mu.sensors.map { s -> Slot(mu.extendedId to s.sensorIndex, "0x%08X · %s".format(mu.extendedId, s.modelName), itemByKey[mu.extendedId to s.sensorIndex]) } } +
				itemsInScope.map { Slot(it.muExtendedId to it.sensorIndex, it.label(), it) })
				.distinctBy { it.key }
				.filter { it.item?.requiresCalibration != false }

		var covered = 0
		var expiring = 0
		var expired = 0
		var issuing = 0
		var nonConform = 0
		var never = 0
		var next: Pair<OffsetDateTime, Slot>? = null
		for (slot in slots) {
			val list = slot.item?.let { certs[it.id] }.orEmpty()
			val effective = list.firstOrNull { it.status == CertificateStatus.EFFECTIVE }
			if (list.isEmpty()) never++
			if (list.any { it.status in setOf(CertificateStatus.DRAFT, CertificateStatus.ENTERED, CertificateStatus.APPROVED, CertificateStatus.SIGNED, CertificateStatus.VERIFIED) }) issuing++
			if (effective == null) continue
			val until = effective.validUntil
			when {
				until != null && !now.isBefore(until) -> expired++
				else -> {
					covered++
					if (until != null && now.plusDays(horizonDays).isAfter(until)) expiring++
					if (until != null && (next == null || until.isBefore(next.first))) next = until to slot
				}
			}
			if (effective.conformity == Conformity.NON_CONFORM) nonConform++
		}
		val inScope = slots.size
		val uncovered = inScope - covered
		val percent = if (inScope == 0) 0 else Math.round(covered * 100.0 / inScope).toInt()
		val isAdmin = caller.isAdmin
		val companyId = clientId ?: policy.companyOf(caller.sub)
		val clientName = companyId?.takeIf { it != UNASSIGNED }?.let { companies.findById(it).orElse(null)?.name } ?: ""
		val (inCalibration, oldestSince) = progress.inCalibration(itemsInScope.map { it.id })

		val referenceCertificates = references.findAll().mapNotNull { r -> r.currentCertificateId?.let { certificates.findById(it).orElse(null) } }
		val internalLab = labs.findFirstByInternalTrue()

		return mapOf(
			"scope" to mapOf("role" to if (isAdmin) "app-admin" else "app-user", "clientId" to companyId, "clientName" to clientName, "canSelectClient" to isAdmin),
			"coverage" to mapOf("sensorsInScope" to inScope, "covered" to covered, "expiring" to expiring, "issuing" to issuing, "uncovered" to uncovered, "percent" to percent),
			"sensors" to mapOf("conform" to (covered - nonConform).coerceAtLeast(0), "nonConform" to nonConform, "toCalibrate" to uncovered + expiring),
			"expiry" to
				mapOf(
					"horizonDays" to horizonDays,
					"expiringCount" to expiring,
					"expiredCount" to expired,
					"nextExpiration" to next?.first?.toString(),
					"nextExpirationSensorId" to next?.second?.item?.id,
					"nextExpirationSensorLabel" to next?.second?.label,
				),
			"exceptions" to mapOf("nonConform" to nonConform, "neverCalibrated" to never, "signatureInvalid" to 0),
			"progress" to mapOf("sensorsInCalibration" to inCalibration, "oldestSince" to oldestSince?.toString()),
			"queue" to
				if (isAdmin)
					mapOf(
						"inProgress" to progress.openSessions(),
						"queued" to progress.queuedJobs(),
						"samplesInField" to 0,
						"samplesExpiring" to referenceCertificates.count { it.validUntil != null && now.plusDays(horizonDays).isAfter(it.validUntil) },
						"samplesExpiringDays" to horizonDays,
					)
				else null,
			"lab" to
				if (isAdmin && internalLab != null)
					mapOf(
						"name" to internalLab.name,
						"legalName" to internalLab.name,
						"accreditation" to (internalLab.accreditation() ?: ""),
						"accreditationValidUntil" to internalLab.accreditationValidUntil?.toString(),
					)
				else null,
			"verdict" to if (expired > 0 || nonConform > 0 || uncovered > 0) "ACTION_REQUIRED" else "CONFORM",
			"computedAt" to now.toString(),
		)
	}

	/** Il documento da scaricare per un formato del contratto dell'Hub. */
	@Transactional(readOnly = true)
	fun documentFor(id: Long, format: String): Long {
		val certificate = certificateService.readable(id)
		return when (format) {
			"dccXml" -> certificate.xmlDocumentId
			"dccPdf", "reportPdf" -> certificate.pdfDocumentId ?: certificate.originalDocumentId
			"original" -> certificate.originalDocumentId
			else -> null
		} ?: throw NotFound("Il certificato $id non ha un documento nel formato $format")
	}

	/** Nomi leggibili per i `sub`, da chi li conosce: ruoli metrologici e aziende. */
	private fun names(): Map<String, String> =
		members.findAll().mapNotNull { m -> m.displayName?.let { m.userSub to it } }.toMap() +
			roles.findAll().mapNotNull { r -> r.displayName?.let { r.userSub to it } }.toMap()

	companion object {
		/** Il pseudo-cliente «non assegnati»: utenti che non appartengono a nessuna azienda. */
		const val UNASSIGNED = 0L
	}
}

/**
 * Cio' che l'Hub sa delle sessioni e dei job senza dipendere dal loro modulo: lo implementa il
 * modulo delle sessioni (fase 3).
 */
interface HubProgressSource {
	/** Quanti esemplari sono in una sessione aperta, e da quando la piu' vecchia. */
	fun inCalibration(itemIds: Collection<Long>): Pair<Int, OffsetDateTime?>

	fun openSessions(): Int

	fun queuedJobs(): Int

	/** Il campione usato per un certificato interno, come lo legge una persona. */
	fun referenceLabel(certificate: Certificate): String?
}
