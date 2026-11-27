package com.fem.linexa.ui.misc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P0-4 (2026-11-23 performance remediation): the SAF child-listing order.
 *
 * The provider used to sort with `compareBy({ !it.isDirectory }, { it.name })`
 * directly on `File`, so every comparison statted the filesystem. The
 * precomputed [orderChildRows] must preserve the exact old order — directories
 * first, then name, stable for equal keys — while statting each child once.
 */
class DocumentsProviderListingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Counts `isDirectory` calls so the test can prove the comparator is stat-free. */
    private class CountingFile(pathname: String) : File(pathname) {
        var directoryCalls = 0
            private set

        override fun isDirectory(): Boolean {
            directoryCalls++
            return super.isDirectory()
        }
    }

    @Test
    fun `directories come first and each group is sorted by name`() {
        val children = arrayOf(
            tmp.newFile("zeta.txt"),
            tmp.newFolder("beta"),
            tmp.newFile("alpha.txt"),
            tmp.newFolder("alpha")
        )

        val ordered = orderChildRows(children)

        assertEquals(
            listOf("alpha", "beta", "alpha.txt", "zeta.txt"),
            ordered.map { it.name }
        )
        assertEquals(
            listOf(true, true, false, false),
            ordered.map { it.isDirectory }
        )
    }

    @Test
    fun `sorting is stable for equal keys`() {
        val first = File("/one/same.txt")
        val second = File("/two/same.txt")

        val ordered = orderChildRows(arrayOf(first, second))

        assertEquals(
            "equal (isDirectory, name) keys must keep listFiles() order",
            listOf(first, second),
            ordered.map { it.file }
        )
    }

    @Test
    fun `isDirectory is read once per child before sorting`() {
        val a = CountingFile(tmp.newFolder("a").absolutePath)
        val b = CountingFile(tmp.newFolder("b").absolutePath)

        val ordered = orderChildRows(arrayOf(a, b))

        assertEquals(listOf("a", "b"), ordered.map { it.name })
        assertEquals("each child is statted exactly once", 1, a.directoryCalls)
        assertEquals("each child is statted exactly once", 1, b.directoryCalls)
    }

    @Test
    fun `a symlink to a directory keeps the old directory classification`() {
        val target = tmp.newFolder("target")
        val link = File(tmp.root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            Assume.assumeTrue("fixture filesystem cannot create symlinks", false)
            return
        }

        val ordered = orderChildRows(arrayOf(File(tmp.root, "zzz.txt"), link))

        assertTrue("File.isDirectory follows links — the old order did too", ordered.first().isDirectory)
        assertEquals("link", ordered.first().name)
    }

    @Test
    fun `an empty directory has no rows`() {
        assertTrue(orderChildRows(emptyArray()).isEmpty())
    }
}
