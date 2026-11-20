package ly.com.tahaben.notification_filter_data.sound

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import ly.com.tahaben.notification_filter_domain.sound.SoundIntent
import kotlin.random.Random
import org.junit.Test

class SoundControlModelTest {
    private enum class Command { ENABLE, OFF, CRASH_ENABLE, CRASH_OFF, RESTART, USER_STOP, REVOKE, GRANT }

    private data class Model(val on: Boolean = false, val active: Boolean = false, val listener: Boolean = true) {
        fun after(command: Command): Model = when (command) {
            Command.ENABLE -> if (listener) copy(on = true, active = true) else this
            Command.OFF -> copy(on = false, active = false)
            Command.CRASH_ENABLE -> copy(on = true, active = false)
            Command.CRASH_OFF -> copy(on = false)
            Command.RESTART -> if (on && listener) this else copy(active = false)
            Command.USER_STOP -> copy(active = false)
            Command.REVOKE -> copy(active = false, listener = false)
            Command.GRANT -> copy(listener = true)
        }
    }

    // why: v2.3–7 let background events release the rule while reserving activation for a user action.
    // catches: restart relatching a stopped rule, double add on repeated enable, stale journal recovery
    // activating a half-created rule, or reconciling a foreign rule.
    @Test
    fun `seeded command traces agree with the release only model after each command`() = runTest {
        val random = Random(20261120)
        val mismatches = mutableListOf<String>()
        var generatedStops = 0
        var observedStops = 0
        var generatedCrashes = 0
        var observedCrashes = 0
        var observedOffCrashes = 0

        repeat(48) { trace ->
            val fixture = SoundControlFixture(SoundIntent(), FOREIGN)
            var controller = fixture.controller()
            var model = Model()
            var needsDrain = false
            val commands = List(32) { Command.entries[random.nextInt(Command.entries.size)] } + Command.RESTART
            for ((step, proposed) in commands.withIndex()) {
                val command = when {
                    needsDrain -> Command.RESTART
                    proposed == Command.CRASH_ENABLE && (model.on || !model.listener) -> Command.RESTART
                    proposed == Command.CRASH_OFF && !model.active -> Command.RESTART
                    proposed == Command.ENABLE && !model.listener -> Command.RESTART
                    else -> proposed
                }
                val before = model
                val ownedBefore = fixture.rules.values.filter { it.ownerPackage == OWNER }
                val mutationCount = fixture.mutations.size
                val activationCount = fixture.activations.size

                when (command) {
                    Command.ENABLE -> controller.enable()
                    Command.OFF -> controller.off()
                    Command.CRASH_ENABLE -> {
                        generatedCrashes++
                        fixture.crashAfterAdd = true
                        val crash = runCatching { controller.enable() }.exceptionOrNull()
                        fixture.crashAfterAdd = false
                        if (crash is ProcessDeath && fixture.intent.desiredOn &&
                            fixture.rules.values.count { it.ownerPackage == OWNER && !it.active } == 1
                        ) observedCrashes++
                    }
                    Command.CRASH_OFF -> {
                        fixture.crashBeforeRemove = true
                        val crash = runCatching { controller.off() }.exceptionOrNull()
                        fixture.crashBeforeRemove = false
                        if (crash is ProcessDeath && !fixture.intent.desiredOn &&
                            fixture.rules.values.any { it.ownerPackage == OWNER && it.active }
                        ) observedOffCrashes++
                    }
                    Command.RESTART -> {
                        controller = fixture.controller()
                        controller.onAppStart()
                    }
                    Command.USER_STOP -> {
                        if (before.active) generatedStops++
                        if (ownedBefore.any { it.active }) observedStops++
                        for (rule in ownedBefore) fixture.rules[rule.id] = rule.copy(active = false)
                    }
                    Command.REVOKE -> {
                        fixture.grants = fixture.grants.copy(listener = false)
                        controller.onListenerConnected()
                    }
                    Command.GRANT -> fixture.grants = fixture.grants.copy(listener = true)
                }

                needsDrain = command == Command.CRASH_ENABLE || command == Command.CRASH_OFF
                model = before.after(command)
                val owned = fixture.rules.values.filter { it.ownerPackage == OWNER }
                val activations = fixture.activations.drop(activationCount)
                val mutations = fixture.mutations.drop(mutationCount)
                val explicitIntent = when (command) {
                    Command.ENABLE, Command.CRASH_ENABLE -> true
                    Command.OFF, Command.CRASH_OFF -> false
                    Command.RESTART, Command.USER_STOP, Command.REVOKE, Command.GRANT -> null
                }
                val errors = buildList {
                    if (explicitIntent != null && fixture.intent.desiredOn != explicitIntent) {
                        add("journal On=${fixture.intent.desiredOn}, commanded On=$explicitIntent")
                    }
                    if (owned.any { it.active } != model.active) add("owned rules=$owned, model active=${model.active}")
                    if (owned.size > 1) add("duplicate owned rules $owned")
                    if (fixture.rules["theirs"] != FOREIGN || mutations.any { it.endsWith(":theirs") || ":theirs:" in it }) {
                        add("foreign mutation $mutations")
                    }
                    if (activations.size > 1 || activations.any {
                            command != Command.ENABLE || it != fixture.intent.generation
                        }
                    ) add("activation without matching user action $activations")
                    if (!needsDrain && !fixture.intent.desiredOn && owned.any { it.active }) {
                        add("active owned rule after Off had a chance to drain")
                    }
                }
                if (errors.isNotEmpty() && mismatches.size < 8) mismatches += "$trace/$step $command $errors"
            }
        }
        assertThat(generatedStops).isGreaterThan(0)
        assertThat(generatedCrashes).isGreaterThan(0)
        assertThat(mismatches).isEmpty()
        assertThat(observedStops).isGreaterThan(0)
        assertThat(observedCrashes).isEqualTo(generatedCrashes)
        assertThat(observedOffCrashes).isGreaterThan(0)
    }
}
