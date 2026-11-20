package ly.com.tahaben.notification_filter_presentation.apppriority

import kotlin.math.roundToInt

/** Top of the bubble in pixels for a valid letter index in a nonempty, evenly divided rail. */
internal fun railBubbleTop(index: Int, letterCount: Int, railHeight: Int, bubbleHeight: Int): Int {
    val center = (index + 0.5) * railHeight / letterCount
    return (center - bubbleHeight / 2.0).roundToInt().coerceIn(0, railHeight - bubbleHeight)
}
