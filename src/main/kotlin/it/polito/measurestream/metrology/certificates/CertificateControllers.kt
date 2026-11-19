package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.documents.DocumentService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** L'esito di un'azione di massa su un certificato: le altre non si fermano per un errore. */
data class ActionOutcome(val id: Long, val ok: Boolean, val status: CertificateStatus? = null, val error: String? = null)

/**
 * Il contratto di `hubApi.ts` (PIANO_METROLOGIA 1.9, 3.9). Le azioni di massa lavorano un
 * certificato alla volta, ognuno nella sua transazione: un certificato che non si puo' firmare
 * non impedisce di firmare gli altri, e la risposta dice quale e perche'.
 */
@RestController
@RequestMapping("/API/dcc")
class HubController(
	private val hub: HubService,
	private val service: CertificateService,
	private val documents: DocumentService,
) {
	@GetMapping("/summary")
	fun summary(@RequestParam(required = false) clientId: Long?) = hub.summary(clientId)

	@GetMapping("/certificates")
	fun certificates(@RequestParam(required = false) clientId: Long?) = hub.certificates(clientId)

	@GetMapping("/clients")
	fun clients() = hub.clients()

	@PostMapping("/certificates/{action}")
	fun action(@PathVariable action: String, @RequestBody request: IdsRequest): List<ActionOutcome> =
		request.ids.map { id ->
			try {
				val result =
					when (action) {
						"approve" -> service.approve(id)
						"sign" -> service.sign(id)
						"verify" -> service.verify(id)
						"effective" -> service.makeEffective(id)
						"invalidate" -> service.invalidate(id, request.reason)
						else -> throw IllegalArgumentException("Azione sconosciuta: $action")
					}
				ActionOutcome(id, true, result.status)
			} catch (e: RuntimeException) {
				ActionOutcome(id, false, error = e.message)
			}
		}

	@GetMapping("/certificates/{id}/download")
	fun download(@PathVariable id: Long, @RequestParam(defaultValue = "dccPdf") format: String): ResponseEntity<ByteArray> {
		val document = documents.readable(hub.documentFor(id, format))
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(document.mime))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(document.filename ?: "certificato-$id").build().toString())
			.header("X-Content-SHA256", document.sha256)
			.body(document.content)
	}

	/* -------------------------------------------------- certificati esterni [S §5.5] */

	/** Carica il PDF di un certificato esterno per un esemplare o un campione: nasce una bozza. */
	@PostMapping("/external", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
	fun uploadExternal(
		@RequestParam(required = false) file: MultipartFile?,
		@RequestParam(required = false) itemId: Long?,
		@RequestParam(required = false) muExtendedId: Long?,
		@RequestParam(required = false) sensorIndex: Int?,
		@RequestParam(required = false) referenceId: Long?,
	): CertificateDetailDTO =
		service.createExternal(SubjectRef(itemId, muExtendedId, sensorIndex, referenceId), file?.bytes, file?.contentType, file?.originalFilename)

	@PutMapping("/external/{id}/data")
	fun enterData(@PathVariable id: Long, @RequestBody data: CertificateDataRequest) = service.enterData(id, data)

	@PostMapping("/external/{id}/verify")
	fun verify(@PathVariable id: Long) = service.verify(id)

	@PostMapping("/external/{id}/reject")
	fun reject(@PathVariable id: Long, @RequestBody request: ReasonRequest) = service.reject(id, request.reason)
}

/** Il dettaglio di un certificato e le azioni singole, per le pagine di inserimento e verifica. */
@RestController
@RequestMapping("/API/metrology/certificates")
class CertificateController(private val service: CertificateService) {
	@GetMapping("/{id}")
	fun detail(@PathVariable id: Long) = service.detail(id)

	@GetMapping
	fun forSubject(
		@RequestParam(required = false) itemId: Long?,
		@RequestParam(required = false) referenceId: Long?,
	): List<CertificateDetailDTO> =
		when {
			itemId != null -> service.forSubject(SubjectType.ITEM, itemId)
			referenceId != null -> service.forSubject(SubjectType.REFERENCE, referenceId)
			else -> throw IllegalArgumentException("Indica itemId o referenceId")
		}

	@PostMapping("/{id}/revise")
	fun revise(@PathVariable id: Long, @RequestBody request: ReasonRequest) = service.revise(id, request.reason)

	@PostMapping("/{id}/invalidate")
	fun invalidate(@PathVariable id: Long, @RequestBody request: ReasonRequest) = service.invalidate(id, request.reason)

	@PostMapping("/{id}/effective")
	fun effective(@PathVariable id: Long) = service.makeEffective(id)

	@PostMapping("/{id}/approve")
	fun approve(@PathVariable id: Long) = service.approve(id)

	@PostMapping("/{id}/sign")
	fun sign(@PathVariable id: Long) = service.sign(id)
}
