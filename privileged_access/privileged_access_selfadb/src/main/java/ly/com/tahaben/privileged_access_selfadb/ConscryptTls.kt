// Adapted from the settings rescue helper's adb/ConscryptTls.kt (settings_rescue is an application module with internal classes,
// so it cannot be called from here). Keep the two in step until both move to one shared library.
package ly.com.tahaben.privileged_access_selfadb

import org.conscrypt.Conscrypt
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 for wireless debugging, from the bundled Conscrypt (no hidden platform API).
 *
 * adbd identifies a client by the RSA public key in the certificate it presents, matched against the keys saved at
 * pairing; it never checks a certificate chain. So the key manager serves exactly the paired identity, and the trust
 * manager accepts the daemon's self-signed certificate: the daemon is on this phone's loopback and was authenticated
 * by the pairing code, not by PKI (the platform `adb` client does the same). A different key than the paired one
 * fails the handshake with a certificate-unknown alert, which [AdbWireSession.connect] reports as "pairing required".
 */
internal class ConscryptTls(
    privateKey: PrivateKey,
    certificate: X509Certificate,
    /** False builds the context from the platform's own provider, to compare it with the bundled one in a diagnostic. */
    standalone: Boolean = true,
    /** False sends no client certificate, to see whether the daemon wants one. */
    presentCertificate: Boolean = true,
) : TlsUpgrade {
    private val context: SSLContext = (if (standalone) SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()) else SSLContext.getInstance("TLSv1.3")).apply {
        init(if (presentCertificate) arrayOf<KeyManager>(SingleKeyManager(privateKey, certificate)) else null, arrayOf<TrustManager>(AcceptDaemon), SecureRandom())
    }

    override fun upgrade(plain: Socket, host: String, port: Int): SSLSocket =
        context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
}

/** One RSA client identity under a fixed alias. */
internal class SingleKeyManager(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyTypes?.contains("RSA") == true) ALIAS else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType == "RSA") ALIAS else null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null

    private companion object { const val ALIAS = "adb" }
}

private object AcceptDaemon : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
