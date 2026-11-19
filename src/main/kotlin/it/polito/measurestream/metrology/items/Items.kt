package it.polito.measurestream.metrology.items

import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.security.AccessPolicy
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class ItemStatus { IN_SERVICE, REPLACED, RETIRED }

/**
 * Un esemplare tarato: lo strumento fisico, riconosciuto dalla chiave naturale (EUID della MU,
 * indice del sensore) e non dalla riga `sensor` di sensor-manager, che puo' essere ricreata
 * dalla topologia (M1). Nasce la prima volta che serve: un certificato, una sessione.
 */
@Entity
@Table(name = "calibrated_item")
class CalibratedItem(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "mu_extended_id", nullable = false) var muExtendedId: Long,
	@Column(name = "sensor_index", nullable = false) var sensorIndex: Int,
	@Column(length = 8) var channel: String? = null,
	@Column(name = "model_name", length = 120) var modelName: String? = null,
	@Column(name = "sensor_template_id") var sensorTemplateId: Int? = null,
	@Column(name = "sensor_template_major") var sensorTemplateMajor: Int? = null,
	@Column(name = "serial_number", length = 80) var serialNumber: String? = null,
	@Column(name = "owner_sub", length = 64) var ownerSub: String? = null,
	@Column(name = "requires_calibration", nullable = false) var requiresCalibration: Boolean = true,
	@Column(name = "interval_override_days") var intervalOverrideDays: Int? = null,
	@Column(name = "assigned_lab_id") var assignedLabId: Long? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: ItemStatus = ItemStatus.IN_SERVICE,
	@Column(name = "created_by", nullable = false, length = 64) var createdBy: String,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
	/** Come lo si chiama in un'interfaccia o in un certificato: MU e indice, e il modello. */
	fun label(): String = "0x%08X · %d%s".format(muExtendedId, sensorIndex, modelName?.let { " · $it" } ?: "")
}

interface CalibratedItemRepository : JpaRepository<CalibratedItem, Long> {
	fun findByMuExtendedIdAndSensorIndex(muExtendedId: Long, sensorIndex: Int): CalibratedItem?

	fun findAllByMuExtendedIdOrderBySensorIndex(muExtendedId: Long): List<CalibratedItem>

	fun findAllByOwnerSubIn(owners: Collection<String>): List<CalibratedItem>

	fun findAllByAssignedLabId(labId: Long): List<CalibratedItem>
}

/** Cio' che l'amministratore o il personale possono cambiare di un esemplare. */
data class ItemUpdate(
	val serialNumber: String? = null,
	val requiresCalibration: Boolean? = null,
	val intervalOverrideDays: Int? = null,
	val assignedLabId: Long? = null,
	val status: ItemStatus? = null,
)

@Service
class ItemService(
	private val repository: CalibratedItemRepository,
	private val sensorManager: SensorManagerClient,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	/**
	 * L'esemplare per (EUID, indice), creato se non esiste ancora. La MU si chiede a
	 * sensor-manager con il token di chi chiama: se non torna, quell'utente non la possiede e
	 * l'esemplare non si crea (403), qualunque cosa dica la richiesta.
	 */
	@Transactional
	fun resolveOrCreate(muExtendedId: Long, sensorIndex: Int): CalibratedItem {
		repository.findByMuExtendedIdAndSensorIndex(muExtendedId, sensorIndex)?.let {
			policy.requireSee(it.ownerSub, it.assignedLabId, "L'esemplare")
			return it
		}

		val caller = policy.caller()
		val mu =
			sensorManager.measurementUnit(muExtendedId)
				?: throw Forbidden(policy.invisibleMu(muExtendedId, caller))
		val sensor =
			mu.sensors.firstOrNull { it.sensorIndex == sensorIndex }
				?: throw BadRequest("La MU 0x%08X non ha un sensore con indice %d".format(muExtendedId, sensorIndex))

		// La MU di un altro utente la vede solo l'amministratore: anche lui pero' non ne
		// diventa il proprietario, il proprietario resta quello che dice sensor-manager.
		val item =
			repository.save(
				CalibratedItem(
					muExtendedId = muExtendedId,
					sensorIndex = sensorIndex,
					channel = sensor.channel,
					modelName = sensor.modelName,
					sensorTemplateId = sensor.template?.templateId,
					sensorTemplateMajor = sensor.template?.major,
					ownerSub = mu.ownerSub ?: caller.sub.takeUnless { caller.isAdmin },
					createdBy = caller.sub,
				)
			)
		audit.record("ITEM_CREATED", "calibrated_item", item.id, after = item.snapshot())
		return item
	}

	@Transactional(readOnly = true)
	fun get(id: Long): CalibratedItem {
		val item = repository.findById(id).orElseThrow { NotFound("Esemplare $id non trovato") }
		policy.requireSee(item.ownerSub, item.assignedLabId, "L'esemplare")
		return item
	}

	/** Gli esemplari visibili a chi chiama, eventualmente di una MU sola. */
	@Transactional(readOnly = true)
	fun visible(muExtendedId: Long?): List<CalibratedItem> {
		val caller = policy.caller()
		val owners = policy.visibleOwners(caller)
		val candidates =
			when {
				muExtendedId != null -> repository.findAllByMuExtendedIdOrderBySensorIndex(muExtendedId)
				owners == null -> repository.findAll()
				else -> {
					val lab = if (caller.isLab) policy.labOf(caller.sub) else null
					repository.findAllByOwnerSubIn(owners) + (lab?.let { repository.findAllByAssignedLabId(it) } ?: emptyList())
				}
			}
		return candidates.distinctBy { it.id }.filter { policy.canSee(it.ownerSub, it.assignedLabId, caller) }
	}

	@Transactional
	fun update(id: Long, update: ItemUpdate): CalibratedItem {
		val caller = policy.caller()
		if (!policy.isStaff(caller)) throw Forbidden("Solo il personale del laboratorio modifica un esemplare")
		val item = get(id)
		val before = item.snapshot()
		update.serialNumber?.let { item.serialNumber = it.trim().ifBlank { null } }
		update.requiresCalibration?.let { item.requiresCalibration = it }
		update.intervalOverrideDays?.let { item.intervalOverrideDays = it.takeIf { d -> d > 0 } }
		update.assignedLabId?.let { item.assignedLabId = it.takeIf { l -> l > 0 } }
		update.status?.let { item.status = it }
		audit.record("ITEM_UPDATED", "calibrated_item", item.id, before = before, after = item.snapshot())
		return repository.save(item)
	}
}

fun CalibratedItem.snapshot(): Map<String, Any?> =
	mapOf(
		"muExtendedId" to muExtendedId,
		"sensorIndex" to sensorIndex,
		"serialNumber" to serialNumber,
		"ownerSub" to ownerSub,
		"requiresCalibration" to requiresCalibration,
		"intervalOverrideDays" to intervalOverrideDays,
		"assignedLabId" to assignedLabId,
		"status" to status.name,
	)
