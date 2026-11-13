package com.fem.linexa.domain.commands

import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceStatus
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistent Custom Commands feature: validation, encoding, and the
 * read-modify-write store (against a recording [WorkspaceRepository]).
 *
 * The properties worth pinning are the ones a user can lose data to: a save
 * must persist (and survive re-decode), an edit must UPDATE the stable record
 * rather than create a duplicate, a delete must remove exactly one record,
 * malformed persisted data must degrade to an empty list rather than a crash,
 * and nothing here may ever write into built-in command storage (there is
 * none to write into — the store owns exactly one attribute key).
 */
class CustomCommandsTest {

    // ── input validation ────────────────────────────────────────────────

    @Test
    fun aBlankNameOrCommandIsRefused() {
        assertNull(CustomCommandInput.validate("", "apt-get update"))
        assertNull(CustomCommandInput.validate("   ", "apt-get update"))
        assertNull(CustomCommandInput.validate("Update System", ""))
        assertNull(CustomCommandInput.validate("Update System", "   "))
        assertNull(CustomCommandInput.validate(null, "x"))
        assertNull(CustomCommandInput.validate("x", null))
    }

    @Test
    fun aMultiLineCommandIsRefusedBecauseTheRuntimeGuaranteesSingleLine() {
        // The execution contract (declared shortcuts and custom commands
        // alike) writes ONE line to a shell; a newline would be a second
        // command the card never displayed. Every legit single-line shell
        // construct still passes.
        assertNull(CustomCommandInput.validate("X", "apt-get update\nrm -rf /tmp"))
        assertNull(CustomCommandInput.validate("X", "echo \u0003 beep"))
        assertNotNull0(CustomCommandInput.validate("Chains", "sudo apt update && sudo apt upgrade -y"))
        assertNotNull0(CustomCommandInput.validate("Pipes", "dmesg | grep -i usb | head -5"))
        assertNotNull0(CustomCommandInput.validate("Redirects", "ls -la > files.txt 2>&1"))
        assertNotNull0(CustomCommandInput.validate("Globs", "tar -czf backup.tgz ~/projects/*.md"))
    }

    @Test
    fun inputIsTrimmedAndCapped() {
        val input = CustomCommandInput.validate("  Update System  ", "  apt-get update  ")
        assertEquals("Update System", input!!.name)
        assertEquals("apt-get update", input.command)
        assertNull(
            CustomCommandInput.validate("n".repeat(CustomCommand.MAX_NAME_CHARS + 1), "x")
        )
        assertNull(
            CustomCommandInput.validate("n", "c".repeat(CustomCommand.MAX_COMMAND_CHARS + 1))
        )
        assertNotNull0(CustomCommandInput.validate("n".repeat(CustomCommand.MAX_NAME_CHARS), "x"))
    }

    // ── codec ───────────────────────────────────────────────────────────

    @Test
    fun encodeThenDecodeRoundTripsAndKeepsOrder() {
        val records = listOf(
            CustomCommand(id = "a", name = "Update System", command = "apt-get update", createdAt = 1),
            CustomCommand(id = "b", name = "Clean Cache", command = "apt-get clean", createdAt = 2, updatedAt = 3)
        )
        val decoded = CustomCommandsCodec.decode(CustomCommandsCodec.encode(records))
        assertEquals(records, decoded)
        assertEquals(listOf("Update System", "Clean Cache"), decoded.map { it.name })
    }

    @Test
    fun malformedPersistedDataDecodesToEmptyRatherThanCrashing() {
        assertEquals(emptyList<CustomCommand>(), CustomCommandsCodec.decode(null))
        assertEquals(emptyList<CustomCommand>(), CustomCommandsCodec.decode(""))
        assertEquals(emptyList<CustomCommand>(), CustomCommandsCodec.decode("   "))
        assertEquals(emptyList<CustomCommand>(), CustomCommandsCodec.decode("{not json at all"))
        assertEquals(emptyList<CustomCommand>(), CustomCommandsCodec.decode("[{\"id\": 12}]"))
        assertEquals(emptyList<CustomCommand>(), CustomCommandsCodec.decode("\"just a string\""))
    }

    @Test
    fun recordsViolatingTheCapsAreDroppedIndividually() {
        val payload = CustomCommandsCodec.encode(
            listOf(
                CustomCommand(id = "good", name = "OK", command = "true", createdAt = 1),
                CustomCommand(id = "", name = "No Id", command = "true", createdAt = 2),
                CustomCommand(id = "long", name = "n".repeat(CustomCommand.MAX_NAME_CHARS + 1), command = "x", createdAt = 3)
            )
        )
        val decoded = CustomCommandsCodec.decode(payload)
        assertEquals(listOf("good"), decoded.map { it.id })
    }

    // ── the store ───────────────────────────────────────────────────────

    /** Records what was persisted, per workspace; behaves like the real merge. */
    private class FakeRepository : WorkspaceRepository {
        val stored = AtomicReference<Map<String, Map<String, String>>>(emptyMap())
        var writes = 0

        override fun observeAll() = throw UnsupportedOperationException()
        override fun observeById(id: String) = throw UnsupportedOperationException()
        override suspend fun getById(id: String): WorkspaceMetadata? = null
        override suspend fun insert(metadata: WorkspaceMetadata) = Unit
        override suspend fun update(metadata: WorkspaceMetadata) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun updateStatus(id: String, status: WorkspaceStatus) = Unit
        override suspend fun updateFeatureToggles(id: String, toggles: Map<String, Boolean>) = Unit
        override suspend fun updateAttributes(id: String, attributes: Map<String, String>) {
            writes++
            while (true) {
                val current = stored.get()
                val merged = (current[id] ?: emptyMap()) + attributes
                if (stored.compareAndSet(current, current + (id to merged))) return
            }
        }
    }

    @Test
    fun savePersistsAndTheRecordSurvivesAReload() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        val result = store.save("ws-1", emptyList(), "Update System", "sudo apt update && sudo apt upgrade -y")

        assertEquals(listOf("Update System"), result.map { it.name })
        // The persisted attribute decodes back to the same list — that IS the
        // "after restart" read path.
        val reloaded = CustomCommandsCodec.decode(
            fake.stored.get().getValue("ws-1")[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
        )
        assertEquals(result, reloaded)
        assertTrue(reloaded.single().createdAt > 0)
        assertNull(reloaded.single().updatedAt)
    }

    @Test
    fun eachNewCommandGetsAStableUniqueIdAppendedInCreationOrder() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        var current = store.save("ws", emptyList(), "First", "one")
        current = store.save("ws", current, "Second", "two")
        current = store.save("ws", current, "Third", "three")

        assertEquals(listOf("First", "Second", "Third"), current.map { it.name })
        assertEquals("ids must be unique", current.size, current.map { it.id }.toSet().size)
        // Creation order is the persisted order — never sorted by text.
        val reloaded = CustomCommandsCodec.decode(
            fake.stored.get().getValue("ws")[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
        )
        assertEquals(listOf("First", "Second", "Third"), reloaded.map { it.name })
    }

    @Test
    fun editingUpdatesTheExistingRecordInPlaceWithoutADuplicate() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        var current = store.save("ws", emptyList(), "Old Name", "old-command")
        val originalId = current.single().id

        current = store.save("ws", current, "New Name", "new-command", editId = originalId)

        assertEquals("an edit must not create a duplicate record", 1, current.size)
        assertEquals("the stable id survives an edit", originalId, current.single().id)
        assertEquals("New Name", current.single().name)
        assertEquals("new-command", current.single().command)
        assertNotNull0(current.single().updatedAt)
        val reloaded = CustomCommandsCodec.decode(
            fake.stored.get().getValue("ws")[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
        )
        assertEquals(current, reloaded)
    }

    @Test
    fun anEditForAnUnknownIdRefusesInsteadOfCreatingADuplicate() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        val current = store.save("ws", emptyList(), "Keep", "keep")
        val writesBefore = fake.writes

        val result = store.save("ws", current, "Sneaky", "sneaky", editId = "does-not-exist")

        assertEquals(current, result)
        assertEquals("no write for a refused mutation", writesBefore, fake.writes)
    }

    @Test
    fun anInvalidSaveIsRefusedWithoutWritingOrLosingData() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        val current = store.save("ws", emptyList(), "Keep", "keep")
        val writesBefore = fake.writes

        assertEquals(current, store.save("ws", current, "", "valid"))
        assertEquals(current, store.save("ws", current, "Valid", ""))
        assertEquals(current, store.save("ws", current, "Valid", "two\nlines"))
        assertEquals("no write for a refused mutation", writesBefore, fake.writes)
    }

    @Test
    fun deleteRemovesExactlyTheTargetRecord() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        var current = store.save("ws", emptyList(), "One", "one")
        current = store.save("ws", current, "Two", "two")
        val target = current.last()

        val after = store.delete("ws", current, target.id)

        assertEquals(listOf("One"), after.map { it.name })
        val reloaded = CustomCommandsCodec.decode(
            fake.stored.get().getValue("ws")[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
        )
        assertEquals(listOf("One"), reloaded.map { it.name })
        assertNotEquals(target.id, reloaded.single().id)
    }

    @Test
    fun deletingAnUnknownIdChangesNothing() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        val current = store.save("ws", emptyList(), "One", "one")
        val writesBefore = fake.writes

        assertEquals(current, store.delete("ws", current, "nope"))
        assertEquals(writesBefore, fake.writes)
    }

    @Test
    fun theCountCapIsEnforced() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        var current = emptyList<CustomCommand>()
        repeat(CustomCommand.MAX_COUNT) { index ->
            current = store.save("ws", current, "C$index", "c$index")
        }
        val writesBefore = fake.writes
        assertEquals(current, store.save("ws", current, "One Too Many", "nope"))
        assertEquals("the overflowing save must not write", writesBefore, fake.writes)
    }

    @Test
    fun workspaceIsolationComesFromTheStorageKey() = runBlocking {
        val fake = FakeRepository()
        val store = CustomCommandsStore(fake)
        val forA = store.save("ws-A", emptyList(), "A's command", "one")
        store.save("ws-B", emptyList(), "B's command", "two")

        // Workspace A's row carries only A's records: attributes are keyed by
        // the workspace's stable id, so a command created for workspace A can
        // never surface in workspace B.
        val persistedForA = CustomCommandsCodec.decode(
            fake.stored.get().getValue("ws-A")[CustomCommandsStore.ATTR_CUSTOM_COMMANDS]
        )
        assertEquals(listOf("A's command"), persistedForA.map { it.name })
        assertTrue(persistedForA.none { it.name == "B's command" })
    }

    private fun assertNotNull0(value: Any?) {
        assertTrue("expected non-null", value != null)
    }
}
