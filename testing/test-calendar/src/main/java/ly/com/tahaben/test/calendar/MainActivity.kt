package ly.com.tahaben.test.calendar

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

    private val CHANNEL_ID = "test_calendar"
    private var notifId = 300

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Test Calendar"
        createChannel()
        requestPermission()
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(header("HIGH priority / PRODUCTIVITY — intercepted by Farhan"))
        root.addView(btn("Event starting soon  [HIGH / PRODUCTIVITY]") {
            notify("Team standup", "Meeting starts in 15 minutes — Room 3B",
                Notification.CATEGORY_EVENT)
        })
        root.addView(btn("Reminder  [HIGH / PRODUCTIVITY]") {
            notify("Dentist appointment", "Tomorrow at 2:00 PM — don't forget to confirm.",
                Notification.CATEGORY_REMINDER)
        })
        root.addView(btn("3 Reminders burst (200 ms apart)") {
            val items = listOf(
                Pair("Take medication", "Daily 8:00 AM reminder"),
                Pair("Call mum", "Weekly reminder — due today"),
                Pair("Submit timesheet", "Due by 5 PM today")
            )
            items.forEachIndexed { i, (title, body) ->
                bg(i * 200L) { notify(title, body, Notification.CATEGORY_REMINDER) }
            }
        })

        root.addView(space())
        root.addView(header("MEDIUM priority edge cases"))
        root.addView(btn("Navigation update  [MEDIUM / HEALTH]") {
            // 'navigation' maps to HEALTH category in ClassifyNotification
            notify("Maps", "Turn right in 200 m onto Main St.", "navigation")
        })
        root.addView(btn("No category set  [MEDIUM / OTHER]") {
            notify("Test Calendar", "Uncategorized notification — null category fallback",
                null)
        })

        root.addView(space())
        root.addView(header("Pass-through (NOT intercepted by Farhan)"))
        root.addView(btn("Service notification  [stays in status bar]") {
            notify("Test Calendar", "Syncing calendar data…", Notification.CATEGORY_SERVICE)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun notify(title: String, text: String, category: String?) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .apply { category?.let { setCategory(it) } }
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(notifId++, n)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Calendar", NotificationManager.IMPORTANCE_DEFAULT)
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
