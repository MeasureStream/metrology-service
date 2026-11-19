package it.polito.measurestream.metrology.sessions

import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import it.polito.measurestream.metrology.references.ReferenceStandard
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.support.EmbeddedDatabase
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * I gradini dal Raspberry, anche quando il suo software non e' ancora aggiornato: senza fase,
 * senza indice del sensore, con il vecchio `calib_id` inventato, ripetuti.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CalibrationStepsListener::class)
class CalibrationStepsListenerTest {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun database(registry: DynamicPropertyRegistry) = EmbeddedDatabase.register(registry)
	}

	@Autowired lateinit var listener: CalibrationStepsListener
	@Autowired lateinit var sessions: CalibrationSessionRepository
	@Autowired lateinit var steps: SessionStepRepository
	@Autowired lateinit var items: CalibratedItemRepository
	@Autowired lateinit var references: ReferenceStandardRepository

	private fun session(vararg indexes: Int): CalibrationSession {
		val reference = references.save(ReferenceStandard(name = "Fluke 9142", serialNumber = "SN-${System.nanoTime()}", createdBy = "admin"))
		val sessionItems = indexes.map { items.save(CalibratedItem(muExtendedId = 1000L + it + System.nanoTime() % 1000, sensorIndex = it, createdBy = "anna")) }
		return sessions.save(
			CalibrationSession(
				muExtendedId = 1000,
				referenceStandardId = reference.id,
				procedureTemplateId = 401,
				procedureVersion = "1.0.0",
				operatorSub = "anna",
				items = sessionItems.toMutableSet(),
			)
		)
	}

	/** Il formato di oggi, quello di Mario: due campioni little-endian, 1733 e 3527. */
	private fun message(calibId: String, extra: String = "") =
		ContractJson.mapper.readTree(
			"""{"calib_id":"$calibId","target":20.0,"step_index":0,
			    "step_summary":[{"target":20.0,"minutes":5},{"target":40.0,"minutes":5}],
			    "start_time":"2026-12-03T10:00:00","start_time_dwell":"2026-12-03T10:05:00",
			    "ref_readings":[20.01,20.02],"sensor_sampling_freq":1,
			    "sensor_b64":"${Base64.getEncoder().encodeToString(byteArrayOf(0xC5.toByte(), 0x06, 0xC7.toByte(), 0x0D))}" $extra}"""
		)

	@Test
	fun `senza fase ne' indice, con un sensore solo`() {
		val s = session(3)
		val step = assertNotNull(listener.store(message(s.id.toString())))

		assertEquals(StepPhase.AS_FOUND, step.phase, "sessione aperta: e' l'as-found")
		assertEquals(3, step.sensorIndex)
		assertEquals(listOf(1733, 3527), step.sensorSamples)
		assertEquals(5, step.minutes)
		assertEquals(2, step.totalSteps)
	}

	@Test
	fun `un gradino ripetuto non crea una riga in piu'`() {
		val s = session(0)
		assertNotNull(listener.store(message(s.id.toString())))
		assertNull(listener.store(message(s.id.toString())))
		assertEquals(1, steps.findAllBySessionIdOrderByPhaseAscSensorIndexAscStepIndexAsc(s.id).size)
	}

	@Test
	fun `dopo la chiusura dell'as-found il gradino senza fase e' as-left`() {
		val s = session(0)
		s.status = SessionStatus.AS_FOUND_DONE
		sessions.save(s)
		assertEquals(StepPhase.AS_LEFT, listener.store(message(s.id.toString()))?.phase)
	}

	@Test
	fun `con piu' sensori serve l'indice`() {
		val s = session(0, 1)
		assertNull(listener.store(message(s.id.toString())), "senza sensor_index non si sa di chi e'")
		assertEquals(1, listener.store(message(s.id.toString(), ""","sensor_index":1,"phase":"AS_FOUND" """))?.sensorIndex)
	}

	@Test
	fun `il formato di oggi del Raspberry, con numeri e liste scritti come stringhe`() {
		val s = session(4)
		val today =
			ContractJson.mapper.readTree(
				"""{"calib_id":"${s.id}","target":"-20.0","step_index":"0",
				    "step_summary":"[{'target': -20.0, 'minutes': 1}, {'target': -10.0, 'minutes': 1}]",
				    "start_time":"2026-07-09T18:49:56Z","start_time_dwell":"2026-07-09T18:49:56.200000Z",
				    "ref_readings":"[-20.0136, -20.0144]","sensor_sampling_freq":"1",
				    "sensor_b64":"${Base64.getEncoder().encodeToString(byteArrayOf(0xC5.toByte(), 0x06))}"}"""
			)
		val step = assertNotNull(listener.store(today))
		assertEquals(-20.0, step.setpoint)
		assertEquals(listOf(-20.0136, -20.0144), step.referenceReadings)
		assertEquals(1, step.minutes)
		assertEquals(2, step.totalSteps)
		assertEquals(1, step.sensorSamplingFreq)
	}

	@Test
	fun `il vecchio calib_id inventato si scarta`() {
		assertNull(listener.store(message("calib-1-1-20260422T175123")))
	}
}
