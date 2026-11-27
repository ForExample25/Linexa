package com.fem.linexa.ui.misc

import com.fem.linexa.domain.workspace.DistroInfo
import com.fem.linexa.domain.workspace.RootfsCheck
import com.fem.linexa.domain.workspace.RootfsInspector
import com.fem.linexa.domain.workspace.RuntimeConfig
import com.fem.linexa.domain.workspace.TerminalSettings
import com.fem.linexa.domain.workspace.WorkspaceLaunchValidator
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceRepository
import com.fem.linexa.domain.workspace.WorkspaceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * P0-4 (2026-11-23 performance remediation): the SAF provider's workspace
 * index. Pins the properties that removed the per-query `runBlocking`
 * repository read and the all-workspace validator sweep.
 */
class BrowsableWorkspaceCacheTest {

    private class FakeInspector : RootfsInspector {
        val probes = HashMap<String, Int>()
        val blocked = mutableSetOf<String>()

        override fun checkRootfs(workspaceId: String): RootfsCheck = RootfsCheck.Ready

        override fun hasValidRootfs(workspaceId: String): Boolean = true

        override fun getRootfsSize(workspaceId: String): Long = 0L

        override fun hasAnyRegularFile(workspaceId: String): Boolean {
            probes[workspaceId] = (probes[workspaceId] ?: 0) + 1
            return workspaceId !in blocked
        }
    }

    private fun metadata(
        id: String,
        name: String = id,
        status: WorkspaceStatus = WorkspaceStatus.Ready
    ) = WorkspaceMetadata(
        id = id,
        status = status,
        distro = DistroInfo(name = "Debian", version = "13", arch = "arm64"),
        displayName = name,
        avatarLetter = name.take(1).uppercase(),
        avatarColorHex = "#112233",
        rootfsPath = "/tmp/$id/rootfs",
        createdAt = 0L,
        runtimeConfig = RuntimeConfig(),
        terminalSettings = TerminalSettings(),
        featureToggles = emptyMap()
    )

    private fun repository(flow: kotlinx.coroutines.flow.Flow<List<WorkspaceMetadata>>): WorkspaceRepository {
        val repo = Mockito.mock(WorkspaceRepository::class.java)
        Mockito.`when`(repo.observeAll()).thenReturn(flow)
        return repo
    }

    private fun cache(
        repo: WorkspaceRepository,
        inspector: FakeInspector,
        clock: () -> Long,
        readinessTtlMs: Long = 30_000L,
        snapshotTtlMs: Long = 30_000L
    ) = BrowsableWorkspaceCache(
        repository = repo,
        validator = WorkspaceLaunchValidator(inspector),
        clock = clock,
        readinessTtlMs = readinessTtlMs,
        snapshotTtlMs = snapshotTtlMs,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    @Test
    fun `only Ready workspaces with a real file are browsable`() {
        val inspector = FakeInspector().apply { blocked += "empty" }
        val flow = MutableStateFlow(
            listOf(
                metadata("ready"),
                // Installed is launchable but was never listed by the provider
                // (LinexaDocumentsMapping.isBrowsable requires Ready) — the
                // cache preserves exactly that contract.
                metadata("installed", status = WorkspaceStatus.Installed),
                metadata("empty"),
                metadata("broken", status = WorkspaceStatus.Broken("nope")),
                metadata("created", status = WorkspaceStatus.Created)
            )
        )
        val cache = cache(repository(flow), inspector, clock = { 0L })

        assertEquals(listOf("ready"), cache.browsableWorkspaces().map { it.id })
        assertEquals("only Ready rows incur the disk probe", 2, inspector.probes.size)
        assertTrue(inspector.probes.containsKey("empty"))
        assertFalse(inspector.probes.containsKey("installed"))
        assertFalse(inspector.probes.containsKey("broken"))
    }

    @Test
    fun `display names are sanitized and made unique across the listing`() {
        val inspector = FakeInspector()
        val flow = MutableStateFlow(
            listOf(
                metadata("a", name = "My/Debian?"),
                metadata("b", name = "My/Debian?")
            )
        )
        val names = cache(repository(flow), inspector, clock = { 0L })
            .browsableWorkspaces()
            .associate { it.id to it.displayName }

        assertEquals(2, names.values.toSet().size)
        assertFalse(names.getValue("a").contains('/'))
        assertFalse(names.getValue("a").contains('?'))
    }

    @Test
    fun `find evaluates only the requested workspace`() {
        val inspector = FakeInspector()
        val flow = MutableStateFlow(listOf(metadata("a"), metadata("b")))
        val cache = cache(repository(flow), inspector, clock = { 0L })

        val found = cache.find("a")
        assertEquals("a", found?.id)
        assertEquals(1, inspector.probes["a"])
        assertNull("queryDocument must not evaluate unrelated workspaces", inspector.probes["b"])
    }

    @Test
    fun `find returns null for a stale or non-browsable id`() {
        val inspector = FakeInspector().apply { blocked += "empty" }
        val flow = MutableStateFlow(listOf(metadata("ready"), metadata("empty")))
        val cache = cache(repository(flow), inspector, clock = { 0L })

        assertEquals("ready", cache.find("ready")?.id)
        assertNull(cache.find("deleted"))
        assertNull(cache.find("empty"))
    }

    @Test
    fun `a Room emission updates the snapshot`() {
        val inspector = FakeInspector()
        val flow = MutableStateFlow(listOf(metadata("a")))
        val cache = cache(repository(flow), inspector, clock = { 0L })

        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        flow.value = listOf(metadata("a"), metadata("b"))
        assertEquals(listOf("a", "b"), cache.browsableWorkspaces().map { it.id })
    }

    @Test
    fun `readiness is cached within its TTL and re-probed after it`() {
        val inspector = FakeInspector()
        var now = 0L
        val flow = MutableStateFlow(listOf(metadata("a")))
        val cache = cache(
            repository(flow),
            inspector,
            clock = { now },
            readinessTtlMs = 100L,
            snapshotTtlMs = 0L
        )

        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        assertEquals(1, inspector.probes["a"])

        now = 50L
        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        assertEquals("within the TTL the disk is not re-probed", 1, inspector.probes["a"])

        now = 150L
        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        assertEquals("after the TTL the rootfs is re-checked", 2, inspector.probes["a"])
    }

    @Test
    fun `a row emission clears readiness verdicts`() {
        val inspector = FakeInspector()
        val flow = MutableStateFlow(listOf(metadata("a")))
        val cache = cache(repository(flow), inspector, clock = { 0L })

        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        assertEquals(1, inspector.probes["a"])

        flow.value = listOf(metadata("a", name = "renamed"))
        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        assertEquals(
            "a changed row means the previous readiness verdict is no longer trusted",
            2,
            inspector.probes["a"]
        )
    }

    @Test
    fun `a repository failure yields an empty valid listing`() {
        val inspector = FakeInspector()
        val cache = cache(
            repository(flow { throw IllegalStateException("db unavailable") }),
            inspector,
            clock = { 0L }
        )
        assertTrue(cache.browsableWorkspaces().isEmpty())
    }

    @Test
    fun `a cold read never waits for the repository and converges afterwards`() {
        // LINEXA-PERF-008: the first listing used to `runBlocking` on Room on
        // whatever thread asked (a Binder thread for the SAF provider).
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = repository(flow {
            gate.await()
            emit(listOf(metadata("ready")))
        })
        val inspector = FakeInspector()
        val cache = cache(repo, inspector, clock = { 0L })

        // The collector is parked on `gate`, so this returns the empty view
        // immediately instead of waiting for the database.
        assertTrue("a cold read must not block", cache.browsableWorkspaces().isEmpty())

        gate.complete(Unit)
        assertEquals(
            "the asynchronous first snapshot must become visible",
            listOf("ready"),
            cache.browsableWorkspaces().map { it.id }
        )
    }

    @Test
    fun `the first published snapshot notifies exactly once`() {
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        val repo = repository(MutableStateFlow(listOf(metadata("a"))))
        val cache = BrowsableWorkspaceCache(
            repository = repo,
            validator = WorkspaceLaunchValidator(FakeInspector()),
            clock = { 0L },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onFirstRowsPublished = { notifications.incrementAndGet() }
        )

        assertEquals(listOf("a"), cache.browsableWorkspaces().map { it.id })
        assertEquals("a cold listing is refreshed when the first snapshot lands", 1, notifications.get())
        cache.browsableWorkspaces()
        cache.browsableWorkspaces()
        assertEquals("later queries must not re-notify", 1, notifications.get())
    }
}
