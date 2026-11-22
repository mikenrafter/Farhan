package ly.com.tahaben.privileged_access_selfadb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The public key in the form adbd stores for a paired device: Android's `RSAPublicKey` struct for a 2048-bit key
 * (AOSP `libcrypto_utils/android_pubkey.cpp`), base64, then a space, the device name and a NUL. It is what the client
 * sends as its PeerInfo during pairing.
 *
 * Struct, little-endian: u32 words in the modulus (64), u32 `n0inv` = -1 / n[0] mod 2^32, the modulus, `rr` = (2^2048)^2
 * mod n (both 256 bytes) and the u32 exponent.
 */
internal object AndroidPubkey {
    private const val MODULUS_BYTES = 2048 / 8
    private const val STRUCT_BYTES = 3 * 4 + 2 * MODULUS_BYTES

    fun encodeWithName(key: RSAPublicKey, name: String): ByteArray {
        val modulus = key.modulus
        require(modulus.bitLength() in (2048 - 7)..2048) { "adb needs a 2048-bit RSA key" }
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(modulus.mod(two32).modInverse(two32))
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.valueOf(2), modulus)
        val struct = ByteBuffer.allocate(STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_BYTES / 4)
            .putInt(n0inv.toInt())
            .put(littleEndian(modulus))
            .put(littleEndian(rr))
            .putInt(key.publicExponent.toInt())
        return Base64.getEncoder().encode(struct.array()) + " $name\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        val out = ByteArray(MODULUS_BYTES)
        val big = value.toByteArray() // big-endian, maybe with a leading sign byte
        for (index in 0 until MODULUS_BYTES) {
            val source = big.size - 1 - index
            if (source >= 0) out[index] = big[source]
        }
        return out
    }
}
