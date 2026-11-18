package ly.com.tahaben.farhan_rescue

import com.google.common.truth.Truth.assertThat
import ly.com.tahaben.farhan_rescue.adb.*
import org.junit.Test

class AdbCommandTest {
    private val device = target(user = 10)
    private val pkg = "ly.com.tahaben.farhan_alpha"
    private val prefs = file(RemoteDirectory.SHARED_PREFS, "shared_pref.xml")

    // why: the exact service strings are the contract with adbd (run-as syntax: package BEFORE --user, from AOSP
    // run-as.cpp usage text) and carry the sentinel/stderr rules. catches: swapping --user and package, a missing
    // sentinel on force-stop, stderr leaking into a binary copy, a changed stat format.
    @Test fun serviceStringsMatchTheDocumentedShapes() {
        assertThat(AdbCommand.ProbeUid(device).destination()).isEqualTo("exec:run-as $pkg --user 10 id -u")
        assertThat(AdbCommand.ReadVersion(device.packageName).destination()).isEqualTo("exec:dumpsys package $pkg")
        assertThat(AdbCommand.ForceStop(device).destination()).isEqualTo("exec:am force-stop --user 10 $pkg && echo farhan-rescue-ok")
        assertThat(AdbCommand.ListDirectory(device, RemoteDirectory.ROOT).destination())
            .isEqualTo("exec:run-as $pkg --user 10 ls -1 . 2>/dev/null && echo farhan-rescue-ok")
        assertThat(AdbCommand.StatFile(device, prefs).destination())
            .isEqualTo("exec:run-as $pkg --user 10 stat -c '%s %Y %Z %i %F' shared_prefs/shared_pref.xml 2>/dev/null && echo farhan-rescue-ok")
        assertThat(AdbCommand.HashFile(device, prefs).destination())
            .isEqualTo("exec:run-as $pkg --user 10 sha256sum shared_prefs/shared_pref.xml 2>/dev/null && echo farhan-rescue-ok")
        assertThat(AdbCommand.CopyFile(device, file(RemoteDirectory.DATABASES, "farhan_db")).destination())
            .isEqualTo("exec:run-as $pkg --user 10 cat databases/farhan_db 2>/dev/null")
    }

    // why: CLAIM(sentinel) in the decoder. catches: treating empty output, or a sentinel that is not last, as success.
    @Test fun successIsTheSentinelOnTheLastLine() {
        assertThat(AdbOutput.linesBeforeSentinel("a\nb\nfarhan-rescue-ok\n")).containsExactly("a", "b").inOrder()
        assertThat(AdbOutput.linesBeforeSentinel("farhan-rescue-ok\n")).isEmpty()
        assertThat(AdbOutput.linesBeforeSentinel("farhan-rescue-ok")).isEmpty()
        for (text in listOf("", "\n", "a\n", "farhan-rescue-ok\nextra\n", "xfarhan-rescue-ok\n", "Error: failed\n")) {
            assertThat(AdbOutput.linesBeforeSentinel(text)).isNull()
        }
    }

    // why: run-as error strings from AOSP run-as.cpp (main) classify failures; anything else fails closed.
    // catches: treating an unknown reply as a uid, or a trailing-junk number as a uid.
    @Test fun uidProbeClassifiesRunAsErrorsAndFailsClosed() {
        assertThat(AdbOutput.parseUidProbe("10234\n")).isEqualTo(UidProbe.Uid(10234))
        assertThat(AdbOutput.parseUidProbe("run-as: package not debuggable: x\n")).isEqualTo(UidProbe.Failed(RunAsFailure.NOT_DEBUGGABLE))
        assertThat(AdbOutput.parseUidProbe("run-as: unknown package: x\n")).isEqualTo(UidProbe.Failed(RunAsFailure.PACKAGE_NOT_FOUND))
        assertThat(AdbOutput.parseUidProbe("run-as: package not an application: x\n")).isEqualTo(UidProbe.Failed(RunAsFailure.NOT_AN_APPLICATION))
        assertThat(AdbOutput.parseUidProbe("run-as: user id too big: 99999999\n")).isEqualTo(UidProbe.Failed(RunAsFailure.USER_REJECTED))
        assertThat(AdbOutput.parseUidProbe("run-as: only 'shell' or 'root' users can run this program\n")).isEqualTo(UidProbe.Failed(RunAsFailure.UNAVAILABLE))
        for (text in listOf("", "10234 extra\n", "uid=10234\n", "-1\n", "12345678901\n", "something new\n")) {
            assertThat(AdbOutput.parseUidProbe(text)).isEqualTo(UidProbe.Failed(RunAsFailure.UNRECOGNIZED))
        }
    }

    // why: uid = user * 100000 + appId, appId in 10000..19999 (Android UserHandle). Hand arithmetic: user 10, app 10234 -> 1010234.
    // catches: accepting any numeric uid, or a uid from another user.
    @Test fun uidMustBelongToTheRequestedUser() {
        assertThat(AdbOutput.uidMatchesUser(10234, 0)).isTrue()
        assertThat(AdbOutput.uidMatchesUser(1_010_234, 10)).isTrue()
        assertThat(AdbOutput.uidMatchesUser(10234, 10)).isFalse()
        assertThat(AdbOutput.uidMatchesUser(1_010_234, 0)).isFalse()
        assertThat(AdbOutput.uidMatchesUser(9_999, 0)).isFalse()
        assertThat(AdbOutput.uidMatchesUser(20_000, 0)).isFalse()
        assertThat(AdbOutput.uidMatchesUser(1000, 0)).isFalse() // a system uid is not an app uid
    }

    // why: stat is the size authority for bounds and the stability check. catches: symlinks/dirs accepted, missing sentinel
    // accepted, negative or non-numeric fields accepted.
    @Test fun statParsingIsStrict() {
        assertThat(AdbOutput.parseStat("1234 1700000000 1700000001 55 regular file\nfarhan-rescue-ok\n"))
            .isEqualTo(FileFingerprint(1234, 1_700_000_000, 1_700_000_001, 55))
        assertThat(AdbOutput.parseStat("0 1 2 3 regular empty file\nfarhan-rescue-ok\n")?.sizeBytes).isEqualTo(0)
        for (text in listOf("", "1234 1 2 3 symbolic link\nfarhan-rescue-ok\n", "1234 1 2 3 directory\nfarhan-rescue-ok\n",
            "1234 1 2 3 regular file\n", "-1 1 2 3 regular file\nfarhan-rescue-ok\n", "x 1 2 3 regular file\nfarhan-rescue-ok\n",
            "1 2 3 regular file\nfarhan-rescue-ok\n", "1 1 2 3 regular file\n1 1 2 3 regular file\nfarhan-rescue-ok\n")) {
            assertThat(AdbOutput.parseStat(text)).isNull()
        }
    }

    // why: the device digest must be a lowercase 64-hex value. catches: accepting uppercase/short output or a failed tool.
    @Test fun sha256ParsingIsStrict() {
        val digest = "a".repeat(64)
        assertThat(AdbOutput.parseSha256("$digest  shared_prefs/shared_pref.xml\nfarhan-rescue-ok\n")).isEqualTo(digest)
        for (text in listOf("", "$digest  x\n", "A".repeat(64) + "  x\nfarhan-rescue-ok\n", "abc  x\nfarhan-rescue-ok\n", "sha256sum: not found\n")) {
            assertThat(AdbOutput.parseSha256(text)).isNull()
        }
    }

    // why: the version only labels the file; a hostile or odd value is dropped, not carried. catches: accepting spaces/control text.
    @Test fun versionIsParsedFromTheFirstOccurrenceAndSanitised() {
        val lines = sequenceOf("Packages:", "    versionCode=11 minSdk=24 targetSdk=35", "    versionName=0.2.6-alpha", "    versionName=9.9.9")
        assertThat(AdbOutput.parseVersion(lines)).isEqualTo(PackageVersion("0.2.6-alpha", 11))
        assertThat(AdbOutput.parseVersion(sequenceOf("versionCode=11", "versionName=bad name"))).isNull()
        assertThat(AdbOutput.parseVersion(emptySequence())).isNull()
    }
}
