package ly.com.tahaben.notification_filter_domain.schedule

import ly.com.tahaben.notification_filter_domain.delivery.DeliveryBudgetState
import ly.com.tahaben.notification_filter_domain.model.NotificationSchedulePeriod
import ly.com.tahaben.notification_filter_domain.testsupport.window
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** In-memory store; [commits] records every atomic commit so tests can see writes that must not happen. */
class FakeScheduleBookStore(var snapshot: ScheduleSnapshot) : ScheduleBookStore {
    val commits = mutableListOf<ScheduleSnapshot>()
    override suspend fun read(): ScheduleSnapshot = snapshot
    override suspend fun commit(snapshot: ScheduleSnapshot) {
        this.snapshot = snapshot
        commits += snapshot
    }
}

class FakeCredentials(var enabled: Boolean = true, private val secret: String = "s3cret") : AutomationCredentials {
    override fun isEnabled() = enabled
    override fun matches(presented: String?) = presented == secret
}

/** 10:00 on Wed 2026-11-18 in Denver (MST, UTC-7). */
val TEN_AM_DENVER: Clock = Clock.fixed(Instant.parse("2026-11-18T17:00:00Z"), ZoneId.of("America/Denver"))

/** [0,540) 2, [540,720) [middleBudget], [720,1440) 0. */
fun threeWindows(middleBudget: Int, middleStart: Int = 540): List<NotificationSchedulePeriod> =
    listOf(window(0, middleStart, 2), window(middleStart, 720, middleBudget), window(720, 1440, 0))

/** Six points already spent in the window that starts at 09:00 (the budget state delivery stores today). */
val SIX_SPENT_IN_NINE_AM_WINDOW = DeliveryBudgetState(spentPoints = 6, periodStartMinute = 540)
