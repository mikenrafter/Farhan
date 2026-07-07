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
