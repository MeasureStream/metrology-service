package it.polito.measurestream.metrology.certificates

import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.companies.LabCompany
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.references.ReferenceStandardRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.signing.VerificationService
import it.polito.measurestream.metrology.signing.Verdict
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.w3c.dom.Element

/* -------------------------------------------------------------------------------- lettura */

data class DccIdentification(val issuer: String?, val value: String, val name: String?)

data class ParsedDcc(
	val number: String?,
	val issuer: String?,
	val laboratoryCode: String?,
	val calibrationDate: LocalDate?,
	val identifications: List<DccIdentification>,
	val points: List<PointRequest>,
	val warnings: List<String>,
)

/**
 * La lettura di un DCC PTB (schema 3.x), il piu' possibile tollerante: i laboratori scrivono i
 * risultati in modi diversi, quindi si cercano le grandezze per `refType` e per nome e si
 * prende cio' che si riconosce, dicendo che cosa non si e' potuto leggere (PIANO_METROLOGIA 5.4).
 *
 * - riferimento: refType con `referenceValue`; indicazione: `measuredValue` o `indicat…`;
 *   errore dopo la taratura: `basic_measurementError`; errore prima: `…PreCalibration` o
 *   `asFound`. I valori in kelvin di riferimento e indicazione si portano in gradi Celsius
 *   (gli errori e le incertezze sono differenze e restano come sono).
 * - incertezza: `si:expandedUncXMLList` (SI 1.x) o `si:expandedMUXMLList` (SI 2.x), con il
 *   fattore di copertura.
 *
 * Il parser rifiuta DOCTYPE ed entita' esterne: un file caricato da un utente non deve poter
 * leggere file del server (XXE).
 */
object DccParser {
	private const val DCC = "https://ptb.de/dcc"
	private const val SI = "https://ptb.de/si"

	fun parse(bytes: ByteArray): ParsedDcc {
		val factory =
			DocumentBuilderFactory.newInstance().apply {
				isNamespaceAware = true
				setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
				setFeature("http://xml.org/sax/features/external-general-entities", false)
				setFeature("http://xml.org/sax/features/external-parameter-entities", false)
				setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
				isXIncludeAware = false
				isExpandEntityReferences = false
			}
		val document =
			try {
				factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
			} catch (e: Exception) {
				throw BadRequest("Il file non e' un XML leggibile: ${e.message}")
			}
		val root = document.documentElement
		if (root.localName != "digitalCalibrationCertificate" || root.namespaceURI != DCC) throw BadRequest("Il file non e' un DCC PTB")
		val warnings = mutableListOf<String>()

		val core = first(root, DCC, "coreData")
		val number = core?.let { text(first(it, DCC, "uniqueIdentifier")) }
		val date = core?.let { text(first(it, DCC, "beginPerformanceDate")) ?: text(first(it, DCC, "endPerformanceDate")) }
			?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
		val lab = first(root, DCC, "calibrationLaboratory")
		val issuer = lab?.let { first(it, DCC, "contact") }?.let { contact -> first(contact, DCC, "name")?.let { content(it) } }
		val code = lab?.let { text(first(it, DCC, "calibrationLaboratoryCode")) }

		val identifications =
			elements(first(root, DCC, "items"), DCC, "identification").mapNotNull { id ->
				val value = text(first(id, DCC, "value")) ?: return@mapNotNull null
				DccIdentification(text(first(id, DCC, "issuer")), value, first(id, DCC, "name")?.let { content(it) })
			}

		val points = mutableListOf<PointRequest>()
		for (list in elements(first(root, DCC, "measurementResults"), DCC, "list")) {
			val quantities = elements(list, DCC, "quantity").filter { it.parentNode == list }
			fun find(predicate: (String) -> Boolean) = quantities.firstOrNull { predicate((it.getAttribute("refType") ?: "").lowercase()) }
			val reference = find { "referencevalue" in it }?.let { values(it, warnings, absolute = true) } ?: continue
			val indicated = find { "measuredvalue" in it || "indicat" in it }?.let { values(it, warnings, absolute = true) }
			val errorQuantity = find { it.contains("basic_measurementerror") || (it.contains("measurementerror") && !it.contains("pre")) }
			val error = errorQuantity?.let { values(it, warnings, absolute = false) }
			val pre = find { it.contains("precalibration") || it.contains("asfound") }?.let { values(it, warnings, absolute = false) }
			val uncertainty = listOfNotNull(errorQuantity, find { "measuredvalue" in it }).firstNotNullOfOrNull { uncertainty(it) }

			for (i in reference.values.indices) {
				val r = reference.values[i]
				val y = indicated?.values?.getOrNull(i)
				val e = error?.values?.getOrNull(i) ?: y?.let { it - r }
				val (u, k) = uncertainty?.let { (us, ks) -> us.getOrNull(i) to (ks.getOrNull(i) ?: ks.firstOrNull() ?: 2.0) } ?: (null to 2.0)
				points += PointRequest(phase = PointPhase.AS_LEFT, pointIndex = i, referenceValue = r, indicatedValue = y ?: e?.let { r + it }, error = e, expandedU = u, k = k)
				pre?.values?.getOrNull(i)?.let { p ->
					points += PointRequest(phase = PointPhase.AS_FOUND, pointIndex = i, referenceValue = r, indicatedValue = r + p, error = p, expandedU = u, k = k)
				}
			}
		}
		if (points.isEmpty()) warnings += "nessuna tabella di risultati riconosciuta: i punti vanno trascritti a mano"
		return ParsedDcc(number, issuer, code, date, identifications, points, warnings)
	}

	private data class Series(val values: List<Double>, val unit: String?)

	private fun values(quantity: Element, warnings: MutableList<String>, absolute: Boolean): Series? {
		val list = firstDeep(quantity, SI, "valueXMLList")?.let { numbers(it.textContent) }
			?: firstDeep(quantity, SI, "value")?.let { numbers(it.textContent) }
			?: return null
		val unit = (firstDeep(quantity, SI, "unitXMLList") ?: firstDeep(quantity, SI, "unit"))?.textContent?.trim()?.split(Regex("\\s+"))?.firstOrNull()
		if (absolute && unit == "\\kelvin") {
			warnings += "valori in kelvin portati in gradi Celsius"
			return Series(list.map { it - 273.15 }, "\\degreeCelsius")
		}
		return Series(list, unit)
	}

	private fun uncertainty(quantity: Element): Pair<List<Double>, List<Double>>? {
		val u = (firstDeep(quantity, SI, "uncertaintyXMLList") ?: firstDeep(quantity, SI, "valueExpandedMUXMLList") ?: firstDeep(quantity, SI, "uncertainty"))
			?.let { numbers(it.textContent) } ?: return null
		val k = firstDeep(quantity, SI, "coverageFactorXMLList")?.let { numbers(it.textContent) } ?: firstDeep(quantity, SI, "coverageFactor")?.let { numbers(it.textContent) } ?: emptyList()
		return u to k
	}

	private fun numbers(text: String): List<Double> = text.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }

	private fun first(parent: Element?, ns: String, name: String): Element? {
		val nodes = parent?.getElementsByTagNameNS(ns, name) ?: return null
		return if (nodes.length > 0) nodes.item(0) as Element else null
	}

	private fun firstDeep(parent: Element, ns: String, name: String): Element? = first(parent, ns, name)

	private fun elements(parent: Element?, ns: String, name: String): List<Element> {
		val nodes = parent?.getElementsByTagNameNS(ns, name) ?: return emptyList()
		return (0 until nodes.length).map { nodes.item(it) as Element }
	}

	private fun text(element: Element?): String? = element?.textContent?.trim()?.takeIf { it.isNotEmpty() }

	/** Il testo di un elemento a contenuti per lingua: l'inglese, o il primo. */
	private fun content(element: Element): String? {
		val contents = elements(element, DCC, "content")
		return (contents.firstOrNull { it.getAttribute("lang") == "en" } ?: contents.firstOrNull())?.let { text(it) } ?: text(element)
	}
}

/* -------------------------------------------------------------------------------- servizio */

data class DccCandidate(val subjectType: SubjectType, val subjectId: Long, val label: String, val matchedBy: String)

data class DccPreview(
	val parsed: ParsedDcc,
	val signature: Verdict,
	val signer: String?,
	val signatureIssuer: String?,
	val signatureReason: String?,
	val laboratory: Map<String, Any?>?,
	val recognized: Boolean,
	val candidates: List<DccCandidate>,
)

/**
 * L'import del DCC esterno (PIANO_METROLOGIA 5.4): validazione della firma, lettura di
 * identificazioni, risultati e incertezze, abbinamento all'oggetto confermato dall'utente.
 *
 * Con firma valida di un laboratorio riconosciuto il certificato salta la verifica a quattro
 * occhi: non c'e' una trascrizione da controllare. Se all'esemplare manca la funzione, la
 * verifica aspetta CERT_FIT e scatta da sola quando la funzione arriva (`auto_verify_reason`).
 */
@Service
class DccImportService(
	private val certificates: CertificateService,
	private val repository: CertificateRepository,
	private val verification: VerificationService,
	private val labs: LabCompanyRepository,
	private val items: CalibratedItemRepository,
	private val references: ReferenceStandardRepository,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	fun preview(bytes: ByteArray, filename: String?): DccPreview {
		val parsed = DccParser.parse(bytes)
		val signature = verification.verify(bytes, filename)
		val lab = laboratoryOf(parsed)
		return DccPreview(
			parsed = parsed,
			signature = signature.verdict,
			signer = signature.signer,
			signatureIssuer = signature.issuer,
			signatureReason = signature.reason,
			laboratory = lab?.let { mapOf("id" to it.id, "name" to it.name, "accreditationNumber" to it.accreditationNumber, "recognized" to it.recognized) },
			recognized = signature.verdict == Verdict.AUTHENTIC && lab?.recognized == true,
			candidates = candidates(parsed),
		)
	}

	@Transactional
	fun import(bytes: ByteArray, filename: String?, subject: SubjectRef): CertificateDetailDTO {
		val preview = preview(bytes, filename)
		val parsed = preview.parsed
		val number = parsed.number ?: throw BadRequest("Il DCC non dichiara il numero del certificato (uniqueIdentifier)")
		val date = parsed.calibrationDate ?: throw BadRequest("Il DCC non dichiara la data di taratura (beginPerformanceDate)")
		val lab = (preview.laboratory?.get("id") as? Long)?.let { labs.findById(it).orElse(null) }

		val draft = certificates.createExternal(subject, bytes, "application/xml", filename ?: "dcc.xml")
		repository.findById(draft.id).ifPresent { it.source = CertificateSource.DCC_XML; repository.saveAndFlush(it) }
		val entered =
			certificates.enterData(
				draft.id,
				CertificateDataRequest(
					number = number,
					labCompanyId = lab?.id,
					issuer = parsed.issuer ?: lab?.name ?: "Laboratorio del DCC",
					issuerAccreditation = parsed.laboratoryCode,
					calibrationDate = date,
					points = parsed.points,
				),
			)
		audit.record("CERTIFICATE_DCC_IMPORTED", "certificate", entered.id, detail = mapOf("signature" to preview.signature.name, "signer" to preview.signer, "recognized" to preview.recognized, "warnings" to parsed.warnings))

		if (!preview.recognized) return entered
		val certificate = repository.findById(entered.id).orElseThrow()
		val reason = "firma valida del laboratorio riconosciuto ${lab?.name} (${preview.signer})"
		if (certificate.subjectType == SubjectType.REFERENCE || certificate.function != null) {
			certificate.status = CertificateStatus.VERIFIED
			certificate.verifiedBy = "dcc-signature"
			certificate.verifiedAt = OffsetDateTime.now()
			repository.save(certificate)
			audit.record("CERTIFICATE_VERIFIED_BY_SIGNATURE", "certificate", certificate.id, detail = mapOf("reason" to reason))
		} else {
			certificate.autoVerifyReason = reason
			repository.save(certificate)
		}
		return certificates.detail(entered.id)
	}

	/** Il laboratorio del DCC fra quelli registrati: per numero di accreditamento, poi per nome. */
	private fun laboratoryOf(parsed: ParsedDcc): LabCompany? {
		val all = labs.findAll()
		return parsed.laboratoryCode?.let { code -> all.firstOrNull { it.accreditationNumber != null && code.contains(it.accreditationNumber!!, ignoreCase = true) } }
			?: parsed.issuer?.let { name -> all.firstOrNull { it.name.equals(name, ignoreCase = true) } }
	}

	/** Gli oggetti che il DCC potrebbe descrivere, dalle identificazioni: l'utente conferma. */
	private fun candidates(parsed: ParsedDcc): List<DccCandidate> {
		val values = parsed.identifications.map { it.value.trim() }.filter { it.isNotEmpty() }.toSet()
		val out = mutableListOf<DccCandidate>()
		for (item in items.findAll()) {
			if (!policy.canSee(item.ownerSub, item.assignedLabId)) continue
			val euid = "0x%08X".format(item.muExtendedId)
			val bySerial = item.serialNumber?.let { it in values } == true
			val byEuid = values.any { it.equals(euid, ignoreCase = true) || it.equals("$euid:${item.sensorIndex}", ignoreCase = true) }
			if (bySerial || byEuid) {
				out += DccCandidate(SubjectType.ITEM, item.id, "MU $euid sensore ${item.sensorIndex}", if (bySerial) "numero di serie" else "EUID")
			}
		}
		for (reference in references.findAll()) {
			if (reference.serialNumber in values) out += DccCandidate(SubjectType.REFERENCE, reference.id, "campione ${reference.name} (${reference.serialNumber})", "numero di serie")
		}
		return out
	}
}

@RestController
@RequestMapping("/API/metrology/certificates/dcc")
class DccImportController(private val service: DccImportService) {
	@PostMapping("/preview")
	fun preview(@RequestPart("file") file: MultipartFile) = service.preview(file.bytes, file.originalFilename)

	@PostMapping("/import")
	fun import(
		@RequestPart("file") file: MultipartFile,
		@RequestParam(required = false) itemId: Long?,
		@RequestParam(required = false) muExtendedId: Long?,
		@RequestParam(required = false) sensorIndex: Int?,
		@RequestParam(required = false) referenceId: Long?,
	) = service.import(file.bytes, file.originalFilename, SubjectRef(itemId = itemId, muExtendedId = muExtendedId, sensorIndex = sensorIndex, referenceId = referenceId))
}
