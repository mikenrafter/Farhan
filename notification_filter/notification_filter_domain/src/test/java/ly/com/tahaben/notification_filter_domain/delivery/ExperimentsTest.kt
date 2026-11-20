package ly.com.tahaben.notification_filter_domain.delivery

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ExperimentsTest {
    // why: D1 removes the former name and package allowlist throughout the app source.
    // catches: renaming the toggle while leaving an obsolete preference, constant, or package gate elsewhere.
    @Test
    fun `snooze source has no legacy name or fixture gate`() {
        assertThat(Experiments.SNOOZE).isEqualTo("snooze")
        val legacyName = Regex("""snoozed?[ _-]?demo|demo[ _-]?snoozed?|\bSNOOZE_MILLIS\b""", RegexOption.IGNORE_CASE)
        val fixturePackage = Regex(""""ly\.com\.tahaben\.test\.(messenger|storm|social|calendar)"""")
        assertThat(legacyName.containsMatchIn("private fun handleSnoozeDemo()")).isTrue()
        assertThat(fixturePackage.containsMatchIn("""if (name == "ly.com.tahaben.test.calendar")""")).isTrue()
        assertThat(fixturePackage.containsMatchIn(""""ly.com.tahaben.test.messenger.evil"""")).isFalse()

        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
        assertThat(root).isNotNull()
        val project = requireNotNull(root)
        val sources = project.walkTopDown()
            .onEnter { it == project || (it.name !in setOf("build", "research") && !it.name.startsWith(".")) }
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "kts", "properties") }
            .filterNot { it.name == "ExperimentsTest.kt" }
            .toList()

        assertThat(sources.map { it.name }).containsAtLeast("Experiments.kt", "NotificationService.kt", "AutomationSettingsSection.kt")
        val offenders = sources.flatMap { file ->
            val relativePath = file.relativeTo(project)
            val isFixtureModule = relativePath.startsWith(File("testing"))
            file.readLines().mapIndexedNotNull { index, line ->
                if (legacyName.containsMatchIn(line) || (!isFixtureModule && fixturePackage.containsMatchIn(line))) {
                    "$relativePath:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertThat(offenders).isEmpty()
    }

    // why: the snooze event log is bounded and newest first; it never grows without limit while the service runs.
    // catches: an unbounded list; oldest-first order.
    @Test
    fun `the event log keeps the newest twenty`() {
        val log = InMemoryExperimentEventLog()
        val events = (1..25).map {
            ExperimentEvent(
                atEpochMillis = it.toLong(),
                kind = ExperimentEventKind.REMOVED,
                removalReason = it,
                packageName = "com.example.app$it",
            )
        }
        events.forEach(log::record)

        assertThat(log.recent()).hasSize(20)
        assertThat(log.recent().first().atEpochMillis).isEqualTo(25)
        assertThat(log.recent().last().atEpochMillis).isEqualTo(6)
        assertThat(log.recent()).containsExactlyElementsIn(events.takeLast(20).reversed()).inOrder()
    }
}
