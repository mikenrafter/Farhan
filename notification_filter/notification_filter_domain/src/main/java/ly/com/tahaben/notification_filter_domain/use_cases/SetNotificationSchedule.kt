package ly.com.tahaben.notification_filter_domain.use_cases

import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.schedule.*

class SetNotificationSchedule(private val writer: ScheduleWriter) {
    suspend operator fun invoke(schedule: List<NotificationSchedulePeriod>): ScheduleWriteResult = writer.write(ScheduleWrite(schedule))
}
