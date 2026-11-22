package ly.com.tahaben.notification_filter_presentation.components

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun Long.toNotificationDateLabel(nowMillis: Long = System.currentTimeMillis()): String {
    if (this <= 0L) return ""

    val todayCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStart = todayCal.timeInMillis
    val yesterdayStart = todayStart - 86_400_000L

    val weekStartCal = (todayCal.clone() as Calendar).apply {
        firstDayOfWeek = Calendar.SUNDAY
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
    }
    val weekStart = weekStartCal.timeInMillis

    val date = Date(this)
    return when {
        this >= todayStart -> "Today"
        this >= yesterdayStart -> "Yesterday"
        this >= weekStart -> SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("MMMM d", Locale.getDefault()).format(date)
    }
}

fun Long.toNotificationDateTimeLabel(nowMillis: Long = System.currentTimeMillis()): String {
    if (this <= 0L) return ""
    val dateLabel = toNotificationDateLabel(nowMillis)
    val timeLabel = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(this))
    return "$dateLabel • $timeLabel"
}

/**
 * Builds Inbox date labels for one list refresh. The day boundaries and the time formatter are created once,
 * so labelling a page of rows costs two lookups per row instead of a fresh Calendar and DateFormat each.
 */
class NotificationDateLabeler(
    private val nowMillis: Long = System.currentTimeMillis(),
    private val locale: Locale = Locale.getDefault(),
) {
    private val todayStart = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val yesterdayStart = todayStart - 86_400_000L
    private val weekStart = Calendar.getInstance().apply {
        timeInMillis = todayStart
        firstDayOfWeek = Calendar.SUNDAY
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
    }.timeInMillis
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    private val weekdayFormat = SimpleDateFormat("EEEE", locale)
    private val monthDayFormat = SimpleDateFormat("MMMM d", locale)

    fun time(timestamp: Long): String = if (timestamp <= 0L) "" else timeFormat.format(Date(timestamp))

    fun date(timestamp: Long): String = when {
        timestamp <= 0L -> ""
        timestamp >= todayStart -> "Today"
        timestamp >= yesterdayStart -> "Yesterday"
        timestamp >= weekStart -> weekdayFormat.format(Date(timestamp))
        else -> monthDayFormat.format(Date(timestamp))
    }

    /** "Today • 10:39", the same text the row showed before. */
    fun dateTime(timestamp: Long): String =
        if (timestamp <= 0L) "" else "${date(timestamp)} • ${time(timestamp)}"

    /** Today's messages need only the clock; older ones keep their day. */
    fun compact(timestamp: Long): String =
        if (timestamp >= todayStart) time(timestamp) else dateTime(timestamp)
}
