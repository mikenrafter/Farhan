package ly.com.tahaben.farhan.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupActionTest {
    @Test
    fun `explicit actions match the external extra values`() {
        // why: each backup row must launch the picker for its own action.
        // catches: swapping the import and export wire values.
        assertEquals(BackupAction.Export, BackupAction.fromIntentValue("export"))
        assertEquals(BackupAction.Import, BackupAction.fromIntentValue("import"))
    }

    @Test
    fun `absent or unrecognized extras leave the activity in manual mode`() {
        // why: legacy launches and malformed extras must not start a picker.
        // catches: treating unrecognized actions as export.
        assertNull(BackupAction.fromIntentValue(null))
        assertNull(BackupAction.fromIntentValue("IMPORT"))
        assertNull(BackupAction.fromIntentValue(""))
    }
}
