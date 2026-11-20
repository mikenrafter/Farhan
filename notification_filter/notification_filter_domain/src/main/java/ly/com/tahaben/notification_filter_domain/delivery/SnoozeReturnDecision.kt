package ly.com.tahaben.notification_filter_domain.delivery

import java.time.Instant

sealed interface SnoozeReturnDecision {
    data object Charged : SnoozeReturnDecision
    data class RetryAt(val instant: Instant) : SnoozeReturnDecision
    data object NoFundedMoment : SnoozeReturnDecision
}
