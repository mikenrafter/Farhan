package ly.com.tahaben.privileged_access_selfadb

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The SPAKE2 step, apart from the cipher it feeds so the cipher can be tested without the native library. */
internal interface PairingSpake2 {
    /** Our message, sent to the daemon. */
    val message: ByteArray

    /** The shared key material once the daemon's message is in, or null when it was refused. */
    fun process(theirMessage: ByteArray): ByteArray?

    fun destroy()
}

/** SPAKE2 from spake2-android (BoringSSL under JNI), in the client role with adb's two fixed names. */
internal class NativeSpake2(password: ByteArray) : PairingSpake2 {
    private val context = Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME)
    override val message: ByteArray = context.generateMessage(password)

    override fun process(theirMessage: ByteArray): ByteArray? = context.processMessage(theirMessage)

    override fun destroy() = context.destroy()

    private companion object {
        // The NUL is part of the name adbd uses (AOSP pairing_auth.cpp).
        val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
    }
}

/**
 * The key and cipher adbd's pairing uses after SPAKE2 (AOSP `pairing_auth/aes_128_gcm.cpp`): HKDF-SHA256 of the SPAKE2
 * key material with no salt and the info string below gives an AES-128-GCM key. Each direction has its own message
 * counter, sent as a 12-byte little-endian nonce, and the 16-byte tag is appended to the ciphertext.
 */
internal class PairingCrypto(private val spake2: PairingSpake2) {
    private var key: SecretKeySpec? = null
    private var encryptCounter = 0L
    private var decryptCounter = 0L

    val message: ByteArray get() = spake2.message

    fun initCipher(theirMessage: ByteArray): Boolean {
        val material = spake2.process(theirMessage) ?: return false
        key = SecretKeySpec(hkdfSha256(material, INFO, KEY_BYTES), "AES")
        return true
    }

    fun encrypt(plain: ByteArray): ByteArray? = apply(Cipher.ENCRYPT_MODE, plain, encryptCounter++)

    /** Null when the tag does not match: the code was wrong. */
    fun decrypt(sealed: ByteArray): ByteArray? = apply(Cipher.DECRYPT_MODE, sealed, decryptCounter++)

    fun destroy() = spake2.destroy()

    private fun apply(mode: Int, input: ByteArray, counter: Long): ByteArray? {
        val secret = key ?: return null
        val nonce = ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, secret, GCMParameterSpec(TAG_BITS, nonce))
        return try {
            cipher.doFinal(input)
        } catch (failure: java.security.GeneralSecurityException) {
            if (mode == Cipher.DECRYPT_MODE) null else throw failure
        }
    }

    companion object {
        private const val KEY_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

        /** RFC 5869 with no salt, for an output of at most one hash block. */
        fun hkdfSha256(material: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length in 1..32)
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
            val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract.doFinal(material), "HmacSHA256")) }
            expand.update(info)
            expand.update(1.toByte())
            return expand.doFinal().copyOf(length)
        }
    }
}
