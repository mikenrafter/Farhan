package ly.com.tahaben.notification_filter_presentation.apppriority

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAlphabetRailPositionTest {
    // why: five 60 px letter slots have centers at 30, 90, 150, 210 and 270 px.
    // catches: keeping the bubble at the midpoint or anchoring to the slot's leading edge.
    @Test fun centersBubbleOnTopMiddleAndBottomLetters() {
        assertEquals(10, railBubbleTop(0, 5, 300, 40))
        assertEquals(130, railBubbleTop(2, 5, 300, 40))
        assertEquals(250, railBubbleTop(4, 5, 300, 40))
    }

    // why: a bubble wider than an end slot must fit within the rail while held.
    // catches: a negative offset at the top or a bubble that extends below the rail.
    @Test fun clampsBubbleAtBothEnds() {
        assertEquals(0, railBubbleTop(0, 5, 300, 80))
        assertEquals(220, railBubbleTop(4, 5, 300, 80))
    }
}
