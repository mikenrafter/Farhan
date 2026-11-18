package ly.com.tahaben.test.messenger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val CHANNEL_ID = "test_messages"
    private var notifId = 100
    private var lastNotifId: Int? = null
    private var revision = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Test Messenger"
        createChannel()
        requestPermission()
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(header("HIGH priority / COMMUNICATION — intercepted by Farhan"))
        root.addView(btn("Send 1 Message  [HIGH / COMM]") {
            notify("Alice", "Hey, are you free tonight?", Notification.CATEGORY_MESSAGE)
        })
        root.addView(btn("Send 5 Messages burst (200 ms apart)") {
            val from = listOf("Bob", "Carol", "Dave", "Eve", "Frank")
            val body = listOf(
                "What time is the meeting?",
                "Did you see the game last night?",
                "Running 10 mins late, sorry!",
                "Can you review my PR?",
                "Lunch tomorrow?"
            )
            from.forEachIndexed { i, sender ->
                bg(i * 200L) { notify(sender, body[i], Notification.CATEGORY_MESSAGE) }
            }
        })
        root.addView(btn("Send Email  [HIGH / COMM]") {
            notify("Your boss", "Please review the attached report before the meeting.",
                Notification.CATEGORY_EMAIL)
        })
        root.addView(btn("Update / repost latest message (same ID)") {
            lastNotifId?.let { id ->
                notify("Alice — update ${++revision}", "Updated synthetic message", Notification.CATEGORY_MESSAGE, id)
            }
        })
        root.addView(btn("Cancel latest notification in source app") {
            lastNotifId?.let { NotificationManagerCompat.from(this).cancel(it) }
        })
        root.addView(btn("Cancel all source notifications") {
            NotificationManagerCompat.from(this).cancelAll()
        })

        root.addView(space())
        root.addView(header("Pass-through (NOT intercepted — stays in status bar)"))
        root.addView(btn("Incoming Call  [pass-through]") {
            notify("Alice", "Incoming call…", Notification.CATEGORY_CALL)
        })
        root.addView(btn("Missed Call  [pass-through]") {
            notify("Bob", "Missed call", Notification.CATEGORY_MISSED_CALL)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun notify(title: String, text: String, category: String?, id: Int = notifId++) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .apply { category?.let { setCategory(it) } }
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(id, n)
        lastNotifId = id
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun requestPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setPadding(0, 24, 0, 4)
        setTextColor(0xFF777777.toInt())
    }

    private fun space() = Space(this).apply { minimumHeight = 32 }

    private fun bg(delayMs: Long, block: () -> Unit) {
        Thread { Thread.sleep(delayMs); runOnUiThread(block) }.start()
    }
}
