package ly.com.tahaben.notification_filter_data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import ly.com.tahaben.core.R
import ly.com.tahaben.notification_filter_domain.delivery.PostResult
import ly.com.tahaben.notification_filter_domain.inbox.OtpCodeExtractor

/**
 * Tag prefix of the code notifications. Deliberately not a prefix-extension of [PROXY_TAG] ("farhan:"), so every
 * `startsWith(PROXY_TAG)` cleanup and removal mapping in [NotificationService] leaves these alone.
 */
internal const val CODE_TAG = "farhan-code:"
internal fun codeTag(sourceId: String) = CODE_TAG + sourceId

/** Remembers the code last raised for each source, so an update that repeats it does not raise it again. */
internal class RaisedCodes(private val capacity: Int = 32) {
    private val last = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    @Synchronized fun contains(sourceId: String, code: String): Boolean = last[sourceId] == code
    @Synchronized fun remember(sourceId: String, code: String) { last[sourceId] = code }
}

/**
 * Raises a one-time code as its own Farhan notification. The code is visible in the shade and hidden on the lock
 * screen, the Copy action puts it on the clipboard through [OtpCopyReceiver], and nothing here ever copies on its
 * own or cancels the notification. It is independent of the source notification: clearing or removing that one
 * leaves this one, and swiping this one away touches no Inbox row (it is not a proxy and has no source key).
 */
internal object VerificationCodeNotifier {
    const val CHANNEL_ID = "farhan_verification_codes"
    const val TIMEOUT_MS = 10 * 60 * 1000L
    private const val NOTIFICATION_ID = 2
    private val raised = RaisedCodes()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Verification codes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "One-time codes Farhan found in your notifications, with a Copy action"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    fun post(context: Context, sourceId: String, appName: String?, code: String): PostResult {
        if (raised.contains(sourceId, code)) return PostResult.Posted
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return PostResult.Failed("notification_permission_denied")
        }
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return PostResult.Failed("notifications_disabled")
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return PostResult.Failed("notification_channel_disabled")
        }
        manager.notify(codeTag(sourceId), NOTIFICATION_ID, build(context, sourceId, appName, code))
        raised.remember(sourceId, code)
        return PostResult.Posted
    }

    private fun build(context: Context, sourceId: String, appName: String?, code: String): android.app.Notification {
        val copy = Intent(context, OtpCopyReceiver::class.java)
            .setAction(OtpCopyReceiver.ACTION_COPY)
            .setData(Uri.Builder().scheme("farhan-code").authority("copy").appendPath(sourceId).build())
            .putExtra(OtpCopyReceiver.EXTRA_CODE, OtpCodeExtractor.copyForm(code))
        val copyIntent = PendingIntent.getBroadcast(context, 0, copy, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hidden = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_farhan_transparent)
            .setContentTitle("Verification code")
            .setContentText("Unlock to see the code")
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_farhan_transparent)
            .setContentTitle("Verification code")
            .setContentText(code)
            .setSubText(appName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            // A re-sent code replaces the earlier one in the same slot and must still alert.
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .addAction(0, "Copy", copyIntent)
            .build()
    }
}
