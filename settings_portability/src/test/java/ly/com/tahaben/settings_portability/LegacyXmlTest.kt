package ly.com.tahaben.settings_portability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ly.com.tahaben.settings_portability.SettingValue.Text
import org.junit.Test

/** The legacy XML reader at the shapes Android writes and the shapes a damaged or hostile file could take. */
class LegacyXmlTest {

    private val source = PortableSource("ly.com.tahaben.farhan_alpha", "0.2.6-alpha", 11L, 0)
    private val acceptAll = SemanticValidator { null }

    private fun xml(body: String) = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n$body\n</map>\n"

    private fun convert(document: String, limits: DecodeLimits = DecodeLimits()) =
        LegacySettingsConverter.convert(LegacySourceSnapshot(source, document, emptyMap(), ConfigurationRows()), acceptAll, limits)

    private fun errorOf(document: String, limits: DecodeLimits = DecodeLimits()): PortableSettingsErrorCode {
        val result = convert(document, limits)
        assertThat(result).isInstanceOf(PortableSettingsResult.Failed::class.java)
        return (result as PortableSettingsResult.Failed).error.code
    }

    // why: Android writes an empty string as an empty element and a float as an attribute. The reader must keep
    //      the empty string (a setting the owner cleared), name an unknown float without carrying its value, and
    //      keep the exact text of a set element.
    // proves: CLAIM(types-kept), CLAIM(catalog-only)
    // catches: dropping empty strings; treating a self-closing string as malformed; carrying an unknown value.
    @Test
    fun `an empty string element is kept and an unknown float is only named`() {
        val result = convert(xml("""<string name="custom_message" /><float name="future_float" value="2.5" />"""))

        assertThat(result).isInstanceOf(PortableSettingsResult.Ready::class.java)
        val ready = result as PortableSettingsResult.Ready
        assertThat(ready.settings.configuration.sharedPreferences).containsExactly("custom_message", Text(""))
        assertThat(ready.settings.unsupportedKeys).containsExactly(UnsupportedKey(SettingsStoreId.SHARED_PREFERENCES, "future_float"))
    }

    // why: a damaged file must name the structural defect instead of being read as a shorter valid file.
    // proves: CLAIM(no-silent-default)
    // catches: ignoring text between elements; accepting another root; a set holding a named or nested child;
    //          a repeated set element; a boolean that is not true/false; an empty input; extra root content.
    @Test
    fun `structural defects in the xml are malformed`() {
        val control = xml("""<set name="message_array"><string>a</string><string>b</string></set>""")
        assertThat(convert(control)).isInstanceOf(PortableSettingsResult.Ready::class.java)

        val cases = listOf(
            Triple("text in the map", xml("stray text"), PortableSettingsErrorCode.MALFORMED_XML),
            Triple("another root", "<?xml version='1.0'?><prefs></prefs>", PortableSettingsErrorCode.MALFORMED_XML),
            Triple("empty input", "", PortableSettingsErrorCode.MALFORMED_XML),
            Triple("second root", xml("") + "<map></map>", PortableSettingsErrorCode.MALFORMED_XML),
            Triple("named child in a set", xml("""<set name="message_array"><string name="x">a</string></set>"""), PortableSettingsErrorCode.MALFORMED_XML),
            Triple("int in a set", xml("""<set name="message_array"><int value="1" /></set>"""), PortableSettingsErrorCode.MALFORMED_XML),
            Triple("child inside a string", xml("""<string name="custom_message"><b>x</b></string>"""), PortableSettingsErrorCode.MALFORMED_XML),
            Triple("repeated set element", xml("""<set name="message_array"><string>a</string><string>a</string></set>"""), PortableSettingsErrorCode.DUPLICATE_KEY),
            Triple("boolean not true or false", xml("""<boolean name="notification_service_stats" value="yes" />"""), PortableSettingsErrorCode.MALFORMED_XML),
            Triple("missing name", xml("""<boolean value="true" />"""), PortableSettingsErrorCode.MALFORMED_XML),
            Triple("long beyond range", xml("""<long name="notify_me_schedule_date" value="9223372036854775808" />"""), PortableSettingsErrorCode.OUT_OF_RANGE),
        )
        for ((name, document, code) in cases) {
            assertWithMessage(name).that(errorOf(document)).isEqualTo(code)
        }
    }

    // why: a document type declaration must be refused whatever form it takes and whatever the platform parser
    //      supports (Android's does not accept the Xerces flags). Neither an internal entity nor an external one
    //      may be read or fetched.
    // proves: CLAIM(xml-no-doctype)
    // catches: relying on setFeature(disallow-doctype-decl) alone; allowing a DOCTYPE with no entities;
    //          resolving an external entity.
    @Test
    fun `any document type declaration is refused`() {
        val noEntity = "<?xml version='1.0'?>\n<!DOCTYPE map>\n<map></map>\n"
        val externalEntity = "<?xml version='1.0'?>\n<!DOCTYPE map [<!ENTITY e SYSTEM \"file:///does/not/exist\">]>\n<map>" +
            """<string name="custom_message">&e;</string></map>"""
        val control = xml("""<string name="custom_message">plain</string>""")
        assertThat(convert(control)).isInstanceOf(PortableSettingsResult.Ready::class.java)
        for ((name, document) in listOf("empty doctype" to noEntity, "external entity" to externalEntity)) {
            assertWithMessage(name).that(errorOf(document)).isEqualTo(PortableSettingsErrorCode.MALFORMED_XML)
        }
    }

    // why: the XML is bounded before the parser sees it, and every element counts against the entry limit, so a
    //      huge or huge-count file cannot allocate without limit.
    // proves: CLAIM(strict-input)
    // catches: parsing first and measuring after; an entry limit applied to the output only.
    @Test
    fun `the xml size and entry limits are enforced`() {
        val three = xml("""<int name="notification_flood_threshold" value="1" /><int name="notification_batch_window_minutes" value="2" /><int name="notify_me_hour" value="3" />""")
        assertThat(convert(three, DecodeLimits(maxEntries = 3))).isInstanceOf(PortableSettingsResult.Ready::class.java)
        assertThat(errorOf(three, DecodeLimits(maxEntries = 2))).isEqualTo(PortableSettingsErrorCode.TOO_MANY_ENTRIES)
        assertThat(convert(three, DecodeLimits(maxLegacyXmlChars = three.length))).isInstanceOf(PortableSettingsResult.Ready::class.java)
        assertThat(errorOf(three, DecodeLimits(maxLegacyXmlChars = three.length - 1))).isEqualTo(PortableSettingsErrorCode.TOO_LARGE)
    }

    // why: a converted file the importer would refuse (over the string cap) must fail at conversion, while the
    //      owner still has the source, not later at import.
    // proves: CLAIM(self-decode)
    // catches: converting without checking the output against the same limits as stage.
    @Test
    fun `a conversion whose file would exceed the limits fails`() {
        val big = xml("""<string name="custom_message">${"x".repeat(300)}</string>""")
        assertThat(convert(big, DecodeLimits(maxStringLength = 300))).isInstanceOf(PortableSettingsResult.Ready::class.java)
        assertThat(errorOf(big, DecodeLimits(maxStringLength = 299))).isEqualTo(PortableSettingsErrorCode.TOO_LARGE)
    }
}
