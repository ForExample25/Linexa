package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.148 unit tests for [RootfsFixupService] — the two real-archive failure
 * modes that broke Fedora and Arch installs:
 *
 *  1. **mode-0000 account files.** Fedora 44 ships `/etc/shadow` and
 *     `/etc/gshadow` as `---------- root/root` (verified against
 *     `fedora-44-arm64-rootfs.tar.xz`). The fixup ran `sed -i` BEFORE
 *     `chmod u+rw`, so sed could not open the file and the install died with
 *     `sed: .../etc/shadow: Permission denied` (exit=1). Arch ships 0600, so
 *     it never hit this and failed later, at resolv.conf — which is exactly
 *     what the two reported failures looked like.
 *
 *  2. **`/etc/resolv.conf` as a dangling symlink.** Both Arch
 *     (`-> /run/systemd/resolve/resolv.conf`) and Fedora
 *     (`-> /run/systemd/resolve/stub-resolv.conf`) ship it as an absolute
 *     symlink into systemd-resolved's runtime dir, and `/run` does not exist
 *     on the Android host. [File.writeText] followed the link and tried to
 *     CREATE the target: `ENOENT (No such file or directory)`.
 *
 * These run the REAL service against a real fake busybox whose `sed`, `chmod`
 * and `id` applets forward to the host's own binaries, so the permission
 * semantics are genuinely exercised on disk rather than mocked away.
 */
class RootfsFixupServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Builds the service with a working fake busybox in
     * `{filesDir}/bootstrap/bin`: `sed`, `chmod` and `id` forward to the
     * host's real applets, so `sed -i` really fails on a mode-0000 file and
     * really succeeds once `chmod u+rw` has run.
     *
     * Each forwarder execs an ABSOLUTE host path. It must not exec a bare
     * name: [BootstrapManager.processEnv] sets `PATH` to `binDir` alone, so
     * `exec sed` would re-resolve to this very script and spin forever.
     */
    private fun newService(
        identity: ProcessIdentity? = DEFAULT_IDENTITY
    ): RootfsFixupService {
        val filesDir = tmp.newFolder("files")
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        for (applet in listOf("sed", "chmod", "id")) {
            val host = hostBinary(applet)
            val script = File(binDir, applet)
            script.writeText("#!$SHELL\nexec $host \"\$@\"\n")
            require(script.setExecutable(true))
        }

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        return RootfsFixupService(
            BootstrapManager(app),
            DiagnosticLogManager(app),
            // Transformation spec §19/§20: the seed comes from the host's live
            // DNS. The fake reports a deterministic pair so the seeded content
            // is assertable; no hardcoded resolver exists in production anymore.
            com.fem.linexa.domain.network.HostDnsProvider { FAKE_HOST_DNS },
            // RFS-01/RFS-08: the production fixup path consumes this seam
            // instead of Process.myUid()/Os.getgid(), so these tests run on a
            // plain JVM and exercise the gid actually written to the guest.
            FixedIdentityProvider(identity)
        )
    }

    /** A staging rootfs with the four account files at Fedora's exact modes. */
    private fun fedoraStyleStaging(name: String = "staging-fedora"): File {
        val staging = tmp.newFolder(name)
        val etc = File(staging, "etc")
        require(etc.mkdirs())
        // Fedora: -rw-r--r--
        File(etc, "passwd").writeText("root:x:0:0:root:/root:/bin/bash\naid_stale:x:1:1::/:/sbin/nologin\n")
        File(etc, "group").writeText("root:x:0:\naid_stale:x:1:\n")
        // Fedora: ---------- — the whole point of these tests.
        writeMode000(File(etc, "shadow"), "root:*:18000:0:99999:7:::\naid_stale:*:1::::::\n")
        writeMode000(File(etc, "gshadow"), "root:::\naid_stale:!::\n")
        return staging
    }

    /** Writes [content], then strips every permission bit — mode 0000. */
    private fun writeMode000(file: File, content: String) {
        file.writeText(content)
        Files.setPosixFilePermissions(file.toPath(), emptySet())
        assertFalse("precondition: ${file.name} must be unreadable", file.canRead())
        assertFalse("precondition: ${file.name} must be unwritable", file.canWrite())
    }

    /** Permission bits of [file] (low 9 bits of `unix:mode`). */
    private fun modeBits(file: File): Int =
        (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1FF

    // ── 1. mode-0000 account files (Fedora) ──────────────────────────────

    /**
     * The regression: with `sed -i` ahead of `chmod`, this returned
     * `sed fixup failed for shadow: ... Permission denied`. With chmod first
     * it must succeed, and the pre-existing `aid_stale` lines must really be
     * gone — proving sed ran rather than being skipped.
     */
    @Test
    fun `account fixup succeeds on mode-0000 shadow and gshadow`() {
        val staging = fedoraStyleStaging()

        val failure = newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false)

        assertNull("fixup must not fail on mode-0000 account files, got: $failure", failure)
        for (name in listOf("passwd", "shadow", "group", "gshadow")) {
            val text = File(File(staging, "etc"), name).readText()
            assertFalse(
                "$name must have had its pre-existing aid_stale line stripped by sed: $text",
                text.lineSequence().any { it.startsWith("aid_stale:") }
            )
        }
    }

    /** `chmod u+rw` must leave the file owner-readable and owner-writable. */
    @Test
    fun `account fixup makes mode-0000 files owner readable and writable`() {
        val staging = fedoraStyleStaging()

        assertNull(newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false))

        for (name in listOf("shadow", "gshadow")) {
            val bits = modeBits(File(File(staging, "etc"), name))
            val octal = Integer.toOctalString(bits)
            assertEquals("$name must be owner-readable after chmod u+rw (mode=0$octal)", OWNER_READ, bits and OWNER_READ)
            assertEquals("$name must be owner-writable after chmod u+rw (mode=0$octal)", OWNER_WRITE, bits and OWNER_WRITE)
        }
    }

    /**
     * The fixup's own `aid_*` line for the app uid must be appended to a
     * mode-0000 shadow, proving the file was made usable end to end.
     */
    @Test
    fun `account fixup appends an aid entry to a mode-0000 shadow`() {
        val staging = fedoraStyleStaging()

        assertNull(newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false))

        val shadow = File(File(staging, "etc"), "shadow").readText()
        assertTrue(
            "shadow must carry the app's own aid_* line after the fixup: $shadow",
            shadow.lineSequence().any { it.startsWith("aid_") }
        )
    }

    /** Arch (0600), Alpine/Ubuntu (0640) and Debian (0664) are unaffected. */
    @Test
    fun `account fixup still succeeds on normally permissioned files`() {
        val staging = tmp.newFolder("staging-arch")
        val etc = File(staging, "etc")
        require(etc.mkdirs())
        File(etc, "passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(etc, "group").writeText("root:x:0:\n")
        val shadow = File(etc, "shadow").apply { writeText("root:!*:18000::::::\n") }
        val gshadow = File(etc, "gshadow").apply { writeText("root:::\n") }
        // Arch's exact 0600.
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(shadow.toPath(), ownerOnly)
        Files.setPosixFilePermissions(gshadow.toPath(), ownerOnly)

        assertNull(newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false))
        assertTrue(shadow.readText().lineSequence().any { it.startsWith("aid_") })
    }

    // ── 2. dangling /etc/resolv.conf symlink (Arch + Fedora) ─────────────

    /** Arch's link shape: an absolute path under a nonexistent `/run` tree. */
    @Test
    fun `seeds resolv conf over a dangling arch style symlink`() {
        assertSeededOverDanglingSymlink("arch", "run/systemd/resolve/resolv.conf")
    }

    /** Fedora's link shape: the systemd-resolved stub, same nonexistent tree. */
    @Test
    fun `seeds resolv conf over a dangling fedora style symlink`() {
        assertSeededOverDanglingSymlink("fedora", "run/systemd/resolve/stub-resolv.conf")
    }

    /**
     * Creates `etc/resolv.conf` as an ABSOLUTE symlink to [relativeTarget]
     * under a `/run`-shaped tree that is guaranteed not to exist, seeds it,
     * and asserts the symlink was replaced by a regular file with our
     * resolvers — and that nothing was created at the target.
     *
     * The target is rooted in the test's temp dir rather than at the literal
     * `/run/systemd/resolve/...` so the test is meaningful on a systemd host
     * too (where that path may genuinely exist); what is being reproduced is
     * a dangling ABSOLUTE symlink, which is identical in both cases.
     */
    private fun assertSeededOverDanglingSymlink(label: String, relativeTarget: String) {
        val staging = tmp.newFolder("staging-$label")
        val etc = File(staging, "etc")
        require(etc.mkdirs())
        val target: Path = tmp.root.toPath().toAbsolutePath().resolve("nonexistent-root/$relativeTarget")
        val resolvConf = File(etc, "resolv.conf")
        Files.createSymbolicLink(resolvConf.toPath(), target)

        assertTrue("precondition: resolv.conf must be a symlink", Files.isSymbolicLink(resolvConf.toPath()))
        assertFalse("precondition: $target must not exist", Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertFalse("precondition: the link must dangle", resolvConf.exists())

        newService().seedResolvConf(staging, WORKSPACE)

        assertFalse(
            "resolv.conf must no longer be a symlink — it must have been unlinked and replaced",
            Files.isSymbolicLink(resolvConf.toPath())
        )
        assertTrue("resolv.conf must now be a regular file", resolvConf.isFile)
        assertEquals(SEEDED, resolvConf.readText())
        assertFalse(
            "the fixup must not have followed the link and created $target",
            Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        )
    }

    /** Ubuntu/Debian ship a regular file; replacing it must keep working. */
    @Test
    fun `seeds resolv conf over an existing regular file`() {
        val staging = tmp.newFolder("staging-regular")
        val etc = File(staging, "etc")
        require(etc.mkdirs())
        val resolvConf = File(etc, "resolv.conf")
        resolvConf.writeText("nameserver 127.0.0.53\n")

        newService().seedResolvConf(staging, WORKSPACE)

        assertEquals(SEEDED, resolvConf.readText())
    }

    /** Alpine ships none at all — the seed must create it, and its parent. */
    @Test
    fun `seeds resolv conf when absent`() {
        val staging = tmp.newFolder("staging-absent")

        newService().seedResolvConf(staging, WORKSPACE)

        val resolvConf = File(File(staging, "etc"), "resolv.conf")
        assertTrue("resolv.conf must be created when the archive ships none", resolvConf.isFile)
        assertEquals(SEEDED, resolvConf.readText())
    }

    /**
     * Both halves together — the default install path, against a rootfs that
     * carries BOTH Fedora failure modes at once. This is the end-to-end
     * reproduction of the reported Broken install.
     */
    @Test
    fun `full fixup succeeds on a fedora style rootfs with dangling resolv conf`() {
        val staging = fedoraStyleStaging("staging-fedora-full")
        val resolvConf = File(File(staging, "etc"), "resolv.conf")
        Files.createSymbolicLink(
            resolvConf.toPath(),
            tmp.root.toPath().toAbsolutePath().resolve("nonexistent-root/run/systemd/resolve/stub-resolv.conf")
        )

        val failure = newService().fixUp(staging, WORKSPACE)

        assertNull("the default fixup path must succeed end to end, got: $failure", failure)
        assertTrue(resolvConf.isFile)
        assertEquals(SEEDED, resolvConf.readText())
        assertTrue(File(File(staging, "etc"), "shadow").readText().lineSequence().any { it.startsWith("aid_") })
    }

    // ── 3. RFS-01 identity provider (no host passwd dependency) ──────────

    /**
     * The audit-device shape: app UID/GID absent from host `/etc/passwd`.
     * The fixup must still write the Android primary gid and every live
     * supplementary gid into the guest account files.
     */
    @Test
    fun `account fixup writes the Android primary gid without a host passwd entry`() {
        val staging = fedoraStyleStaging()

        assertNull(newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false))

        val passwd = File(File(staging, "etc"), "passwd").readText()
        val expected = "aid_${DEFAULT_IDENTITY.uid}:x:${DEFAULT_IDENTITY.uid}:${DEFAULT_IDENTITY.primaryGid}:Linexa:/:/sbin/nologin"
        assertTrue("passwd must carry the Android uid/gid pair: $passwd", passwd.lineSequence().any { it == expected })

        val group = File(File(staging, "etc"), "group").readText()
        for (gid in listOf(DEFAULT_IDENTITY.primaryGid) + DEFAULT_IDENTITY.supplementaryGids) {
            assertTrue(
                "group must carry gid $gid from the live process identity: $group",
                group.lineSequence().any { it.endsWith(":$gid:root,aid_${DEFAULT_IDENTITY.uid}") }
            )
        }
    }

    /** The optional host passwd name is used only when it is a safe token. */
    @Test
    fun `account fixup uses a safe optional host account name`() {
        val staging = fedoraStyleStaging()
        val identity = DEFAULT_IDENTITY.copy(accountName = "u0_a672")

        assertNull(newService(identity).fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false))

        val passwd = File(File(staging, "etc"), "passwd").readText()
        assertTrue(
            "passwd must use the safe host account name: $passwd",
            passwd.lineSequence().any { it.startsWith("aid_u0_a672:x:${DEFAULT_IDENTITY.uid}:") }
        )
    }

    /** An unsafe host name falls back to the numeric uid, never the raw text. */
    @Test
    fun `account fixup rejects an unsafe host account name`() {
        val staging = fedoraStyleStaging()
        val identity = DEFAULT_IDENTITY.copy(accountName = "u:r:untrusted_app:s0")

        assertNull(newService(identity).fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false))

        val passwd = File(File(staging, "etc"), "passwd").readText()
        assertFalse("an unsafe name must not reach passwd: $passwd", passwd.contains("untrusted_app"))
        assertTrue(
            "the numeric uid must be used instead: $passwd",
            passwd.lineSequence().any { it.startsWith("aid_${DEFAULT_IDENTITY.uid}:x:") }
        )
    }

    /** A platform that cannot report uid/gid must fail loudly, not silently. */
    @Test
    fun `account fixup fails when the identity provider reports nothing`() {
        val staging = fedoraStyleStaging()

        val failure = newService(identity = null)
            .fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false)

        assertNotNull("a null identity must surface as a fixup failure", failure)
        assertTrue(
            "the failure must name identity resolution: $failure",
            failure!!.contains("identity resolution failed")
        )
    }

    // ── 4. RFS-03 no-follow account files ─────────────────────────────────

    /**
     * `etc/passwd` extracted as a symlink to a host victim used to be chmod'ed
     * and edited through the link. The fixup must refuse before any host-side
     * mutation and leave both the victim's content and mode unchanged.
     */
    @Test
    fun `account fixup refuses a symlinked passwd and leaves the victim untouched`() {
        val staging = tmp.newFolder("staging-symlink-passwd")
        val etc = File(staging, "etc")
        require(etc.mkdirs())

        val victim = File(tmp.root, "outside-victim").apply {
            writeText("root:x:0:0:root:/root:/bin/sh\naid_victim:x:1:1::/:/sbin/nologin\n")
        }
        val victimMode = modeBits(victim)
        Files.createSymbolicLink(File(etc, "passwd").toPath(), victim.toPath())
        File(etc, "group").writeText("root:x:0:\n")
        File(etc, "shadow").writeText("root:*:18000:0:99999:7:::\n")
        File(etc, "gshadow").writeText("root:::\n")

        val failure = newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false)

        assertNotNull("a symlinked passwd must fail the fixup", failure)
        assertTrue("the failure must explain the no-follow refusal: $failure", failure!!.contains("symlink"))
        assertEquals(
            "the host victim content must be unchanged",
            "root:x:0:0:root:/root:/bin/sh\naid_victim:x:1:1::/:/sbin/nologin\n",
            victim.readText()
        )
        assertEquals("the host victim mode must be unchanged", victimMode, modeBits(victim))
    }

    /** A symlinked group file must be rejected for the same reason. */
    @Test
    fun `account fixup refuses a symlinked group file`() {
        val staging = fedoraStyleStaging("staging-symlink-group")
        val etc = File(staging, "etc")
        val victim = File(tmp.root, "outside-group").apply { writeText("root:x:0:\n") }
        File(etc, "group").delete()
        Files.createSymbolicLink(File(etc, "group").toPath(), victim.toPath())

        val failure = newService().fixUp(staging, WORKSPACE, accountFixup = true, seedResolvConf = false)

        assertNotNull("a symlinked group file must fail the fixup", failure)
        assertTrue("the failure must explain the no-follow refusal: $failure", failure!!.contains("symlink"))
        assertEquals("root:x:0:\n", victim.readText())
    }

    private class FixedIdentityProvider(
        private val identity: ProcessIdentity?
    ) : ProcessIdentityProvider {
        override fun currentIdentity(): ProcessIdentity? = identity
    }

    private companion object {
        const val WORKSPACE = "test-workspace"

        /**
         * The audit device's shape: an app uid/gid with no host passwd entry
         * and the live Android supplementary groups observed in the terminal
         * (`3003`, `9997` on this device class).
         */
        val DEFAULT_IDENTITY = ProcessIdentity(
            uid = 10234,
            primaryGid = 10234,
            supplementaryGids = listOf(3003, 9997),
            accountName = null,
            groupNamesByGid = emptyMap()
        )
        val FAKE_HOST_DNS = listOf("192.0.2.10", "192.0.2.11")
        val SEEDED = FAKE_HOST_DNS.joinToString("") { "nameserver $it\n" } + "# managed by Linexa\n"
        const val OWNER_READ = 0b100_000_000
        const val OWNER_WRITE = 0b010_000_000

        /** First existing `sh` — Android has no `/usr/bin`, CI Linux may have no `/bin`. */
        val SHELL: String = listOf("/bin/sh", "/system/bin/sh", "/usr/bin/sh")
            .firstOrNull { File(it).canExecute() }
            ?: error("no usable /bin/sh on this host")

        /** Absolute path of a host [name] applet (sed, chmod, id). */
        fun hostBinary(name: String): String =
            listOf("/bin/$name", "/usr/bin/$name", "/system/bin/$name")
                .firstOrNull { File(it).canExecute() }
                ?: error("host binary '$name' not found — required by RootfsFixupServiceTest")
    }
}
