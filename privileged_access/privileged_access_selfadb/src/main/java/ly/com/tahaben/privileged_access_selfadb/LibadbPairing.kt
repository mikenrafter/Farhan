package ly.com.tahaben.privileged_access_selfadb

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * libadb-android 3.1.1 `AbsAdbConnectionManager` with Farhan's key, used for pairing only. Connecting and running
 * commands is [AdbWireSession]: libadb's stream handling never reports end of input when the daemon closes a stream
 * with output still unread, so a fast command hangs it (observed on an Android 15 emulator).
 */
internal class LibadbPairing(
    private val key: PrivateKey,
    private val certificate: Certificate,
) : AbsAdbConnectionManager() {
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(PAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = DEVICE_NAME

    private companion object {
        const val DEVICE_NAME = "Farhan"
        const val PAIR_TIMEOUT_SECONDS = 20L
    }
}
