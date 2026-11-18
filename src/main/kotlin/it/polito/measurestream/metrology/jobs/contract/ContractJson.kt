package it.polito.measurestream.metrology.jobs.contract

import tools.jackson.databind.DeserializationFeature
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
}
