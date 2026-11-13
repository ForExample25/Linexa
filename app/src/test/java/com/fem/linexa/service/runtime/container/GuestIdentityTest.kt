package com.fem.linexa.service.runtime.container

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1 (2026-09-03): the guest identity model — /etc/passwd parsing,
 * uid/gid allocation and the proot identity argv.
 */
class GuestIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfsWith(passwd: String?, group: String? = null): File {
        val rootfs = tmp.newFolder("rootfs-${System.nanoTime()}")
        File(rootfs, "etc").mkdirs()
        passwd?.let { File(rootfs, "etc/passwd").writeText(it) }
        group?.let { File(rootfs, "etc/group").writeText(it) }
        return rootfs
    }

    @Test
    fun `root identity launches with -0`() {
        val identity = GuestIdentity.root()
        assertEquals(listOf("-0"), identity.prootIdentityArgs)
        assertTrue(identity.isRoot)
        assertEquals(0, identity.uid)
    }

    @Test
    fun `normal user identity launches with change-id`() {
        val identity = GuestIdentity(
            name = "linexa", uid = 1000, gid = 1000,
            home = "/home/linexa", shell = "/bin/bash", isRoot = false
        )
        assertEquals(listOf("--change-id=1000:1000"), identity.prootIdentityArgs)
        assertFalse(identity.isRoot)
    }

    @Test
    fun `passwd lines parse all seven fields`() {
        val entry = GuestIdentity.parsePasswdLine("linexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash")
        assertTrue(entry != null)
        assertEquals("linexa", entry!!.name)
        assertEquals(1000, entry.uid)
        assertEquals(1000, entry.gid)
        assertEquals("/home/linexa", entry.home)
        assertEquals("/bin/bash", entry.shell)
    }

    @Test
    fun `malformed passwd lines yield null not a crash`() {
        assertNull(GuestIdentity.parsePasswdLine("nocolons"))
        assertNull(GuestIdentity.parsePasswdLine("bad:uid:x:abc:1000:h:s"))
        assertNull(GuestIdentity.parsePasswdLine(":x:1:1::/:/bin/sh"))
    }

    @Test
    fun `resolveNamedUser finds the account and its own record wins`() {
        val rootfs = rootfsWith(
            "root:x:0:0:root:/root:/bin/bash\n" +
                "ubuntu:x:1000:1000:Ubuntu:/home/ubuntu:/bin/bash\n"
        )
        val identity = GuestIdentity.resolveNamedUser(rootfs, "ubuntu")
        assertTrue(identity != null)
        assertEquals("ubuntu", identity!!.name)
        assertEquals(1000, identity.uid)
        assertEquals("/home/ubuntu", identity.home)
    }

    @Test
    fun `resolveNamedUser returns null for a missing account`() {
        val rootfs = rootfsWith("root:x:0:0:root:/root:/bin/bash\n")
        assertNull(GuestIdentity.resolveNamedUser(rootfs, "linexa"))
    }

    @Test
    fun `first free uid skips taken uids and gids`() {
        // An imported Ubuntu-style image with a stock uid-1000 user: the
        // created Linexa account must take the NEXT free pair, never collide.
        val rootfs = rootfsWith(
            "root:x:0:0:root:/root:/bin/sh\nubuntu:x:1000:1000::/home/ubuntu:/bin/bash\n",
            "root:x:0:\nubuntu:x:1000:\nadm:x:4:\n"
        )
        assertEquals(1001, GuestIdentity.firstFreeUidGid(rootfs))
    }

    @Test
    fun `first free uid is 1000 on a clean image`() {
        val rootfs = rootfsWith("root:x:0:0:root:/root:/bin/sh\n", "root:x:0:\n")
        assertEquals(1000, GuestIdentity.firstFreeUidGid(rootfs))
    }

    @Test
    fun `missing account files resolve to empty lists`() {
        val rootfs = tmp.newFolder("bare")
        assertTrue(GuestIdentity.readPasswd(rootfs).isEmpty())
        assertTrue(GuestIdentity.readGroup(rootfs).isEmpty())
    }
}
