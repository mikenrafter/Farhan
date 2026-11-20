package ly.com.tahaben.notification_filter_data.sound

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class SoundControlSourceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull {
        it.resolve("settings.gradle.kts").isFile
    }

    private val setters = Regex(
        """\b(setRingerMode|setInterruptionFilter|setNotificationPolicy|requestInterruptionFilter|requestListenerHints)\b""",
    )
    private val assignments = Regex("""\b(ringerMode|interruptionFilter|notificationPolicy)\s*=(?!=)""")

    private fun forbiddenUses(source: String): List<String> =
        setters.findAll(source).map { it.value }.toList() + assignments.findAll(source).map { it.groupValues[1] }.toList()

    // why: v2.8 covers Android setters even when a Kotlin caller uses a synthetic property or reference.
    // catches: a scan missing any named setter, checking only qualified calls, or matching a read as a write.
    @Test
    fun `source scanner detects each forbidden API in a positive fixture`() {
        val fixture = """
            audio.setRingerMode(2)
            manager.setInterruptionFilter(0)
            manager.setNotificationPolicy(policy)
            requestInterruptionFilter(0)
            val listenerSetter = this::requestListenerHints
            audio.ringerMode = 2
            manager.interruptionFilter = 0
            manager.notificationPolicy = policy
            check(audio.ringerMode == 2)
        """.trimIndent()
        assertThat(forbiddenUses(fixture)).containsExactly(
            "setRingerMode", "setInterruptionFilter", "setNotificationPolicy", "requestInterruptionFilter",
            "requestListenerHints", "ringerMode", "interruptionFilter", "notificationPolicy",
        ).inOrder()
    }

    // why: v2.8 prohibits process-wide sound setters across all shipped modules and variants.
    // catches: a new call from a different module or a DEBUG-only behavior outside the future experiment package.
    @Test
    fun `shipped Kotlin and Java never use global sound setters outside timed unmute`() {
        assertThat(root).isNotNull()
        val checkout = root ?: return
        val sources = checkout.walkTopDown()
            .onEnter { it.name !in setOf(".git", ".gradle", ".idea", "build", "research") }
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter {
                val parts = it.relativeTo(checkout).invariantSeparatorsPath.split('/')
                val sourceSet = parts.getOrNull(parts.indexOf("src") + 1).orEmpty()
                "src" in parts && !sourceSet.startsWith("test", ignoreCase = true) &&
                    !sourceSet.contains("androidTest", ignoreCase = true)
            }.toList()
        assertThat(sources.map { it.name }).containsAtLeast("NotificationService.kt", "MainActivity.kt")

        val experimentPackage = Regex(
            """(?m)^\s*package\s+ly\.com\.tahaben\.notification_filter_data\.sound\.experiment(?:\.[\w.]+)?[;\s]""",
        )
        val hits = sources.flatMap { file ->
            val source = file.readText()
            if (experimentPackage.containsMatchIn(source)) emptyList()
            else forbiddenUses(source).map { "${file.relativeTo(checkout)} → $it" }
        }
        assertThat(hits).isEmpty()
    }

    // why: v2.9 keeps the synchronous intent store out of legacy backups, cloud and device transfer.
    // catches: applying an exclusion to only one transfer mode, or using the wrong domain or prefs filename.
    @Test
    fun `sound journal is excluded from every Android backup path`() {
        assertThat(root).isNotNull()
        val checkout = root ?: return
        val xml = checkout.resolve("app/src/main/res/xml")
        val factory = DocumentBuilderFactory.newInstance()
        val legacy = factory.newDocumentBuilder().parse(xml.resolve("backup_rules.xml")).documentElement
        val current = factory.newDocumentBuilder().parse(xml.resolve("data_extraction_rules.xml")).documentElement

        fun exclusions(parent: Element?): List<String> {
            val nodes = parent?.getElementsByTagName("exclude") ?: return emptyList()
            return (0 until nodes.length).map {
                val node = nodes.item(it) as Element
                "${node.getAttribute("domain")}:${node.getAttribute("path")}"
            }
        }

        val journal = "sharedpref:sound_control.xml"
        assertThat(legacy.tagName).isEqualTo("full-backup-content")
        assertThat(current.tagName).isEqualTo("data-extraction-rules")
        assertThat(
            listOf(
                exclusions(legacy), exclusions(current.getElementsByTagName("cloud-backup").item(0) as? Element),
                exclusions(current.getElementsByTagName("device-transfer").item(0) as? Element),
            ).map { journal in it },
        ).containsExactly(true, true, true)
    }
}
