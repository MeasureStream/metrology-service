package it.polito.measurestream.metrology.items

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import it.polito.measurestream.metrology.common.CurrentUser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body

/** Il riferimento al template di un sensore, come lo espone sensor-manager. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteTemplateRef(
	val templateId: Int? = null,
	val major: Int? = null,
	val resolvedVersion: String? = null,
	val modelName: String? = null,
	val type: String? = null,
	val unit: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteSensor(
	val id: Long = 0,
	val modelName: String = "",
	val sensorIndex: Int = 0,
	val channel: String? = null,
	val template: RemoteTemplateRef? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteMeasurementUnit(
	val id: Long = 0,
	val extendedId: Long = 0,
	val localId: Int = 0,
	val model: Int = 0,
	val controlUnitId: Long? = null,
	val sensors: List<RemoteSensor> = emptyList(),
	val ownerSub: String? = null,
)

/**
 * La strada verso sensor-manager, che possiede MU e sensori (M1). Il servizio non legge le sue
 * tabelle: gli chiede la MU con il token di chi sta chiamando, e la risposta e' gia' filtrata
 * per proprieta' da chi la proprieta' la conosce. Se la MU non torna, per quell'utente non
 * esiste.
 */
@Component
class SensorManagerClient(
	@Value("\${metrology.sensor-manager.url}") private val baseUrl: String,
	private val currentUser: CurrentUser,
) {
	private val log = LoggerFactory.getLogger(SensorManagerClient::class.java)
	private val client = RestClient.builder().baseUrl(baseUrl).build()

	/** La MU con quell'EUID, se chi chiama la puo' vedere. */
	fun measurementUnit(extendedId: Long): RemoteMeasurementUnit? {
		val token = currentUser.get().token ?: return null
		return try {
			client.get()
				.uri("/API/measurementunits?extendedId={id}", extendedId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
				.retrieve()
				.body<List<RemoteMeasurementUnit>>()
				?.firstOrNull { it.extendedId == extendedId }
		} catch (e: RestClientResponseException) {
			log.warn("sensor-manager ha risposto {} per la MU {}", e.statusCode, extendedId)
			null
		}
	}

	/** Tutte le MU che chi chiama puo' vedere: le sue, o tutte per l'amministratore. */
	fun measurementUnits(): List<RemoteMeasurementUnit> {
		val token = currentUser.get().token ?: return emptyList()
		return try {
			client.get()
				.uri("/API/measurementunits")
				.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
				.retrieve()
				.body<List<RemoteMeasurementUnit>>()
				.orEmpty()
		} catch (e: Exception) {
			// L'Hub resta utilizzabile anche se sensor-manager non risponde: conta solo cio'
			// che conosce gia', e il numero dei sensori mai tarati sara' una sottostima.
			log.warn("sensor-manager non raggiungibile per l'elenco delle MU: {}", e.message)
			emptyList()
		}
	}

	/** Un template del registro, per tipo e versione esatta (procedure, profili). */
	fun template(kind: String, templateId: Int, version: String): Map<String, Any?>? {
		val token = currentUser.get().token ?: return null
		return try {
			@Suppress("UNCHECKED_CAST")
			client.get()
				.uri("/API/templates/{kind}/{id}/{version}", kind, templateId, version)
				.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
				.retrieve()
				.body<Map<String, Any?>>()
		} catch (e: RestClientResponseException) {
			log.warn("sensor-manager ha risposto {} per il template {} {} {}", e.statusCode, kind, templateId, version)
			null
		}
	}
}
