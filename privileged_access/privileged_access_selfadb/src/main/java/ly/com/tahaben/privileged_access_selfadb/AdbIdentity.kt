package ly.com.tahaben.privileged_access_selfadb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Farhan's adb key: an RSA-2048 key and a self-signed certificate. adbd identifies a client by the public key it was
 * paired with and never checks the certificate chain, so the certificate only has to be well formed. It is built to
 * the shape adb's own client certificate has ([CertificateShape]), the same one the other apps that pair on a Pixel
 * on Android 17 present. [certificate] builds it from the key each time, so a key stored with an older certificate
 * presents the current shape without being re-paired.
 */
class AdbIdentity(val privateKeyPkcs8: ByteArray, val certificateDer: ByteArray, val createdAtMillis: Long) {
    fun privateKey(): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))

    fun storedCertificate(): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate

    fun publicKey(): RSAPublicKey = storedCertificate().publicKey as RSAPublicKey

    fun certificate(): X509Certificate = CertificateShape.build(privateKey(), publicKey())

    // The key must never reach a log or a crash report through string formatting.
    override fun toString() = "AdbIdentity(created=$createdAtMillis)"
}

/**
 * The client certificate adb itself makes (AOSP `adb/crypto/x509_generator.cpp`): subject and issuer `CN=00`, serial 1,
 * valid from 1970 to 2048, signed with SHA-256.
 */
internal object CertificateShape {
    const val SUBJECT = "CN=00"
    const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    const val NOT_BEFORE_MILLIS = 0L
    const val NOT_AFTER_MILLIS = 2_461_449_600_000L

    fun build(privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
        val name = X500Name(SUBJECT)
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date(NOT_BEFORE_MILLIS), Date(NOT_AFTER_MILLIS), name, publicKey,
        )
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}

/** The certificate shape earlier builds presented, kept only as a labelled comparison in the diagnostic. */
internal object LegacyCertificateShape {
    fun build(privateKey: PrivateKey, publicKey: PublicKey, nowMillis: Long): X509Certificate {
        val name = X500Name("CN=farhan")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.TEN, Date(nowMillis - 60_000), Date(nowMillis + 3650L * 24 * 3600 * 1000), name, publicKey,
        )
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA512withRSA").build(privateKey)))
    }
}

internal object IdentityFactory {
    fun generate(nowMillis: Long): AdbIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val certificate = CertificateShape.build(keyPair.private, keyPair.public)
        return AdbIdentity(keyPair.private.encoded, certificate.encoded, nowMillis)
    }
}

/**
 * The one retained adb key, in a single private file (the app's `noBackupFilesDir`, so it is not backed up or
 * transferred). Retaining it is what lets a second access run with no pairing flow; the pairing code that created it
 * is never stored. A file that cannot be read back exactly is deleted and counts as "not paired".
 */
class AdbKeyFile(private val file: File) {
    fun exists(): Boolean = file.isFile

    fun save(identity: AdbIdentity) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(identity.createdAtMillis)
                out.writeInt(identity.privateKeyPkcs8.size); out.write(identity.privateKeyPkcs8)
                out.writeInt(identity.certificateDer.size); out.write(identity.certificateDer)
            }
        }.toByteArray()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(file)) { "could not store the adb key" }
    }

    /** Null when absent or unreadable; an unreadable file is deleted. */
    fun load(): AdbIdentity? {
        if (!file.isFile) return null
        if (file.length() > MAX_FILE_BYTES) { delete(); return null }
        return try {
            DataInputStream(file.readBytes().inputStream()).use { input ->
                check(input.readInt() == MAGIC)
                val created = input.readLong()
                val key = ByteArray(input.readInt().also { check(it in 1..MAX_PART_BYTES) }).also(input::readFully)
                val certificate = ByteArray(input.readInt().also { check(it in 1..MAX_PART_BYTES) }).also(input::readFully)
                check(input.available() == 0)
                AdbIdentity(key, certificate, created)
            }
        } catch (failure: Exception) {
            delete(); null
        }
    }

    fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private companion object {
        const val MAGIC = 0x46414b31 // "FAK1"
        const val MAX_PART_BYTES = 16 * 1024
        const val MAX_FILE_BYTES = 40L * 1024
    }
}
