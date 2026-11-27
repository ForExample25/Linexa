package com.fem.linexa.ui.misc

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0-4 (2026-11-23 performance remediation) structural guard for the SAF
 * provider. The properties are not observable from a JVM test without a
 * ContentProvider harness, but they are exactly the ones that regress
 * silently:
 *
 *  - `queryDocument` resolves ONE workspace through the cache instead of
 *    rebuilding the whole browsable list;
 *  - child listing stats each child once (the comparator used to call
 *    `File.isDirectory` per comparison);
 *  - the Binder thread performs no repository `runBlocking` read once warm.
 */
class LinexaDocumentsProviderSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/ui/misc/LinexaDocumentsProvider.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val provider: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/ui/misc/LinexaDocumentsProvider.kt").readText()

    private val cache: String
        get() = File(moduleRoot, "src/main/java/com/fem/linexa/ui/misc/BrowsableWorkspaceCache.kt").readText()

    private fun function(name: String): String =
        Regex("(override )?fun $name\\([\\s\\S]*?\\n    \\}").find(provider)?.value ?: ""

    @Test
    fun `the provider owns a workspace cache instead of re-querying per request`() {
        assertTrue(provider.contains("BrowsableWorkspaceCache("))
        assertTrue(provider.contains("browsableCache()"))
        assertFalse(
            "the provider must not run the repository on the Binder thread per query",
            provider.contains("runBlocking {")
        )
        assertFalse(
            "the old full-list helper must stay deleted",
            provider.contains("listBrowsableWorkspaces()")
        )
        assertTrue(
            "the cache must own exactly one observeAll collector",
            cache.contains("repository.observeAll()") && cache.contains(".collect {")
        )
    }

    @Test
    fun `queryDocument resolves one workspace id`() {
        val body = function("queryDocument")
        assertTrue("queryDocument must be present", body.isNotEmpty())
        assertTrue(
            "the workspace branch must ask the cache for THIS id",
            body.contains("findBrowsableOrNull(decoded.workspaceId)")
        )
        assertFalse(
            "queryDocument must not rebuild the browsable listing",
            body.contains("browsableWorkspaces(")
        )
    }

    @Test
    fun `workspace listing failures stay fail-open`() {
        assertTrue(
            "the containment root must use the guarded listing wrapper",
            provider.contains("browsableWorkspacesOrEmpty().forEach")
        )
        assertTrue(
            "a cache/Hilt failure must degrade to an empty listing, not a broken provider",
            Regex(
                "private fun browsableWorkspacesOrEmpty\\(\\)[\\s\\S]{0,400}catch \\(e: Exception\\)[\\s\\S]{0,200}emptyList\\(\\)"
            ).containsMatchIn(provider)
        )
        assertTrue(
            "a per-id lookup failure must fall back to the document id",
            Regex(
                "private fun findBrowsableOrNull\\([\\s\\S]{0,400}catch \\(e: Exception\\)[\\s\\S]{0,200}null"
            ).containsMatchIn(provider)
        )
    }

    @Test
    fun `child rows stat each entry once`() {
        val body = function("addChildRows")
        assertTrue("addChildRows must be present", body.isNotEmpty())
        assertTrue(
            "child rows must be built through the precomputing helper",
            body.contains("orderChildRows(children)")
        )
        assertTrue(
            "isDirectory must be captured once per child before sorting",
            provider.contains("ChildRow(file = child, isDirectory = child.isDirectory, name = child.name)")
        )
        assertTrue(
            "the helper must sort by the precomputed fields",
            provider.contains("compareBy({ !it.isDirectory }, { it.name })")
        )
        assertFalse(
            "the listing function must not own a stat-per-comparison sort",
            body.contains("it.isDirectory")
        )
    }

    @Test
    fun `the readiness verdict is cached rather than re-walked per query`() {
        assertTrue(cache.contains("readinessTtlMs"))
        assertTrue(cache.contains("val readiness = HashMap<String, CachedReadiness>()"))
        assertTrue(
            "a row emission must drop the readiness verdicts",
            Regex("private fun publishLocked[\\s\\S]{0,400}readiness\\.clear\\(\\)").containsMatchIn(cache)
        )
    }
}
