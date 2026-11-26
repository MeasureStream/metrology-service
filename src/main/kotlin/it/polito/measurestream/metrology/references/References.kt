package it.polito.measurestream.metrology.references

import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.control.IntermediateCheckRepository
import it.polito.measurestream.metrology.control.ScheduleItemRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.sessions.CalibrationSessionRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

enum class ReferenceStatus { IN_SERVICE, IN_CALIBRATION, RETIRED }

/**
 * Un campione di riferimento: un esemplare a sua volta, con un certificato che ne dice le
 * correzioni e l'incertezza (M2). Non piu' un file JSON nella cartella del motore.
 */
@Entity
@Table(name = "reference_standard")
class ReferenceStandard(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(nullable = false, length = 120) var name: String,
	@Column(length = 120) var manufacturer: String? = null,
	@Column(length = 120) var model: String? = null,
	@Column(name = "serial_number", nullable = false, length = 80, unique = true) var serialNumber: String,
	@Column(name = "model_template_id") var modelTemplateId: Int? = null,
	@Column(name = "model_template_major") var modelTemplateMajor: Int? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: ReferenceStatus = ReferenceStatus.IN_SERVICE,
	@Column(length = 200) var location: String? = null,
	@Column(name = "current_certificate_id") var currentCertificateId: Long? = null,
	@Column(columnDefinition = "text") var notes: String? = null,
	@Column(name = "created_by", nullable = false, length = 64) var createdBy: String,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ReferenceStandardRepository : JpaRepository<ReferenceStandard, Long> {
	fun findBySerialNumber(serialNumber: String): ReferenceStandard?

	fun findAllByOrderByName(): List<ReferenceStandard>
}

data class ReferenceRequest(
	val name: String,
	val serialNumber: String,
	val manufacturer: String? = null,
	val model: String? = null,
	val modelTemplateId: Int? = null,
	val modelTemplateMajor: Int? = null,
	val status: ReferenceStatus? = null,
	val location: String? = null,
	val notes: String? = null,
)

/**
 * Un campione con lo stato che conta per chi lo deve usare: utilizzabile oggi o no, e perche'.
 * La pagina «Campioni di riferimento» e l'apertura di una sessione leggono la stessa regola.
 */
data class ReferenceDTO(
	val id: Long,
	val name: String,
	val manufacturer: String?,
	val model: String?,
	val serialNumber: String,
	val modelTemplateId: Int?,
	val modelTemplateMajor: Int?,
	val status: ReferenceStatus,
	val location: String?,
	val notes: String?,
	val currentCertificateId: Long?,
	val currentCertificateNumber: String?,
	val certificateIssuer: String?,
	val validUntil: OffsetDateTime?,
	val usable: Boolean,
	/** Perche' non e' utilizzabile, in chiaro; null se lo e'. */
	val unusableReason: String?,
)

@Service
class ReferenceService(
	private val repository: ReferenceStandardRepository,
	private val certificates: CertificateRepository,
	private val sessions: CalibrationSessionRepository,
	private val checks: IntermediateCheckRepository,
	private val schedule: ScheduleItemRepository,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	/**
	 * Utilizzabile alla data: in servizio, con un certificato in vigore e non scaduto. E' la
	 * condizione per aprire una sessione: un campione scaduto non da' riferibilita'.
	 */
	fun usability(reference: ReferenceStandard, certificate: Certificate?, at: OffsetDateTime = OffsetDateTime.now()): String? =
		when {
			reference.status != ReferenceStatus.IN_SERVICE -> "Il campione non e' in servizio (${reference.status})"
			certificate == null -> "Il campione non ha un certificato in vigore"
			certificate.status != CertificateStatus.EFFECTIVE -> "Il certificato del campione non e' in vigore (${certificate.status})"
			certificate.validUntil != null && !at.isBefore(certificate.validUntil) -> "Il certificato del campione e' scaduto il ${certificate.validUntil!!.toLocalDate()}"
			else -> null
		}

	fun toDTO(reference: ReferenceStandard): ReferenceDTO {
		val certificate = reference.currentCertificateId?.let { certificates.findById(it).orElse(null) }
		val reason = usability(reference, certificate)
		return ReferenceDTO(
			id = reference.id,
			name = reference.name,
			manufacturer = reference.manufacturer,
			model = reference.model,
			serialNumber = reference.serialNumber,
			modelTemplateId = reference.modelTemplateId,
			modelTemplateMajor = reference.modelTemplateMajor,
			status = reference.status,
			location = reference.location,
			notes = reference.notes,
			currentCertificateId = certificate?.id,
			currentCertificateNumber = certificate?.number,
			certificateIssuer = certificate?.issuer,
			validUntil = certificate?.validUntil,
			usable = reason == null,
			unusableReason = reason,
		)
	}

	@Transactional(readOnly = true)
	fun all(): List<ReferenceDTO> {
		requireStaff()
		return repository.findAllByOrderByName().map { toDTO(it) }
	}

	@Transactional(readOnly = true)
	fun get(id: Long): ReferenceStandard = repository.findById(id).orElseThrow { NotFound("Campione $id non trovato") }

	fun bySerial(serialNumber: String): ReferenceStandard =
		repository.findBySerialNumber(serialNumber.trim()) ?: throw NotFound("Nessun campione con numero di serie $serialNumber")

	@Transactional(readOnly = true)
	fun detail(id: Long): ReferenceDTO {
		requireStaff()
		return toDTO(get(id))
	}

	@Transactional
	fun create(request: ReferenceRequest): ReferenceDTO {
		val caller = requireStaff()
		if (request.name.isBlank() || request.serialNumber.isBlank()) throw BadRequest("Nome e numero di serie sono obbligatori")
		if (repository.findBySerialNumber(request.serialNumber.trim()) != null) {
			throw BadRequest("Esiste gia' un campione con numero di serie ${request.serialNumber}")
		}
		val reference =
			repository.save(
				ReferenceStandard(
					name = request.name.trim(),
					serialNumber = request.serialNumber.trim(),
					manufacturer = request.manufacturer,
					model = request.model,
					modelTemplateId = request.modelTemplateId,
					modelTemplateMajor = request.modelTemplateMajor,
					status = request.status ?: ReferenceStatus.IN_SERVICE,
					location = request.location,
					notes = request.notes,
					createdBy = caller.sub,
				)
			)
		audit.record("REFERENCE_CREATED", "reference_standard", reference.id, after = request)
		return toDTO(reference)
	}

	@Transactional
	fun update(id: Long, request: ReferenceRequest): ReferenceDTO {
		requireStaff()
		val reference = get(id)
		val before = toDTO(reference)
		// Il numero di serie identifica l'oggetto fisico a cui rimandano i certificati: si
		// corregge solo in rodaggio, e solo dall'amministratore.
		val serial = request.serialNumber.trim()
		if (serial.isNotEmpty() && serial != reference.serialNumber) {
			policy.requireRunInAdmin("Cambiare il numero di serie di un campione")
			if (repository.findBySerialNumber(serial) != null) throw BadRequest("Esiste gia' un campione con numero di serie $serial")
			reference.serialNumber = serial
		}
		reference.name = request.name.ifBlank { reference.name }
		reference.manufacturer = request.manufacturer
		reference.model = request.model
		reference.modelTemplateId = request.modelTemplateId
		reference.modelTemplateMajor = request.modelTemplateMajor
		request.status?.let { reference.status = it }
		reference.location = request.location
		reference.notes = request.notes
		audit.record("REFERENCE_UPDATED", "reference_standard", id, before = before, after = request)
		return toDTO(repository.save(reference))
	}

	/**
	 * Un campione creato per errore o per prova si cancella, se non ha lasciato tracce: nessun
	 * certificato, nessuna sessione, nessuna verifica intermedia. Uno usato si ritira (RETIRED),
	 * perche' chi e' stato tarato con lui deve poterlo ritrovare.
	 */
	@Transactional
	fun delete(id: Long) {
		policy.requireAdmin()
		val reference = get(id)
		val serial = reference.serialNumber
		if (certificates.findAllBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(SubjectType.REFERENCE, id).isNotEmpty()) {
			throw Conflict("Il campione $serial ha dei certificati: non si cancella, si ritira")
		}
		if (sessions.existsByReferenceStandardId(id)) throw Conflict("Il campione $serial e' stato usato in una sessione di taratura: non si cancella, si ritira")
		if (checks.existsByReferenceStandardId(id)) throw Conflict("Il campione $serial e' stato usato in una verifica intermedia: non si cancella, si ritira")
		schedule.deleteAll(schedule.findAllBySubjectTypeAndSubjectId(SubjectType.REFERENCE, id))
		audit.record("REFERENCE_DELETED", "reference_standard", id, before = toDTO(reference))
		repository.delete(reference)
	}

	private fun requireStaff() =
		policy.caller().also {
			if (!policy.isStaff(it) && !it.isEngine) throw Forbidden("I campioni li gestisce il personale del laboratorio")
		}
}

@RestController
@RequestMapping("/API/metrology/references")
class ReferenceController(private val service: ReferenceService) {
	@GetMapping
	fun all(@RequestParam(required = false) usableOnly: Boolean?): List<ReferenceDTO> =
		service.all().filter { usableOnly != true || it.usable }

	@GetMapping("/{id}")
	fun one(@PathVariable id: Long): ReferenceDTO = service.detail(id)

	@PostMapping
	fun create(@RequestBody request: ReferenceRequest): ReferenceDTO = service.create(request)

	@PutMapping("/{id}")
	fun update(@PathVariable id: Long, @RequestBody request: ReferenceRequest): ReferenceDTO = service.update(id, request)

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: Long) = service.delete(id)
}
