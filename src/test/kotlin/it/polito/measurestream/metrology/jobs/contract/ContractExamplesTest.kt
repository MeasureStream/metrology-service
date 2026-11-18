package it.polito.measurestream.metrology.jobs.contract

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Gli esempi dei contratti si leggono e si riscrivono identici.
 *
 * Sono gli stessi file di calibration-engine/contracts, e il test gemello in Python fa la stessa
 * cosa: se i due lati smettono di capirsi, uno dei due test diventa rosso.
 */
class ContractExamplesTest {
	private val mapper = ContractJson.mapper

	private fun example(name: String): String = Files.readString(Path.of("contracts", name))

	@ParameterizedTest(name = "{0}")
	@MethodSource("examples")
	fun `l'esempio fa andata e ritorno senza perdite`(name: String, type: Class<*>) {
		val original = example(name)
		val roundTrip = mapper.writeValueAsString(mapper.readValue(original, type))
		assertEquals(mapper.readTree(original), mapper.readTree(roundTrip))
	}

	@Test
	fun `la formula sta sotto la chiave che si chiama come il tipo`() {
		val result = mapper.readValue<CalibrationResult>(example("calibration-result.example.json"))
		val function = requireNotNull(result.items.single().function)

		assertEquals("cubic_lsb", function.type)
		assertEquals("c[0] + c[1] * x + c[2] * x**2 + c[3] * x**3", function.formula)
		assertEquals(listOf(0, 1, 2, 3), function.c.map { it.id })
		assertEquals(-28.6037326404808, function.c.first().value)
	}

	@Test
	fun `una funzione senza formula e' rifiutata`() {
		assertThrows<Exception> {
			mapper.readValue<CalibrationFunction>("""{"type": "cubic_lsb", "input": ["x"], "c": []}""")
		}
	}

	@Test
	fun `un job fallito deve dire perche'`() {
		val withoutError = example("calibration-result.failed.example.json")
			.replace(Regex("\"error\": \\{[^}]*}"), "\"error\": null")
		assertThrows<Exception> { mapper.readValue<CalibrationResult>(withoutError) }
	}

	@Test
	fun `un campo sconosciuto non rompe la lettura`() {
		val withExtra = example("calibration-job.example.json").replaceFirst("{", """{"campoDelFuturo": 1,""")
		assertEquals(JobKind.SESSION_FIT, mapper.readValue<CalibrationJob>(withExtra).kind)
	}

	companion object {
		@JvmStatic
		fun examples(): List<Arguments> = listOf(
			Arguments.of("calibration-job.example.json", CalibrationJob::class.java),
			Arguments.of("calibration-result.example.json", CalibrationResult::class.java),
			Arguments.of("calibration-result.failed.example.json", CalibrationResult::class.java),
		)
	}
}
