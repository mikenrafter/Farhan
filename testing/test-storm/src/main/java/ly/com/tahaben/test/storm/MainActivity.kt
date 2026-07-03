package ly.com.tahaben.test.storm

import android.Manifest
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

    private val CHANNEL_ID = "test_storm"
    private var notifId = 400
    private var stormCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Test Storm"
        createChannel()
        requestPermission()
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(header("Flood detection — threshold: 10 notifications in 10 minutes"))
        root.addView(header("All notifications: MEDIUM priority / OTHER category"))
        root.addView(btn("Send 5  (below flood threshold)") {
            burst(5)
        })
        root.addView(btn("Send 11 — trigger flood!") {
            // 11 >= 10 threshold → isFlooding = true in dashboard
            burst(11)
        })
        root.addView(btn("Send 20 — stress test") {
            burst(20)
        })

        root.addView(space())
        root.addView(header("Health score impact (expected after flood trigger):"))
        root.addView(header("  healthScore -= 25  (flood penalty)"))
        root.addView(header("  + additional penalty for unread CRITICAL items if present"))

        root.addView(space())
        root.addView(header("Standing alarms — send now, leave unread 24h to become 'standing'"))
        root.addView(btn("Send 5 background tasks (mark unread, wait 24h)") {
            val labels = listOf(
                "Backup completed", "Sync finished", "Download done",
                "Update available", "Cache cleared"
            )
            labels.forEachIndexed { i, label ->
                bg(i * 300L) {
                    notify("$label", "Tap to dismiss — or leave unread to test standing alarm threshold.")
                }
            }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun burst(count: Int) {
        repeat(count) { i ->
            bg(i * 80L) {
                val n = ++stormCount
                notify("Storm #$n", "Flood test — rapid burst notification")
            }
        }
    }

    private fun notify(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(notifId++, n)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Storm", NotificationManager.IMPORTANCE_DEFAULT)
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
        setPadding(0, 12, 0, 2)
        setTextColor(0xFF777777.toInt())
    }

    private fun space() = Space(this).apply { minimumHeight = 32 }

    private fun bg(delayMs: Long, block: () -> Unit) {
        Thread { Thread.sleep(delayMs); runOnUiThread(block) }.start()
    }
}
