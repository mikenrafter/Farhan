package ly.com.tahaben.test.social

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

    private val CHANNEL_ID = "test_social"
    private var notifId = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Test Social"
        createChannel()
        requestPermission()
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(header("LOW priority / SOCIAL — all intercepted by Farhan"))
        root.addView(btn("Social notification  [LOW / SOCIAL]") {
            notify("Test Social", "@you were mentioned in a comment",
                Notification.CATEGORY_SOCIAL)
        })
        root.addView(btn("3 Social burst (200 ms apart)  [LOW / SOCIAL]") {
            val texts = listOf(
                "@tech_user liked your photo",
                "3 people reacted to your post",
                "@friend started following you"
            )
            texts.forEachIndexed { i, t ->
                bg(i * 200L) { notify("Test Social", t, Notification.CATEGORY_SOCIAL) }
            }
        })

        root.addView(space())
        root.addView(header("LOW priority / SOCIAL (promo & recommendation)"))
        root.addView(btn("Promo  [LOW / SOCIAL]") {
            notify("Test Social", "Flash sale! 50% off all orders today only.",
                Notification.CATEGORY_PROMO)
        })
        root.addView(btn("Recommendation  [LOW / SOCIAL]") {
            // 'recommendation' string — NotificationCategory maps to SOCIAL
            notify("Test Social", "Based on your history: try this new feature!",
                "recommendation")
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun notify(title: String, text: String, category: String?) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(title)
            .setContentText(text)
            .apply { category?.let { setCategory(it) } }
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(notifId++, n)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Social Updates", NotificationManager.IMPORTANCE_DEFAULT)
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
