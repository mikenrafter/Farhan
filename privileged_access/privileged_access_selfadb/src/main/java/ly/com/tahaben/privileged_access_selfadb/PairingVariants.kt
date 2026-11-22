package ly.com.tahaben.privileged_access_selfadb

/**
 * One way of reaching the pairing port and building the TLS side, with the name the log and the report use for it.
 * [host] replaces the host the caller gave, when set.
 */
internal class PairingVariant(val label: String, val transport: PairingTransport, val host: String? = null)

internal object PairingVariants {
    /**
     * The first is what pairing uses. The others run only after the one before broke before the code was used, and
     * say whether another way in would have got through: the Wi-Fi address instead of loopback, no client
     * certificate, the platform's own TLS provider, and the certificate earlier builds presented. All of them stop
     * before the pairing code is used.
     */
    fun of(identity: AdbIdentity, wifiAddress: String?, nowMillis: Long = System.currentTimeMillis()): List<PairingVariant> {
        val key = identity.privateKey()
        val certificate = identity.certificate()
        fun bundled(present: Boolean = true, cert: java.security.cert.X509Certificate = certificate) =
            ConscryptPairingTransport(ConscryptTls(key, cert, presentCertificate = present))
        return buildList {
            add(PairingVariant("bundled Conscrypt, TLS 1.3, adb certificate", bundled()))
            if (wifiAddress != null) add(PairingVariant("bundled Conscrypt, TLS 1.3, adb certificate, Wi-Fi address instead of 127.0.0.1", bundled(), host = wifiAddress))
            add(PairingVariant("bundled Conscrypt, TLS 1.3, no client certificate", bundled(present = false)))
            add(PairingVariant("platform TLS provider, TLS 1.3, adb certificate", ConscryptPairingTransport(ConscryptTls(key, certificate, standalone = false))))
            add(
                PairingVariant(
                    "bundled Conscrypt, TLS 1.3, previous certificate (CN=farhan, SHA-512)",
                    bundled(cert = LegacyCertificateShape.build(key, identity.publicKey(), nowMillis)),
                ),
            )
        }
    }
}
