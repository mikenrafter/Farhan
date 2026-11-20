package ly.com.tahaben.farhan.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnoozeMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, FarhanDatabase::class.java)

    // why: the automatic migration must preserve a v7 inbox notification as ordinary pending work.
    // catches: requiring a column default, losing the original payload, or stranding old rows outside pending queries.
    @Test
    fun pendingNotificationSurvivesMigrationFrom7To8() = runBlocking<Unit> {
        val name = "snooze-migration-7-8"
        val context = instrumentation.targetContext
        context.deleteDatabase(name)
        try {
            helper.createDatabase(name, 7).use { db ->
                db.execSQL(
                    "INSERT INTO NotificationItemEntity (id, appName, title, text, time, creatorPackage, priority) " +
                        "VALUES ('legacy', 'Chat', 'Title', 'Body', '10:00', 'com.chat', 2)",
                )
            }
            helper.runMigrationsAndValidate(name, 8, true).close()
            val db = Room.databaseBuilder(context, FarhanDatabase::class.java, name).build()
            try {
                val dao = db.notificationDao
                val old = dao.find("legacy")
                assertThat(old?.title).isEqualTo("Title")
                assertThat(old?.text).isEqualTo("Body")
                assertThat(old?.snoozeDeadline).isNull()
                assertThat(old?.snoozePriority).isNull()
                assertThat(old?.snoozeBootCount).isNull()
                assertThat(dao.pendingGroups(4).map { it.packageName }).containsExactly("com.chat")
                assertThat(dao.pendingHead("com.chat", 2, false)?.id).isEqualTo("legacy")
                assertThat(dao.getUndeliveredNotificationsUpToPriority(4).map { it.id }).containsExactly("legacy")
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
