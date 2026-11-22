package ly.com.tahaben.notification_filter_domain.inbox

/**
 * Finds a one-time verification code in notification text. A pure function of its input.
 *
 * Follows what `research/clearsms-filtering-research.md` ("OTP", ClearSMS pinned at 6ededf7) says the ClearSMS
 * engine accepts and rejects. The pinned source is not vendored here: these rules were written from that
 * description, not copied, and the cases in `OtpCodeExtractorTest` pin them.
 *
 * Accepted, in this order inside one sentence:
 *  1. **Digits first** - `482913 is your verification code`, `G-482913 is your Google code`.
 *  2. **Keyword anchored** - 4-8 digits within 60 characters after OTP, one-time password, verification,
 *     security / authorization (or authorisation) / login / confirmation code, PIN, passcode, password or a bare
 *     "code" (`code: 4821`).
 *  3. **Use / enter wording** - `Use 482913 to verify your account`, `Enter 1234 to log in`.
 *  4. **Bare six digits** - a lone six-digit run, accepted only when the whole text has verification context
 *     (verify, authenticate, log in, security ...) and no transaction or promo wording.
 *
 * Rejected candidates: amounts (`Rs 5000`, `$1234`, `1234.50`, `5000 INR`), times (`10:45`, `1045 hrs`, `5 pm`),
 * phone numbers (`+1 555 0142`, `call 5550142`), order / reference / account numbers, a promo or coupon "code",
 * and any run of more than 8 digits. When the sentence holds a rejected number and a real code (`OTP for Rs 5000
 * is 123456`) the real code still wins.
 *
 * Farhan extras beyond the engine description: a code written `123 456` or `1234-5678` is accepted with its
 * spacing kept, and a Google style `G-123456` letter prefix. [copyForm] gives the digits to put on a clipboard.
 */
object OtpCodeExtractor {
    private const val CODE = """(?:[A-Z]-\d{4,8}|\d{4}[ -]\d{4}|\d{3}[ -]\d{3}|\d{4,8})"""

    /** Not part of a longer token: not after a word character, decimal, time, currency or phone mark. */
    private const val LEAD = """(?<![\w.,$+#/@€£₹])(?<!\d:)(?<!\d[ -])"""
    private const val TAIL = """(?!\w|[ -]\d)"""
    private const val CAND = "$LEAD($CODE)$TAIL"

    private const val KIND = """(?:one[- ]?time|verification|security|authori[sz]ation|authentication|login|log[- ]in|sign[- ]?in|confirmation|access|transaction|auth|2fa)"""
    private const val NOUN = """(?:code|pin|password|passcode)"""
    private const val PROMO = """promo|promotional|coupon|discount|voucher|referral|gift|zip|postal|area|country|tracking|booking|offer|invite|redeem"""
    // A bare "password" is not a keyword ("the wifi password is 12345678"); it needs a kind in front of it.
    private const val KEYWORD = """(?:otp|(?:$KIND[ -]+)?(?:code|pin|passcode)|$KIND[ -]+password|passcode|verification)"""

    private val sentenceBreak = Regex("""\n+|(?<!\b(?:rs|inr|usd|no|nr|ref|txn|amt)\.)(?<=[.!?])\s+""", RegexOption.IGNORE_CASE)
    private val keyword = Regex("""(?i)(?<!\b(?:$PROMO)\s)\b$KEYWORD\b""")
    private val candidate = Regex(CAND, RegexOption.IGNORE_CASE)
    private val digitsFirstTail = Regex("""(?i)^[^\n]{0,25}?\b(?:is|as)\s+(?:your|the|my)\b[^\n]{0,30}?\b$KEYWORD\b""")
    private val useEnter = Regex(
        """(?i)\b(?:use|enter|input|type)\s+(?:the\s+)?(?:(?:code|otp|pin|passcode)\s*[:\-]?\s*)?$CAND[^.\n]{0,20}?\bto\s+(?:verify|log\s?in|sign\s?in|confirm|authori[sz]e|authenticate|complete|continue|proceed|access|activate|reset|unlock|approve|validate)\b""",
    )

    private val badBefore = Regex(
        """(?i)(?:\b(?:rs|inr|usd|eur|gbp|aed|amount|amt|balance|bal|a/c|acct|account\s*(?:no|number|num)|card\s*(?:no|number|num|ending)|order|ord|ref|reference|txn|trx|invoice|bill|tracking|awb|booking|ticket|id|no|number)\b\.?|[₹$€£]|\b(?:call|dial|text|contact|phone|tel|mobile|whatsapp)(?:\s+(?:us|me|at|on))*)\s*[:#-]?\s*$""",
    )
    private val badAfter = Regex(
        """(?i)^(?:[.,]\d|[:%/]|\s?(?:am|pm|hrs?|hours?|mins?|minutes?|secs?|seconds?|rs|inr|usd|eur|rupees?|dollars?)\b|/-)""",
    )

    /** What sits right before a code: "is", "are", ":" or "=". Anything else needs the code to touch the keyword. */
    private val markerBefore = Regex("""(?i)(?:\b(?:is|are)|[:=])\s*$""")
    private val adjacentBefore = Regex("""(?i)^[\s:=\-#]*(?:(?:is|are)[\s:=\-]*)?$""")
    private val yearLike = Regex("""^(?:19|20)\d\d$""")

    private val verifyContext = Regex("""(?i)\b(?:verif(?:y|ication|ied)|authenticat\w*|authori[sz]\w*|log[- ]?in|sign[- ]?in|security|one[- ]?time)\b""")
    private val moneyOrPromo = Regex(
        """(?i)\b(?:debited|credited|withdrawn|spent|purchase|payment|paid|balance|txn|a/c|rs|inr|usd|order|invoice|promo\w*|coupon|discount|offer|sale|cashback)\b|[₹$€£]|\d\s?%""",
    )
    private val sixDigits = Regex("""$LEAD(\d{6})$TAIL""")

    fun extract(title: String?, text: String?): String? {
        // A title that is only a number is a sender (a short code or phone number), never part of the message.
        val usableTitle = title?.takeUnless { t -> t.isNotBlank() && t.all { it.isDigit() || it in " +-()." } }
        val haystack = listOfNotNull(usableTitle, text).joinToString("\n")
        if (haystack.isBlank()) return null
        val sentences = sentenceBreak.split(haystack).filter { it.isNotBlank() }
        for (sentence in sentences) {
            digitsFirst(sentence)?.let { return it }
            afterKeyword(sentence)?.let { return it }
            useEnter(sentence)?.let { return it }
        }
        return bareSixDigits(text.orEmpty())
    }

    /** The digits only, for a clipboard or an autofill field: `123 456` becomes `123456`, `G-482913` becomes `482913`. */
    fun copyForm(code: String): String = code.filter(Char::isDigit)

    private fun digitsFirst(sentence: String): String? = candidate.findAll(sentence).firstOrNull { match ->
        plausible(sentence, match) && digitsFirstTail.containsMatchIn(sentence.substring(match.range.last + 1))
    }?.groupValues?.get(1)

    private fun afterKeyword(sentence: String): String? {
        for (anchor in keyword.findAll(sentence)) {
            val end = anchor.range.last + 1
            val window = sentence.substring(end, minOf(sentence.length, end + 60))
            // Another number can sit between the keyword and the code ("OTP to pay 2499 to Shop is 482913"), so a
            // candidate counts only after "is", "are", ":" or "=", or when it touches the keyword and is not a year.
            val before = sentence.substring(0, end)
            val picked = candidate.findAll(window).filter { plausible(window, it, before) }.firstOrNull { match ->
                val lead = window.substring(0, match.range.first)
                markerBefore.containsMatchIn(lead) ||
                    (adjacentBefore.matches(lead) && !yearLike.matches(match.groupValues[1]))
            }
            if (picked != null) return picked.groupValues[1]
        }
        return null
    }

    private fun useEnter(sentence: String): String? {
        val match = useEnter.find(sentence) ?: return null
        val code = match.groupValues[1]
        val start = match.groups[1]!!.range.first
        return code.takeIf { plausible(sentence, candidate.find(sentence.substring(start))!!, offset = start) }
    }

    private fun bareSixDigits(haystack: String): String? {
        if (!verifyContext.containsMatchIn(haystack) || moneyOrPromo.containsMatchIn(haystack)) return null
        val matches = sixDigits.findAll(haystack).filter { plausible(haystack, it) }.toList()
        return matches.singleOrNull()?.groupValues?.get(1)
    }

    /** True when [match] is not an amount, time, phone, order or reference number. */
    private fun plausible(text: String, match: MatchResult, before: String? = null, offset: Int = 0): Boolean {
        val start = match.range.first + offset
        val end = match.range.last + 1 + offset
        val prefix = (before ?: "") + text.substring(0, start)
        if (badBefore.containsMatchIn(prefix.takeLast(24))) return false
        if (badAfter.containsMatchIn(text.substring(end, minOf(text.length, end + 12)))) return false
        return match.groupValues[1].count(Char::isDigit) in 4..8
    }
}
