package ly.com.tahaben.notification_filter_domain.testsupport

import ly.com.tahaben.notification_filter_domain.delivery.DeliveryTimeContext
import ly.com.tahaben.notification_filter_domain.model.NotificationCategory
import ly.com.tahaben.notification_filter_domain.model.NotificationItem
import ly.com.tahaben.notification_filter_domain.model.NotificationPriority
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** A window by minute-of-day. [endMinute] 1440 becomes 24:00. */
fun window(startMinute: Int, endMinute: Int, budget: Int) = NotificationSchedulePeriod(
    startHour = startMinute / 60,
    startMinute = startMinute % 60,
    endHour = endMinute / 60,
    endMinute = endMinute % 60,
    budgetPoints = budget,
)

val NotificationSchedulePeriod.lengthMinutes: Int get() = endMinuteOfDay - startMinuteOfDay

/** Title equals id so spend-ledger entries (which carry titles) can be matched to keys. */
fun item(
    id: String,
    timestamp: Long,
    packageName: String = "com.a",
    priority: NotificationPriority = NotificationPriority.HIGH,
    text: String = "text",
) = NotificationItem(
    id = id,
    appName = packageName,
    title = id,
    text = text,
    time = "12:00",
    packageName = packageName,
    timestamp = timestamp,
    priority = priority,
    category = NotificationCategory.OTHER,
)

fun productionContext(hour: Int, minute: Int = 0) = DeliveryTimeContext.from(
    calendarHour = hour,
    minuteOfDay = hour * 60 + minute,
    dayOfYear = 100,
    testingMode = false,
)

/**
 * A dynamic proxy for a wide interface: records every call name in [calls], answers from
 * [answers] by method name, otherwise returns the neutral value for the return type.
 */
fun <T : Any> recordingProxy(
    type: Class<T>,
    calls: MutableList<String> = mutableListOf(),
    answers: Map<String, (Array<Any?>) -> Any?> = emptyMap(),
): T {
    val handler = InvocationHandler { _, method, args ->
        calls += method.name
        val answer = answers[method.name]
        if (answer != null) {
            answer(args ?: emptyArray<Any?>())
        } else {
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Set::class.java -> emptySet<Any>()
                List::class.java -> emptyList<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }
        }
    }
    return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
}
