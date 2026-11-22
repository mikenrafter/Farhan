package ly.com.tahaben.privileged_access_selfadb

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** The cipher and key encoding pairing relies on. SPAKE2 itself is native and is checked against a real adbd. */
class PairingCryptoTest {
    private fun hex(text: String) = BigInteger(text, 16).toByteArray().let { bytes ->
        // BigInteger drops leading zeros and may add a sign byte; restore the length the text has.
        val want = text.length / 2
        ByteArray(want) { index -> bytes.getOrElse(bytes.size - want + index) { 0 } }
    }

    // why: the AES key comes from this derivation. catches: a wrong salt, info or output length, which would make
    // every pairing end in a bad tag. Vector: RFC 5869 appendix A.3 (zero-length salt), first 16 of the 42 bytes.
    @Test
    fun `hkdf matches the RFC 5869 vector without a salt`() {
        val material = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val out = PairingCrypto.hkdfSha256(material, ByteArray(0), 16)
        // A.3: OKM = 8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8
        assertThat(out).isEqualTo(hex("8da4e775a563c18f715f802a063c5a31"))
    }

    private class FixedSpake2(override val message: ByteArray, private val material: ByteArray?) : PairingSpake2 {
        override fun process(theirMessage: ByteArray) = material
        override fun destroy() = Unit
    }

    private fun pair(material: ByteArray) =
        PairingCrypto(FixedSpake2(byteArrayOf(1), material)).also { assertThat(it.initCipher(byteArrayOf(2))).isTrue() }

    // why: each direction counts its own messages, so the peer's second message only opens with the second nonce.
    @Test
    fun `what one side seals the other side opens in order`() {
        val material = ByteArray(64) { it.toByte() }
        val sender = pair(material)
        val receiver = pair(material)
        val first = sender.encrypt("one".toByteArray())!!
        val second = sender.encrypt("two".toByteArray())!!
        assertThat(String(receiver.decrypt(first)!!)).isEqualTo("one")
        assertThat(String(receiver.decrypt(second)!!)).isEqualTo("two")
    }

    // why: a wrong code gives different key material; the tag check is what tells the client it was refused.
    @Test
    fun `a different key does not open the message`() {
        val sealed = pair(ByteArray(64) { 1 }).encrypt("secret".toByteArray())!!
        assertThat(pair(ByteArray(64) { 2 }).decrypt(sealed)).isNull()
    }

    @Test
    fun `an answer that fails SPAKE2 leaves the cipher unset`() {
        val crypto = PairingCrypto(FixedSpake2(byteArrayOf(1), null))
        assertThat(crypto.initCipher(byteArrayOf(2))).isFalse()
        assertThat(crypto.encrypt(byteArrayOf(1))).isNull()
    }

    // why: adbd stores exactly this text. catches: a wrong struct layout, endianness or n0inv, any of which pairs and
    // then fails every connection.
    @Test
    fun `the adb public key has the layout adbd stores`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val text = String(AndroidPubkey.encodeWithName(key, "Farhan"), Charsets.ISO_8859_1)
        assertThat(text.endsWith(" Farhan\u0000")).isTrue()
        val struct = Base64.getDecoder().decode(text.substringBefore(' '))
        assertThat(struct.size).isEqualTo(524)
        fun word(at: Int) = (struct[at].toLong() and 0xff) or ((struct[at + 1].toLong() and 0xff) shl 8) or
            ((struct[at + 2].toLong() and 0xff) shl 16) or ((struct[at + 3].toLong() and 0xff) shl 24)
        assertThat(word(0)).isEqualTo(64)
        val modulus = BigInteger(1, struct.copyOfRange(8, 264).reversedArray())
        assertThat(modulus).isEqualTo(key.modulus)
        assertThat((word(4) * (modulus.toLong() and 0xffffffffL)) and 0xffffffffL).isEqualTo(0xffffffffL) // n0inv * n[0] = -1
        val rr = BigInteger(1, struct.copyOfRange(264, 520).reversedArray())
        assertThat(rr).isEqualTo(BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), key.modulus))
        assertThat(word(520)).isEqualTo(65537)
    }
}
