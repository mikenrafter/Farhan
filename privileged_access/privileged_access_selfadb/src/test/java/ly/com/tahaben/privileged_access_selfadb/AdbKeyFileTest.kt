package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbKeyFileTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = AdbKeyFile(File(folder.root, "sub/adb_key"))

    // proves: the key is retained between runs, which is what lets a second access skip pairing.
    @Test
    fun `a saved key reads back exactly and is usable`() {
        val keys = file()
        assertThat(keys.exists()).isFalse()
        assertThat(keys.load()).isNull()
        val identity = IdentityFactory.generate(1_700_000_000_000)

        keys.save(identity)
        val loaded = keys.load()!!

        assertThat(keys.exists()).isTrue()
        assertThat(loaded.privateKeyPkcs8).isEqualTo(identity.privateKeyPkcs8)
        assertThat(loaded.certificateDer).isEqualTo(identity.certificateDer)
        assertThat(loaded.createdAtMillis).isEqualTo(1_700_000_000_000)
        assertThat(loaded.privateKey().algorithm).isEqualTo("RSA")
        assertThat(loaded.certificate().subjectX500Principal.name).isEqualTo("CN=00")
    }

    // why: the TLS client certificate is the one thing that differs from the apps that pair on an Android 17 Pixel.
    // catches: a certificate with another subject, hash or dates than adb's own.
    @Test
    fun `the certificate has the shape adb's own client certificate has`() {
        val certificate = IdentityFactory.generate(1_700_000_000_000).certificate()
        assertThat(certificate.subjectX500Principal.name).isEqualTo("CN=00")
        assertThat(certificate.issuerX500Principal.name).isEqualTo("CN=00")
        assertThat(certificate.serialNumber).isEqualTo(java.math.BigInteger.ONE)
        assertThat(certificate.sigAlgName).isEqualTo("SHA256withRSA")
        assertThat(certificate.notBefore.time).isEqualTo(0L)
        assertThat(certificate.notAfter.time).isEqualTo(2_461_449_600_000L)
        certificate.verify(certificate.publicKey)
    }

    // why: a key stored by an earlier build carries a CN=farhan, SHA-512 certificate and must not need re-pairing.
    // catches: presenting the stored certificate unchanged.
    @Test
    fun `a key stored with an older certificate presents the current shape`() {
        val fresh = IdentityFactory.generate(1_700_000_000_000)
        val old = org.bouncycastle.asn1.x500.X500Name("CN=farhan").let { name ->
            val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                name, java.math.BigInteger.TEN, java.util.Date(1), java.util.Date(2), name, fresh.publicKey(),
            )
            val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA512withRSA").build(fresh.privateKey())
            org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(signer)).encoded
        }
        val identity = AdbIdentity(fresh.privateKeyPkcs8, old, 1)
        assertThat(identity.certificate().subjectX500Principal.name).isEqualTo("CN=00")
        assertThat(identity.certificate().publicKey).isEqualTo(fresh.certificate().publicKey)
    }

    @Test
    fun `deleting removes the key`() {
        val keys = file()
        keys.save(IdentityFactory.generate(1))
        keys.delete()
        assertThat(keys.exists()).isFalse()
        assertThat(keys.load()).isNull()
    }

    // why: a half-written or edited file must count as "not paired", not crash the status check.
    @Test
    fun `a damaged file is deleted and reads as not paired`() {
        val keys = file()
        keys.save(IdentityFactory.generate(1))
        val path = File(folder.root, "sub/adb_key")
        path.writeBytes(path.readBytes().copyOf(40))
        assertThat(keys.load()).isNull()
        assertThat(path.exists()).isFalse()
    }

    @Test
    fun `a file with extra bytes is refused`() {
        val keys = file()
        keys.save(IdentityFactory.generate(1))
        val path = File(folder.root, "sub/adb_key")
        path.appendBytes(byteArrayOf(1, 2, 3))
        assertThat(keys.load()).isNull()
    }

    @Test
    fun `the key never prints`() {
        assertThat(IdentityFactory.generate(5).toString()).isEqualTo("AdbIdentity(created=5)")
    }
}
