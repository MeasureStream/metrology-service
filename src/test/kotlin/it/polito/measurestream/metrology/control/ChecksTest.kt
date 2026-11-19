package it.polito.measurestream.metrology.control

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** I conti del passo 5.2, senza database: numero En, esito, carta di controllo. */
class ChecksTest {
	@Test
	fun `En con le incertezze estese di indicazione e riferimento`() {
		assertEquals(0.3 / sqrt(0.2 * 0.2 + 0.1 * 0.1), Checks.en(0.3, 0.2, 0.1), 1e-12)
		assertEquals(0.0, Checks.en(0.0, 0.0, 0.0))
	}

	@Test
	fun `esito della verifica`() {
		assertEquals(CheckVerdict.PASS, Checks.verdict(0.1, 0.5, 0.5))
		assertEquals(CheckVerdict.FAIL, Checks.verdict(0.6, 0.9, 0.5), "fuori dall'MPE")
		assertEquals(CheckVerdict.UNDECIDED, Checks.verdict(0.4, 1.6, 0.5), "dentro l'MPE ma incompatibile con la propria incertezza")
		assertEquals(CheckVerdict.FAIL, Checks.verdict(0.4, 1.6, null), "senza MPE decide En")
	}

	private fun series(vararg values: Double): List<ChartPoint> {
		val t0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
		return values.mapIndexed { i, v -> ChartPoint(t0.plusMonths(i.toLong()), "CHECK", v, 0.1, "verifica $i") }
	}

	@Test
	fun `carta di Shewhart dagli scarti mobili`() {
		val chart = ControlChartService.compute(1, series(0.10, 0.12, 0.08, 0.11, 0.09))
		assertEquals(0.10, chart.center!!, 1e-12)
		val mr = (0.02 + 0.04 + 0.03 + 0.02) / 4
		assertEquals(mr / 1.128, chart.sigma!!, 1e-12)
		assertEquals(chart.center!! + 3 * chart.sigma!!, chart.ucl!!, 1e-12)
		assertTrue(chart.signals.isEmpty(), "una serie tranquilla non segnala niente")
	}

	@Test
	fun `una deriva si vede nella CUSUM`() {
		val drifting = series(0.00, 0.01, -0.01, 0.00, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.09)
		val chart = ControlChartService.compute(1, drifting)
		assertTrue(chart.signals.any { it.contains("CUSUM") }, chart.signals.toString())
		assertTrue(chart.cusumHigh.last() > chart.cusumHigh.first())
	}

	@Test
	fun `sette punti consecutivi dalla stessa parte del centro`() {
		val chart = ControlChartService.compute(1, series(-0.05, -0.04, -0.06, 0.01, 0.02, 0.01, 0.02, 0.01, 0.02, 0.01))
		assertTrue(chart.signals.any { it.contains("sette punti consecutivi") }, chart.signals.toString())
	}

	@Test
	fun `un punto oltre tre sigma`() {
		val chart = ControlChartService.compute(1, series(0.0, 0.01, -0.01, 0.0, 0.01, -0.01, 0.0, 0.5))
		assertTrue(chart.signals.any { it.contains("oltre ±3σ") }, chart.signals.toString())
	}
}
