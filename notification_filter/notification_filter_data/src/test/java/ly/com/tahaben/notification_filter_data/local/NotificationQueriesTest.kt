package ly.com.tahaben.notification_filter_data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.DriverManager

/** Executes the exact SQL constants used by Room. Android driver/runtime remains a device check. */
class NotificationQueriesTest {
    private fun <T> database(block: (java.sql.Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
        db.createStatement().use { it.execute("""CREATE TABLE notificationitementity(id TEXT PRIMARY KEY, appName TEXT, title TEXT, text TEXT, time TEXT, creatorPackage TEXT, timestamp INTEGER, priority INTEGER, category TEXT, isRead INTEGER, channelId TEXT, isCleared INTEGER, isDelivered INTEGER, priorityReason TEXT, sourceSession TEXT NOT NULL DEFAULT '', sourceEventOrder INTEGER NOT NULL DEFAULT 0, deliveryInvalidated INTEGER NOT NULL DEFAULT 0)""") }
        block(db)
    }
    private fun insert(db: java.sql.Connection, id: String, timestamp: Long, priority: Int = 2, cleared: Int = 0, read: Int = 0, pkg: String = "app", title: String = id) {
        db.prepareStatement("INSERT INTO notificationitementity(id,appName,title,text,time,creatorPackage,timestamp,priority,category,isRead,channelId,isCleared,isDelivered,priorityReason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { q ->
            listOf(id, "App", title, "body", "10:00", pkg, timestamp, priority, "OTHER", read, null, cleared, 0, null).forEachIndexed { i, v -> q.setObject(i + 1, v) }
            q.executeUpdate()
        }
    }
    private fun bind(sql: String, arguments: Map<String, Any?>): String =
        Regex(":([A-Za-z]+)").replace(sql) { match ->
            val arg = arguments.getValue(match.groupValues[1])
            when (arg) { null -> "NULL"; is String -> "'" + arg.replace("'", "''") + "'"; is Boolean -> if (arg) "1" else "0"; else -> arg.toString() }
        }
    private fun rows(db: java.sql.Connection, sql: String, arguments: Map<String, Any?>): List<Map<String, Any?>> {
        return db.createStatement().use { statement -> statement.executeQuery(bind(sql, arguments)).use { result -> buildList {
            while (result.next()) add((1..result.metaData.columnCount).associate { result.metaData.getColumnName(it) to result.getObject(it) })
        } } }
    }

    // why: terminal overflow invalidation applies only to ambiguous callbacks from this session and preserves history.
    // catches: invalidating historical sessions, forgetting the order bound, dropping rows or changing delivered/cleared rows.
    @Test fun `invalidation SQL persists only ambiguous undelivered rows in the current session`() = database { db ->
        for (id in listOf("old", "new", "history", "delivered", "cleared")) insert(db, id, 100)
        db.createStatement().use { sql ->
            sql.executeUpdate("UPDATE notificationitementity SET sourceSession='this', sourceEventOrder=1")
            sql.executeUpdate("UPDATE notificationitementity SET sourceEventOrder=3 WHERE id='new'")
            sql.executeUpdate("UPDATE notificationitementity SET sourceSession='prior' WHERE id='history'")
            sql.executeUpdate("UPDATE notificationitementity SET isDelivered=1 WHERE id='delivered'")
            sql.executeUpdate("UPDATE notificationitementity SET isCleared=1 WHERE id='cleared'")
        }
        db.prepareStatement(NotificationQueries.INVALIDATE).use { query ->
            query.setString(1, "this"); query.setLong(2, 2)
            assertThat(query.executeUpdate()).isEqualTo(1)
        }
        val retained = rows(db, "SELECT id, deliveryInvalidated, priorityReason FROM notificationitementity ORDER BY id", emptyMap())
        assertThat(retained).hasSize(5)
        assertThat(retained.filter { (it["deliveryInvalidated"] as Number).toInt() == 1 }.map { it["id"] }).containsExactly("old")
        assertThat(retained.single { it["id"] == "old" }["priorityReason"]).isEqualTo("Not proxied after intake overflow. Open the source app.")
    }

    // why: filtering must precede paging, ties must be stable, and cleared/standing priorities must use identical rules.
    // catches: loading all then filtering, source-priority filtering for standing rows, unbounded result or unstable tie.
    @Test fun `inbox SQL filters before bounded pages and orders ties by stable id`() = database { db ->
        repeat(250) { insert(db, "row%03d".format(it), it.toLong(), cleared = if (it % 2 == 0) 1 else 0) }
        val args = mapOf("cleared" to 0, "priority" to null, "standingCutoff" to 0, "search" to "", "limit" to 3, "offset" to 2)
        assertThat(rows(db, NotificationQueries.INBOX, args).map { it["id"] }).containsExactly("row245", "row243", "row241").inOrder()
        insert(db, "tieA", 1000, title = "needle"); insert(db, "tieB", 1000, title = "needle")
        assertThat(rows(db, NotificationQueries.INBOX, args + mapOf("search" to "needle", "offset" to 0)).map { it["id"] }).containsExactly("tieB", "tieA").inOrder()
        val standing = args + mapOf("priority" to 3, "standingCutoff" to 500, "limit" to 2, "offset" to 0)
        assertThat(rows(db, NotificationQueries.INBOX, standing).map { it["id"] }).containsExactly("row249", "row247").inOrder()
        assertThat(rows(db, NotificationQueries.INBOX, standing + ("cleared" to 1)).map { it["id"] }).containsExactly("row248", "row246").inOrder()
    }

    // why: CLAIM(inbox-clear-membership) covers every page of the selected view at one standing cutoff.
    // catches: deleting the whole inbox, deleting one page, ignoring standing or read status, and including the cutoff boundary.
    @Test fun `scoped clear deletes exactly the ids on all pages of the selected view`() = database { db ->
        val pageOfLow = (0..100).map { "low$it" }
        pageOfLow.forEachIndexed { index, id -> insert(db, id, 600L + index, priority = 4) }
        insert(db, "aged-medium", 499, priority = 3)
        insert(db, "boundary-medium", 500, priority = 3)
        insert(db, "read-medium", 499, priority = 3, read = 1)
        insert(db, "aged-high", 499, priority = 2)
        insert(db, "critical", 499, priority = 1)
        insert(db, "cleared-aged-medium", 499, priority = 3, cleared = 1)
        insert(db, "cleared-high", 501, priority = 2, cleared = 1)

        fun clearView(cleared: Boolean, expected: List<String>) {
            val args = mapOf("cleared" to cleared, "priority" to 4, "standingCutoff" to 500, "search" to "")
            val displayed = listOf(0, 100).flatMap { offset ->
                rows(db, NotificationQueries.INBOX, args + mapOf("limit" to 100, "offset" to offset))
                    .map { it["id"] }
            }
            assertThat(displayed).containsExactlyElementsIn(expected)
            assertThat(rows(db, NotificationQueries.INBOX_CLEAR_IDS, args).map { it["id"] })
                .containsExactlyElementsIn(displayed)

            val allIds = "SELECT id FROM notificationitementity"
            val before = rows(db, allIds, emptyMap()).map { it["id"] }.toSet()
            db.createStatement().use { it.executeUpdate(bind(NotificationQueries.INBOX_CLEAR, args)) }
            val after = rows(db, allIds, emptyMap()).map { it["id"] }.toSet()
            assertThat(after).containsExactlyElementsIn(before - displayed.toSet())
        }

        clearView(cleared = true, expected = listOf("cleared-aged-medium"))
        clearView(cleared = false, expected = pageOfLow + "aged-medium")
    }

    // why: dashboard counts include all retained active history while excluding cleared rows and critical standing.
    // catches: bounded-page summaries, unread/standing mixups, cleared data leakage and full-row output.
    @Test fun `summary SQL aggregates all retained history into bounded counts`() = database { db ->
        repeat(400) { insert(db, "old$it", 10) }
        insert(db, "critical", 10, priority = 1); insert(db, "recent", 1000); insert(db, "cleared", 1000, cleared = 1)
        val summary = rows(db, NotificationQueries.COUNTS, mapOf("dayStart" to 500, "standingCutoff" to 500, "floodStart" to 900)).single()
        assertThat((summary["total"] as Number).toInt()).isEqualTo(402)
        assertThat((summary["today"] as Number).toInt()).isEqualTo(1)
        assertThat((summary["standing"] as Number).toInt()).isEqualTo(400)
        assertThat((summary["unread"] as Number).toInt()).isEqualTo(402)
        assertThat((summary["criticalUnread"] as Number).toInt()).isEqualTo(1)
        assertThat(rows(db, NotificationQueries.CHATTERS, emptyMap())).hasSize(1)
    }

    // why: headers count a batch without carrying item lists; expansion queries only its package/range and page.
    // catches: one-batch-per-app collapse, boundary inclusion on both sides, item materialization, missing limit.
    @Test fun `batch SQL returns bounded headers and loads items on demand`() = database { db ->
        repeat(140) { insert(db, "a%03d".format(it), it.toLong(), pkg = "a") }
        insert(db, "b", 25, pkg = "b"); insert(db, "c", 100, cleared = 1, pkg = "a")
        val headers = rows(db, NotificationQueries.BATCHES, mapOf("windowMillis" to 100, "limit" to 2))
        assertThat(headers).hasSize(2)
        assertThat(headers.map { it["packageName"] }).containsExactly("a", "a").inOrder()
        assertThat(headers.map { (it["count"] as Number).toInt() }).containsExactly(40, 100).inOrder()
        assertThat(headers.first()["title"]).isEqualTo("a139")
        val items = rows(db, NotificationQueries.BATCH_ITEMS, mapOf("packageName" to "a", "start" to 0, "end" to 100, "limit" to 3, "offset" to 1))
        assertThat(items.map { it["id"] }).containsExactly("a098", "a097", "a096").inOrder()
    }
}
