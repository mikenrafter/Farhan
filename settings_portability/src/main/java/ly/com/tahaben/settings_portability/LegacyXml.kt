package ly.com.tahaben.settings_portability

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import javax.xml.parsers.SAXParserFactory

/**
 * Strict reader for Android's SharedPreferences XML: `<map>` holding `boolean`, `int`, `long`,
 * `float`, `string` and `set` elements, each named. SAX (`javax.xml.parsers`, `org.xml.sax`) exists on the JVM and
 * on Android, and `javax.xml.stream` does not, so nothing else is used.
 *
 * Invariants:
 *  - CLAIM(xml-no-doctype): a document type declaration is refused three independent ways, because Android's
 *    parser does not accept the Xerces feature flags: (1) a raw `<!DOCTYPE` / `<!ENTITY` scan before parsing;
 *    (2) a LexicalHandler whose `startDTD` throws, installed as a required property (if the parser lacks it the
 *    parse fails closed instead of running without the guard); (3) an EntityResolver that throws, so no external
 *    entity is ever fetched. The disallow-doctype, external-entity and secure-processing feature flags are set on
 *    a best-effort basis only, never relied on. [LegacySettingsConverterTest]
 *  - Input size is bounded in characters before the parser sees it; every element is counted against maxEntries.
 *  - A duplicate name, a missing attribute, a value that does not parse for its element, an element Android does
 *    not write, or text where only elements belong yields Failure. Android's own parse would drop or default them.
 *  - Failure locations are `sharedPreferences/<name>` or `sharedPreferences`; never a value.
 */
internal object LegacyXml {
    sealed interface Parsed {
        data class Values(val values: Map<String, SettingValue>) : Parsed
        data class Failure(val error: PortableSettingsError) : Parsed
    }

    private const val STORE = "sharedPreferences"
    private const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"
    private val INTEGER = Regex("-?[0-9]+")
    private val INT_RANGE = BigInteger.valueOf(Int.MIN_VALUE.toLong())..BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val LONG_RANGE = BigInteger.valueOf(Long.MIN_VALUE)..BigInteger.valueOf(Long.MAX_VALUE)

    fun parse(xml: String, limits: DecodeLimits): Parsed {
        if (xml.length > limits.maxLegacyXmlChars) return failed(PortableSettingsErrorCode.TOO_LARGE, STORE)
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) return failed(PortableSettingsErrorCode.MALFORMED_XML, STORE)
        val handler = Handler(limits.maxEntries)
        return try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            tryFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            tryFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            tryFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            tryFeature(factory, "http://javax.xml.XMLConstants/feature/secure-processing", true)
            val reader = factory.newSAXParser().xmlReader
            reader.setProperty(LEXICAL_HANDLER, handler) // required: a parser that cannot take it must not run
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.parse(InputSource(StringReader(xml)))
            Parsed.Values(handler.values)
        } catch (failure: Failed) {
            Parsed.Failure(failure.error)
        } catch (failure: SAXException) {
            // Includes a parser error, and the missing lexical-handler property (fail closed).
            failed(PortableSettingsErrorCode.MALFORMED_XML, STORE)
        } catch (failure: IOException) {
            failed(PortableSettingsErrorCode.MALFORMED_XML, STORE)
        } catch (failure: Exception) {
            // ParserConfigurationException and platform parser quirks. An Error is not caught here.
            failed(PortableSettingsErrorCode.MALFORMED_XML, STORE)
        }
    }

    private fun failed(code: PortableSettingsErrorCode, location: String) =
        Parsed.Failure(PortableSettingsError(code, location))

    private fun tryFeature(factory: SAXParserFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (unsupported: Exception) {
            // Android's factory rejects most of these; the three guards above do not depend on them.
        }
    }

    /** Carries the typed error out of the SAX callbacks. SAXException so both parsers rethrow it unchanged. */
    private class Failed(val error: PortableSettingsError) : SAXException(error.code.name)

    private class Handler(private val maxEntries: Int) : DefaultHandler2() {
        val values = LinkedHashMap<String, SettingValue>()
        private var entries = 0
        private var sawRoot = false
        private var rootClosed = false

        private enum class Open { NONE, LEAF, TEXT_ELEMENT, SET, SET_ELEMENT }

        private var open = Open.NONE
        private var openName = ""
        private var openKind = ""
        private val text = StringBuilder()
        private var setElements: LinkedHashSet<String>? = null

        private fun fail(code: PortableSettingsErrorCode, name: String? = null): Nothing =
            throw Failed(PortableSettingsError(code, if (name == null) STORE else "$STORE/$name"))

        private fun countEntry() {
            entries++
            if (entries > maxEntries) fail(PortableSettingsErrorCode.TOO_MANY_ENTRIES)
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) =
            fail(PortableSettingsErrorCode.MALFORMED_XML)

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource? =
            fail(PortableSettingsErrorCode.MALFORMED_XML)

        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource? =
            fail(PortableSettingsErrorCode.MALFORMED_XML)

        override fun fatalError(error: SAXParseException) = fail(PortableSettingsErrorCode.MALFORMED_XML)
        override fun error(error: SAXParseException) = fail(PortableSettingsErrorCode.MALFORMED_XML)

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (!sawRoot) {
                if (qName != "map") fail(PortableSettingsErrorCode.MALFORMED_XML)
                sawRoot = true
                return
            }
            when (open) {
                Open.NONE -> startEntry(qName, attributes)
                Open.SET -> {
                    if (qName != "string" || attributes.getValue("name") != null) fail(PortableSettingsErrorCode.MALFORMED_XML, openName)
                    open = Open.SET_ELEMENT
                    text.setLength(0)
                }
                Open.LEAF, Open.TEXT_ELEMENT, Open.SET_ELEMENT -> fail(PortableSettingsErrorCode.MALFORMED_XML, openName)
            }
        }

        private fun startEntry(kind: String, attributes: Attributes) {
            val name = attributes.getValue("name") ?: fail(PortableSettingsErrorCode.MALFORMED_XML)
            if (values.containsKey(name)) fail(PortableSettingsErrorCode.DUPLICATE_KEY, name)
            countEntry()
            openName = name
            openKind = kind
            when (kind) {
                "boolean" -> {
                    val raw = attributes.getValue("value") ?: fail(PortableSettingsErrorCode.MALFORMED_XML, name)
                    values[name] = SettingValue.Bool(
                        when (raw) {
                            "true" -> true
                            "false" -> false
                            else -> fail(PortableSettingsErrorCode.MALFORMED_XML, name)
                        },
                    )
                    open = Open.LEAF
                }
                "int" -> {
                    values[name] = SettingValue.Int32(wholeNumber(attributes, name, INT_RANGE).toInt())
                    open = Open.LEAF
                }
                "long" -> {
                    values[name] = SettingValue.Int64(wholeNumber(attributes, name, LONG_RANGE).toLong())
                    open = Open.LEAF
                }
                "float" -> {
                    val raw = attributes.getValue("value") ?: fail(PortableSettingsErrorCode.MALFORMED_XML, name)
                    values[name] = SettingValue.Float32(raw.toFloatOrNull() ?: fail(PortableSettingsErrorCode.MALFORMED_XML, name))
                    open = Open.LEAF
                }
                "string" -> {
                    text.setLength(0)
                    open = Open.TEXT_ELEMENT
                }
                "set" -> {
                    setElements = LinkedHashSet()
                    open = Open.SET
                }
                else -> fail(PortableSettingsErrorCode.MALFORMED_XML, name)
            }
        }

        private fun wholeNumber(attributes: Attributes, name: String, range: ClosedRange<BigInteger>): BigInteger {
            val raw = attributes.getValue("value") ?: fail(PortableSettingsErrorCode.MALFORMED_XML, name)
            if (!INTEGER.matches(raw)) fail(PortableSettingsErrorCode.MALFORMED_XML, name)
            val number = BigInteger(raw)
            if (number !in range) fail(PortableSettingsErrorCode.OUT_OF_RANGE, name)
            return number
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            when (open) {
                Open.TEXT_ELEMENT, Open.SET_ELEMENT -> text.append(chars, start, length)
                else -> if (!isBlank(chars, start, length)) fail(PortableSettingsErrorCode.MALFORMED_XML, openName.ifEmpty { null })
            }
        }

        private fun isBlank(chars: CharArray, start: Int, length: Int): Boolean {
            for (index in start until start + length) if (!chars[index].isWhitespace()) return false
            return true
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (open) {
                Open.TEXT_ELEMENT -> {
                    values[openName] = SettingValue.Text(text.toString())
                    open = Open.NONE
                }
                Open.SET_ELEMENT -> {
                    countEntry()
                    if (!checkNotNull(setElements).add(text.toString())) fail(PortableSettingsErrorCode.DUPLICATE_KEY, openName)
                    open = Open.SET
                }
                Open.SET -> {
                    values[openName] = SettingValue.TextSet(checkNotNull(setElements))
                    setElements = null
                    open = Open.NONE
                }
                Open.LEAF -> open = Open.NONE
                Open.NONE -> rootClosed = true // </map>
            }
        }

        override fun endDocument() {
            if (!sawRoot || !rootClosed) fail(PortableSettingsErrorCode.MALFORMED_XML)
        }
    }
}
