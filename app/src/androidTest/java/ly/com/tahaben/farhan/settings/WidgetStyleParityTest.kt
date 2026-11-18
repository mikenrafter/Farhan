package ly.com.tahaben.farhan.settings

import ly.com.tahaben.settings_android.WidgetStyle
import ly.com.tahaben.usage_overview_presentation.widget.UsageWidget
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStyleParityTest {
    // WHY: the portable protocol must cover every real rendering style without adding unsupported names.
    // catches: adding a UsageWidget.Style while forgetting its portable counterpart, or vice versa.
    @Test fun portableStyleNamesMatchRendererAndLiteralContract() {
        assertEquals(setOf("HORIZONTAL", "VERTICAL"), WidgetStyle.entries.map { it.name }.toSet())
        assertEquals(setOf("HORIZONTAL", "VERTICAL"), UsageWidget.Style.entries.map { it.name }.toSet())
    }
}
