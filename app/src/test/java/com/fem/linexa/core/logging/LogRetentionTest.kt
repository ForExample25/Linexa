package com.fem.linexa.core.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F-033 / R-40: the per-workspace diagnostic logs are bounded.
 *
 * The arithmetic lives in [LogRetention] (plain files) precisely so these
 * cases can run on the JVM; the Android-facing manager only supplies
 * `filesDir` and the append cadence.
 */
class LogRetentionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a file under the cap is untouched`() {
        val file = tmp.newFile("runtime.log").apply { writeText("short\n") }
        val before = file.readText()

        assertFalse(LogRetention.trimToTail(file, keepBytes = 1024))

        assertEquals(before, file.readText())
    }

    @Test
    fun `trimming keeps the newest bytes and starts at a line boundary`() {
        val file = tmp.newFile("runtime.log")
        val lines = (1..500).joinToString("") { "line-$it\n" }
        file.writeText(lines)

        assertTrue(LogRetention.trimToTail(file, keepBytes = 400))

        val retained = file.readText()
        assertTrue("the tail must survive", retained.contains("line-500"))
        assertFalse("the head must be gone", retained.contains("line-1\n"))
        assertTrue("the trim must not start mid-line", retained.startsWith("line-"))
        assertTrue("size is bounded", file.length() <= 400)
    }

    @Test
    fun `the total cap deletes the oldest non-essential files first`() {
        val oldest = tmp.newFile("old.log").apply {
            writeText("x".repeat(600))
            setLastModified(1_000L)
        }
        val middle = tmp.newFile("middle.log").apply {
            writeText("x".repeat(600))
            setLastModified(2_000L)
        }
        val essential = tmp.newFile(LogRetention.ESSENTIAL_FILE).apply {
            writeText("x".repeat(600))
            setLastModified(3_000L)
        }

        val deleted = LogRetention.enforceTotalCap(
            files = listOf(oldest, middle, essential),
            // 1800 bytes total: evicting the 600-byte oldest file brings the
            // directory to 1200, still above the cap, so a correct
            // implementation would also evict `middle`. Use a cap that one
            // eviction satisfies so this test pins "oldest first, then stop".
            maxTotalBytes = 1_500L
        )

        assertEquals(listOf("old.log"), deleted)
        assertFalse("oldest goes first", oldest.exists())
        assertTrue("newer file survives", middle.exists())
        assertTrue("latest-error.log is never evicted", essential.exists())
    }

    @Test
    fun `nothing is deleted while the workspace is under the cap`() {
        val a = tmp.newFile("a.log").apply { writeText("12345") }
        val b = tmp.newFile("b.log").apply { writeText("12345") }

        val deleted = LogRetention.enforceTotalCap(
            files = listOf(a, b),
            maxTotalBytes = 100L
        )

        assertTrue(deleted.isEmpty())
        assertTrue(a.exists() && b.exists())
    }
}
