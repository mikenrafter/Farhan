package ly.com.tahaben.privileged_access_data

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.privileged_access_data.shizuku.ExecRunner
import org.junit.Test

class ExecRunnerTest {
    private val anything: (List<String>) -> Boolean = { true }

    // why: this runs as the shell user inside Shizuku's process; it must run only what Farhan's own commands look like.
    // catches: an allow-list that lets another program or an unsafe word through.
    @Test
    fun `the default rule refuses everything but the connector's own commands`() {
        val runner = ExecRunner()
        assertThat(runner.run(listOf("sh", "-c", "id")).exitCode).isEqualTo(ExecRunner.EXIT_REFUSED)
        assertThat(runner.run(listOf("pm", "grant", "a.b", "android.permission.X;id")).exitCode).isEqualTo(ExecRunner.EXIT_REFUSED)
        assertThat(runner.run(emptyList()).exitCode).isEqualTo(ExecRunner.EXIT_REFUSED)
        // an allowed command is attempted, not refused (appops is not on the build machine, so it simply does not start)
        assertThat(runner.run(listOf("appops", "get", "a.b", "X")).exitCode).isNotEqualTo(ExecRunner.EXIT_REFUSED)
    }

    @Test
    fun `a command's exit code and merged output come back`() {
        val ok = ExecRunner(allowed = anything).run(listOf("echo", "hello"))
        assertThat(ok).isEqualTo(ExecRunner.Outcome(0, "hello\n"))
        val failed = ExecRunner(allowed = anything).run(listOf("sh", "-c", "echo oops 1>&2; exit 3"))
        assertThat(failed).isEqualTo(ExecRunner.Outcome(3, "oops\n"))
    }

    // why: a runaway command must not fill memory or the binder.
    @Test
    fun `output is cut at the limit and the command still finishes`() {
        val outcome = ExecRunner(allowed = anything, maxOutputBytes = 100).run(listOf("head", "-c", "100000", "/dev/zero"))
        assertThat(outcome.exitCode).isEqualTo(0)
        assertThat(outcome.output.length).isEqualTo(100)
    }

    @Test(timeout = 20_000)
    fun `a command that never ends is stopped at the timeout`() {
        val outcome = ExecRunner(allowed = anything, timeoutMillis = 500).run(listOf("sleep", "30"))
        assertThat(outcome.exitCode).isNotEqualTo(0)
    }

    @Test
    fun `a program that does not exist is reported as not started`() {
        assertThat(ExecRunner(allowed = anything).run(listOf("definitely-not-a-program-xyz")).exitCode).isEqualTo(ExecRunner.EXIT_NOT_STARTED)
    }
}
