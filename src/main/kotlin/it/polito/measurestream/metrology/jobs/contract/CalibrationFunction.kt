package it.polito.measurestream.metrology.jobs.contract

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

data class Coefficient(
	val id: Int,
	val value: Double,
	val dsi: String? = null,
)

/**
 * La funzione di taratura nella forma del blocco `calibration` del template.
 *
 * Sul filo la formula sta sotto una chiave che si chiama come `type`:
 * `{"type": "cubic_lsb", "cubic_lsb": "c[0] + c[1] * x + ...", "input": ["x"], "c": [...]}`.
 * E' la forma che `SensorConversion` di sensor-manager valuta gia', e quella che porta
 * `calibration-activated`: la si trasporta tale e quale invece di inventarne una seconda.
 * In Kotlin la formula e' un campo normale; la (de)serializzazione la sposta sotto la sua chiave.
 */
data class CalibrationFunction(
	val type: String,
	val formula: String?,
	val input: List<String> = listOf("x"),
	val c: List<Coefficient> = emptyList(),
) {
	init {
		require(type == NONE || !formula.isNullOrBlank()) { "manca la formula sotto la chiave '$type'" }
	}

	@JsonValue
	fun toWire(): Map<String, Any?> =
		buildMap {
			put("type", type)
			if (formula != null) put(type, formula)
			put("input", input)
			put("c", c)
		}

	companion object {
		/** Il tipo di un template senza taratura: il valore resta grezzo. */
		const val NONE = "none"

		@JvmStatic
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		fun fromWire(wire: Map<String, Any?>): CalibrationFunction {
			val type = requireNotNull(wire["type"] as? String) { "la funzione non dichiara il suo type" }
			return CalibrationFunction(
				type = type,
				formula = wire[type] as? String,
				input = (wire["input"] as? List<*>)?.filterIsInstance<String>() ?: listOf("x"),
				c = (wire["c"] as? List<*>).orEmpty().map(::coefficientOf),
			)
		}

		private fun coefficientOf(raw: Any?): Coefficient {
			val map = requireNotNull(raw as? Map<*, *>) { "coefficiente non valido: $raw" }
			return Coefficient(
				id = requireNotNull(map["id"] as? Number) { "coefficiente senza id" }.toInt(),
				value = requireNotNull(map["value"] as? Number) { "coefficiente senza value" }.toDouble(),
				dsi = map["dsi"] as? String,
			)
		}
	}
}
