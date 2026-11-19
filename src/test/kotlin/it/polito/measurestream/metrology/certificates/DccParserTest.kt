package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.common.BadRequest
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** La lettura dei DCC esterni (passo 5.4), sulla forma che scrive anche la pipeline di Mario. */
class DccParserTest {
	companion object {
		/** Un DCC 3.3.0 minimo con la tabella dei risultati come la scrive generate_dcc_xml.py. */
		fun dcc(lab: String = "Laboratorio interno", kelvin: Boolean = false): String {
			val ref = if (kelvin) "253.15 293.15 313.15" else "-20 20 40"
			val unit = if (kelvin) "\\kelvin" else "\\degreeCelsius"
			return """<?xml version="1.0" encoding="UTF-8"?>
<dcc:digitalCalibrationCertificate xmlns:dcc="https://ptb.de/dcc" xmlns:si="https://ptb.de/si" schemaVersion="3.3.0">
  <dcc:administrativeData>
    <dcc:coreData>
      <dcc:uniqueIdentifier>LAT-042-2026-0500</dcc:uniqueIdentifier>
      <dcc:beginPerformanceDate>2026-10-05</dcc:beginPerformanceDate>
      <dcc:endPerformanceDate>2026-10-05</dcc:endPerformanceDate>
    </dcc:coreData>
    <dcc:items><dcc:item><dcc:name><dcc:content lang="en">Thermometer</dcc:content></dcc:name>
      <dcc:identifications><dcc:identification><dcc:issuer>manufacturer</dcc:issuer><dcc:value>REF-SN-77</dcc:value>
        <dcc:name><dcc:content lang="en">Serial number</dcc:content></dcc:name></dcc:identification></dcc:identifications>
    </dcc:item></dcc:items>
    <dcc:calibrationLaboratory><dcc:calibrationLaboratoryCode>LAT 042</dcc:calibrationLaboratoryCode>
      <dcc:contact><dcc:name><dcc:content lang="en">$lab</dcc:content></dcc:name></dcc:contact></dcc:calibrationLaboratory>
  </dcc:administrativeData>
  <dcc:measurementResults><dcc:measurementResult><dcc:name><dcc:content lang="en">Results</dcc:content></dcc:name>
    <dcc:results><dcc:result><dcc:name><dcc:content lang="en">Table</dcc:content></dcc:name><dcc:data><dcc:list>
      <dcc:quantity refType="basic_referenceValue"><si:hybrid><si:realListXMLList>
        <si:valueXMLList>$ref</si:valueXMLList><si:unitXMLList>$unit</si:unitXMLList></si:realListXMLList></si:hybrid></dcc:quantity>
      <dcc:quantity refType="gp_measurementErrorPreCalibration"><si:hybrid><si:realListXMLList>
        <si:valueXMLList>0.4 0.5 0.6</si:valueXMLList><si:unitXMLList>\degreeCelsius</si:unitXMLList></si:realListXMLList></si:hybrid></dcc:quantity>
      <dcc:quantity refType="basic_measurementError"><si:hybrid><si:realListXMLList>
        <si:valueXMLList>0.01 -0.02 0.03</si:valueXMLList><si:unitXMLList>\degreeCelsius</si:unitXMLList>
        <si:expandedUncXMLList><si:uncertaintyXMLList>0.05 0.05 0.06</si:uncertaintyXMLList><si:coverageFactorXMLList>2.0 2.0 2.0</si:coverageFactorXMLList></si:expandedUncXMLList>
      </si:realListXMLList></si:hybrid></dcc:quantity>
    </dcc:list></dcc:data></dcc:result></dcc:results>
  </dcc:measurementResult></dcc:measurementResults>
</dcc:digitalCalibrationCertificate>"""
		}
	}

	@Test
	fun `numero, data, laboratorio, identificazioni e punti`() {
		val parsed = DccParser.parse(dcc().toByteArray())
		assertEquals("LAT-042-2026-0500", parsed.number)
		assertEquals(LocalDate.of(2026, 10, 5), parsed.calibrationDate)
		assertEquals("Laboratorio interno", parsed.issuer)
		assertEquals("LAT 042", parsed.laboratoryCode)
		assertEquals("REF-SN-77", parsed.identifications.single().value)

		val left = parsed.points.filter { it.phase == PointPhase.AS_LEFT }
		val found = parsed.points.filter { it.phase == PointPhase.AS_FOUND }
		assertEquals(listOf(-20.0, 20.0, 40.0), left.map { it.referenceValue })
		assertEquals(listOf(0.01, -0.02, 0.03), left.map { it.error })
		assertEquals(-19.99, left[0].indicatedValue!!, 1e-12)
		assertEquals(listOf(0.05, 0.05, 0.06), left.map { it.expandedU })
		assertEquals(listOf(0.4, 0.5, 0.6), found.map { it.error }, "l'as-found dagli errori prima della taratura")
	}

	@Test
	fun `i valori in kelvin diventano gradi Celsius, gli errori no`() {
		val parsed = DccParser.parse(dcc(kelvin = true).toByteArray())
		val left = parsed.points.filter { it.phase == PointPhase.AS_LEFT }
		assertEquals(-20.0, left[0].referenceValue!!, 1e-9)
		assertEquals(0.01, left[0].error!!, 1e-12)
		assertTrue(parsed.warnings.any { "kelvin" in it })
	}

	@Test
	fun `un DOCTYPE con entita' esterne viene rifiutato`() {
		val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><dcc:digitalCalibrationCertificate xmlns:dcc="https://ptb.de/dcc">&e;</dcc:digitalCalibrationCertificate>"""
		assertThrows<BadRequest> { DccParser.parse(xxe.toByteArray()) }
	}

	@Test
	fun `un XML che non e' un DCC viene rifiutato`() {
		assertThrows<BadRequest> { DccParser.parse("<root/>".toByteArray()) }
	}
}
