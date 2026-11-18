package ly.com.tahaben.farhan_rescue.adb

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import ly.com.tahaben.farhan_rescue.storage.IdentityStore
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * libadb-android 3.1.1 `AbsAdbConnectionManager` with a fixed identity. The three abstract members are
 * `protected abstract PrivateKey getPrivateKey()`, `Certificate getCertificate()`, `String getDeviceName()`
 * [tagged source, AbsAdbConnectionManager.java:44-56]. `setApi`, `setTimeout` and `setThrowOnUnauthorised` are public.
 *
 * CLAIM(no-hidden-api): the certificate comes from BouncyCastle's public builder, never `sun.security`; TLS 1.3 comes
 * from the bundled Conscrypt that `SslUtils` finds by name, so no hidden-API exemption exists anywhere.
 */
internal class RescueAdbManager(
    private val identityKey: PrivateKey,
    private val identityCertificate: Certificate,
) : AbsAdbConnectionManager() {
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // A daemon that has not saved our key rejects the first auth; surface that as "pairing required".
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = identityKey
    override fun getCertificate(): Certificate = identityCertificate
    override fun getDeviceName(): String = DEVICE_NAME

    companion object {
        const val DEVICE_NAME = "Farhan settings rescue"
        private const val CONNECT_TIMEOUT_SECONDS = 30L

        fun restore(identity: IdentityStore.Identity): RescueAdbManager {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(identity.privateKeyPkcs8))
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(identity.certificateDer))
            return RescueAdbManager(key, certificate)
        }
    }
}

/** A software RSA-2048 key and a self-signed certificate valid for one day. */
internal object IdentityFactory {
    fun generate(nowMillis: Long): IdentityStore.Identity {
        val random = SecureRandom()
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }
        val keyPair = generator.generateKeyPair()
        val subject = X500Name("CN=farhan-rescue")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(63, random).add(BigInteger.ONE),
            Date(nowMillis - TimeUnit.MINUTES.toMillis(1)),
            Date(nowMillis + TimeUnit.DAYS.toMillis(1)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return IdentityStore.Identity(keyPair.private.encoded, certificate.encoded, nowMillis)
    }
}
