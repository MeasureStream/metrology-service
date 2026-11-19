package it.polito.measurestream.metrology.jobs.contract

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Il JSON dei messaggi scambiati con il motore.
 *
 * Un mapper dedicato, e non quello di Spring per il REST, perche' le regole del contratto non
 * devono cambiare se qualcuno configura diversamente le API: date in ISO 8601, campi sconosciuti
 * ignorati (l'altra parte puo' essere gia' alla versione successiva).
 */
object ContractJson {
	val mapper: JsonMapper =
		jacksonMapperBuilder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build()

	/**
	 * Forma canonica per le impronte: chiavi ordinate, date come testo ISO. Due stati uguali
	 * danno la stessa stringa, qualunque sia l'ordine in cui i campi sono stati scritti.
	 */
	private val canonicalMapper: JsonMapper =
		jacksonMapperBuilder()
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.enable(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
			.build()

	fun canonical(value: Any): String = canonicalMapper.writeValueAsString(value)

	fun write(value: Any?): String = mapper.writeValueAsString(value)

	/**
	 * Lo stesso valore come mappe, liste e numeri semplici, cioe' come tornera' indietro da una
	 * colonna jsonb. Va usato prima di salvare un oggetto in una colonna JSON: Hibernate confronta
	 * il valore con quello riletto, e un Coefficient o un Long al posto di un Integer sembrerebbero
	 * una modifica, con un UPDATE inutile (o rifiutato, sulle tabelle immutabili).
	 */
	@Suppress("UNCHECKED_CAST")
	fun plain(value: Any): Map<String, Any?> = mapper.readValue(mapper.writeValueAsString(value), Map::class.java) as Map<String, Any?>

	fun <T> read(json: String, type: Class<T>): T = mapper.readValue(json, type)
}
