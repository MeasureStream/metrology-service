package it.polito.measurestream.metrology.signing

import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.documents.Document
import it.polito.measurestream.metrology.documents.DocumentRepository
import it.polito.measurestream.metrology.documents.DocumentService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Calendar
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.apache.xml.security.Init
import org.apache.xml.security.algorithms.MessageDigestAlgorithm
import org.apache.xml.security.signature.XMLSignature
import org.apache.xml.security.transforms.Transforms
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.springframework.stereotype.Service

/**
 * Firma i documenti di un certificato interno (passo 3.8): XMLDSig avvolta nel DCC, con
 * Apache Santuario come in `DccSigningService` di Mario, e firma CAdES staccata nel PDF con
 * PDFBox al posto di iText (AGPL).
 *
 * **Non e' un sigillo qualificato eIDAS**: la chiave e' del laboratorio, non di un prestatore
 * qualificato. Lo dice l'interfaccia accanto a ogni firma.
 */
@Service
class SigningService(
	private val keys: SigningKeys,
	private val documents: DocumentService,
	private val repository: DocumentRepository,
) {
	init {
		Init.init()
	}

	fun available(): Boolean = keys.key != null

	fun devOnly(): Boolean = keys.key?.devOnly == true

	/** Firma l'XML del documento [documentId] e ne conserva la copia firmata come documento nuovo. */
	fun signXml(documentId: Long, ownerSub: String?, by: String): Document {
		val source = repository.findById(documentId).orElseThrow { Conflict("Documento $documentId non trovato") }
		return documents.store(signXmlBytes(source.content), "application/xml", signedName(source.filename, "xml"), "DCC_XML_SIGNED", ownerSub, by)
	}

	fun signPdf(documentId: Long, ownerSub: String?, by: String): Document {
		val source = repository.findById(documentId).orElseThrow { Conflict("Documento $documentId non trovato") }
		return documents.store(signPdfBytes(source.content), "application/pdf", signedName(source.filename, "pdf"), "CERTIFICATE_PDF_SIGNED", ownerSub, by)
	}

	/** Firma XMLDSig avvolta (enveloped) sull'intero documento, con il certificato nel KeyInfo. */
	fun signXmlBytes(xml: ByteArray): ByteArray {
		val key = keys.key ?: throw Conflict("La chiave di firma del laboratorio non e' configurata")
		val doc = secureFactory().newDocumentBuilder().parse(ByteArrayInputStream(xml))
		val signature = XMLSignature(doc, "", XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256)
		doc.documentElement.appendChild(signature.element)
		val transforms =
			Transforms(doc).apply {
				addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE)
				addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS)
			}
		signature.addDocument("", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256)
		signature.addKeyInfo(key.certificate)
		signature.sign(key.privateKey)

		val out = ByteArrayOutputStream()
		TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}.transform(DOMSource(doc), StreamResult(out))
		return out.toByteArray()
	}

	/** Firma CAdES staccata (ETSI.CAdES.detached) in un salvataggio incrementale del PDF. */
	fun signPdfBytes(pdf: ByteArray): ByteArray {
		val key = keys.key ?: throw Conflict("La chiave di firma del laboratorio non e' configurata")
		Loader.loadPDF(pdf).use { document ->
			val signature =
				PDSignature().apply {
					setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
					setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
					name = key.certificate.subjectX500Principal.name
					reason = "Certificato di taratura emesso dal laboratorio"
					signDate = Calendar.getInstance()
				}
			val options = SignatureOptions().apply { preferredSignatureSize = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2 }
			options.use {
				document.addSignature(signature, SignatureInterface { content -> cms(content.readAllBytes()) }, it)
				val out = ByteArrayOutputStream()
				document.saveIncremental(out)
				return out.toByteArray()
			}
		}
	}

	/** La busta CMS con l'attributo signing-certificate-v2, che lega la firma al certificato. */
	private fun cms(content: ByteArray): ByteArray {
		val key = keys.key!!
		val certHash = MessageDigest.getInstance("SHA-256").digest(key.certificate.encoded)
		val signingCertificate = SigningCertificateV2(arrayOf(ESSCertIDv2(certHash)))
		val attributes =
			AttributeTable(ASN1EncodableVector().apply { add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(signingCertificate))) })
		val signerInfo =
			JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
				.setSignedAttributeGenerator(DefaultSignedAttributeTableGenerator(attributes))
				.build(JcaContentSignerBuilder("SHA256withRSA").build(key.privateKey), key.certificate)
		val generator =
			CMSSignedDataGenerator().apply {
				addSignerInfoGenerator(signerInfo)
				addCertificates(JcaCertStore(key.chain))
			}
		// DER, non la codifica BER a lunghezza indefinita che BouncyCastle usa di default: e' cio'
		// che PAdES richiede, e cio' che i lettori di PDF si aspettano dentro /Contents.
		return generator.generate(CMSProcessableByteArray(content), false).getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER)
	}

	private fun signedName(filename: String?, extension: String): String =
		(filename?.substringBeforeLast('.') ?: "certificato") + "-firmato.$extension"

	companion object {
		/** Parser XML senza DTD ne' entita' esterne: un DCC caricato da fuori non e' fidato. */
		fun secureFactory(): DocumentBuilderFactory =
			DocumentBuilderFactory.newInstance().apply {
				isNamespaceAware = true
				setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
				setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
				setFeature("http://xml.org/sax/features/external-general-entities", false)
				setFeature("http://xml.org/sax/features/external-parameter-entities", false)
				isXIncludeAware = false
				isExpandEntityReferences = false
			}
	}
}
