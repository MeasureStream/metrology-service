package it.polito.measurestream.metrology.signing

import jakarta.annotation.PostConstruct
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** La chiave del laboratorio e il suo certificato, con la catena se c'e'. */
class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate, val chain: List<X509Certificate>, val devOnly: Boolean)

/**
 * La chiave di firma del laboratorio (passo 3.8). Sta in un keystore PKCS#12 montato in sola
 * lettura, con la password in un file a parte: mai nell'immagine, mai nel repository (C13).
 *
 * Senza keystore la firma non e' disponibile, a meno che `dev-self-signed` non chieda una
 * chiave generata all'avvio: vive in memoria, cambia a ogni riavvio e non vale fuori da qui.
 * Serve a provare il ciclo bozza → firma → verifica senza una CA.
 */
@Component
class SigningKeys(
	@Value("\${metrology.signing.keystore:}") private val keystorePath: String,
	@Value("\${metrology.signing.password-file:}") private val passwordFile: String,
	@Value("\${metrology.signing.dev-self-signed:false}") private val devSelfSigned: Boolean,
) {
	private val log = LoggerFactory.getLogger(SigningKeys::class.java)

	@Volatile private var loaded: SigningKey? = null

	val key: SigningKey? get() = loaded

	@PostConstruct
	fun load() {
		if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
		// Un keystore assente, vuoto o illeggibile non deve impedire l'avvio: il servizio parte,
		// la firma risulta non disponibile e l'interfaccia lo dice. Il file vuoto e' il segnaposto
		// che il compose richiede finche' la chiave vera non c'e'.
		val fromFile =
			if (keystorePath.isNotBlank() && File(keystorePath).isFile && File(keystorePath).length() > 0)
				runCatching { fromKeystore() }
					.onFailure { log.error("Keystore di firma {} non leggibile: {}", keystorePath, it.message) }
					.getOrNull()
			else null
		loaded =
			when {
				fromFile != null -> fromFile
				devSelfSigned -> selfSigned().also { log.warn("Firma con una chiave di SVILUPPO generata all'avvio: non usarla per certificati veri") }
				else -> null.also { log.info("Nessuna chiave di firma configurata: la firma dei certificati non e' disponibile") }
			}
	}

	private fun fromKeystore(): SigningKey {
		val password = passwordFile.takeIf { it.isNotBlank() }?.let { File(it).readText().trimEnd('\n', '\r').toCharArray() } ?: CharArray(0)
		val store = KeyStore.getInstance("PKCS12").apply { File(keystorePath).inputStream().use { load(it, password) } }
		val alias = store.aliases().toList().first { store.isKeyEntry(it) }
		val chain = store.getCertificateChain(alias).map { it as X509Certificate }
		log.info("Chiave di firma caricata: {} (valida fino al {})", chain.first().subjectX500Principal.name, chain.first().notAfter)
		return SigningKey(store.getKey(alias, password) as PrivateKey, chain.first(), chain, devOnly = false)
	}

	private fun selfSigned(): SigningKey {
		val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
		val name = X500Name("CN=Laboratorio MeasureStream (sviluppo),O=Politecnico di Torino,C=IT")
		val now = Instant.now()
		val holder =
			JcaX509v3CertificateBuilder(
				name,
				BigInteger.valueOf(now.toEpochMilli()),
				Date.from(now.minus(1, ChronoUnit.DAYS)),
				Date.from(now.plus(365, ChronoUnit.DAYS)),
				name,
				pair.public,
			).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
		val certificate = JcaX509CertificateConverter().getCertificate(holder)
		return SigningKey(pair.private, certificate, listOf(certificate), devOnly = true)
	}
}

/**
 * Le CA di cui ci si fida nella verifica: i certificati della cartella configurata, piu' quello
 * della chiave del laboratorio, cosi' cio' che firmiamo noi risulta sempre «dei nostri».
 */
@Component
class TrustStore(
	@Value("\${metrology.signing.trust-anchors-path:/app/trust}") private val path: String,
	private val keys: SigningKeys,
) {
	private val log = LoggerFactory.getLogger(TrustStore::class.java)
	private val anchors = mutableSetOf<TrustAnchor>()

	@PostConstruct
	fun load() {
		val dir = Path.of(path)
		if (Files.isDirectory(dir)) {
			val factory = CertificateFactory.getInstance("X.509")
			Files.list(dir).use { files ->
				files.filter { it.fileName.toString().matches(Regex(".*\\.(crt|pem|cer)$")) }.forEach { file ->
					runCatching { Files.newInputStream(file).use { anchors.add(TrustAnchor(factory.generateCertificate(it) as X509Certificate, null)) } }
						.onFailure { log.warn("Certificato ignorato {}: {}", file.fileName, it.message) }
				}
			}
		}
		keys.key?.let { anchors.add(TrustAnchor(it.chain.last(), null)) }
		log.info("Trust store: {} certificati di fiducia", anchors.size)
	}

	/** Il certificato risale a una delle nostre CA alla data della firma (non a oggi). */
	fun isTrusted(chain: List<X509Certificate>, at: Date): Boolean {
		if (anchors.isEmpty() || chain.isEmpty()) return false
		if (anchors.any { it.trustedCert == chain.first() }) return true
		return runCatching {
			val path = CertificateFactory.getInstance("X.509").generateCertPath(chain)
			val params = PKIXParameters(anchors).apply {
				isRevocationEnabled = false
				date = at
			}
			CertPathValidator.getInstance("PKIX").validate(path, params)
			true
		}.getOrDefault(false)
	}
}
