package it.polito.measurestream.metrology.documents

import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.security.AccessPolicy
import jakarta.persistence.Basic
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.security.MessageDigest
import java.time.OffsetDateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Un file immutabile con la sua impronta. Un trigger rifiuta UPDATE e DELETE [S §5.8]. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "document")
class Document(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(nullable = false, length = 64) var sha256: String,
	@Column(nullable = false, length = 100) var mime: String,
	@Column(nullable = false) var size: Long,
	@Column(length = 255) var filename: String? = null,
	@Column(nullable = false, length = 32) var kind: String,
	@Basic(fetch = FetchType.LAZY) @Column(nullable = false) var content: ByteArray,
	@Column(name = "owner_sub", length = 64) var ownerSub: String? = null,
	@Column(name = "created_by", nullable = false, length = 64) var createdBy: String,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface DocumentRepository : JpaRepository<Document, Long> {
	fun findFirstBySha256AndKindAndCreatedBy(sha256: String, kind: String, createdBy: String): Document?

	fun findAllBySha256(sha256: String): List<Document>
}

/** Cio' che si sa di un documento senza scaricarlo. */
data class DocumentDTO(
	val id: Long,
	val sha256: String,
	val mime: String,
	val size: Long,
	val filename: String?,
	val kind: String,
	val createdBy: String,
	val createdAt: OffsetDateTime,
)

fun Document.toDTO() = DocumentDTO(id, sha256, mime, size, filename, kind, createdBy, createdAt)

@Service
class DocumentService(
	private val repository: DocumentRepository,
	private val policy: AccessPolicy,
	private val audit: AuditService,
	@Value("\${metrology.documents.max-bytes:20971520}") private val maxBytes: Long,
) {
	/**
	 * Conserva un file. Lo stesso file caricato due volte dalla stessa persona con lo stesso
	 * scopo restituisce lo stesso documento: l'impronta e' la sua identita'.
	 */
	@Transactional
	fun store(bytes: ByteArray, mime: String?, filename: String?, kind: String, ownerSub: String?, createdBy: String): Document {
		if (bytes.isEmpty()) throw BadRequest("Il file e' vuoto")
		if (bytes.size > maxBytes) throw BadRequest("Il file supera il limite di ${maxBytes / 1024 / 1024} MB")
		val sha = sha256(bytes)
		repository.findFirstBySha256AndKindAndCreatedBy(sha, kind, createdBy)?.let { return it }

		val document =
			repository.save(
				Document(
					sha256 = sha,
					mime = mime?.takeIf { it.isNotBlank() } ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
					size = bytes.size.toLong(),
					filename = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.take(255),
					kind = kind,
					content = bytes,
					ownerSub = ownerSub,
					createdBy = createdBy,
				)
			)
		audit.record("DOCUMENT_STORED", "document", document.id, detail = mapOf("sha256" to sha, "kind" to kind, "size" to bytes.size))
		return document
	}

	/** Il documento, se chi chiama puo' vederlo: personale, motore, proprietario o sua azienda. */
	@Transactional(readOnly = true)
	fun readable(id: Long): Document {
		val document = repository.findById(id).orElseThrow { NotFound("Documento $id non trovato") }
		val caller = policy.caller()
		if (document.createdBy != caller.sub) policy.requireSee(document.ownerSub, what = "Il documento")
		document.content.size // carica il contenuto dentro la transazione
		return document
	}

	companion object {
		fun sha256(bytes: ByteArray): String =
			MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
	}
}

@RestController
@RequestMapping("/API/metrology/documents")
class DocumentController(
	private val service: DocumentService,
	private val policy: AccessPolicy,
) {
	/**
	 * Carica un documento. Lo usa il motore per DCC, PDF e grafici, e l'interfaccia per i
	 * certificati esterni. [ownerSub] lo puo' indicare solo il personale o il motore: gli altri
	 * caricano sempre a nome proprio.
	 */
	@PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
	fun upload(
		@RequestParam file: MultipartFile,
		@RequestParam(defaultValue = "ATTACHMENT") kind: String,
		@RequestParam(required = false) ownerSub: String?,
	): DocumentDTO {
		val caller = policy.caller()
		val owner = if (caller.isEngine || policy.isStaff(caller)) ownerSub else caller.sub
		return service.store(file.bytes, file.contentType, file.originalFilename, kind.uppercase().take(32), owner, caller.sub).toDTO()
	}

	@GetMapping("/{id}")
	fun download(@PathVariable id: Long): ResponseEntity<ByteArray> {
		val document = service.readable(id)
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(document.mime))
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename(document.filename ?: "documento-$id").build().toString(),
			)
			.header("X-Content-SHA256", document.sha256)
			.body(document.content)
	}

	@GetMapping("/{id}/info")
	fun info(@PathVariable id: Long): DocumentDTO = service.readable(id).toDTO()
}
