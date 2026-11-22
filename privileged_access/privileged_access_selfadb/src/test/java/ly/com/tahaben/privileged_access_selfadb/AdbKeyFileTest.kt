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
        assertThat(loaded.certificate().subjectX500Principal.name).isEqualTo("CN=farhan")
    }

    // why: a key retained for years has to outlive a short certificate date.
    @Test
    fun `the certificate is valid for years`() {
        val now = 1_700_000_000_000
        val certificate = IdentityFactory.generate(now).certificate()
        assertThat(certificate.notAfter.time - now).isGreaterThan(3L * 365 * 24 * 3600 * 1000)
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
