package ly.com.tahaben.privileged_access_domain

/**
 * The part of a self-ADB pairing or connection that stopped. [tag] is the stable name logged under `FarhanSelfAdb`
 * and the name to search for in a logcat.
 */
enum class SelfAdbStep(val tag: String) {
    DISCOVERY("discovery"),
    CONNECT_SOCKET("connect-socket"),
    TLS_HANDSHAKE("tls-handshake"),

    /** The first read after the handshake. TLS 1.3 shows a refused client certificate here, not in the handshake. */
    FIRST_READ("first-read"),
    EXPORT_KEYING_MATERIAL("export-keying-material"),
    SPAKE2("spake2"),
    PEER_INFO("peer-info"),
    CONNECT("connect"),

    /** Making the adb key and its certificate, before any socket is opened. */
    KEY_SETUP("key-setup"),
}

/**
 * Where a self-ADB attempt broke and what broke it. [exceptionClass] is the simple class name. A message can carry an
 * address or a port, so it travels only in [detail], and only for [networkDenied], where it is the operating system's
 * own refusal text. Nothing here holds a code, key or command output.
 */
data class StepFailure(
    val step: SelfAdbStep,
    val exceptionClass: String,
    val detail: String? = null,
    /** The operating system refused Farhan a socket (EPERM): its network access is off. */
    val networkDenied: Boolean = false,
) {
    companion object {
        /** The class name of [failure] without its package, or "Throwable" for an anonymous class. */
        fun of(step: SelfAdbStep, failure: Throwable): StepFailure {
            val name = failure.javaClass.name.substringAfterLast('.').substringAfterLast('$').ifBlank { "Throwable" }
            val refusal = NetworkDenial.refusalText(failure)
            return StepFailure(step, name, refusal, networkDenied = refusal != null)
        }
    }
}

/**
 * Recognises the operating system saying "this app may not use the network": a socket call that fails with EPERM.
 * It happens when a per-app network toggle is off (GrapheneOS's Network permission, for one) even though the manifest
 * asks for INTERNET.
 */
object NetworkDenial {
    private const val MAX_TEXT = 160
    private val REFUSAL = Regex("EPERM|Operation not permitted", RegexOption.IGNORE_CASE)

    /** True when [failure] or any cause is an EPERM or "Operation not permitted" socket failure. */
    fun isRefusal(failure: Throwable?): Boolean = refusalText(failure) != null

    /** The refusal's own message, shortened, or null when [failure] is something else. */
    fun refusalText(failure: Throwable?): String? {
        var current = failure
        var depth = 0
        while (current != null && depth < 8) {
            val message = current.message
            if (message != null && REFUSAL.containsMatchIn(message)) return message.replace('\n', ' ').trim().take(MAX_TEXT)
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return null
    }

    /** The Android name of the failed discovery start, which has no exception behind it. */
    const val NSD_START_FAILED = "NsdStartDiscoveryFailed"
}
