package com.fem.linexa.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalWindowIdentityRegistryTest {

    @Test
    fun recordsAndReadsByWorkspaceAndTerminal() {
        val registry = TerminalWindowIdentityRegistry()
        val identity = TerminalWindowIdentity(
            title = "Python 3 + venv",
            appId = "builtin:python",
            iconKey = "terminal",
            iconRes = 7,
            command = "python3"
        )

        registry.record("ws-1", "T3", identity)

        assertEquals(identity, registry.get("ws-1", "T3"))
        assertNull("same terminal id in another workspace is a different window", registry.get("ws-2", "T3"))
        assertEquals(mapOf("T3" to identity), registry.snapshot("ws-1"))
    }

    @Test
    fun recordingAgainReplacesTheOlderIdentity() {
        val registry = TerminalWindowIdentityRegistry()
        registry.record("ws-1", "T0", TerminalWindowIdentity(title = "Old"))
        registry.record("ws-1", "T0", TerminalWindowIdentity(title = "New"))

        assertEquals("New", registry.get("ws-1", "T0")?.title)
    }

    @Test
    fun blankIdsAreIgnored() {
        val registry = TerminalWindowIdentityRegistry()
        registry.record("", "T0", TerminalWindowIdentity(title = "x"))
        registry.record("ws-1", "", TerminalWindowIdentity(title = "x"))

        assertEquals(emptyMap<String, TerminalWindowIdentity>(), registry.snapshot(""))
        assertEquals(emptyMap<String, TerminalWindowIdentity>(), registry.snapshot("ws-1"))
    }

    @Test
    fun pruneDropsOnlyVanishedTerminalsOfThatWorkspace() {
        val registry = TerminalWindowIdentityRegistry()
        registry.record("ws-1", "T0", TerminalWindowIdentity(title = "zero"))
        registry.record("ws-1", "T1", TerminalWindowIdentity(title = "one"))
        registry.record("ws-2", "T0", TerminalWindowIdentity(title = "other"))

        registry.prune("ws-1", setOf("T0"))

        assertEquals(setOf("T0"), registry.snapshot("ws-1").keys)
        assertEquals("other", registry.get("ws-2", "T0")?.title)
    }

    @Test
    fun removeIsIdempotentAndScoped() {
        val registry = TerminalWindowIdentityRegistry()
        registry.record("ws-1", "T0", TerminalWindowIdentity(title = "zero"))

        registry.remove("ws-2", "T0")
        assertEquals("zero", registry.get("ws-1", "T0")?.title)

        registry.remove("ws-1", "T0")
        assertNull(registry.get("ws-1", "T0"))
        registry.remove("ws-1", "T0")
    }

    @Test
    fun revisionChangesOnlyWhenTheSnapshotChanges() {
        val registry = TerminalWindowIdentityRegistry()
        val start = registry.revision.value

        registry.record("ws-1", "T0", TerminalWindowIdentity(title = "zero"))
        assertEquals(start + 1, registry.revision.value)

        registry.record("ws-1", "T0", TerminalWindowIdentity(title = "zero"))
        assertEquals("recording the same identity is not a change", start + 1, registry.revision.value)

        registry.prune("ws-1", setOf("T0"))
        assertEquals("pruning a live id is not a change", start + 1, registry.revision.value)

        registry.remove("ws-1", "missing")
        assertEquals(start + 1, registry.revision.value)

        registry.prune("ws-1", emptySet())
        assertEquals(start + 2, registry.revision.value)

        registry.remove("ws-1", "T0")
        assertEquals("already pruned", start + 2, registry.revision.value)
    }
}
