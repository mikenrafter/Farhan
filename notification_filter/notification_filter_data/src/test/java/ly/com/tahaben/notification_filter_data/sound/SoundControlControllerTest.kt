package ly.com.tahaben.notification_filter_data.sound

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.sound.*
import org.junit.Test

class SoundControlControllerTest {
    // why: v2.1–2 and v2.4 fix the complete policy and require the intent journal before Android writes.
    // catches: copying the STARRED policy, relying on Zen builder defaults, or adding before commit.
    @Test
    fun `enable journals On then adds a rule that allows all calls`() = runTest {
        val fixture = SoundControlFixture().apply {
            channel = null
            readablePolicy = MutePolicy(
                calls = CallAudience.STARRED, repeatCallers = false, alarms = false, media = false,
                systemSounds = false, priorityChannels = false, messages = true, conversations = true,
                reminders = true, events = true, showFullScreenIntent = false, showLights = false,
                showPeek = false, showStatusBar = false, showBadge = false, showAmbient = false,
                showNotificationList = false,
            )
        }

        assertThat(fixture.controller().enable()).isEqualTo(SoundControlState.Held("new-1"))
        assertThat(fixture.rules["new-1"]).isEqualTo(ZenRule("new-1", OWNER, CONDITION, active = true))
        assertThat(fixture.events).containsAtLeast(
            "channel:create", "channel:read",
        ).inOrder()
        assertThat(fixture.events).containsAtLeast("commit:true:1", "add:new-1", "state:new-1:true").inOrder()
        assertThat(fixture.events).doesNotContain("policy:read")
        assertThat(fixture.addedPolicy).isEqualTo(
            MutePolicy(
                calls = CallAudience.ANY, repeatCallers = true, alarms = true, media = true,
                systemSounds = true, priorityChannels = true, messages = false, conversations = false,
                reminders = false, events = false, showFullScreenIntent = true, showLights = true,
                showPeek = true, showStatusBar = true, showBadge = true, showAmbient = true,
                showNotificationList = true,
            ),
        )
    }

    // why: v2.4 makes the durable commit the precondition for adding a rule.
    // catches: ignoring a false synchronous commit and leaving an unjournaled Android rule.
    @Test
    fun `failed journal write prevents enable mutations`() = runTest {
        val fixture = SoundControlFixture().apply { refuseCommit = true }
        fixture.controller().enable()

        assertThat(fixture.events).contains("commit:true:1")
        assertThat(fixture.mutations).isEmpty()
        assertThat(fixture.intent).isEqualTo(SoundIntent(false, 0, null))
    }

    // why: v2.3 requires both entry points to clean orphans with the flag off, even with no stored ID.
    // catches: early return on flag off, name-only ownership, broad package-only cleanup, removing just one orphan.
    @Test
    fun `flag off cleans every owned orphan and leaves foreign and unrelated rules untouched`() = runTest {
        val second = OWNED.copy(id = "ours-duplicate")
        for (entry in listOf<suspend (SoundControlController) -> SoundControlState>(
            { it.onAppStart() }, { it.onListenerConnected() },
        )) {
            for (intent in listOf(SoundIntent(), HELD_INTENT)) {
                val fixture = SoundControlFixture(intent, OWNED, second, FOREIGN, UNRELATED)
                fixture.switches = fixture.switches.copy(flagOn = false)
                entry(fixture.controller())

                assertThat(fixture.rules.values).containsExactly(FOREIGN, UNRELATED)
                assertThat(fixture.mutations).containsExactly("remove:ours", "remove:ours-duplicate")
                assertThat(fixture.events).doesNotContain("channel:create")
            }
        }
    }

    // why: v2.3 adopts by condition ID when the journal's ID is stale.
    // catches: creating a replacement or trusting a stale stored ID now occupied by a foreign rule.
    @Test
    fun `stale id adopts the exact owned condition without adding`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT.copy(ruleId = "theirs"), OWNED, FOREIGN, UNRELATED)

        assertThat(fixture.controller().onAppStart()).isEqualTo(SoundControlState.Held("ours"))
        assertThat(fixture.intent).isEqualTo(SoundIntent(true, 7, "ours"))
        assertThat(fixture.mutations).isEmpty()
        assertThat(fixture.rules.values).containsExactly(OWNED, FOREIGN, UNRELATED)
    }

    // why: the release-only reconciler cannot restore a deleted rule or restart a stopped one.
    // catches: treating persisted On as permission to add or reactivate on startup.
    @Test
    fun `restart never adds a missing rule or activates a stopped rule`() = runTest {
        val expected = SoundControlState.Suspended(SuspensionReason.STOPPED_IN_SETTINGS)
        val missing = SoundControlFixture(HELD_INTENT, FOREIGN)
        assertThat(missing.controller().onAppStart()).isEqualTo(expected)
        assertThat(missing.mutations).isEmpty()
        assertThat(missing.rules.values).containsExactly(FOREIGN)

        val stoppedRule = OWNED.copy(active = false)
        val stopped = SoundControlFixture(HELD_INTENT, stoppedRule)
        assertThat(stopped.controller().onListenerConnected()).isEqualTo(expected)
        assertThat(stopped.mutations).isEmpty()
        assertThat(stopped.rules.values).containsExactly(stoppedRule)
    }

    // why: v2.6 requires a policy grant when the channel is first registered.
    // catches: eagerly creating the channel while opening settings, before DND access.
    @Test
    fun `enable without policy access leaves channel absent`() = runTest {
        val fixture = SoundControlFixture().apply {
            channel = null
            grants = grants.copy(policy = false)
        }
        assertThat(fixture.controller().enable()).isEqualTo(SoundControlState.Suspended(SuspensionReason.NEEDS_ACCESS))
        assertThat(fixture.channel).isNull()
        assertThat(fixture.events).doesNotContain("channel:create")
        assertThat(fixture.mutations).isEmpty()
    }

    // why: v2.7 requires durable Off before removal and status clearance after confirmation.
    // catches: removing first, leaving the same generation, or clearing status before checking removal.
    @Test
    fun `Off commits next generation before removing and clearing status`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT, OWNED)
        assertThat(fixture.controller().off()).isEqualTo(SoundControlState.Off)

        assertThat(fixture.events).containsAtLeast("commit:false:8", "remove:ours", "status:clear").inOrder()
        assertThat(fixture.intent).isEqualTo(SoundIntent(false, 8, null))
        assertThat(fixture.rules).isEmpty()
        assertThat(fixture.statusVisible).isFalse()
    }

    // why: v2.3 and v2.7 make a half-finished Off recoverable on restart.
    // catches: startup that skips cleanup because the persisted phase already says Off.
    @Test
    fun `restart completes removal after death following the Off commit`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT, OWNED).apply { crashBeforeRemove = true }
        val crash = runCatching { fixture.controller().off() }
        assertThat(crash.exceptionOrNull()).isInstanceOf(ProcessDeath::class.java)
        assertThat(fixture.intent.desiredOn).isFalse()
        assertThat(fixture.intent.generation).isEqualTo(8)
        assertThat(fixture.rules.values).containsExactly(OWNED)
        assertThat(fixture.statusVisible).isTrue()

        fixture.crashBeforeRemove = false
        assertThat(fixture.controller().onAppStart()).isEqualTo(SoundControlState.Off)
        assertThat(fixture.rules).isEmpty()
        assertThat(fixture.statusVisible).isFalse()
        assertThat(fixture.mutations).containsExactly("remove:ours", "remove:ours")
    }

    // why: older work from generation 7 must not operate on the Off generation 8 journal.
    // catches: treating a stale worker as a current cleanup and touching a leftover rule.
    @Test
    fun `older worker is inert even when the current worker has an orphan to clean`() = runTest {
        val fixture = SoundControlFixture(SoundIntent(false, 8, "ours"), OWNED)
        val controller = fixture.controller()
        controller.onWorker(7)
        assertThat(fixture.events).isEmpty()
        assertThat(fixture.mutations).isEmpty()
        assertThat(fixture.rules.values).containsExactly(OWNED)

        controller.onWorker(8)
        assertThat(fixture.mutations).containsExactly("remove:ours")
        assertThat(fixture.rules).isEmpty()
    }

    // why: access may disappear between the grant check and Android's remove operation.
    // catches: swallowing AccessLost and claiming Off, hiding recovery, or repeatedly retrying with a known denial.
    @Test
    fun `AccessLost keeps recovery status and reports NeedsAccess`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT, OWNED).apply { loseAccessAtRemove = true }
        val expected = SoundControlState.Suspended(SuspensionReason.NEEDS_ACCESS)
        val controller = fixture.controller()
        assertThat(runCatching { controller.off() }.getOrNull()).isEqualTo(expected)
        assertThat(fixture.intent.desiredOn).isFalse()
        assertThat(fixture.intent.generation).isEqualTo(8)
        assertThat(fixture.grants.policy).isFalse()
        assertThat(fixture.rules.values).containsExactly(OWNED)
        assertThat(fixture.statusVisible).isTrue()
        assertThat(fixture.recoveryVisible).isTrue()

        assertThat(runCatching { controller.onWorker(8) }.getOrNull()).isEqualTo(expected)
        assertThat(fixture.mutations).containsExactly("remove:ours")
    }

    // why: v2.7 retains the status and a Modes link if Android refuses removal.
    // catches: treating false from remove as success or clearing status while the rule remains.
    @Test
    fun `failed removal keeps status with Modes recovery`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT, OWNED).apply { refuseRemoval = true }
        assertThat(fixture.controller().off()).isEqualTo(SoundControlState.Suspended(SuspensionReason.REMOVAL_FAILED))
        assertThat(fixture.mutations).containsExactly("remove:ours")
        assertThat(fixture.rules.values).containsExactly(OWNED)
        assertThat(fixture.statusVisible).isTrue()
        assertThat(fixture.recoveryVisible).isTrue()
    }

    // why: F2 makes Off release a held mode even when the synchronous journal throws.
    // catches: aborting on a save exception or reporting Held after the rule has been removed.
    @Test
    fun `off releases matching rules and clears status when saving throws`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT, OWNED, FOREIGN)
        val controller = fixture.controller()
        assertThat(controller.onAppStart()).isEqualTo(SoundControlState.Held("ours"))
        // Introduce a matching orphan after the held-state witness.
        fixture.rules["duplicate"] = OWNED.copy(id = "duplicate")
        fixture.mutations.clear()
        fixture.events.clear()
        fixture.commitFailure = IllegalStateException("disk unavailable")

        val state = runCatching { controller.off() }.getOrNull()
        assertThat(fixture.rules.values).containsExactly(FOREIGN)
        assertThat(fixture.mutations).containsExactly("remove:ours", "remove:duplicate")
        assertThat(fixture.events).containsAtLeast("commit:false:8", "remove:ours", "status:clear").inOrder()
        assertThat(state).isEqualTo(SoundControlState.Suspended(SuspensionReason.STORAGE_FAILED))
        assertThat(controller.state.value).isEqualTo(SoundControlState.Suspended(SuspensionReason.STORAGE_FAILED))
        assertThat(fixture.statusVisible).isFalse()
        assertThat(fixture.intent).isEqualTo(HELD_INTENT)

        assertThat(fixture.controller().onAppStart())
            .isEqualTo(SoundControlState.Suspended(SuspensionReason.STOPPED_IN_SETTINGS))
        assertThat(fixture.rules.values).containsExactly(FOREIGN)
    }

    // why: F2 also applies to SharedPreferences.commit returning false. A surviving mute still needs recovery.
    // catches: returning early on false without attempting removal or hiding status after a refused removal.
    @Test
    fun `refused Off save still attempts release and retains recovery when removal fails`() = runTest {
        val fixture = SoundControlFixture(HELD_INTENT, OWNED).apply {
            refuseCommit = true
            refuseRemoval = true
        }

        assertThat(fixture.controller().off()).isEqualTo(SoundControlState.Suspended(SuspensionReason.STORAGE_FAILED))
        assertThat(fixture.events).containsAtLeast("commit:false:8", "remove:ours").inOrder()
        assertThat(fixture.rules.values).containsExactly(OWNED)
        assertThat(fixture.statusVisible).isTrue()
        assertThat(fixture.recoveryVisible).isTrue()
    }
}
