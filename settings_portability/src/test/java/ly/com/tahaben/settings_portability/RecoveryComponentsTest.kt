package ly.com.tahaben.settings_portability

import org.junit.Assert.*
import org.junit.Test

class RecoveryComponentsTest {
    private class Death : Error()
    private class Rig : ComponentStates, ComponentStateSnapshot {
        val marker = "app.Recovery"
        val manifest = linkedMapOf(marker to false, "app.Main" to true, "work.Proxy" to false,
            "app.Hidden" to false, "app.UserDisabled" to true, "app.UntilUsed" to true)
        val raw = linkedMapOf(marker to 0, "app.Main" to 0, "work.Proxy" to 1,
            "app.Hidden" to 0, "app.UserDisabled" to 3, "app.UntilUsed" to 4)
        var saved: Map<String, Int>? = null
        val events = mutableListOf<String>()
        var cutAfter: Int? = null
        var writes = 0
        var failSnapshot = false
        var failSet: String? = null
        fun mutation(event: String) {
            events += event
            if (++writes == cutAfter) throw Death()
        }
        override fun list() = manifest.map { RecoveryComponent(it.key, it.value) }
        override fun get(name: String): Int = raw.getValue(name)
        override fun set(name: String, state: Int) {
            require(name in manifest) { "Removed component" }
            if (name == failSet) throw IllegalStateException("Platform toggle refused")
            raw[name] = state
            mutation("set:$name=$state")
        }
        override fun read() = saved?.toMap()
        override fun write(states: Map<String, Int>) {
            if (failSnapshot) throw IllegalStateException("Snapshot write failed")
            saved = states.toMap()
            mutation("snapshot")
        }
        override fun clear() { saved = null; mutation("clear") }
        fun protocol() = RecoveryComponentProtocol(marker, this, this)
        fun restart() { cutAfter = null; writes = 0; events.clear() }
        fun reboot(): Rig = Rig().also { fresh ->
            fresh.manifest.clear(); fresh.manifest.putAll(manifest)
            fresh.raw.clear(); fresh.raw.putAll(raw)
            fresh.saved = saved?.toMap()
        }
    }
    private val previous = mapOf("app.Main" to 0, "work.Proxy" to 1,
        "app.Hidden" to 0, "app.UserDisabled" to 3, "app.UntilUsed" to 4)
    private val blocked = mapOf("app.Recovery" to 1, "app.Main" to 2, "work.Proxy" to 2,
        "app.Hidden" to 0, "app.UserDisabled" to 3, "app.UntilUsed" to 4)
    private val healed = previous + ("app.Recovery" to 2)

    // WHY: CLAIM(component-snapshot-first); disabling before persisting would lose original overrides on death.
    // catches: moving snapshot.write after disable, unconditional change=true, or rewriting the snapshot each start.
    @Test fun snapshotPrecedesDisablesAndRepeatedBlockDoesNothing() {
        val rig = Rig()
        assertTrue(rig.protocol().block())
        assertEquals(listOf("set:app.Recovery=1", "snapshot", "set:app.Main=2", "set:work.Proxy=2"), rig.events)
        assertEquals(previous, rig.saved)
        assertEquals(blocked, rig.raw)
        rig.restart()
        assertFalse(rig.protocol().block())
        assertEquals(emptyList<String>(), rig.events)
    }

    // WHY: raw explicit ENABLED/disabled overrides are device state, not resettable manifest defaults.
    // catches: healing everything to DEFAULT or failing to clear the snapshot before the marker.
    @Test fun healingRestoresExactOverridesAndClearsSnapshotBeforeMarker() {
        val rig = Rig()
        rig.protocol().block()
        rig.restart()
        rig.protocol().heal()
        assertEquals(healed, rig.raw)
        assertNull(rig.saved)
        assertEquals(listOf("set:app.Main=0", "set:work.Proxy=1", "clear", "set:app.Recovery=2"), rig.events)
    }

    // WHY: CLAIM(component-heal-exact); an upgrade can remove recorded classes and add never-disabled ones.
    // catches: replaying set() for a removed class, or resetting a new component that was never snapshotted.
    @Test fun healingSkipsRemovedAndLeavesNewComponentsAlone() {
        val rig = Rig()
        rig.protocol().block()
        rig.manifest.remove("work.Proxy")
        rig.raw.remove("work.Proxy")
        rig.manifest["app.New"] = true
        rig.raw["app.New"] = 0
        rig.restart()
        rig.protocol().heal()
        assertEquals((healed - "work.Proxy") + ("app.New" to 0), rig.raw)
        assertFalse(rig.events.any { it.contains("work.Proxy") || it.contains("app.New") })
        assertNull(rig.saved)
    }

    // WHY: an upgrade while blocked must preserve a new component's override before disabling it.
    // catches: ignoring new components or adding them to the snapshot after disabling them.
    @Test fun blockExtendsSnapshotForNewComponents() {
        val rig = Rig()
        rig.protocol().block()
        rig.manifest["app.New"] = false
        rig.raw["app.New"] = 1
        rig.restart()
        assertTrue(rig.protocol().block())
        assertEquals(listOf("snapshot", "set:app.New=2"), rig.events)
        assertEquals(previous + ("app.New" to 1), rig.saved)
        rig.protocol().heal()
        assertEquals(healed + ("app.New" to 1), rig.raw)
    }

    // WHY: CLAIM(component-marker-last); every persisted boundary can be the last instruction before process death.
    // catches: clearing too early, marker-first healing, or capturing already-disabled states on replay.
    @Test fun crashesAtEveryDurableBoundaryConverge() {
        // Literal four writes each: marker/snapshot/two disables; two restores/clear/marker.
        for (cut in 1..4) {
            var blockCrash = Rig().apply { cutAfter = cut }
            assertThrows(Death::class.java) { blockCrash.protocol().block() }
            blockCrash = blockCrash.reboot()
            blockCrash.protocol().block()
            assertEquals("block cut $cut", blocked, blockCrash.raw)
            assertEquals("block cut $cut", previous, blockCrash.saved)
            blockCrash.protocol().heal()
            assertEquals("block then heal cut $cut", healed, blockCrash.raw)

            var healCrash = Rig()
            healCrash.protocol().block()
            healCrash.restart()
            healCrash.cutAfter = cut
            assertThrows(Death::class.java) { healCrash.protocol().heal() }
            healCrash = healCrash.reboot()
            healCrash.protocol().heal()
            assertEquals("heal cut $cut", healed, healCrash.raw)
            assertNull("heal cut $cut", healCrash.saved)
        }
    }

    // WHY: healthy startup must not churn PackageManager or erase stale diagnostic files.
    // catches: unconditional marker disable or snapshot reads/writes before checking the disabled marker.
    @Test fun healthyMarkerOffMakesZeroWrites() {
        val rig = Rig().apply { saved = mapOf("invalid" to 99) }
        rig.protocol().heal()
        assertEquals(emptyList<String>(), rig.events)
        assertEquals(mapOf("invalid" to 99), rig.saved)
    }

    // WHY: no normal component may be disabled before a durable snapshot; corrupt state is never guessed.
    // catches: continuing after write/validation failure or silently replacing an invalid snapshot.
    @Test fun snapshotAndToggleFailuresRemainRecoverable() {
        val failedWrite = Rig().apply { failSnapshot = true }
        assertThrows(IllegalStateException::class.java) { failedWrite.protocol().block() }
        assertEquals(previous + ("app.Recovery" to 1), failedWrite.raw)
        assertNull(failedWrite.saved)
        failedWrite.failSnapshot = false
        failedWrite.protocol().block()
        failedWrite.protocol().heal()
        assertEquals(healed, failedWrite.raw)

        val failedToggle = Rig().apply { failSet = "work.Proxy" }
        assertThrows(IllegalStateException::class.java) { failedToggle.protocol().block() }
        assertEquals(previous, failedToggle.saved)
        failedToggle.failSet = null
        failedToggle.protocol().block()
        failedToggle.protocol().heal()
        assertEquals(healed, failedToggle.raw)

        val corrupt = Rig().apply { saved = mapOf("app.Main" to 99) }
        assertThrows(IllegalArgumentException::class.java) { corrupt.protocol().block() }
        assertEquals(previous + ("app.Recovery" to 1), corrupt.raw)
        assertEquals(listOf("set:app.Recovery=1"), corrupt.events)
    }
}
