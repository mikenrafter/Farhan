package ly.com.tahaben.notification_filter_domain.inbox

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Cases follow `research/clearsms-filtering-research.md` ("OTP"): what the ClearSMS engine at 6ededf7 accepts
 * (keyword anchored 4-8 digits, digits first, use / enter wording, a bare six digits with verification context)
 * and rejects (amounts, times, phone numbers, order numbers, promo numbers, bare digits without context).
 * Every sample text is synthetic.
 */
class OtpCodeExtractorTest {
    private fun code(text: String, title: String? = null) = OtpCodeExtractor.extract(title, text)

    // why: the engine reads a code placed next to a verification keyword, digits first, or after use / enter.
    // catches: losing a keyword family, the British spelling, the 8-digit limit, the use / enter and bare-six paths.
    @Test fun `readable codes`() {
        val readable = listOf(
            "Your verification code is 482913. Do not share it." to "482913",
            "Your OTP is 4821" to "4821",
            "OTP:482913" to "482913",
            "code: 4821" to "4821",
            "Your one-time password is 55443322" to "55443322",
            "Your OTP is 12345678" to "12345678",
            "Your security code is 7788" to "7788",
            "Your authorisation code is 7788" to "7788",
            "Your authorization code: 998877" to "998877",
            "Your PIN is 4821" to "4821",
            "Your login password is 123456" to "123456",
            "Use passcode 482913 to sign in" to "482913",
            "5521 is your security code" to "5521",
            "123456 is your OTP" to "123456",
            "482913 is the verification code for your account" to "482913",
            "Use 482913 to verify your account." to "482913",
            "Enter 1234 to log in" to "1234",
            "Verify it's you: 482913" to "482913",
            "Your OTP: 123 456" to "123 456",
            "Your OTP is 1234 5678" to "1234 5678",
            "G-482913 is your Google verification code" to "G-482913",
            "Your OTP for payment of Rs 5000 is 482913" to "482913",
            "Verification code for INR 5000.00: 482913" to "482913",
            "Your OTP is valid for 10 minutes. 482913 is your code" to "482913",
        )
        for ((text, expected) in readable) assertWithMessage(text).that(code(text)).isEqualTo(expected)
    }

    // why: amounts, times, phone numbers, order numbers and promo numbers are the documented false positives.
    // catches: any of those being returned as a code, or a rejected number hiding the real code behind it.
    @Test fun `false positives are rejected`() {
        val rejected = listOf(
            "Order total $1234 paid. Promo code applied",
            "Payment of Rs.1234 received",
            "Your balance is 123456",
            "Rs 482913 debited from A/C XX1234",
            "INR 123456.00 credited to your account",
            "Your OTP is valid for 10 minutes",
            "Your code is 1234.56",
            "Your passcode expires at 10:45",
            "Your login code expires at 10:45 pm",
            "Meeting code at 1030 am",
            "Call 555 0142 for your pin",
            "Your code: call us on 8005550142",
            "Your code: call +44 7700 900123",
            "Your verification code: contact 555-0142",
            "Your order 884213 has shipped",
            "Verification of order 884213 complete",
            "Order #884213 verification pending",
            "Promo code 884213 applied",
            "Use coupon code 123456 at checkout",
            "Your reference is 482913",
            "Lucky number 482913 wins",
            "Your OTP is 123456789",
            "Use 5000 to pay",
        )
        for (text in rejected) assertWithMessage(text).that(code(text)).isNull()
    }

    // why: the engine's fallback takes a bare six-digit run only with verification context.
    // catches: the fallback firing without context, on a transaction or promo text, or on two candidates.
    @Test fun `a bare six digits needs verification context and nothing ambiguous`() {
        val rejected = listOf(
            "See you at 482913",
            "Verify it's you: 482913 or 771203",
            "Verify your payment of Rs 500: 482913",
            "Verify to get 20% off: 482913",
        )
        for (text in rejected) assertWithMessage(text).that(code(text)).isNull()
    }

    @Test fun `code in the title`() = assertThat(code("Tap to copy", title = "Use 771203 as your login code")).isEqualTo("771203")

    @Test fun `amount in the title does not hide a code in the text`() =
        assertThat(code("Your OTP is 482913", title = "Payment of Rs 5000")).isEqualTo("482913")

    @Test fun `empty or blank text`() {
        assertThat(OtpCodeExtractor.extract(null, null)).isNull()
        assertThat(OtpCodeExtractor.extract("  ", "")).isNull()
    }

    @Test fun `copy form keeps digits only`() {
        assertThat(OtpCodeExtractor.copyForm("123 456")).isEqualTo("123456")
        assertThat(OtpCodeExtractor.copyForm("G-482913")).isEqualTo("482913")
        assertThat(OtpCodeExtractor.copyForm("4821")).isEqualTo("4821")
    }

    // why: another number between the keyword and the code was returned instead of the code (review finding).
    // catches: taking the first plausible run after the keyword.
    @Test fun `the code wins over another number between keyword and code`() {
        val cases = listOf(
            "Your OTP to pay 2499 to Swiggy is 482913" to "482913",
            "OTP for your purchase of 2499 at Flipkart is 482913" to "482913",
            "OTP for transaction of 5000 at AMAZON is 482913" to "482913",
            "Your Amazon OTP for order of 1299 is 482913" to "482913",
            "Your login code for 2026 tax filing is 482913" to "482913",
            "Your PIN for room 1204 is 4821" to "4821",
        )
        for ((text, expected) in cases) assertWithMessage(text).that(code(text)).isEqualTo(expected)
    }

    // why: a sender number in the title, dates, times and chat passwords raised ordinary notifications to Critical.
    // catches: bare six digits taken from the title, a year after a keyword, a plain "password is".
    @Test fun `senders dates and chat passwords are not codes`() {
        assertThat(code("New sign-in to your Acme account. Contact support if this was not you.", title = "262966")).isNull()
        assertThat(code("Your Acme account has been verified", title = "729725")).isNull()
        assertThat(code("Your password was changed on 21 Nov 2026.")).isNull()
        assertThat(code("Your login password was changed on 21 Nov 2026.")).isNull()
        assertThat(code("Sign-in attempt from a new device. Code 2026")).isNull()
        assertThat(code("Your PIN was reset at 1045")).isNull()
        assertThat(code("Hey, the wifi password is 12345678")).isNull()
        // A real code still works with a numeric sender title.
        assertThat(code("Your verification code is 482913", title = "262966")).isEqualTo("482913")
    }
}
