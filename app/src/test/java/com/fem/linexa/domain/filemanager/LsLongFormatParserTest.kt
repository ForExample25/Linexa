package com.fem.linexa.domain.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.115 — JVM tests for [LsLongFormatParser].
 *
 * The fixtures are **captured from real `ls -la` output**, not invented: the
 * GNU/coreutils block below was produced on-device (Android/Termux, note the
 * SELinux `.` suffix on the mode string) and the busybox block is the same
 * listing without that suffix. The previous inline parser passed no tests at
 * all and read the filename out of the *time* column, so every path it
 * produced was wrong — the class of bug these fixtures exist to pin.
 */
class LsLongFormatParserTest {

    /** Real coreutils output, `.`-suffixed modes (SELinux), piped (non-tty). */
    private val coreutils = """
        total 15
        drwx------.  3 u0_a511 u0_a511 3452 Aug 10 19:31 .
        drwx------. 12 u0_a511 u0_a511 3452 Aug 10 19:31 ..
        drwx------.  2 u0_a511 u0_a511 3452 Aug 10 19:31 adir
        -rw-------.  1 u0_a511 u0_a511   12 Aug 10 19:31 name with spaces.txt
        -rw-------.  1 u0_a511 u0_a511    0 Aug 10 19:31 regular.txt
        lrwxrwxrwx.  1 u0_a511 u0_a511   10 Aug 10 19:31 symlink_abs -> /etc/hosts
    """.trimIndent()

    /** The same listing as busybox prints it (no SELinux marker). */
    private val busybox = """
        total 15
        drwx------    3 u0_a511  u0_a511       3452 Aug 10 19:31 .
        drwx------    2 u0_a511  u0_a511       3452 Aug 10 19:31 adir
        -rw-------    1 u0_a511  u0_a511         12 Aug 10 19:31 name with spaces.txt
        lrwxrwxrwx    1 u0_a511  u0_a511         10 Aug 10 19:31 symlink_abs -> /etc/hosts
    """.trimIndent()

    // ── The headline bug ────────────────────────────────────────────────────

    @Test
    fun nameStartsAtTheNinthFieldNotTheEighth() {
        // The old parser did parts.drop(7), which is the time column, so this
        // entry came out as "19:31 regular.txt" and its path with it.
        val entry = LsLongFormatParser.parse("/root", coreutils).single { it.name == "regular.txt" }
        assertEquals("/root/regular.txt", entry.path)
    }

    @Test
    fun noEntryNameContainsATimestamp() {
        // A single assertion that the whole class of column-shift bug is gone.
        val names = LsLongFormatParser.parse("/root", coreutils).map { it.name }
        assertTrue(names.isNotEmpty())
        assertTrue(names.none { Regex("^\\d{1,2}:\\d{2}").containsMatchIn(it) })
    }

    @Test
    fun namesWithSpacesSurviveVerbatim() {
        // Re-joining split tokens with a single space corrupts any run of
        // spaces; the name is taken as a raw substring instead.
        val entry = LsLongFormatParser.parse("/", coreutils).single { it.name.startsWith("name") }
        assertEquals("name with spaces.txt", entry.name)
        assertEquals("/name with spaces.txt", entry.path)
    }

    @Test
    fun preservesConsecutiveSpacesInsideAName() {
        val line = "-rw-r--r-- 1 root root 4 Aug 10 19:31 two  spaces.txt"
        val entry = LsLongFormatParser.parse("/tmp", line).single()
        assertEquals("two  spaces.txt", entry.name)
    }

    // ── Both ls variants ────────────────────────────────────────────────────

    @Test
    fun parsesCoreutilsAndBusyboxIdentically() {
        val fromCoreutils = LsLongFormatParser.parse("/x", coreutils)
            .filter { it.name != "regular.txt" }
            .map { it.name to it.isDirectory }
        val fromBusybox = LsLongFormatParser.parse("/x", busybox).map { it.name to it.isDirectory }
        assertEquals(fromCoreutils, fromBusybox)
    }

    @Test
    fun acceptsTheSelinuxAclMarkerOnTheModeString() {
        val entry = LsLongFormatParser.parse("/", coreutils).first()
        assertTrue(entry.permissions.endsWith("."))
    }

    @Test
    fun acceptsTheGnuAclPlusMarker() {
        val line = "-rw-rw-r--+ 1 root root 5 Aug 10 19:31 acl.txt"
        assertEquals("acl.txt", LsLongFormatParser.parse("/", line).single().name)
    }

    // ── Entries the old parser mangled ──────────────────────────────────────

    @Test
    fun symlinkTargetIsNotPartOfTheName() {
        val entry = LsLongFormatParser.parse("/etc", coreutils).single { it.isSymlink }
        assertEquals("symlink_abs", entry.name)
        assertEquals("/etc/symlink_abs", entry.path)
        assertEquals("/etc/hosts", entry.linkTarget)
    }

    @Test
    fun symlinkIsNotReportedAsADirectory() {
        // A symlink to a directory still has mode 'l'; treating it as a dir
        // here would make the row navigate rather than resolve.
        val entry = LsLongFormatParser.parse("/", coreutils).single { it.isSymlink }
        assertFalse(entry.isDirectory)
    }

    @Test
    fun deviceNodeMajorMinorIsNotReadAsASize() {
        // Real /dev output: the size column is "1, 3", so a fixed-index parser
        // both mis-reads the size AND shifts the name by one.
        val line = "crw-rw-rw-. 1 root root 1, 3 Jan  1  1970 null"
        val entry = LsLongFormatParser.parse("/dev", line).single()
        assertEquals("null", entry.name)
        assertFalse(entry.hasByteSize)
        assertEquals(0L, entry.size)
    }

    @Test
    fun deviceNodeWithAFusedMajorMinorColumn() {
        val line = "brw-rw---- 1 root disk 8,0 Aug 10 19:31 sda"
        val entry = LsLongFormatParser.parse("/dev", line).single()
        assertEquals("sda", entry.name)
        assertFalse(entry.hasByteSize)
    }

    @Test
    fun regularFileReportsARealByteSize() {
        val entry = LsLongFormatParser.parse("/", coreutils).single { it.name.startsWith("name") }
        assertTrue(entry.hasByteSize)
        assertEquals(12L, entry.size)
    }

    // ── Date formats ────────────────────────────────────────────────────────

    @Test
    fun handlesTheYearFormUsedForOldFiles() {
        // Files older than six months print a year instead of a time.
        val line = "-rw-r--r-- 1 root root 42 Jan  1  1970 epoch.txt"
        val entry = LsLongFormatParser.parse("/", line).single()
        assertEquals("epoch.txt", entry.name)
        assertEquals("Jan 1 1970", entry.lastModified)
        assertEquals(42L, entry.size)
    }

    @Test
    fun handlesLongIsoTimeStyle() {
        // `ls --time-style=long-iso` is TWO date columns, not three, so any
        // fixed-index parser is off by one against it.
        val line = "-rw-r--r-- 1 root root 7 2026-08-10 19:31 iso.txt"
        val entry = LsLongFormatParser.parse("/", line).single()
        assertEquals("iso.txt", entry.name)
        assertEquals(7L, entry.size)
    }

    @Test
    fun handlesFullIsoTimeStyle() {
        val line = "-rw-r--r-- 1 root root 7 2026-08-10 19:31:02.123456789+0200 full.txt"
        assertEquals("full.txt", LsLongFormatParser.parse("/", line).single().name)
    }

    @Test
    fun handlesADayFirstLocaleOrdering() {
        val line = "-rw-r--r-- 1 root root 7 10 Aug 19:31 dayfirst.txt"
        assertEquals("dayfirst.txt", LsLongFormatParser.parse("/", line).single().name)
    }

    // ── Lines that must be skipped ──────────────────────────────────────────

    @Test
    fun dropsTheTotalHeaderWithoutSpecialCasingIt() {
        // "total 15" has no mode field, so the mode check drops it.
        assertTrue(LsLongFormatParser.parse("/", "total 15").isEmpty())
    }

    @Test
    fun dropsDotAndDotDot() {
        val names = LsLongFormatParser.parse("/", coreutils).map { it.name }
        assertFalse(names.contains("."))
        assertFalse(names.contains(".."))
    }

    @Test
    fun dropsBlankAndGarbageLines() {
        val output = "\n   \nls: cannot access '/nope': No such file or directory\n"
        assertTrue(LsLongFormatParser.parse("/", output).isEmpty())
    }

    @Test
    fun toleratesLeadingWhitespaceOnALine() {
        val line = "   -rw-r--r-- 1 root root 3 Aug 10 19:31 indented.txt"
        assertEquals("indented.txt", LsLongFormatParser.parse("/", line).single().name)
    }

    @Test
    fun toleratesCarriageReturns() {
        val line = "-rw-r--r-- 1 root root 3 Aug 10 19:31 crlf.txt\r"
        assertEquals("crlf.txt", LsLongFormatParser.parse("/", line).single().name)
    }

    @Test
    fun dropsALineWhoseModeFieldIsNotAModeString() {
        val line = "notamode 1 root root 3 Aug 10 19:31 x.txt"
        assertTrue(LsLongFormatParser.parse("/", line).isEmpty())
    }

    @Test
    fun dropsALineWithNoDateBlockAtAll() {
        val line = "-rw-r--r-- 1 root root 3 whenever x.txt"
        assertTrue(LsLongFormatParser.parse("/", line).isEmpty())
    }

    // ── Types ───────────────────────────────────────────────────────────────

    @Test
    fun recognisesDirectories() {
        val entry = LsLongFormatParser.parse("/", coreutils).single { it.name == "adir" }
        assertTrue(entry.isDirectory)
        assertFalse(entry.isSymlink)
    }

    @Test
    fun recognisesFifosAndSockets() {
        val output = """
            prw-r--r-- 1 root root 0 Aug 10 19:31 apipe
            srwxr-xr-x 1 root root 0 Aug 10 19:31 asocket
        """.trimIndent()
        val names = LsLongFormatParser.parse("/run", output).map { it.name }
        assertEquals(listOf("apipe", "asocket"), names)
    }

    @Test
    fun setuidAndStickyModesAreStillValidModes() {
        val output = """
            -rwsr-xr-x 1 root root 40 Aug 10 19:31 su
            drwxrwxrwt 2 root root 80 Aug 10 19:31 tmp
        """.trimIndent()
        assertEquals(listOf("su", "tmp"), LsLongFormatParser.parse("/", output).map { it.name })
    }

    // ── Path joining ────────────────────────────────────────────────────────

    @Test
    fun joinsPathsWithoutDoubleSlashAtRoot() {
        assertEquals("/etc", LsLongFormatParser.joinPath("/", "etc"))
    }

    @Test
    fun joinsNestedPaths() {
        assertEquals("/etc/apt/sources.list", LsLongFormatParser.joinPath("/etc/apt", "sources.list"))
    }

    @Test
    fun joinsWhenTheDirectoryAlreadyEndsInASlash() {
        assertEquals("/etc/apt/x", LsLongFormatParser.joinPath("/etc/apt/", "x"))
    }

    @Test
    fun pathsAreResolvedAgainstTheListedDirectoryNotTheRoot() {
        val entries = LsLongFormatParser.parse("/etc/apt", coreutils)
        assertTrue(entries.all { it.path.startsWith("/etc/apt/") })
    }

    @Test
    fun anEmptyDirectoryArgumentFallsBackToRoot() {
        assertEquals("/x", LsLongFormatParser.joinPath("", "x"))
    }

    @Test
    fun dotfilesAreParsedLikeAnyOtherEntry() {
        val line = "-rw------- 1 root root 9 Aug 10 19:31 .bashrc"
        val entry = LsLongFormatParser.parse("/root", line).single()
        assertEquals(".bashrc", entry.name)
        assertEquals("/root/.bashrc", entry.path)
    }

    @Test
    fun aNameThatLooksLikeAMonthIsStillANameNotADateColumn() {
        // The date block is found at its earliest legal position, so a file
        // literally named "Aug" cannot be mistaken for the date.
        val line = "-rw-r--r-- 1 root root 5 Aug 10 19:31 Aug"
        val entry = LsLongFormatParser.parse("/", line).single()
        assertEquals("Aug", entry.name)
        assertEquals(5L, entry.size)
    }

    @Test
    fun aSymlinkWithoutAnArrowStillYieldsAName() {
        // Defensive: a truncated line must not produce an empty name.
        val line = "lrwxrwxrwx 1 root root 4 Aug 10 19:31 dangling"
        val entry = LsLongFormatParser.parse("/", line).single()
        assertEquals("dangling", entry.name)
        assertNull(entry.linkTarget)
    }
}
