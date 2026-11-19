package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.activation.ActivationService
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Caller
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.documents.DocumentService
import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import it.polito.measurestream.metrology.references.ReferenceStandard
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRoleType
import it.polito.measurestream.metrology.signing.SigningService
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Il soggetto di un certificato, come lo indica chi lo carica. */
data class SubjectRef(
	val itemId: Long? = null,
	val muExtendedId: Long? = null,
	val sensorIndex: Int? = null,
	val referenceId: Long? = null,
)

/**
 * Il ciclo di vita dei certificati [S §5.4, §5.5]: inserimento, verifica a quattro occhi,
 * approvazione e firma, entrata in vigore, invalidazione, revisione.
 *
 * Ogni transizione controlla due cose — il ruolo valido oggi e la proprieta' dell'oggetto —
 * e lascia una riga nel registro delle azioni. L'entrata in vigore di un certificato di un
 * esemplare accoda nella stessa transazione l'attivazione per sensor-manager.
 */
@Service
class CertificateService(
	private val repository: CertificateRepository,
	private val items: CalibratedItemRepository,
	private val itemService: ItemService,
	private val references: ReferenceStandardRepository,
	private val labs: LabCompanyRepository,
	private val documents: DocumentService,
	private val activation: ActivationService,
	private val signing: SigningService,
	private val policy: AccessPolicy,
	private val audit: AuditService,
	@Value("\${metrology.certificates.default-interval-days:365}") private val defaultIntervalDays: Long,
) {
	// ------------------------------------------------------------------ lettura

	@Transactional(readOnly = true)
	fun detail(id: Long): CertificateDetailDTO = toDetail(readable(id))

	/** Il certificato, se chi chiama ne puo' vedere il soggetto. */
	@Transactional(readOnly = true)
	fun readable(id: Long, caller: Caller? = null): Certificate {
		val certificate = find(id)
		if (!canSee(certificate, caller ?: policy.caller())) throw NotFound("Certificato $id non trovato")
		return certificate
	}

	fun canSee(certificate: Certificate, caller: Caller): Boolean =
		when (certificate.subjectType) {
			SubjectType.ITEM -> items.findById(certificate.subjectId).orElse(null)?.let { policy.canSee(it.ownerSub, it.assignedLabId, caller) } ?: false
			SubjectType.REFERENCE -> policy.isStaff(caller) || caller.isEngine
		}

	@Transactional(readOnly = true)
	fun forSubject(subjectType: SubjectType, subjectId: Long): List<CertificateDetailDTO> {
		val caller = policy.caller()
		return repository.findAllBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(subjectType, subjectId)
			.filter { canSee(it, caller) }
			.map { toDetail(it, caller) }
	}

	// ------------------------------------------------------ certificati esterni

	/**
	 * Carica il PDF di un certificato esterno: nasce una bozza con il documento originale e
	 * l'impronta. Chi non e' personale del laboratorio non carica: chiede all'amministratore
	 * (o, se e' un utente di laboratorio, carica solo per gli esemplari affidati al suo).
	 */
	@Transactional
	fun createExternal(subject: SubjectRef, bytes: ByteArray?, mime: String?, filename: String?): CertificateDetailDTO {
		val caller = policy.caller()
		val (type, subjectId, owner, lab) = resolveSubject(subject)
		requireEntry(caller, type, lab)

		val original = bytes?.takeIf { it.isNotEmpty() }?.let { documents.store(it, mime, filename, "EXTERNAL_ORIGINAL", owner, caller.sub) }
		val certificate =
			repository.save(
				Certificate(
					kind = CertificateKind.EXTERNAL,
					subjectType = type,
					subjectId = subjectId,
					// Provvisorio finche' non si trascrive il numero vero: deve solo essere unico.
					number = "BOZZA-" + UUID.randomUUID().toString().take(8).uppercase(),
					status = CertificateStatus.DRAFT,
					source = CertificateSource.PDF_MANUAL,
					originalDocumentId = original?.id,
					createdBy = caller.sub,
				)
			)
		audit.record("CERTIFICATE_UPLOADED", "certificate", certificate.id, detail = mapOf("sha256" to original?.sha256, "subject" to "$type:$subjectId"))
		return toDetail(certificate, caller)
	}

	/** Trascrive i dati del certificato: dalla bozza a «da verificare». */
	@Transactional
	fun enterData(id: Long, data: CertificateDataRequest): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		if (certificate.kind != CertificateKind.EXTERNAL) throw Conflict("Solo i certificati esterni si trascrivono a mano")
		if (certificate.status !in setOf(CertificateStatus.DRAFT, CertificateStatus.ENTERED)) {
			throw Conflict("Il certificato e' ${certificate.status}: i dati non si modificano piu', si emette una revisione")
		}
		requireEntry(caller, certificate.subjectType, labOfSubject(certificate))
		validate(certificate, data)

		val before = snapshot(certificate)
		val lab = data.labCompanyId?.let { labs.findById(it).orElseThrow { BadRequest("Laboratorio $it non trovato") } }
		certificate.number = data.number.trim()
		certificate.labCompanyId = lab?.id
		certificate.issuer = data.issuer?.trim()?.ifBlank { null } ?: lab?.name
		certificate.issuerAccreditation = data.issuerAccreditation?.trim()?.ifBlank { null } ?: lab?.accreditation()
		certificate.calibrationDate = data.calibrationDate
		certificate.issueDate = data.issueDate
		certificate.validUntil = data.validUntil ?: defaultValidity(certificate, data.calibrationDate.atStartOfDay().atOffset(ZoneOffset.UTC))
		certificate.function = data.function?.let { ContractJson.plain(it.toWire()) }
		certificate.covariance = data.covariance
		certificate.calibratedMin = data.calibratedMin
		certificate.calibratedMax = data.calibratedMax
		certificate.calibratedDsi = data.calibratedDsi
		certificate.conformity = data.conformity
		certificate.conformityStatement = data.conformityStatement
		certificate.decisionRule = data.decisionRule
		certificate.ambient = data.ambient
		certificate.points.clear()
		data.points.forEach { p ->
			certificate.points.add(
				CertificatePoint(
					certificate = certificate,
					phase = p.phase,
					pointIndex = p.pointIndex,
					nominal = p.nominal,
					referenceValue = p.referenceValue,
					indicatedValue = p.indicatedValue,
					error = p.error ?: if (p.indicatedValue != null && p.referenceValue != null) p.indicatedValue - p.referenceValue else null,
					correction = p.correction,
					expandedU = p.expandedU,
					k = p.k,
					dsi = p.dsi,
				)
			)
		}
		certificate.fingerprint = fingerprint(certificate)
		certificate.status = CertificateStatus.ENTERED
		certificate.enteredBy = caller.sub
		certificate.enteredAt = OffsetDateTime.now()
		val saved = repository.saveAndFlush(certificate)
		audit.record("CERTIFICATE_ENTERED", "certificate", id, before = before, after = snapshot(saved))
		return toDetail(saved, caller)
	}

	/**
	 * La verifica a quattro occhi: un secondo utente, con il ruolo di approvatore, diverso da
	 * chi ha trascritto, confronta i dati con il PDF affiancato [S §5.5].
	 */
	@Transactional
	fun verify(id: Long): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		expect(certificate, CertificateStatus.ENTERED, "verificare")
		policy.requireRole(caller, MetrologyRoleType.APPROVER, what = "Verificare un certificato esterno")
		if (certificate.enteredBy == caller.sub) {
			throw Conflict("Chi ha trascritto i dati non puo' anche verificarli: serve un secondo utente (quattro occhi)")
		}
		certificate.status = CertificateStatus.VERIFIED
		certificate.verifiedBy = caller.sub
		certificate.verifiedAt = OffsetDateTime.now()
		audit.record("CERTIFICATE_VERIFIED", "certificate", id, after = snapshot(certificate))
		return toDetail(repository.save(certificate), caller)
	}

	/** Rimanda in trascrizione, con il motivo: un dato non corrisponde al PDF. */
	@Transactional
	fun reject(id: Long, reason: String): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		if (certificate.status !in setOf(CertificateStatus.ENTERED, CertificateStatus.DRAFT) || certificate.kind != CertificateKind.EXTERNAL) {
			throw Conflict("Si rimanda in trascrizione solo un certificato esterno non ancora verificato")
		}
		policy.requireRole(caller, MetrologyRoleType.APPROVER, what = "Rimandare un certificato in trascrizione")
		if (reason.isBlank()) throw BadRequest("Serve il motivo")
		certificate.status = CertificateStatus.DRAFT
		audit.record("CERTIFICATE_RETURNED", "certificate", id, detail = mapOf("reason" to reason))
		return toDetail(repository.save(certificate), caller)
	}

	// ------------------------------------------------------- certificati interni

	/** L'approvazione di un certificato interno: un approvatore, possibilmente non il tecnico. */
	@Transactional
	fun approve(id: Long): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		if (certificate.kind != CertificateKind.INTERNAL) throw Conflict("Si approvano i certificati interni; quelli esterni si verificano")
		expect(certificate, CertificateStatus.DRAFT, "approvare")
		policy.requireRole(caller, MetrologyRoleType.APPROVER, what = "Approvare un certificato")
		if (certificate.createdBy == caller.sub && !caller.isAdmin) {
			throw Conflict("Il tecnico che ha eseguito la taratura non approva il proprio certificato")
		}
		certificate.status = CertificateStatus.APPROVED
		certificate.approvedBy = caller.sub
		certificate.approvedAt = OffsetDateTime.now()
		audit.record("CERTIFICATE_APPROVED", "certificate", id, after = snapshot(certificate))
		return toDetail(repository.save(certificate), caller)
	}

	/** Firma XML e PDF con la chiave del laboratorio (passo 3.8). */
	@Transactional
	fun sign(id: Long): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		expect(certificate, CertificateStatus.APPROVED, "firmare")
		policy.requireRole(caller, MetrologyRoleType.APPROVER, what = "Firmare un certificato")
		if (!signing.available()) throw Conflict("La chiave di firma del laboratorio non e' configurata")
		val owner = ownerOf(certificate)
		certificate.xmlDocumentId?.let { certificate.xmlDocumentId = signing.signXml(it, owner, caller.sub).id }
		certificate.pdfDocumentId?.let { certificate.pdfDocumentId = signing.signPdf(it, owner, caller.sub).id }
		certificate.status = CertificateStatus.SIGNED
		certificate.signedAt = OffsetDateTime.now()
		audit.record("CERTIFICATE_SIGNED", "certificate", id, after = snapshot(certificate))
		return toDetail(repository.save(certificate), caller)
	}

	// ------------------------------------------------------- entrata in vigore

	/**
	 * Il certificato entra in vigore. Per un esemplare parte l'attivazione verso sensor-manager
	 * (via outbox); per un campione diventa il suo certificato corrente. Il precedente in vigore
	 * sullo stesso soggetto passa a «sostituito».
	 */
	@Transactional
	fun makeEffective(id: Long, validFrom: OffsetDateTime? = null): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		val ready = if (certificate.kind == CertificateKind.EXTERNAL) CertificateStatus.VERIFIED else CertificateStatus.SIGNED
		expect(certificate, ready, "mettere in vigore")
		policy.requireRole(caller, MetrologyRoleType.APPROVER, what = "Mettere in vigore un certificato")

		val from = validFrom ?: OffsetDateTime.now()
		repository.findAllBySubjectTypeAndSubjectIdAndStatus(certificate.subjectType, certificate.subjectId, CertificateStatus.EFFECTIVE)
			.filter { it.id != certificate.id }
			.forEach {
				it.status = CertificateStatus.SUPERSEDED
				audit.record("CERTIFICATE_SUPERSEDED", "certificate", it.id, detail = mapOf("by" to certificate.id))
			}

		when (certificate.subjectType) {
			SubjectType.ITEM -> {
				val item = items.findById(certificate.subjectId).orElseThrow { NotFound("Esemplare ${certificate.subjectId} non trovato") }
				if (certificate.function == null) {
					throw Conflict("Il certificato non porta la funzione di taratura: senza, non puo' cambiare la conversione dei campioni")
				}
				activation.activate(certificate, item, from)
			}
			SubjectType.REFERENCE -> {
				val reference = references.findById(certificate.subjectId).orElseThrow { NotFound("Campione ${certificate.subjectId} non trovato") }
				reference.currentCertificateId = certificate.id
				references.save(reference)
			}
		}

		certificate.status = CertificateStatus.EFFECTIVE
		certificate.effectiveFrom = from
		audit.record("CERTIFICATE_EFFECTIVE", "certificate", id, after = snapshot(certificate))
		return toDetail(repository.save(certificate), caller)
	}

	/** Invalida: il certificato non vale piu', e se era in vigore la taratura si revoca. */
	@Transactional
	fun invalidate(id: Long, reason: String?): CertificateDetailDTO {
		val caller = policy.caller()
		val certificate = find(id)
		if (certificate.status == CertificateStatus.INVALIDATED) return toDetail(certificate, caller)
		policy.requireRole(caller, MetrologyRoleType.APPROVER, what = "Invalidare un certificato")
		if (reason.isNullOrBlank()) throw BadRequest("Serve il motivo dell'invalidazione")

		if (certificate.status == CertificateStatus.EFFECTIVE) {
			when (certificate.subjectType) {
				SubjectType.ITEM -> items.findById(certificate.subjectId).ifPresent { activation.revoke(it, certificate) }
				SubjectType.REFERENCE ->
					references.findById(certificate.subjectId).ifPresent {
						if (it.currentCertificateId == certificate.id) {
							it.currentCertificateId = null
							references.save(it)
						}
					}
			}
		}
		val before = certificate.status
		certificate.status = CertificateStatus.INVALIDATED
		certificate.invalidatedBy = caller.sub
		certificate.invalidatedAt = OffsetDateTime.now()
		certificate.invalidationReason = reason
		audit.record("CERTIFICATE_INVALIDATED", "certificate", id, detail = mapOf("from" to before.name, "reason" to reason))
		return toDetail(repository.save(certificate), caller)
	}

	/**
	 * Una correzione e' una revisione nuova, con il motivo obbligatorio (ISO/IEC 17025 § 7.8.8).
	 * Copia i dati del precedente e riparte dalla trascrizione o dall'approvazione; il
	 * precedente resta in vigore finche' la revisione non lo sostituisce.
	 */
	@Transactional
	fun revise(id: Long, reason: String): CertificateDetailDTO {
		val caller = policy.caller()
		val previous = find(id)
		if (reason.isBlank()) throw BadRequest("Una revisione deve dire perche' esiste")
		if (!policy.isStaff(caller)) throw Forbidden("Le revisioni le emette il personale del laboratorio")
		if (previous.status !in setOf(CertificateStatus.EFFECTIVE, CertificateStatus.VERIFIED, CertificateStatus.SIGNED, CertificateStatus.SUPERSEDED_BY_CONFIG)) {
			throw Conflict("Si rivede un certificato emesso; una bozza si corregge direttamente")
		}
		val latest = repository.findAllByNumberOrderByRevisionDesc(previous.number).firstOrNull { it.kind == previous.kind && it.labCompanyId == previous.labCompanyId }
		val revision =
			Certificate(
				kind = previous.kind,
				subjectType = previous.subjectType,
				subjectId = previous.subjectId,
				number = previous.number,
				revision = (latest?.revision ?: previous.revision) + 1,
				supersedesId = previous.id,
				amendmentReason = reason,
				labCompanyId = previous.labCompanyId,
				issuer = previous.issuer,
				issuerAccreditation = previous.issuerAccreditation,
				calibrationDate = previous.calibrationDate,
				issueDate = previous.issueDate,
				validUntil = previous.validUntil,
				status = CertificateStatus.DRAFT,
				source = previous.source,
				function = previous.function,
				covariance = previous.covariance,
				calibratedMin = previous.calibratedMin,
				calibratedMax = previous.calibratedMax,
				calibratedDsi = previous.calibratedDsi,
				conformity = previous.conformity,
				conformityStatement = previous.conformityStatement,
				decisionRule = previous.decisionRule,
				ambient = previous.ambient,
				calibrationResultId = previous.calibrationResultId,
				originalDocumentId = previous.originalDocumentId,
				createdBy = caller.sub,
			)
		previous.points.forEach {
			revision.points.add(
				CertificatePoint(
					certificate = revision,
					phase = it.phase,
					pointIndex = it.pointIndex,
					nominal = it.nominal,
					referenceValue = it.referenceValue,
					indicatedValue = it.indicatedValue,
					error = it.error,
					correction = it.correction,
					expandedU = it.expandedU,
					k = it.k,
					dsi = it.dsi,
				)
			)
		}
		revision.fingerprint = fingerprint(revision)
		val saved = repository.save(revision)
		audit.record("CERTIFICATE_REVISED", "certificate", saved.id, detail = mapOf("supersedes" to previous.id, "reason" to reason))
		return toDetail(saved, caller)
	}

	// ------------------------------------------------------------------ interni

	/** Il soggetto risolto: tipo, id, proprietario e laboratorio a cui e' affidato. */
	private data class ResolvedSubject(val type: SubjectType, val id: Long, val owner: String?, val lab: Long?)

	private fun resolveSubject(subject: SubjectRef): ResolvedSubject {
		subject.referenceId?.let { id ->
			val reference = references.findById(id).orElseThrow { NotFound("Campione $id non trovato") }
			return ResolvedSubject(SubjectType.REFERENCE, reference.id, null, null)
		}
		val item =
			when {
				subject.itemId != null -> itemService.get(subject.itemId)
				subject.muExtendedId != null && subject.sensorIndex != null -> itemService.resolveOrCreate(subject.muExtendedId, subject.sensorIndex)
				else -> throw BadRequest("Indica l'esemplare (itemId, oppure EUID della MU e indice del sensore) o il campione")
			}
		return ResolvedSubject(SubjectType.ITEM, item.id, item.ownerSub, item.assignedLabId)
	}

	/** Chi puo' caricare e trascrivere: personale, o un utente del laboratorio a cui e' affidato. */
	private fun requireEntry(caller: Caller, type: SubjectType, assignedLab: Long?) {
		if (policy.has(caller, MetrologyRoleType.TECHNICIAN, MetrologyRoleType.APPROVER)) return
		if (caller.isLab && type == SubjectType.ITEM && assignedLab != null && policy.labOf(caller.sub) == assignedLab) return
		throw Forbidden(
			"Caricare un certificato esterno e' riservato al personale del laboratorio: " +
				"chiedilo all'amministratore allegando il PDF"
		)
	}

	private fun labOfSubject(certificate: Certificate): Long? =
		if (certificate.subjectType == SubjectType.ITEM) items.findById(certificate.subjectId).orElse(null)?.assignedLabId else null

	private fun ownerOf(certificate: Certificate): String? =
		if (certificate.subjectType == SubjectType.ITEM) items.findById(certificate.subjectId).orElse(null)?.ownerSub else null

	private fun validate(certificate: Certificate, data: CertificateDataRequest) {
		if (data.number.isBlank()) throw BadRequest("Il numero del certificato e' obbligatorio")
		if (data.labCompanyId == null && data.issuer.isNullOrBlank()) throw BadRequest("Indica il laboratorio che ha emesso il certificato")
		if (data.issueDate != null && data.issueDate.isBefore(data.calibrationDate)) {
			throw BadRequest("La data di emissione non puo' precedere quella di taratura")
		}
		val function = data.function
		if (certificate.subjectType == SubjectType.ITEM && function == null) {
			throw BadRequest("Per un esemplare serve la funzione di taratura: e' quella che cambiera' la conversione dei campioni")
		}
		data.covariance?.let { matrix ->
			val n = function?.c?.size ?: throw BadRequest("Una covarianza senza funzione non ha senso")
			if (matrix.size != n || matrix.any { it.size != n }) throw BadRequest("La covarianza deve essere una matrice ${n}x$n, come i coefficienti")
			for (i in 0 until n) {
				if (matrix[i][i] < 0) throw BadRequest("La varianza del coefficiente $i e' negativa")
				for (j in 0 until n) if (kotlin.math.abs(matrix[i][j] - matrix[j][i]) > 1e-12 * (kotlin.math.abs(matrix[i][j]) + 1e-300)) {
					throw BadRequest("La covarianza deve essere simmetrica")
				}
			}
		}
		if (data.calibratedMin != null && data.calibratedMax != null && data.calibratedMin >= data.calibratedMax) {
			throw BadRequest("Il campo tarato deve avere il minimo sotto il massimo")
		}
		val duplicates = data.points.groupBy { it.phase to it.pointIndex }.filterValues { it.size > 1 }.keys
		if (duplicates.isNotEmpty()) throw BadRequest("Punti ripetuti: $duplicates")
	}

	/** La validita' la decide il sistema: l'intervallo dell'esemplare, o quello predefinito. */
	private fun defaultValidity(certificate: Certificate, from: OffsetDateTime): OffsetDateTime {
		val days =
			if (certificate.subjectType == SubjectType.ITEM) items.findById(certificate.subjectId).orElse(null)?.intervalOverrideDays?.toLong()
			else null
		return from.plusDays(days ?: defaultIntervalDays)
	}

	private fun expect(certificate: Certificate, status: CertificateStatus, what: String) {
		if (certificate.status != status) throw Conflict("Per $what il certificato deve essere $status, ed e' ${certificate.status}")
	}

	private fun find(id: Long): Certificate = repository.findById(id).orElseThrow { NotFound("Certificato $id non trovato") }

	/** Impronta di configurazione [S §5.4]: funzione, template di riferimento, CRC del Block A. */
	fun fingerprint(certificate: Certificate): String? {
		val function = certificate.function ?: return null
		val item = if (certificate.subjectType == SubjectType.ITEM) items.findById(certificate.subjectId).orElse(null) else null
		val canonical =
			ContractJson.canonical(
				mapOf(
					"function" to function,
					"template" to item?.let { mapOf("templateId" to it.sensorTemplateId, "major" to it.sensorTemplateMajor) },
					"blockACrc" to null,
				)
			)
		return "sha256:" + MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
	}

	private fun snapshot(c: Certificate): Map<String, Any?> =
		mapOf(
			"number" to c.number,
			"revision" to c.revision,
			"status" to c.status.name,
			"function" to c.function,
			"covariance" to c.covariance,
			"calibratedMin" to c.calibratedMin,
			"calibratedMax" to c.calibratedMax,
			"validUntil" to c.validUntil?.toString(),
			"points" to c.points.map { it.toDTO() },
		)

	/** Le transizioni che chi guarda puo' fare adesso, e perche' non le altre. */
	fun actionsFor(c: Certificate, caller: Caller): CertificateActions {
		val allowed = mutableListOf<String>()
		val blocked = mutableMapOf<String, String>()
		val approver = policy.has(caller, MetrologyRoleType.APPROVER)
		val staff = policy.isStaff(caller)
		val lab = if (c.subjectType == SubjectType.ITEM) labOfSubject(c) else null
		val canEnter = policy.has(caller, MetrologyRoleType.TECHNICIAN, MetrologyRoleType.APPROVER) || (caller.isLab && lab != null && policy.labOf(caller.sub) == lab)

		when (c.status) {
			CertificateStatus.DRAFT ->
				if (c.kind == CertificateKind.EXTERNAL) {
					if (canEnter) allowed += "ENTER" else blocked["ENTER"] = "Riservato al personale del laboratorio"
				} else if (approver && (c.createdBy != caller.sub || caller.isAdmin)) allowed += "APPROVE"
				else blocked["APPROVE"] = if (!approver) "Serve il ruolo di approvatore" else "Il tecnico non approva il proprio certificato"
			CertificateStatus.ENTERED -> {
				if (canEnter) allowed += "ENTER"
				when {
					!approver -> blocked["VERIFY"] = "Serve il ruolo di approvatore"
					c.enteredBy == caller.sub -> blocked["VERIFY"] = "Ha trascritto i dati: serve un secondo utente"
					else -> allowed += listOf("VERIFY", "REJECT")
				}
			}
			CertificateStatus.APPROVED ->
				if (!approver) blocked["SIGN"] = "Serve il ruolo di approvatore"
				else if (!signing.available()) blocked["SIGN"] = "La chiave di firma non e' configurata"
				else allowed += "SIGN"
			CertificateStatus.VERIFIED, CertificateStatus.SIGNED ->
				if (approver) allowed += "EFFECTIVE" else blocked["EFFECTIVE"] = "Serve il ruolo di approvatore"
			else -> Unit
		}
		if (c.status != CertificateStatus.INVALIDATED) {
			if (approver) allowed += "INVALIDATE" else blocked["INVALIDATE"] = "Serve il ruolo di approvatore"
		}
		if (staff && c.status in setOf(CertificateStatus.EFFECTIVE, CertificateStatus.VERIFIED, CertificateStatus.SIGNED, CertificateStatus.SUPERSEDED_BY_CONFIG)) {
			allowed += "REVISE"
		}
		return CertificateActions(allowed, blocked)
	}

	fun toDetail(c: Certificate, callerOrNull: Caller? = null): CertificateDetailDTO {
		val caller = callerOrNull ?: policy.caller()
		val item: CalibratedItem? = if (c.subjectType == SubjectType.ITEM) items.findById(c.subjectId).orElse(null) else null
		val reference: ReferenceStandard? = if (c.subjectType == SubjectType.REFERENCE) references.findById(c.subjectId).orElse(null) else null
		return CertificateDetailDTO(
			id = c.id,
			kind = c.kind,
			subjectType = c.subjectType,
			subjectId = c.subjectId,
			subjectLabel = item?.label() ?: reference?.let { "${it.name} · ${it.serialNumber}" } ?: "#${c.subjectId}",
			muExtendedId = item?.muExtendedId,
			sensorIndex = item?.sensorIndex,
			number = c.number,
			revision = c.revision,
			supersedesId = c.supersedesId,
			amendmentReason = c.amendmentReason,
			labCompanyId = c.labCompanyId,
			issuer = c.issuer,
			issuerAccreditation = c.issuerAccreditation,
			calibrationDate = c.calibrationDate,
			issueDate = c.issueDate,
			validUntil = c.validUntil,
			status = c.status,
			source = c.source,
			function = c.function,
			covariance = c.covariance,
			calibratedMin = c.calibratedMin,
			calibratedMax = c.calibratedMax,
			calibratedDsi = c.calibratedDsi,
			conformity = c.conformity,
			conformityStatement = c.conformityStatement,
			decisionRule = c.decisionRule,
			ambient = c.ambient,
			fingerprint = c.fingerprint,
			originalDocumentId = c.originalDocumentId,
			pdfDocumentId = c.pdfDocumentId,
			xmlDocumentId = c.xmlDocumentId,
			calibrationResultId = c.calibrationResultId,
			createdBy = c.createdBy,
			createdAt = c.createdAt,
			enteredBy = c.enteredBy,
			enteredAt = c.enteredAt,
			approvedBy = c.approvedBy,
			approvedAt = c.approvedAt,
			signedAt = c.signedAt,
			verifiedBy = c.verifiedBy,
			verifiedAt = c.verifiedAt,
			effectiveFrom = c.effectiveFrom,
			invalidatedBy = c.invalidatedBy,
			invalidatedAt = c.invalidatedAt,
			invalidationReason = c.invalidationReason,
			points = c.points.map { it.toDTO() },
			actions = actionsFor(c, caller),
		)
	}
}
