package com.fem.linexa.service.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RFS-01` / `RFS-08` identity-provider tests.
 *
 * These exercise the exact resolver the production provider uses, without
 * calling `android.os.Process` or `android.system.Os`. The audit device's
 * shape is the central case: app UID `10672` is absent from host
 * `/etc/passwd`, so the primary gid must come from the Android API and the
 * missing name lookup must degrade instead of failing.
 */
class ProcessIdentityProviderTest {

    @Test
    fun `primary gid comes from the Android API when host passwd is missing`() {
        val identity = resolveProcessIdentity(
            uid = 10672,
            apiGid = 10672,
            procStatusText = null,
            passwdText = null,
            groupText = null
        )

        assertEquals(10672, identity?.uid)
        assertEquals("the Android gid must not depend on host /etc/passwd", 10672, identity?.primaryGid)
        assertNull("no host passwd entry means no optional name", identity?.accountName)
        assertTrue("a process with no Groups: line has no supplementary gids", identity?.supplementaryGids?.isEmpty() == true)
    }

    @Test
    fun `gid falls back to proc status only when the Android API is unavailable`() {
        val status = "Name:\tcom.fem.linexa\nGid:\t10672\t10672\t10672\t10672\nGroups:\t3003 9997 10672\n"

        val identity = resolveProcessIdentity(
            uid = 10672,
            apiGid = null,
            procStatusText = status,
            passwdText = null,
            groupText = null
        )

        assertEquals(10672, identity?.primaryGid)
        assertEquals(listOf(3003, 9997, 10672), identity?.supplementaryGids)
    }

    @Test
    fun `the Android API gid wins over a contradictory proc status line`() {
        val identity = resolveProcessIdentity(
            uid = 10672,
            apiGid = 10234,
            procStatusText = "Gid:\t1\t1\t1\t1\n",
            passwdText = null,
            groupText = null
        )

        assertEquals(10234, identity?.primaryGid)
    }

    @Test
    fun `no uid means no identity and therefore a loud fixup failure`() {
        assertNull(resolveProcessIdentity(null, 10234, null, null, null))
    }

    @Test
    fun `no gid from any source means no identity`() {
        assertNull(resolveProcessIdentity(10672, null, "Name:\tcom.fem.linexa\n", null, null))
    }

    @Test
    fun `a matching host passwd entry supplies only the optional name`() {
        val passwd = "root:x:0:0:root:/root:/bin/sh\nu0_a672:x:10672:10672::/:/sbin/nologin\n"

        val identity = resolveProcessIdentity(10672, 10672, null, passwd, null)

        assertEquals("u0_a672", identity?.accountName)
    }

    @Test
    fun `malformed or non-matching passwd lines are ignored`() {
        val passwd = """
            malformed
            broken:x:not-a-number:1::/:/bin/sh
            other:x:99999:99999::/:/bin/sh
            short:x:10672
        """.trimIndent()

        assertNull(parsePasswdAccountName(passwd, 10672))
    }

    @Test
    fun `a blank passwd name is ignored`() {
        assertNull(parsePasswdAccountName(":x:10672:10672::/:/bin/sh\n", 10672))
    }

    @Test
    fun `supplementary groups parse the real proc status shape`() {
        val status = """
            Name:   com.fem.linexa
            Umask:  0077
            Groups: 1077 3003 9997 20672 50672 99909997
            VmPeak:   123456 kB
        """.trimIndent()

        assertEquals(
            listOf(1077, 3003, 9997, 20672, 50672, 99909997),
            parseProcStatusGroups(status)
        )
    }

    @Test
    fun `supplementary groups tolerate malformed tokens and duplicates`() {
        assertEquals(
            listOf(3003, 9997),
            parseProcStatusGroups("Groups: 3003 nope 9997 3003\n")
        )
    }

    @Test
    fun `an empty or absent Groups line yields no supplementary gids`() {
        assertTrue(parseProcStatusGroups(null).isEmpty())
        assertTrue(parseProcStatusGroups("Name: x\n").isEmpty())
        assertTrue(parseProcStatusGroups("Groups:\n").isEmpty())
    }

    @Test
    fun `host group names map gid to the first usable name`() {
        val group = """
            root:x:0:
            aid_inet:x:3003:
            malformed
            duplicate:x:3003:
            aid_everybody:x:9997:
        """.trimIndent()

        val names = parseGroupNamesByGid(group)

        assertEquals("aid_inet", names[3003])
        assertEquals("aid_everybody", names[9997])
        assertEquals("root", names[0])
        assertEquals(3, names.size)
    }

    @Test
    fun `the audit-device identity resolves without any host passwd entry`() {
        val status = "Gid:\t10672\t10672\t10672\t10672\nGroups:\t3003 9997 20672\n"

        val identity = resolveProcessIdentity(
            uid = 10672,
            apiGid = 10672,
            procStatusText = status,
            passwdText = "root:x:0:0:root:/root:/bin/sh\n",
            groupText = "root:x:0:\n"
        )

        assertEquals(10672, identity?.primaryGid)
        assertEquals(listOf(3003, 9997, 20672), identity?.supplementaryGids)
        assertNull("host passwd has no app entry; installation must still proceed", identity?.accountName)
    }
}
