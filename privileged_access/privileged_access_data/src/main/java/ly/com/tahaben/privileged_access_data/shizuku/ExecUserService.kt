package ly.com.tahaben.privileged_access_data.shizuku

import android.content.Context
import android.os.Bundle
import kotlin.system.exitProcess

/**
 * Shizuku's user service: Shizuku starts this class in a process of its own, with shell rights, and Farhan calls it
 * over binder. Shizuku requires a no-argument constructor or one taking a [Context].
 */
class ExecUserService() : IExecService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(argv: Array<out String>?): Bundle {
        val outcome = ExecRunner().run(argv?.toList().orEmpty())
        return Bundle().apply {
            putInt(KEY_EXIT, outcome.exitCode)
            putString(KEY_OUTPUT, outcome.output)
        }
    }

    companion object {
        const val KEY_EXIT = "exit"
        const val KEY_OUTPUT = "output"
    }
}
