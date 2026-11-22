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
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Farhan's adb key: an RSA-2048 key and a self-signed certificate. adbd identifies a client by the public key it was
 * paired with and never checks the certificate chain or its dates, so the certificate only has to exist.
 */
class AdbIdentity(val privateKeyPkcs8: ByteArray, val certificateDer: ByteArray, val createdAtMillis: Long) {
    fun privateKey(): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))

    fun certificate(): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate

    // The key must never reach a log or a crash report through string formatting.
    override fun toString() = "AdbIdentity(created=$createdAtMillis)"
}

internal object IdentityFactory {
    fun generate(nowMillis: Long): AdbIdentity {
        val random = SecureRandom()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val subject = X500Name("CN=farhan")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(63, random).add(BigInteger.ONE),
            Date(nowMillis - TimeUnit.MINUTES.toMillis(1)),
            Date(nowMillis + TimeUnit.DAYS.toMillis(3650)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
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
