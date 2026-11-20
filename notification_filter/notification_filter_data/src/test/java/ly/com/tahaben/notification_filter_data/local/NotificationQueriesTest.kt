package ly.com.tahaben.notification_filter_data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.DriverManager

/** Executes the exact SQL constants used by Room. Android driver/runtime remains a device check. */
class NotificationQueriesTest {
    private fun <T> database(block: (java.sql.Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
        db.createStatement().use { it.execute("""CREATE TABLE notificationitementity(id TEXT PRIMARY KEY, appName TEXT, title TEXT, text TEXT, time TEXT, creatorPackage TEXT, timestamp INTEGER, priority INTEGER, category TEXT, isRead INTEGER, channelId TEXT, isCleared INTEGER, isDelivered INTEGER, priorityReason TEXT, sourceSession TEXT NOT NULL DEFAULT '', sourceEventOrder INTEGER NOT NULL DEFAULT 0, deliveryInvalidated INTEGER NOT NULL DEFAULT 0, snoozeDeadline INTEGER, snoozePriority INTEGER, snoozeBootCount INTEGER)""") }
        block(db)
    }
    private fun insert(db: java.sql.Connection, id: String, timestamp: Long, priority: Int = 2, cleared: Int = 0, read: Int = 0, pkg: String = "app", title: String = id) {
        db.prepareStatement("INSERT INTO notificationitementity(id,appName,title,text,time,creatorPackage,timestamp,priority,category,isRead,channelId,isCleared,isDelivered,priorityReason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { q ->
            listOf(id, "App", title, "body", "10:00", pkg, timestamp, priority, "OTHER", read, null, cleared, 0, null).forEachIndexed { i, v -> q.setObject(i + 1, v) }
            q.executeUpdate()
        }
    }
    private fun rows(db: java.sql.Connection, sql: String, arguments: Map<String, Any?>): List<Map<String, Any?>> {
        val bound = Regex(":([A-Za-z]+)").replace(sql) { match ->
            val arg = arguments.getValue(match.groupValues[1])
            when (arg) { null -> "NULL"; is String -> "'" + arg.replace("'", "''") + "'"; is Boolean -> if (arg) "1" else "0"; else -> arg.toString() }
        }
        return db.createStatement().use { statement -> statement.executeQuery(bound).use { result -> buildList {
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

    // why: a package whose only pending row is natively snoozed cannot head a batch group.
    // catches: grouping on future snoozes before choosing app heads.
    @Test fun `pending groups exclude packages whose rows still have snooze deadlines`() = pendingSnoozes { db ->
        assertThat(rows(db, NotificationQueries.PENDING_GROUPS, mapOf("now" to 1000L, "maxPriority" to 4))
            .map { it["packageName"] }).containsExactly("mixed")
    }

    // why: a future snooze must not hide an ordinary pending row from the app-head query.
    // catches: applying the snooze filter after ordering and LIMIT 1.
    @Test fun `pending head selects the ordinary row over a newer snoozed row`() = pendingSnoozes { db ->
        val arguments = mapOf("now" to 1000L, "packageName" to "mixed", "priority" to 2, "oldest" to false)
        assertThat(rows(db, NotificationQueries.PENDING_HEAD, arguments).map { it["id"] }).containsExactly("ordinary")
    }

    // why: ungrouped priority reads must leave future native snoozes in Android's custody too.
    // catches: fixing the grouped pending queries while still leaking rows from the full priority query.
    @Test fun `pending priority query excludes any nonnull snooze deadline`() = pendingSnoozes { db ->
        assertThat(rows(db, NotificationQueries.PENDING_UP_TO_PRIORITY, mapOf("now" to 1000L, "priorityLevel" to 4))
            .map { it["id"] }).containsExactly("ordinary")
    }

    // why: the row step must atomically close custody so a restarted scan cannot charge it again.
    // catches: using markDelivered alone, forgetting one snooze column, or changing an unrelated row.
    @Test fun `return completion atomically clears all snooze columns on only the returned row`() = pendingSnoozes { db ->
        db.prepareStatement(NotificationQueries.COMPLETE_SNOOZE_RETURN).use { query ->
            query.setString(1, "hiddenHead")
            assertThat(query.executeUpdate()).isEqualTo(1)
        }
        val after = rows(db, "SELECT id, isDelivered, snoozeDeadline, snoozePriority, snoozeBootCount FROM notificationitementity", emptyMap())
            .associateBy { it["id"] }
        val completed = after.getValue("hiddenHead")
        assertThat((completed["isDelivered"] as Number).toInt()).isEqualTo(1)
        assertThat(completed["snoozeDeadline"]).isNull()
        assertThat(completed["snoozePriority"]).isNull()
        assertThat(completed["snoozeBootCount"]).isNull()
        val untouched = after.getValue("hiddenOnly")
        assertThat((untouched["isDelivered"] as Number).toInt()).isEqualTo(0)
        assertThat((untouched["snoozeDeadline"] as Number).toLong()).isEqualTo(2000L)
    }

    // why: the durable custody query must agree with pre-checks and ignore invalidated rows.
    // catches: rehydrating a cleared, delivered, invalidated, or ordinary row as an owned key.
    @Test fun `live snoozes excludes closed and invalidated custody`() = pendingSnoozes { db ->
        db.createStatement().use { sql ->
            sql.executeUpdate("UPDATE notificationitementity SET isCleared=1 WHERE id='hiddenHead'")
            sql.executeUpdate("UPDATE notificationitementity SET isDelivered=1 WHERE id='onlyAtDeadline'")
            sql.executeUpdate("UPDATE notificationitementity SET deliveryInvalidated=1 WHERE id='onlyPastDeadline'")
        }
        assertThat(rows(db, NotificationQueries.LIVE_SNOOZES, emptyMap()).map { it["id"] })
            .containsExactly("hiddenOnly", "atDeadline", "pastDeadline")
    }

    private fun pendingSnoozes(block: (java.sql.Connection) -> Unit) = database { db ->
        insert(db, "ordinary", 100, pkg = "mixed")
        insert(db, "hiddenHead", 200, pkg = "mixed")
        insert(db, "hiddenOnly", 300, pkg = "hidden")
        insert(db, "atDeadline", 400, pkg = "mixed")
        insert(db, "onlyAtDeadline", 600, pkg = "deadline-now")
        insert(db, "onlyPastDeadline", 700, pkg = "deadline-past")
        insert(db, "pastDeadline", 500, pkg = "mixed")
        db.createStatement().use { sql ->
            sql.executeUpdate("UPDATE notificationitementity SET snoozeDeadline=2000, snoozePriority=2, snoozeBootCount=10 WHERE id LIKE 'hidden%'")
            sql.executeUpdate("UPDATE notificationitementity SET snoozeDeadline=1000 WHERE id IN ('atDeadline', 'onlyAtDeadline')")
            sql.executeUpdate("UPDATE notificationitementity SET snoozeDeadline=999 WHERE id IN ('pastDeadline', 'onlyPastDeadline')")
        }
        block(db)
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
