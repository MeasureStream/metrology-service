package it.polito.measurestream.metrology.signing

import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.documents.DocumentRepository
import it.polito.measurestream.metrology.documents.DocumentService
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Firma e verifica, senza database: un DCC e un PDF firmati con la chiave di sviluppo
 * risultano autentici; modificati di un byte, manomessi; firmati da un'altra chiave, di un
 * emittente che non riconosciamo (PIANO_METROLOGIA 1.10 e 3.8).
 */
class SigningRoundTripTest {

	private fun signer(): Pair<SigningService, VerificationService> {
		val keys = SigningKeys("", "", devSelfSigned = true).apply { load() }
		val trust = TrustStore("/non/esiste", keys).apply { load() }
		val documents = Mockito.mock(DocumentRepository::class.java)
		Mockito.`when`(documents.findAllBySha256(Mockito.anyString())).thenReturn(emptyList())
		val signing = SigningService(keys, Mockito.mock(DocumentService::class.java), documents)
		val verification = VerificationService(trust, documents, Mockito.mock(CertificateRepository::class.java))
		return signing to verification
	}

	private val dcc =
		"""<?xml version="1.0" encoding="UTF-8"?>
		<dcc:digitalCalibrationCertificate xmlns:dcc="https://ptb.de/dcc" schemaVersion="3.3.0">
		  <dcc:administrativeData><dcc:coreData><dcc:uniqueIdentifier>LAB-2026-0001</dcc:uniqueIdentifier></dcc:coreData></dcc:administrativeData>
		  <dcc:measurementResults><value>20.0312</value></dcc:measurementResults>
		</dcc:digitalCalibrationCertificate>""".trimIndent().toByteArray()

	private fun pdf(): ByteArray =
		PDDocument().use { doc ->
			val page = PDPage().also { doc.addPage(it) }
			PDPageContentStream(doc, page).use {
				it.beginText()
				it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
				it.newLineAtOffset(72f, 720f)
				it.showText("Certificato di taratura LAB-2026-0001")
				it.endText()
			}
			ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
		}

	@Test
	fun `DCC firmato e autentico, modificato di un byte e manomesso`() {
		val (signing, verification) = signer()
		val signed = signing.signXmlBytes(dcc)

		val ok = verification.verify(signed, "dcc.xml")
		assertEquals(Verdict.AUTHENTIC, ok.verdict, ok.reason)
		assertEquals("valid", ok.outcome)

		val tampered = String(signed).replace("20.0312", "20.0313").toByteArray()
		val ko = verification.verify(tampered, "dcc.xml")
		assertEquals(Verdict.TAMPERED, ko.verdict)
		assertEquals("invalid", ko.outcome)
	}

	@Test
	fun `un XML senza firma lo dice`() {
		val (_, verification) = signer()
		assertEquals(Verdict.NOT_SIGNED, verification.verify(dcc, "dcc.xml").verdict)
	}

	@Test
	fun `PDF firmato e autentico, con byte aggiunti dopo la firma manomesso`() {
		val (signing, verification) = signer()
		val signed = signing.signPdfBytes(pdf())

		val ok = verification.verify(signed, "certificato.pdf")
		assertEquals(Verdict.AUTHENTIC, ok.verdict, ok.reason)

		val appended = signed + "\n% aggiunto dopo".toByteArray()
		val ko = verification.verify(appended, "certificato.pdf")
		assertEquals(Verdict.TAMPERED, ko.verdict, ko.reason)
	}

	@Test
	fun `firmato da una chiave che non conosciamo`() {
		val (otherSigning, _) = signer()
		val (_, verification) = signer()
		val result = verification.verify(otherSigning.signXmlBytes(dcc), "dcc.xml")
		assertEquals(Verdict.UNTRUSTED_ISSUER, result.verdict)
		assertTrue(result.reason!!.contains("non riconosciamo"))
	}
}
