package it.polito.measurestream.metrology.reports

import it.polito.measurestream.metrology.certificates.Certificate
import it.polito.measurestream.metrology.certificates.CertificateRepository
import it.polito.measurestream.metrology.certificates.CertificateStatus
import it.polito.measurestream.metrology.certificates.SubjectType
import it.polito.measurestream.metrology.common.AuditLogRepository
import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.control.ControlPoint
import it.polito.measurestream.metrology.control.ControlPointAssignmentRepository
import it.polito.measurestream.metrology.control.ControlPointRepository
import it.polito.measurestream.metrology.control.ControlPointService
import it.polito.measurestream.metrology.control.ExcursionRepository
import it.polito.measurestream.metrology.control.IntermediateCheckRepository
import it.polito.measurestream.metrology.control.NonconformityRepository
import it.polito.measurestream.metrology.control.ScheduleService
import it.polito.measurestream.metrology.documents.DocumentRepository
import it.polito.measurestream.metrology.documents.DocumentService
import it.polito.measurestream.metrology.items.CalibratedItem
import it.polito.measurestream.metrology.items.CalibratedItemRepository
import it.polito.measurestream.metrology.items.ItemService
import it.polito.measurestream.metrology.jobs.contract.ContractJson
import it.polito.measurestream.metrology.security.AccessPolicy
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/* ------------------------------------------------------------------------- impaginazione */

/**
 * Un rapporto PDF scritto con PDFBox, gia' usato per la firma: titolo, coppie chiave-valore,
 * tabelle con colonne a larghezza fissa e cambio pagina automatico. I font standard coprono il
 * WinAnsi (accentate italiane, °, ±): gli altri caratteri si traslitterano.
 */
class PdfReport(private val title: String) {
	private val document = PDDocument()
	private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
	private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
	private val margin = 48f
	private val width = PDRectangle.A4.width - 2 * margin
	private lateinit var stream: PDPageContentStream
	private var y = 0f

	init {
		newPage()
		write(title, bold, 15f)
		write("Generato il ${OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} UTC", font, 8f)
		space(6f)
	}

	private fun newPage() {
		if (this::stream.isInitialized) stream.close()
		val page = PDPage(PDRectangle.A4)
		document.addPage(page)
		stream = PDPageContentStream(document, page)
		y = PDRectangle.A4.height - margin
	}

	private fun ensure(height: Float) {
		if (y - height < margin + 20) newPage()
	}

	private fun clean(text: String, f: PDType1Font): String =
		buildString {
			for (ch in text) {
				val replaced =
					when (ch) {
						'σ' -> "sigma"
						'≤' -> "<="
						'≥' -> ">="
						'√' -> "sqrt"
						'−', '–', '—' -> "-"
						'’', '‘' -> "'"
						'“', '”', '«', '»' -> "\""
						'…' -> "..."
						'ᵀ' -> "T"
						'²' -> "2"
						'Σ' -> "S"
						'Δ' -> "D"
						else -> null
					}
				if (replaced != null) append(replaced)
				else append(if (runCatching { f.encode(ch.toString()) }.isSuccess) ch else '?')
			}
		}

	private fun write(text: String, f: PDType1Font = font, size: Float = 9.5f, x: Float = margin) {
		ensure(size + 4)
		stream.beginText()
		stream.setFont(f, size)
		stream.newLineAtOffset(x, y - size)
		stream.showText(clean(text, f))
		stream.endText()
		y -= size + 4
	}

	fun heading(text: String) {
		space(6f)
		write(text, bold, 11.5f)
	}

	fun line(text: String) {
		// A capo semplice sulle parole, per i testi lunghi (motivi, descrizioni).
		val words = clean(text, font).split(' ')
		var current = StringBuilder()
		for (word in words) {
			val candidate = if (current.isEmpty()) word else "$current $word"
			if (font.getStringWidth(candidate) / 1000 * 9.5f > width && current.isNotEmpty()) {
				write(current.toString())
				current = StringBuilder(word)
			} else {
				current = StringBuilder(candidate)
			}
		}
		if (current.isNotEmpty()) write(current.toString())
	}

	fun pair(key: String, value: Any?) = line("$key: ${value ?: "-"}")

	fun space(height: Float) {
		y -= height
	}

	/** Una tabella: le colonne si dividono la larghezza secondo [weights]; i testi troppo lunghi si troncano. */
	fun table(headers: List<String>, rows: List<List<Any?>>, weights: List<Float> = headers.map { 1f }) {
		val total = weights.sum()
		val widths = weights.map { it / total * width }
		fun row(cells: List<Any?>, f: PDType1Font) {
			ensure(13f)
			var x = margin
			cells.forEachIndexed { i, cell ->
				var text = clean(cell?.toString() ?: "-", f)
				while (text.length > 1 && f.getStringWidth(text) / 1000 * 8f > widths[i] - 4) text = text.dropLast(2) + "…".let { "." }
				stream.beginText()
				stream.setFont(f, 8f)
				stream.newLineAtOffset(x, y - 8f)
				stream.showText(text)
				stream.endText()
				x += widths[i]
			}
			y -= 12f
		}
		row(headers, bold)
		if (rows.isEmpty()) write("(nessuna riga)", font, 8f) else rows.forEach { row(it, font) }
		space(4f)
	}

	fun bytes(): ByteArray {
		stream.close()
		val pages = document.numberOfPages
		document.pages.forEachIndexed { i, page ->
			PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true).use { s ->
				s.beginText()
				s.setFont(font, 7.5f)
				s.newLineAtOffset(margin, margin - 10)
				s.showText(clean("$title - pagina ${i + 1} di $pages", font))
				s.endText()
			}
		}
		return ByteArrayOutputStream().use { out ->
			document.save(out)
			document.close()
			out.toByteArray()
		}
	}
}

/* ------------------------------------------------------------------------------ servizio */

data class ReportRef(val documentId: Long, val sha256: String, val filename: String, val kind: String, val size: Long)

/**
 * I rapporti del sistema qualita' (PIANO_METROLOGIA 5.5): registro del punto di controllo,
 * rapporto di verifica intermedia, stato della flotta, pacchetto per l'ispettore. Ognuno e' un
 * documento con la sua impronta SHA-256, conservato come gli altri e scaricabile da
 * `/API/metrology/documents/{id}`.
 */
@Service
class ReportService(
	private val points: ControlPointRepository,
	private val controlPoints: ControlPointService,
	private val assignments: ControlPointAssignmentRepository,
	private val excursions: ExcursionRepository,
	private val nonconformities: NonconformityRepository,
	private val checks: IntermediateCheckRepository,
	private val items: CalibratedItemRepository,
	private val itemService: ItemService,
	private val certificates: CertificateRepository,
	private val schedule: ScheduleService,
	private val documents: DocumentService,
	private val documentRepository: DocumentRepository,
	private val auditLogs: AuditLogRepository,
	private val policy: AccessPolicy,
	private val audit: AuditService,
) {
	private val day = DateTimeFormatter.ISO_LOCAL_DATE

	private fun store(bytes: ByteArray, mime: String, filename: String, kind: String, owner: String?): ReportRef {
		val caller = policy.caller()
		val document = documents.store(bytes, mime, filename, kind, owner, caller.sub)
		audit.record("REPORT_GENERATED", "document", document.id, detail = mapOf("kind" to kind, "filename" to filename, "sha256" to document.sha256))
		return ReportRef(document.id, document.sha256, filename, kind, bytes.size.toLong())
	}

	/* --------------------------------------------------------- registro del punto di controllo */

	@Transactional
	fun controlPointLog(id: Long, from: OffsetDateTime, to: OffsetDateTime): ReportRef {
		val point = controlPoints.visible(id)
		return store(controlPointPdf(point, from, to), "application/pdf", "registro-punto-${point.id}-${from.format(day)}-${to.format(day)}.pdf", "REPORT_PDF", point.ownerSub)
	}

	private fun controlPointPdf(point: ControlPoint, from: OffsetDateTime, to: OffsetDateTime): ByteArray {
		val spec = point.spec
		val pdf = PdfReport("Registro del punto di controllo «${point.name}»")
		pdf.pair("Periodo", "${from.format(day)} - ${to.format(day)}")
		pdf.pair("Luogo", point.location)
		pdf.pair("Processo", point.process)
		pdf.pair("Profilo", "${point.profileTemplateId} v${point.profileVersion} - ${spec.name ?: ""}")
		pdf.pair("Limiti in vigore", "inferiore ${point.limitLow ?: "-"}, superiore ${point.limitHigh ?: "-"}" + if (spec.climate != null) " (clima storico EN 15757)" else "")
		point.overrides?.let { pdf.pair("Eccezioni del cliente", ContractJson.write(it)) }
		pdf.line("Regola decisionale: banda di guardia U = k·u con k = 2; zona di guardia che apre un'escursione dopo ${spec.guardZoneOpensExcursionAfter ?: "mai"}.")

		pdf.heading("Esemplari assegnati")
		pdf.table(
			listOf("MU", "Sensore", "Metrica", "Dal", "Al"),
			assignments.findAllByControlPointIdOrderByFromTsDesc(point.id)
				.filter { it.fromTs.isBefore(to) && (it.toTs == null || it.toTs!!.isAfter(from)) }
				.map { a ->
					val item = items.findById(a.calibratedItemId).orElse(null)
					listOf(item?.let { "0x%08X".format(it.muExtendedId) }, item?.sensorIndex, a.metric, a.fromTs.format(day), a.toTs?.format(day) ?: "in corso")
				},
			listOf(2f, 1f, 1f, 1.5f, 1.5f),
		)

		val inPeriod = excursions.findAllByControlPointIdOrderByStartedAtDesc(point.id).filter { it.startedAt.isBefore(to) && (it.endedAt == null || it.endedAt!!.isAfter(from)) }
		pdf.heading("Escursioni (${inPeriod.size})")
		pdf.table(
			listOf("Inizio", "Fine", "Tipo", "Lato", "Limite", "Picco", "Grado-min", "Stato"),
			inPeriod.map { listOf(it.startedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16), it.endedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)?.take(16), it.kind, it.side, it.limitValue, "%.3g".format(it.peak), "%.2f".format(it.degreeMinutes), it.state) },
			listOf(2f, 2f, 1.3f, 0.8f, 0.9f, 0.9f, 1f, 1f),
		)

		val ncs = nonconformities.findAll().filter { it.controlPointId == point.id && it.detectedAt.isBefore(to) && it.detectedAt.isAfter(from.minusDays(1)) }
		pdf.heading("Non conformita' di processo (${ncs.size})")
		pdf.table(
			listOf("Rilevata", "Gravita'", "Stato", "Decisione", "Motivo"),
			ncs.map { listOf(it.detectedAt.format(day), it.severity, it.state, it.decision, it.decisionReason) },
			listOf(1.2f, 0.9f, 1.2f, 1.6f, 3f),
		)
		return pdf.bytes()
	}

	/* -------------------------------------------------------- rapporto di verifica intermedia */

	@Transactional
	fun checkReport(checkId: Long): ReportRef {
		val check = checks.findById(checkId).orElseThrow { NotFound("Verifica $checkId non trovata") }
		val item = itemService.get(check.calibratedItemId)
		val pdf = PdfReport("Rapporto di verifica intermedia n. ${check.id}")
		pdf.pair("Esemplare", "MU 0x%08X sensore %d (%s)".format(item.muExtendedId, item.sensorIndex, item.modelName ?: ""))
		pdf.pair("Eseguita il", check.checkedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16))
		pdf.pair("Eseguita da", check.performedBy)
		pdf.pair("Riferimento", check.referenceDescription ?: check.referenceStandardId?.let { "campione $it" })
		pdf.heading("Risultato")
		pdf.table(
			listOf("Riferimento", "U rif. (k)", "Indicazione", "U ind. (k=2)", "Errore", "En", "Tolleranza", "Esito"),
			listOf(listOf(check.referenceValue, "${check.referenceU} (${check.referenceK})", check.indicatedValue, "%.3g".format(check.indicatedU), "%.3g".format(check.error), "%.2f".format(check.en), check.tolerance, check.verdict)),
		)
		pdf.line("En = errore / sqrt(U_ind² + U_rif²) con le incertezze estese a k = 2: |En| <= 1 indica indicazione e riferimento compatibili. Esito: fuori tolleranza FAIL; entro la tolleranza e |En| <= 1 PASS; altrimenti UNDECIDED.")
		check.notes?.let { pdf.pair("Note", it) }
		return store(pdf.bytes(), "application/pdf", "verifica-${check.id}.pdf", "REPORT_PDF", item.ownerSub)
	}

	/* ----------------------------------------------------------------- stato della flotta */

	@Transactional
	fun fleetStatus(): ReportRef = store(fleetPdf(), "application/pdf", "stato-flotta-${OffsetDateTime.now().format(day)}.pdf", "REPORT_PDF", null)

	private fun visibleItems(): List<CalibratedItem> = itemService.visible(null)

	private fun fleetPdf(): ByteArray {
		val pdf = PdfReport("Stato della flotta")
		val entries = schedule.entries()
		val list = visibleItems()
		pdf.pair("Esemplari", list.size)
		pdf.table(
			listOf("Esemplare", "Modello", "Certificato in vigore", "Valido fino al", "Prossima taratura", "Badge", "NC aperte"),
			list.map { item ->
				val current = certificates.findAllBySubjectTypeAndSubjectIdAndStatus(SubjectType.ITEM, item.id, CertificateStatus.EFFECTIVE).firstOrNull()
				val due = entries.firstOrNull { it.subjectType == SubjectType.ITEM && it.subjectId == item.id && it.kind.name == "CALIBRATION" }
				val open = nonconformities.findAll().count { it.calibratedItemId == item.id && it.state.name != "RESOLVED" }
				listOf(
					"0x%08X/%d".format(item.muExtendedId, item.sensorIndex),
					item.modelName,
					current?.let { "${it.number} rev. ${it.revision}" },
					current?.validUntil?.format(day),
					due?.dueAt?.format(day),
					due?.level,
					open,
				)
			},
			listOf(1.6f, 1.6f, 2f, 1.2f, 1.2f, 1f, 0.8f),
		)
		return pdf.bytes()
	}

	/* ------------------------------------------------------------ pacchetto per l'ispettore */

	/**
	 * Tutto cio' che serve a un audit del periodo, in un file ZIP con il manifesto delle impronte:
	 * stato della flotta, registri dei punti di controllo, certificati (DCC e PDF firmati,
	 * originali esterni, dati e punti), verifiche intermedie, escursioni, non conformita',
	 * scadenze e il registro di audit del periodo. Il personale lo prepara per tutto; un cliente
	 * per i suoi oggetti.
	 */
	@Transactional
	fun inspectorPackage(from: OffsetDateTime, to: OffsetDateTime): ReportRef {
		if (!to.isAfter(from)) throw BadRequest("Il periodo e' vuoto")
		val caller = policy.caller()
		val staff = policy.isStaff(caller)
		val itemsInScope = visibleItems()
		val itemIds = itemsInScope.map { it.id }.toSet()
		val pointsInScope = controlPoints.list().mapNotNull { points.findById(it.id).orElse(null) }
		val files = linkedMapOf<String, ByteArray>()

		files["stato-flotta.pdf"] = fleetPdf()
		for (point in pointsInScope) files["punti-di-controllo/${point.id}-${slug(point.name)}.pdf"] = controlPointPdf(point, from, to)

		val certificatesInScope =
			certificates.findAllBySubjectTypeAndSubjectIdIn(SubjectType.ITEM, itemIds)
				.plus(if (staff) certificates.findAll().filter { it.subjectType == SubjectType.REFERENCE } else emptyList())
				.filter { relevant(it, from, to) }
		for (certificate in certificatesInScope) {
			val folder = "certificati/${slug(certificate.number)}-rev${certificate.revision}"
			files["$folder/certificato.json"] = ContractJson.write(certificateSummary(certificate)).toByteArray()
			listOfNotNull(certificate.xmlDocumentId, certificate.pdfDocumentId, certificate.originalDocumentId).forEach { docId ->
				documentRepository.findById(docId).ifPresent { d -> files["$folder/${d.kind.lowercase()}-${d.filename ?: d.id}"] = d.content }
			}
		}

		files["verifiche-intermedie.csv"] =
			csv(
				listOf("id", "esemplare", "eseguita_il", "riferimento", "U_rif", "k_rif", "indicazione", "U_ind", "errore", "En", "tolleranza", "esito", "eseguita_da"),
				itemIds.flatMap { checks.findAllByCalibratedItemIdOrderByCheckedAtAsc(it) }.filter { it.checkedAt.isAfter(from) && it.checkedAt.isBefore(to) }.map {
					listOf(it.id, it.calibratedItemId, it.checkedAt, it.referenceValue, it.referenceU, it.referenceK, it.indicatedValue, it.indicatedU, it.error, it.en, it.tolerance, it.verdict, it.performedBy)
				},
			)
		files["escursioni.csv"] =
			csv(
				listOf("id", "punto_di_controllo", "tipo", "lato", "limite", "inizio", "fine", "picco", "grado_minuti", "valori", "stato", "non_conformita"),
				pointsInScope.flatMap { excursions.findAllByControlPointIdOrderByStartedAtDesc(it.id) }.filter { it.startedAt.isBefore(to) && (it.endedAt == null || it.endedAt!!.isAfter(from)) }.map {
					listOf(it.id, it.controlPointId, it.kind, it.side, it.limitValue, it.startedAt, it.endedAt, it.peak, it.degreeMinutes, it.samples, it.state, it.nonconformityId)
				},
			)
		val ncsInScope = nonconformities.findAll().filter { nc -> (staff || nc.ownerSub == null || policy.canSee(nc.ownerSub, caller = caller)) && nc.detectedAt.isBefore(to) && (nc.decidedAt == null || nc.decidedAt!!.isAfter(from)) }
		files["non-conformita.csv"] =
			csv(
				listOf("id", "tipo", "titolo", "rilevata", "gravita", "stato", "decisione", "motivo", "deciso_da", "deciso_il"),
				ncsInScope.map { listOf(it.id, it.type, it.title, it.detectedAt, it.severity, it.state, it.decision, it.decisionReason, it.decidedBy, it.decidedAt) },
			)
		files["scadenze.csv"] =
			csv(listOf("oggetto", "tipo", "scadenza", "giorni", "badge", "origine"), schedule.entries().map { listOf(it.label, it.kind, it.dueAt, it.daysLeft, it.level, it.source) })

		val entities = mapOf(
			"certificate" to certificatesInScope.map { it.id }.toSet(),
			"calibrated_item" to itemIds,
			"control_point" to pointsInScope.map { it.id }.toSet(),
			"nonconformity" to ncsInScope.map { it.id }.toSet(),
		)
		files["registro-audit.csv"] =
			csv(
				listOf("istante", "utente", "azione", "oggetto", "id", "impronta_prima", "impronta_dopo", "dettaglio"),
				auditLogs.findAllByAtBetweenOrderByAtAsc(from, to)
					.filter { staff || (it.entityId != null && it.entityId in entities[it.entity].orEmpty()) }
					.map { listOf(it.at, it.userSub, it.action, it.entity, it.entityId, it.beforeSha, it.afterSha, it.detail?.let { d -> ContractJson.write(d) }) },
			)
		files["LEGGIMI.txt"] =
			(
				"Pacchetto per l'ispettore, periodo ${from.format(day)} - ${to.format(day)}.\n" +
					"Generato da ${caller.sub} il ${OffsetDateTime.now(ZoneOffset.UTC)}; ambito: ${if (staff) "tutto il laboratorio" else "gli oggetti del cliente"}.\n\n" +
					"manifest.json elenca ogni file con la sua impronta SHA-256. I DCC firmati si verificano\n" +
					"con la pagina di verifica del portale (o POST /API/metrology/verify): la verifica dice anche\n" +
					"se il certificato e' la revisione in vigore.\n"
			).toByteArray()

		val manifest =
			mapOf(
				"period" to mapOf("from" to from, "to" to to),
				"generatedBy" to caller.sub,
				"generatedAt" to OffsetDateTime.now(ZoneOffset.UTC),
				"scope" to if (staff) "LABORATORY" else "CUSTOMER",
				"files" to files.map { (path, bytes) -> mapOf("path" to path, "sha256" to sha256(bytes), "size" to bytes.size) },
			)
		val zip =
			ByteArrayOutputStream().use { out ->
				ZipOutputStream(out).use { z ->
					z.putNextEntry(ZipEntry("manifest.json"))
					z.write(ContractJson.write(manifest).toByteArray())
					z.closeEntry()
					files.forEach { (path, bytes) ->
						z.putNextEntry(ZipEntry(path))
						z.write(bytes)
						z.closeEntry()
					}
				}
				out.toByteArray()
			}
		return store(zip, "application/zip", "pacchetto-ispettore-${from.format(day)}-${to.format(day)}.zip", "INSPECTOR_PACKAGE", if (staff) null else caller.sub)
	}

	private fun relevant(certificate: Certificate, from: OffsetDateTime, to: OffsetDateTime): Boolean {
		if (certificate.status in setOf(CertificateStatus.DRAFT, CertificateStatus.ENTERED)) return false
		val start = certificate.effectiveFrom ?: certificate.calibrationDate?.atStartOfDay()?.atOffset(ZoneOffset.UTC) ?: return false
		val end = certificate.validUntil ?: to
		return start.isBefore(to) && end.isAfter(from)
	}

	private fun certificateSummary(c: Certificate): Map<String, Any?> =
		mapOf(
			"id" to c.id, "number" to c.number, "revision" to c.revision, "kind" to c.kind, "status" to c.status,
			"issuer" to c.issuer, "issuerAccreditation" to c.issuerAccreditation, "calibrationDate" to c.calibrationDate?.toString(),
			"validUntil" to c.validUntil, "effectiveFrom" to c.effectiveFrom, "conformity" to c.conformity, "decisionRule" to c.decisionRule,
			"function" to c.function, "covariance" to c.covariance, "fingerprint" to c.fingerprint,
			"enteredBy" to c.enteredBy, "verifiedBy" to c.verifiedBy, "approvedBy" to c.approvedBy, "signedAt" to c.signedAt,
			"points" to c.points.map { mapOf("phase" to it.phase, "index" to it.pointIndex, "reference" to it.referenceValue, "indicated" to it.indicatedValue, "error" to it.error, "U" to it.expandedU, "k" to it.k) },
		)

	private fun csv(headers: List<String>, rows: List<List<Any?>>): ByteArray =
		buildString {
			append(headers.joinToString(";")).append('\n')
			rows.forEach { row -> append(row.joinToString(";") { (it?.toString() ?: "").replace(";", ",").replace("\n", " ") }).append('\n') }
		}.toByteArray()

	private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/API/metrology/reports")
class ReportController(private val service: ReportService) {
	@PostMapping("/control-point/{id}")
	fun controlPoint(
		@PathVariable id: Long,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: OffsetDateTime,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: OffsetDateTime,
	) = service.controlPointLog(id, from, to)

	@PostMapping("/check/{id}") fun check(@PathVariable id: Long) = service.checkReport(id)

	@PostMapping("/fleet") fun fleet() = service.fleetStatus()

	@PostMapping("/inspector-package")
	fun inspector(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: OffsetDateTime,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: OffsetDateTime,
	) = service.inspectorPackage(from, to)
}
