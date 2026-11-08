package dev.avijit.orbit.server

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date

/** The server's TLS identity. [fingerprintSha256] is what phones pin (SHA-256 of the DER cert, lowercase hex). */
class TlsMaterial(val keyStore: KeyStore, val alias: String, val password: CharArray, val fingerprintSha256: String) {
    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate
}

/**
 * Self-signed TLS identity, created on first run and reused afterwards.
 *
 * Identity policy: the certificate is **kept** even when a later run asks for host names it does not cover
 * (a new `--host`). Regenerating would change the fingerprint and break every paired phone, so that only
 * happens with an explicit `regenerate` (`--regenerate-cert`); otherwise a warning names the missing hosts.
 * Phones pin the fingerprint rather than checking host names, so a missing SAN only affects other clients.
 */
object Tls {
    const val KEYSTORE_FILE = "tls.p12"
    const val KEYSTORE_PW_FILE = "tls.pass"
    const val ALIAS = "orbit-server"
    val BUILT_IN_HOSTS = listOf("localhost", "127.0.0.1", "10.0.2.2")
    private val VALIDITY_YEARS = 10L
    private val log = LoggerFactory.getLogger(Tls::class.java)
    private val random = SecureRandom()

    fun loadOrCreate(home: ServerHome, hostnames: List<String>, regenerate: Boolean = false): TlsMaterial {
        val wanted = (BUILT_IN_HOSTS + listOfNotNull(machineHostname()) + hostnames)
            .map { normalise(it) }.filter { it.isNotEmpty() }.distinct()
        val ksPath = home.file(KEYSTORE_FILE)
        val pwPath = home.file(KEYSTORE_PW_FILE)
        if (!regenerate && Files.exists(ksPath) && Files.exists(pwPath)) {
            home.makePrivate(ksPath)
            home.makePrivate(pwPath)
            val password = Files.readString(pwPath).trim().toCharArray()
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(ksPath).use { ks.load(it, password) }
            val material = material(ks, password)
            val missing = wanted - subjectAltNames(material).toSet()
            if (missing.isNotEmpty()) {
                log.warn(
                    "TLS certificate does not cover {}. Keeping the existing identity so paired phones keep working. " +
                        "To include them, rerun with --regenerate-cert (this changes the fingerprint; every phone must re-pair).",
                    missing,
                )
            }
            return material
        }
        if (regenerate && Files.exists(ksPath)) {
            log.warn("Regenerating the TLS certificate: the fingerprint changes and every paired phone must re-pair.")
        }
        val password = randomPassword()
        val ks = create(wanted, password)
        // Password file first so a crash never leaves a keystore nobody can open.
        home.writePrivate(KEYSTORE_PW_FILE, String(password).toByteArray(Charsets.US_ASCII))
        val out = ByteArrayOutputStream()
        ks.store(out, password)
        home.writePrivate(KEYSTORE_FILE, out.toByteArray())
        return material(ks, password)
    }

    /** DNS names and IP addresses in the certificate's subjectAltName extension. */
    fun subjectAltNames(material: TlsMaterial): List<String> =
        material.certificate.subjectAlternativeNames.orEmpty().map { it[1].toString().lowercase() }

    /** The certificate (public part only) as PEM. */
    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
            "\n-----END CERTIFICATE-----\n"

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Best effort; never blocks startup on a slow resolver beyond the JDK's own lookup. */
    fun machineHostname(): String? = cachedHostname

    private val cachedHostname: String? by lazy {
        System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun material(ks: KeyStore, password: CharArray): TlsMaterial {
        val cert = ks.getCertificate(ALIAS) as? X509Certificate
            ?: error("keystore has no '$ALIAS' certificate; delete it or run with --regenerate-cert")
        return TlsMaterial(ks, ALIAS, password, fingerprint(cert))
    }

    private fun create(hosts: List<String>, password: CharArray): KeyStore {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = kpg.generateKeyPair()
        val now = Instant.now()
        val notBefore = now.minus(Duration.ofMinutes(5)) // tolerate small clock skew between server and phone
        val notAfter = ZonedDateTime.ofInstant(notBefore, ZoneOffset.UTC).plusYears(VALIDITY_YEARS).toInstant()
        val subject = X500Name("CN=Orbit Server,O=Orbit Health (self-hosted)")
        val serial = BigInteger(127, random).add(BigInteger.ONE)
        val sans = GeneralNames(hosts.map { h ->
            if (isIpLiteral(h)) GeneralName(GeneralName.iPAddress, h) else GeneralName(GeneralName.dNSName, h)
        }.toTypedArray())
        val builder = JcaX509v3CertificateBuilder(subject, serial, Date.from(notBefore), Date.from(notAfter), subject, kp.public)
            .addExtension(Extension.subjectAlternativeName, false, sans)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, password, arrayOf(cert))
        return ks
    }

    private fun randomPassword(): CharArray {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }

    /** Lower-cases names; writes IP literals in the JDK's canonical form so they compare equal to SAN entries. */
    private fun normalise(h: String): String {
        val t = h.trim().lowercase().removePrefix("[").removeSuffix("]")
        return if (isIpLiteral(t)) runCatching { InetAddress.getByName(t).hostAddress }.getOrDefault(t) else t
    }

    private fun isIpLiteral(h: String) = HostNames.isIpLiteral(h)
}
