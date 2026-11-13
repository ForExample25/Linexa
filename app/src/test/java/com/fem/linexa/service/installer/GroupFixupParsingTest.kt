package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group-fixup parsing verification (P0-3 §1.62, harden §1.64).
 *
 * The original bug: `id -Gn` outputs all group names space-separated on one
 * line; `.lines()` then `.zip()` produced one malformed entry whose "name"
 * was the whole space-separated string.
 *
 * The P0-3 fix: split on whitespace, filter empties, assert equal lengths.
 *
 * §1.64 hardens that: on Android/busybox there may be NO /etc/group name
 * database readable by the app, so `id -Gn` correctly returns zero names
 * while `id -G` still returns the numeric GIDs. The fixup must never
 * require equal lengths: pairing is positional and a missing/unusable name
 * falls back to the numeric GID. These tests exercise the REAL production
 * function [groupFixupPairs] (not a mirror) plus the raw-output parsing.
 */
class GroupFixupParsingTest {

    /**
     * Mirrors the fixed parsing logic from RootfsFixupService.fixUp
     * (whitespace split + empty filter + trim).
     */
    private fun parseIdOutput(stdout: String): List<String> =
        stdout.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun singleLineSpaceSeparatedNamesAreCorrectlySplit() {
        val idGnOutput = "root aid_external_storage aid_inet aid_everybody\n"
        val names = parseIdOutput(idGnOutput)
        assertEquals(4, names.size)
        assertEquals("aid_everybody", names[3])
    }

    @Test
    fun singleLineSpaceSeparatedIdsAreCorrectlySplit() {
        val idGOutput = "0 1077 3003 9997\n"
        val ids = parseIdOutput(idGOutput)
        assertEquals(4, ids.size)
        assertEquals("9997", ids[3])
    }

    @Test
    fun linuxSameLengthNamesAndIdsPairByPosition() {
        // Normal Linux: -Gn and -G are same length, same order → names kept.
        val names = parseIdOutput("root aid_inet\n")
        val ids = parseIdOutput("0 3003\n")
        assertEquals(names.size, ids.size)
        val pairs = groupFixupPairs(names, ids)
        assertEquals(2, pairs.size)
        assertEquals("root" to "0", pairs[0])
        assertEquals("aid_inet" to "3003", pairs[1])
    }

    @Test
    fun trailingNewlineDoesNotProduceEmptyEntry() {
        val output = "root aid_inet\n"
        val parsed = parseIdOutput(output)
        assertTrue("No empty entries from trailing newline", parsed.none { it.isEmpty() })
        assertEquals(2, parsed.size)
    }

    @Test
    fun idGnReturnsNoOutputFallsBackToNumericGids() {
        // Android busybox: 0 names vs N ids. MUST NOT fail — every group
        // falls back to its numeric GID as the name.
        val names = parseIdOutput("") // id -Gn output empty
        val ids = parseIdOutput("0 1077 3003 9997 9998 9999 9996\n")
        assertTrue("Android has no group names but has gids", names.isEmpty() && ids.size == 7)
        val pairs = groupFixupPairs(names, ids)
        assertEquals(7, pairs.size)
        pairs.forEach { (name, id) ->
            assertEquals("Fallback name must equal the numeric gid", id, name)
        }
        assertEquals("0" to "0", pairs[0])
        assertEquals("9997" to "9997", pairs[3])
    }

    @Test
    fun idGnFewerEntriesThanIdGNamePartneredWherePossible() {
        // Android partial resolution: fewer names than ids. Positional
        // names are used where available; the tail falls back to gid.
        val names = parseIdOutput("root aid_inet\n")
        val ids = parseIdOutput("0 3003 9997 9998\n")
        val pairs = groupFixupPairs(names, ids)
        assertEquals(4, pairs.size)
        assertEquals("root" to "0", pairs[0])
        assertEquals("aid_inet" to "3003", pairs[1])
        assertEquals("9997" to "9997", pairs[2])
        assertEquals("9998" to "9998", pairs[3])
    }

    @Test
    fun unusableNameFallsBackToNumericGid() {
        // A name that would corrupt /etc/group (colon or whitespace, or an
        // empty string) must be replaced by the numeric gid.
        val names = listOf("bad:name", "has space", "")
        val ids = parseIdOutput("1000 2000 3000\n")
        val pairs = groupFixupPairs(names, ids)
        assertEquals(3, pairs.size)
        assertEquals("1000" to "1000", pairs[0])
        assertEquals("2000" to "2000", pairs[1])
        assertEquals("3000" to "3000", pairs[2])
    }

    @Test
    fun duplicateGidsAreCollapsedFirstWins() {
        // A redundant Android group list (same gid repeated) must not
        // produce duplicate /etc/group lines.
        val names = parseIdOutput("root aid_inet root\n")
        val ids = parseIdOutput("0 3003 0\n")
        val pairs = groupFixupPairs(names, ids)
        assertEquals(2, pairs.size)
        assertEquals("root" to "0", pairs[0])
        assertEquals("aid_inet" to "3003", pairs[1])
    }

    @Test
    fun androidBusyboxEndToEndFixupEntriesAreWellFormed() {
        // Full flow shape for the reported device failure (0 names vs 7
        // ids): every produced /etc/group line must be a valid aid_* entry
        // (exactly 3 colons, whitespace-free name) — the §1.64 fallback.
        val names = parseIdOutput("") // id -Gn: empty
        val ids = parseIdOutput("10239 3003 9997 9998 9996 1000 2000\n")
        val resolvedName = "u0_a239"
        val pairs = groupFixupPairs(names, ids)
        val lines = pairs.map { (name, id) -> "aid_$name:x:$id:root,aid_$resolvedName" }
        assertEquals(7, lines.size)
        lines.forEach { entry ->
            assertEquals("Group entry must have exactly 3 colons (4 fields): $entry", 3, entry.count { it == ':' })
            val name = entry.substringBefore(":")
            assertTrue("Group name must not contain whitespace: $entry", !name.contains(Regex("\\s")))
        }
        assertTrue("Fallback entries are numeric aid_* groups", lines.all { it.startsWith("aid_") && it.contains(":x:") })
    }

    @Test
    fun emptyOutputProducesEmptyList() {
        val names = parseIdOutput("")
        val ids = parseIdOutput("")
        assertEquals(0, names.size)
        assertEquals(0, ids.size)
        assertEquals(0, groupFixupPairs(names, ids).size)
    }

    @Test
    fun whitespaceOnlyOutputProducesEmptyList() {
        val names = parseIdOutput("   \n  \n")
        assertEquals(0, names.size)
    }

    // ── §1.65: SELinux context / diagnostic text must never reach a group line ──

    @Test
    fun selinuxContextInIdGnOutputIsRejectedAndFallsBackToNumericGid() {
        // On-device report (§1.65): `id -Gn` emitted SELinux context text.
        val (names, rejectedNames) = parseIdOutput("u:r:untrusted_app:s0:c46,c258,c512,c768\n", numericOnly = false)
        assertEquals("context token must not become a group name", 0, names.size)
        assertEquals(listOf("u:r:untrusted_app:s0:c46,c258,c512,c768"), rejectedNames)
        val (ids, rejectedGids) = parseIdOutput("10239 3003\n", numericOnly = true)
        assertEquals(listOf("10239", "3003"), ids)
        assertEquals(0, rejectedGids.size)
        val pairs = groupFixupPairs(names, ids)
        assertEquals(listOf("10239" to "10239", "3003" to "3003"), pairs)
    }

    @Test
    fun selinuxContextTokenInIdGOutputIsDroppedFromGids() {
        // The gid field is digits-only: a context token in `id -G` output
        // must be dropped, never written into /etc/group.
        val (ids, rejected) = parseIdOutput("10239 u:r:untrusted_app:s0:c46,c258,c512,c768 3003\n", numericOnly = true)
        assertEquals(listOf("10239", "3003"), ids)
        assertEquals(listOf("u:r:untrusted_app:s0:c46,c258,c512,c768"), rejected)
        val pairs = groupFixupPairs(emptyList(), ids)
        assertEquals(listOf("10239" to "10239", "3003" to "3003"), pairs)
    }

    @Test
    fun reportedContextCaseNeverProducesContextInGeneratedGroupLine() {
        // End-to-end shape of the reported device failure: whatever junk the
        // id outputs carried, every generated line is a well-formed aid_* entry
        // and no line contains any context/diagnostic text.
        val (names, _) = parseIdOutput("u:r:untrusted_app:s0:c46,c258,c512,c768 20109 aid_everybody\n", numericOnly = false)
        val (ids, _) = parseIdOutput("10239 20109 u:r:untrusted_app:s0:c46,c258,c512,c768 3003\n", numericOnly = true)
        val resolvedName = "u0_a10563"
        val lines = groupFixupPairs(names, ids).map { (name, id) -> "aid_$name:x:$id:root,aid_$resolvedName" }
        assertTrue("no context text may appear in any generated line: $lines", lines.none { it.contains("u:r:") })
        lines.forEach { entry ->
            assertEquals("Group entry must have exactly 3 colons (4 fields): $entry", 3, entry.count { it == ':' })
            val name = entry.substringBefore(":")
            assertTrue("Group name must not contain whitespace: $entry", !name.contains(Regex("\\s")))
        }
    }

    @Test
    fun mixedNamesAndNumbersInIdGnAreKept() {
        // busybox prints the bare number when a name can't be resolved —
        // numeric tokens are valid account-name tokens.
        val (names, rejected) = parseIdOutput("10563 aid_inet 3003 aid_everybody\n", numericOnly = false)
        assertEquals(listOf("10563", "aid_inet", "3003", "aid_everybody"), names)
        assertEquals(0, rejected.size)
    }

    @Test
    fun accountNameWhitelistAcceptsLegitNamesRejectsContextShapes() {
        assertTrue(isSafeAccountNameToken("u0_a10563"))
        assertTrue(isSafeAccountNameToken("aid_inet"))
        assertTrue(isSafeAccountNameToken("10563"))
        assertTrue(isSafeAccountNameToken("aid_99909997"))
        assertTrue(isSafeAccountNameToken("tiny"))
        assertFalse(isSafeAccountNameToken("u:r:untrusted_app:s0:c46,c258,c512,c768"))
        assertFalse(isSafeAccountNameToken("untrusted_app:s0"))
        assertFalse(isSafeAccountNameToken("has space"))
        assertFalse(isSafeAccountNameToken("has/slash"))
        assertFalse(isSafeAccountNameToken(""))
        assertFalse(isSafeAccountNameToken("-leading"))
        assertFalse(isSafeAccountNameToken("a".repeat(33)))
    }
}