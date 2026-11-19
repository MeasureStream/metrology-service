package it.polito.measurestream.metrology.activation

import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import java.time.OffsetDateTime
import org.springframework.stereotype.Service

/** Il campo tarato: fuori, la funzione e' estrapolata e il campione lo dichiara. */
data class ActivatedRange(val min: Double?, val max: Double?, val dsi: String?)

/**
 * Un messaggio di `calibration-activated` (specifica § 3 e appendice A). Gemello di
 * `CalibrationActivatedEvent` in sensor-manager: i campi nuovi si aggiungono con un default.
 */
data class ActivationEvent(
	val schemaVersion: Int = 1,
	val muExtendedId: Long,
	val sensorIndex: Int,
	val calibratedItemId: Long,
	val certificateId: Long,
	val certificateNumber: String,
	val revision: Int,
	val validFrom: OffsetDateTime,
	val validUntil: OffsetDateTime?,
	val function: Map<String, Any?>,
	val covariance: List<List<Double>>?,
	val calibratedRange: ActivatedRange?,
	val blockACrc: String? = null,
	val fingerprint: String?,
) {
	companion object {
		const val TOPIC = "calibration-activated"

		/** `EUID:indice`, con l'EUID in decimale come in `measurement_unit.extended_id`. */
		fun keyOf(item: CalibratedItem) = "${item.muExtendedId}:${item.sensorIndex}"
	}
}

/**
 * Attiva e revoca la taratura di un esemplare (PIANO_METROLOGIA 1.7). Il messaggio passa
 * dall'outbox, quindi esiste se e solo se la transizione del certificato e' stata salvata.
 */
@Service
class ActivationService(
	private val outbox: Outbox,
	private val audit: AuditService,
) {
	fun activate(certificate: Certificate, item: CalibratedItem, validFrom: OffsetDateTime) {
		val function = certificate.function ?: throw IllegalStateException("Certificato ${certificate.id} senza funzione")
		val event =
			ActivationEvent(
				muExtendedId = item.muExtendedId,
				sensorIndex = item.sensorIndex,
				calibratedItemId = item.id,
				certificateId = certificate.id,
				certificateNumber = certificate.number,
				revision = certificate.revision,
				validFrom = validFrom,
				validUntil = certificate.validUntil,
				function = function,
				covariance = certificate.covariance,
				calibratedRange =
					if (certificate.calibratedMin != null || certificate.calibratedMax != null)
						ActivatedRange(certificate.calibratedMin, certificate.calibratedMax, certificate.calibratedDsi)
					else null,
				fingerprint = certificate.fingerprint,
			)
		outbox.enqueue(ActivationEvent.TOPIC, ActivationEvent.keyOf(item), ContractJson.write(event))
		audit.record("CALIBRATION_ACTIVATED", "calibrated_item", item.id, after = event)
	}

	/** Il tombstone: da adesso l'esemplare torna alla formula nominale del modello. */
	fun revoke(item: CalibratedItem, certificate: Certificate) {
		outbox.enqueue(ActivationEvent.TOPIC, ActivationEvent.keyOf(item), null)
		audit.record("CALIBRATION_REVOKED", "calibrated_item", item.id, detail = mapOf("certificateId" to certificate.id))
	}
}
