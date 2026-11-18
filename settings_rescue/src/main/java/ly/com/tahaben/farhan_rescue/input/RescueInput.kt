package ly.com.tahaben.farhan_rescue.input

/** Why an input was refused. Never carries the input itself (a pairing code must not reach any message). */
enum class InputError {
    PACKAGE_EMPTY_OR_MALFORMED,
    PACKAGE_IS_HELPER,
    USER_MALFORMED,
    PORT_MALFORMED,
    PAIRING_CODE_MALFORMED,
    HOST_NOT_LOOPBACK,
    JOB_ID_MALFORMED,
}

sealed interface Checked<out T> {
    data class Ok<T>(val value: T) : Checked<T>
    data class Rejected(val error: InputError) : Checked<Nothing>
}

/** Value or null. For callers that only branch on validity. */
fun <T> Checked<T>.orNull(): T? = (this as? Checked.Ok)?.value

/**
 * An Android application id: dotted segments, each starting with a letter. The UI and the bridge both parse with
 * this type, so a value that reaches a shell command line has already passed.
 *
 * CLAIM(package-shape): regex `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$`, at most 255 characters, never
 * the helper's own id (the helper must not read itself) [RescueInputTest].
 */
@JvmInline
value class PackageName private constructor(val value: String) {
    companion object {
        const val DEFAULT = "ly.com.tahaben.farhan_alpha"
        const val HELPER = "ly.com.tahaben.farhan_rescue"
        private const val MAX_LENGTH = 255
        private val SHAPE = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

        fun parse(text: String, helperPackage: String = HELPER): Checked<PackageName> = when {
            text.length > MAX_LENGTH || !SHAPE.matches(text) -> Checked.Rejected(InputError.PACKAGE_EMPTY_OR_MALFORMED)
            text == helperPackage -> Checked.Rejected(InputError.PACKAGE_IS_HELPER)
            else -> Checked.Ok(PackageName(text))
        }
    }
}

/** Android user id, nonnegative. 0 is the personal profile; a work profile or second user has another id. */
@JvmInline
value class AndroidUserId private constructor(val value: Int) {
    companion object {
        const val DEFAULT = 0
        private val DIGITS = Regex("[0-9]{1,9}")

        fun parse(text: String): Checked<AndroidUserId> =
            if (DIGITS.matches(text)) Checked.Ok(AndroidUserId(text.toInt())) else Checked.Rejected(InputError.USER_MALFORMED)
    }
}

/** A TCP port, 1..65535, digits only. */
@JvmInline
value class TcpPort private constructor(val value: Int) {
    companion object {
        private val DIGITS = Regex("[0-9]{1,5}")

        fun parse(text: String): Checked<TcpPort> {
            if (!DIGITS.matches(text)) return Checked.Rejected(InputError.PORT_MALFORMED)
            val number = text.toInt()
            return if (number in 1..65535) Checked.Ok(TcpPort(number)) else Checked.Rejected(InputError.PORT_MALFORMED)
        }
    }
}

/**
 * The six-digit wireless-debugging pairing code. CLAIM(code-redacted): [toString] never shows the digits, so no
 * log line, exception message or data-class dump can leak it [RescueInputTest]. It is exposed only through [reveal],
 * which the bridge calls once, at the pairing call.
 */
class PairingCode private constructor(private val digits: String) {
    fun reveal(): String = digits
    override fun toString(): String = "PairingCode(******)"
    override fun equals(other: Any?): Boolean = other is PairingCode && other.digits == digits
    override fun hashCode(): Int = digits.hashCode()

    companion object {
        private val SIX_DIGITS = Regex("[0-9]{6}")

        fun parse(text: String): Checked<PairingCode> =
            if (SIX_DIGITS.matches(text)) Checked.Ok(PairingCode(text)) else Checked.Rejected(InputError.PAIRING_CODE_MALFORMED)
    }
}

/**
 * The only two addresses the helper will ever dial. CLAIM(loopback-only): there is no other constructor, so a
 * non-loopback address cannot reach the ADB client [RescueInputTest].
 */
enum class LoopbackHost(val address: String) {
    IPV4("127.0.0.1"),
    IPV6("::1");

    companion object {
        fun parse(text: String): Checked<LoopbackHost> =
            entries.firstOrNull { it.address == text }?.let { Checked.Ok(it) } ?: Checked.Rejected(InputError.HOST_NOT_LOOPBACK)
    }
}

/** The installed Farhan to read, as the owner chose it. */
data class SourceTarget(val packageName: PackageName, val user: AndroidUserId)

/** Random 128-bit identifier of one capture. Used as a directory name, so only `[0-9a-f]{32}` is accepted. */
@JvmInline
value class JobId private constructor(val value: String) {
    companion object {
        private val HEX = Regex("[0-9a-f]{32}")

        fun parse(text: String): Checked<JobId> =
            if (HEX.matches(text)) Checked.Ok(JobId(text)) else Checked.Rejected(InputError.JOB_ID_MALFORMED)

        fun random(random: java.security.SecureRandom = java.security.SecureRandom()): JobId {
            val bytes = ByteArray(16).also(random::nextBytes)
            return JobId(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}
