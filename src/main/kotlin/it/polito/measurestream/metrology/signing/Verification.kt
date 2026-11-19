package it.polito.measurestream.metrology.signing

import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.documents.DocumentRepository
import it.polito.measurestream.metrology.documents.DocumentService
import java.io.ByteArrayInputStream
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import org.apache.pdfbox.Loader
import org.apache.xml.security.signature.XMLSignature
import org.apache.xml.security.utils.Constants
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.w3c.dom.Element

/** I sei esiti di dccverify, nell'ordine in cui si controllano. */
enum class Verdict { AUTHENTIC, TAMPERED, CERTIFICATE_EXPIRED, UNTRUSTED_ISSUER, NOT_SIGNED, UNREADABLE }

data class VerificationChecks(
	val signaturePresent: Boolean = false,
	val signatureIntegrity: Boolean = false,
	val certificateValidAtSigning: Boolean = false,
	val trustChain: Boolean = false,
)

/** Se il file e' un nostro certificato: quale, e qual e' la revisione in vigore oggi [S §5.4]. */
data class OurCertificate(
	val certificateId: Long,
	val number: String,
	val revision: Int,
	val status: CertificateStatus,
	val currentRevision: Int,
	val currentStatus: CertificateStatus,
	/** Vero se esiste una revisione successiva: il file e' autentico ma non e' piu' quello che vale. */
	val superseded: Boolean,
)

/**
 * L'esito per `dccVerification.ts`: `outcome`, `issuer`, `issuedAt`, `reason` sono il contratto
 * che l'interfaccia aveva gia'; il resto e' il dettaglio di dccverify, che ora non si perde.
 */
data class VerificationResponse(
	val outcome: String,
	val issuer: String? = null,
	val issuedAt: String? = null,
	val reason: String? = null,
	val verdict: Verdict,
	val checks: VerificationChecks,
	val signer: String? = null,
	val algorithm: String? = null,
	val sha256: String,
	val ours: OurCertificate? = null,
)

/**
 * La verifica delle firme, fusa da dccverify (decisione D-B, passo 1.10). XML con Apache
 * Santuario come prima; PDF con PDFBox e BouncyCastle al posto di iText 8.
 *
 * Ordine dei controlli, deliberato: prima la manomissione, poi la scadenza, poi la fiducia.
 * Un file modificato e' modificato, chiunque l'abbia firmato.
 */
@Service
class VerificationService(
	private val trust: TrustStore,
	private val documents: DocumentRepository,
	private val certificates: CertificateRepository,
) {
	private val log = LoggerFactory.getLogger(VerificationService::class.java)

	@Transactional(readOnly = true)
	fun verify(bytes: ByteArray, filename: String?): VerificationResponse {
		val sha = DocumentService.sha256(bytes)
		val pdf = filename?.lowercase()?.endsWith(".pdf") == true || bytes.take(5).toByteArray().decodeToString() == "%PDF-"
		val (verdict, checks, details) = if (pdf) verifyPdf(bytes) else verifyXml(bytes)
		return VerificationResponse(
			outcome = if (verdict == Verdict.AUTHENTIC) "valid" else "invalid",
			issuer = details.issuer,
			issuedAt = details.signedAt?.toInstant()?.toString(),
			reason = details.message ?: reasonOf(verdict),
			verdict = verdict,
			checks = checks,
			signer = details.signer,
			algorithm = details.algorithm,
			sha256 = sha,
			ours = if (verdict == Verdict.AUTHENTIC || verdict == Verdict.CERTIFICATE_EXPIRED) ours(sha) else null,
		)
	}

	private data class Details(
		val signer: String? = null,
		val issuer: String? = null,
		val algorithm: String? = null,
		val signedAt: Date? = null,
		val message: String? = null,
	)

	private fun decide(c: VerificationChecks): Verdict =
		when {
			!c.signaturePresent -> Verdict.NOT_SIGNED
			!c.signatureIntegrity -> Verdict.TAMPERED
			!c.certificateValidAtSigning -> Verdict.CERTIFICATE_EXPIRED
			!c.trustChain -> Verdict.UNTRUSTED_ISSUER
			else -> Verdict.AUTHENTIC
		}

	private fun verifyXml(bytes: ByteArray): Triple<Verdict, VerificationChecks, Details> {
		val doc =
			try {
				SigningService.secureFactory().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
			} catch (e: Exception) {
				return Triple(Verdict.UNREADABLE, VerificationChecks(), Details(message = "Il file non e' un XML leggibile"))
			}
		// Santuario deve sapere quali attributi sono identificatori per risolvere i riferimenti.
		val all = doc.getElementsByTagName("*")
		for (i in 0 until all.length) {
			(all.item(i) as Element).let { el ->
				if (el.hasAttribute("Id")) el.setIdAttribute("Id", true)
				if (el.hasAttribute("id")) el.setIdAttribute("id", true)
			}
		}
		val element =
			doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature").item(0) as? Element
				?: return Triple(Verdict.NOT_SIGNED, VerificationChecks(), Details(message = "Il documento non contiene una firma digitale"))
		return try {
			val signature = XMLSignature(element, "")
			val certificate =
				signature.keyInfo?.x509Certificate
					?: return Triple(Verdict.UNREADABLE, VerificationChecks(signaturePresent = true), Details(message = "La firma non contiene il certificato del firmatario"))
			val integrity = signature.checkSignatureValue(certificate)
			val signedAt = signingTime(doc) ?: Date()
			val checks = check(listOf(certificate), integrity, signedAt)
			Triple(decide(checks), checks, details(certificate, signature.signedInfo.signatureMethodURI, signedAt))
		} catch (e: Exception) {
			log.warn("Verifica XML fallita: {}", e.message)
			Triple(Verdict.UNREADABLE, VerificationChecks(signaturePresent = true), Details(message = e.message))
		}
	}

	private fun verifyPdf(bytes: ByteArray): Triple<Verdict, VerificationChecks, Details> =
		try {
			Loader.loadPDF(bytes).use { pdf ->
				val signature =
					pdf.signatureDictionaries.lastOrNull()
						?: return Triple(Verdict.NOT_SIGNED, VerificationChecks(), Details(message = "Il PDF non contiene una firma digitale"))
				val contents = signature.contents
				val signed = signature.getSignedContent(bytes)
				// /Contents e' riempito di zeri fino allo spazio riservato: si legge un solo oggetto
				// ASN.1, perche' BouncyCastle rifiuta i byte in piu' dopo la busta CMS.
				val envelope = org.bouncycastle.asn1.ASN1InputStream(contents).use { it.readObject() }
				val cms = CMSSignedData(CMSProcessableByteArray(signed), org.bouncycastle.asn1.cms.ContentInfo.getInstance(envelope))
				val signer = cms.signerInfos.signers.first()
				@Suppress("UNCHECKED_CAST")
				val holders = cms.certificates.getMatches(null) as Collection<X509CertificateHolder>
				val converter = JcaX509CertificateConverter()
				@Suppress("UNCHECKED_CAST")
				val selector = signer.sid as org.bouncycastle.util.Selector<X509CertificateHolder>
				val signerCert = converter.getCertificate(cms.certificates.getMatches(selector).first() as X509CertificateHolder)
				val chain = listOf(signerCert) + holders.map { converter.getCertificate(it) }.filter { it != signerCert }

				val integrity = runCatching { signer.verify(JcaSimpleSignerInfoVerifierBuilder().build(signerCert)) }.getOrDefault(false)
				// La firma deve coprire tutto il file: byte aggiunti dopo sono modifiche successive.
				val range = signature.byteRange
				val coversAll = range.size == 4 && range[2] + range[3] == bytes.size
				val signedAt = signature.signDate?.time ?: Date()
				val checks = check(chain, integrity && coversAll, signedAt)
				val message = if (integrity && !coversAll) "Il PDF e' stato modificato dopo la firma" else null
				Triple(decide(checks), checks, details(signerCert, signer.encryptionAlgOID, signedAt).copy(message = message))
			}
		} catch (e: Exception) {
			log.warn("Verifica PDF fallita: {}", e.message)
			Triple(Verdict.UNREADABLE, VerificationChecks(), Details(message = "Il file non e' un PDF leggibile"))
		}

	private fun check(chain: List<X509Certificate>, integrity: Boolean, signedAt: Date) =
		VerificationChecks(
			signaturePresent = true,
			signatureIntegrity = integrity,
			certificateValidAtSigning = runCatching { chain.first().checkValidity(signedAt) }.isSuccess,
			trustChain = trust.isTrusted(chain, signedAt),
		)

	private fun details(certificate: X509Certificate, algorithm: String?, signedAt: Date) =
		Details(
			signer = certificate.subjectX500Principal.name,
			issuer = certificate.issuerX500Principal.name,
			algorithm = algorithm,
			signedAt = signedAt,
		)

	private fun signingTime(doc: org.w3c.dom.Document): Date? {
		val nodes = doc.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", "SigningTime")
		if (nodes.length == 0) return null
		return runCatching { Date.from(Instant.parse(nodes.item(0).textContent.trim())) }.getOrNull()
	}

	/** Il file e' uno dei nostri documenti? Allora si dice anche quale revisione vale oggi. */
	private fun ours(sha: String): OurCertificate? {
		val ids = documents.findAllBySha256(sha).map { it.id }
		if (ids.isEmpty()) return null
		val certificate = certificates.findAllByXmlDocumentIdInOrPdfDocumentIdInOrOriginalDocumentIdIn(ids, ids, ids).firstOrNull() ?: return null
		val revisions = certificates.findAllByNumberOrderByRevisionDesc(certificate.number).filter { it.kind == certificate.kind && it.labCompanyId == certificate.labCompanyId }
		val current = revisions.firstOrNull { it.status == CertificateStatus.EFFECTIVE } ?: revisions.first()
		return OurCertificate(
			certificateId = certificate.id,
			number = certificate.number,
			revision = certificate.revision,
			status = certificate.status,
			currentRevision = current.revision,
			currentStatus = current.status,
			superseded = current.revision > certificate.revision,
		)
	}

	private fun reasonOf(verdict: Verdict): String? =
		when (verdict) {
			Verdict.AUTHENTIC -> null
			Verdict.TAMPERED -> "La firma non corrisponde al contenuto: il file e' stato modificato dopo la firma"
			Verdict.CERTIFICATE_EXPIRED -> "Il certificato del firmatario non era valido alla data della firma"
			Verdict.UNTRUSTED_ISSUER -> "Il documento e' integro ma firmato da un emittente che non riconosciamo"
			Verdict.NOT_SIGNED -> "Il documento non contiene una firma digitale"
			Verdict.UNREADABLE -> "Il file non e' leggibile nel formato atteso"
		}
}

@RestController
@RequestMapping("/API/dcc")
class VerificationController(private val service: VerificationService) {
	/** Il contratto di `dccVerification.ts`: multipart con il file, esito in JSON. */
	@PostMapping("/verify", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
	fun verify(@RequestParam file: MultipartFile): VerificationResponse = service.verify(file.bytes, file.originalFilename)
}
