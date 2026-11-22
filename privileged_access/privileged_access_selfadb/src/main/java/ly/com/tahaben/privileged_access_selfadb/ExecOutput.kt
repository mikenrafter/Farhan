package ly.com.tahaben.privileged_access_selfadb

/**
 * The `exec:` service has no exit status, and stderr shares the stream. So the command is run as
 * `<command> && echo <SENTINEL>`: the sentinel on the last line means the command exited 0. Anything else is a
 * failure, and the text is what the command printed.
 */
internal object ExecOutput {
    const val SENTINEL = "farhan-privileged-ok"

    fun destination(commandLine: String) = "exec:$commandLine && echo $SENTINEL"

    data class Parsed(val exitCode: Int, val output: String)

    fun parse(text: String): Parsed {
        val lines = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        return if (lines.isNotEmpty() && lines.last().trimEnd('\r') == SENTINEL) {
            Parsed(0, lines.dropLast(1).joinToString("\n"))
        } else {
            // The real status is lost with `&&`; 1 means "it did not exit 0".
            Parsed(1, lines.joinToString("\n"))
        }
    }
}
