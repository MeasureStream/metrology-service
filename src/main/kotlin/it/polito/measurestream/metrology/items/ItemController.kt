package it.polito.measurestream.metrology.items

import it.polito.measurestream.metrology.certificates.CertificateDetailDTO
import it.polito.measurestream.metrology.certificates.CertificateService
import it.polito.measurestream.metrology.certificates.SubjectType
import java.time.OffsetDateTime
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ItemDTO(
	val id: Long,
	val muExtendedId: Long,
	val sensorIndex: Int,
	val channel: String?,
	val modelName: String?,
	val sensorTemplateId: Int?,
	val sensorTemplateMajor: Int?,
	val serialNumber: String?,
	val ownerSub: String?,
	val requiresCalibration: Boolean,
	val intervalOverrideDays: Int?,
	val assignedLabId: Long?,
	val status: ItemStatus,
	val createdAt: OffsetDateTime,
	val label: String,
)

/** L'esemplare con la sua storia: i certificati, dal piu' recente. */
data class ItemDetailDTO(val item: ItemDTO, val certificates: List<CertificateDetailDTO>)

data class ItemRequest(val muExtendedId: Long, val sensorIndex: Int)

fun CalibratedItem.toDTO() =
	ItemDTO(
		id, muExtendedId, sensorIndex, channel, modelName, sensorTemplateId, sensorTemplateMajor, serialNumber, ownerSub,
		requiresCalibration, intervalOverrideDays, assignedLabId, status, createdAt, label(),
	)

@RestController
@RequestMapping("/API/metrology/items")
class ItemController(
	private val service: ItemService,
	private val certificates: CertificateService,
) {
	@GetMapping
	fun visible(@RequestParam(required = false) muExtendedId: Long?): List<ItemDTO> = service.visible(muExtendedId).map { it.toDTO() }

	/** L'esemplare per chiave naturale, creato su richiesta (M1). */
	@PostMapping
	fun resolve(@RequestBody request: ItemRequest): ItemDTO = service.resolveOrCreate(request.muExtendedId, request.sensorIndex).toDTO()

	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	fun detail(@PathVariable id: Long): ItemDetailDTO =
		ItemDetailDTO(service.get(id).toDTO(), certificates.forSubject(SubjectType.ITEM, id))

	@PutMapping("/{id}")
	fun update(@PathVariable id: Long, @RequestBody update: ItemUpdate): ItemDTO = service.update(id, update).toDTO()
}
