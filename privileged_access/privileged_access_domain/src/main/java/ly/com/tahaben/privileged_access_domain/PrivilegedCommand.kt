package ly.com.tahaben.privileged_access_domain

/** The two things Farhan asks the phone to allow. Neither can be granted by tapping a normal permission prompt. */
enum class Access {
    /** Android 15 and later hide a one-time code's text from listeners without this app-op. */
    SENSITIVE_NOTIFICATIONS,

    /** Lets the Grayscale feature write the secure settings that tint the screen. */
    SECURE_SETTINGS,
}

/**
 * The application id a command is for. Only a well-formed id exists as a value, so no other text ever reaches a
 * command line. Production passes `context.packageName`.
 */
@JvmInline
value class AppId private constructor(val value: String) {
    companion object {
        private val SHAPE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun parse(raw: String): AppId? = raw.takeIf { it.length <= 255 && SHAPE.matches(it) }?.let(::AppId)
    }
}

/**
 * One command line as separate words. The only way to make one is [PrivilegedCommand.shellCommand], and every word
 * must be letters, digits, `.` or `_`: nothing a shell treats specially, so no quoting is needed and no untrusted
 * text can add a second command. A shell back end may join [args] with spaces; a process back end passes them as an
 * argument vector.
 */
class ShellCommand private constructor(val args: List<String>) {
    val line: String get() = args.joinToString(" ")

    override fun equals(other: Any?) = other is ShellCommand && other.args == args
    override fun hashCode() = args.hashCode()
    override fun toString() = line

    companion object {
        private val WORD = Regex("[A-Za-z0-9._]{1,255}")

        /** The two programs the connector ever runs. A process that executes commands checks against this. */
        val PROGRAMS: Set<String> = setOf("appops", "pm")

        /** True when [args] could have come from [PrivilegedCommand.shellCommand]: a known program, safe words only. */
        fun isAllowed(args: List<String>): Boolean =
            args.size in 3..8 && args.first() in PROGRAMS && args.all { WORD.matches(it) }

        internal fun of(args: List<String>): ShellCommand {
            require(isAllowed(args)) { "not a connector command" }
            return ShellCommand(args)
        }
    }
}

/**
 * Every command the connector can run, as a closed list. Revoking is a command too and runs over the same link.
 *
 * `appops set ... default` is used to revoke the app-op, not `appops reset <package>`: reset would also wipe every
 * other app-op Farhan holds (usage access, for one).
 */
enum class PrivilegedCommand(val access: Access, val grants: Boolean) {
    GRANT_SENSITIVE_NOTIFICATIONS(Access.SENSITIVE_NOTIFICATIONS, grants = true),
    REVOKE_SENSITIVE_NOTIFICATIONS(Access.SENSITIVE_NOTIFICATIONS, grants = false),
    GRANT_SECURE_SETTINGS(Access.SECURE_SETTINGS, grants = true),
    REVOKE_SECURE_SETTINGS(Access.SECURE_SETTINGS, grants = false);

    fun shellCommand(app: AppId): ShellCommand = ShellCommand.of(
        when (this) {
            GRANT_SENSITIVE_NOTIFICATIONS -> listOf("appops", "set", app.value, APP_OP_SENSITIVE_NOTIFICATIONS, "allow")
            REVOKE_SENSITIVE_NOTIFICATIONS -> listOf("appops", "set", app.value, APP_OP_SENSITIVE_NOTIFICATIONS, "default")
            GRANT_SECURE_SETTINGS -> listOf("pm", "grant", app.value, PERMISSION_WRITE_SECURE_SETTINGS)
            REVOKE_SECURE_SETTINGS -> listOf("pm", "revoke", app.value, PERMISSION_WRITE_SECURE_SETTINGS)
        },
    )

    companion object {
        const val APP_OP_SENSITIVE_NOTIFICATIONS = "RECEIVE_SENSITIVE_NOTIFICATIONS"
        const val PERMISSION_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        fun of(access: Access, grants: Boolean): PrivilegedCommand = entries.first { it.access == access && it.grants == grants }
    }
}
