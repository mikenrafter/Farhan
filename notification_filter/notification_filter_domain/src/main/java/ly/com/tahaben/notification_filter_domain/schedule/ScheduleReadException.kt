package ly.com.tahaben.notification_filter_domain.schedule

/** Saved data could not be read/repaired. Keep its bytes and refuse writes until repair succeeds. */
class ScheduleReadException(cause: Exception) : IllegalStateException("Saved schedule needs repair; original retained", cause)
