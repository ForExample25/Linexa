package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.146 — the parser's whitelist, from the perspective of an **untrusted**
 * document.
 *
 * The parser is the boundary that makes an embedded profile safe to act on, so
 * the rejection cases here are the substance of this test, not its edge cases. A
 * profile is data; if any of these assertions regress, an imported rootfs gains
 * influence over host behaviour it must never have.
 */
class RootFsProfileParserTest {

    private fun parse(yaml: String, source: RootFsProfileSource = RootFsProfileSource.EMBEDDED) =
        RootFsProfileParser.parse(yaml.toByteArray(), source)

    private fun valid(yaml: String): RootFsProfile {
        val result = parse(yaml)
        assertTrue("expected valid, got $result", result is RootFsProfileParser.Result.Valid)
        return (result as RootFsProfileParser.Result.Valid).profile
    }

    private fun invalid(yaml: String): String {
        val result = parse(yaml)
        assertTrue("expected invalid, got $result", result is RootFsProfileParser.Result.Invalid)
        return (result as RootFsProfileParser.Result.Invalid).reason
    }

    private val minimal = "id: testdistro\nname: Test Distro"

    /**
     * A minimal document plus [extra].
     *
     * A helper rather than `"$minimal\n" + """…""".trimIndent()` inline, because
     * interpolating a multi-line value *into* an indented raw string defeats
     * `trimIndent`: the injected lines carry no indentation, so the computed
     * common prefix becomes zero and the surrounding lines keep theirs. The
     * result is a document YAML rejects for reasons that have nothing to do with
     * what the test is asserting.
     */
    private fun doc(extra: String): String = minimal + "\n" + extra.trimIndent()

    @Test
    fun aMinimalProfileParsesAndInheritsSafeDefaults() {
        val profile = valid(minimal)
        assertEquals("testdistro", profile.id)
        assertEquals("Test Distro", profile.name)
        assertEquals(RootFsProfileSource.EMBEDDED, profile.source)
        // The generic intersection, not Debian's list.
        assertEquals(setOf("etc", "usr"), profile.layout.requiredDirectories)
        assertEquals(ArchiveKind.ROOTFS_TAR, profile.layout.archiveKind)
    }

    @Test
    fun aFullProfileRoundTripsEveryField() {
        val profile = valid(
            """
            id: alpine
            name: Alpine Linux
            aliases: [alpinelinux]
            detection:
              os_release_ids: [alpine]
              marker_files: [etc/alpine-release, etc/os-release]
              version_from: etc/alpine-release
              architecture_hints:
                ld-musl-aarch64: arm64
              elf_probe_paths: [bin/busybox]
            supported_architectures: [arm64, x86_64]
            layout:
              required_directories: [bin, etc, usr]
              shell_candidates: [bin/sh, bin/ash]
              archive_kind: rootfs_tar
            install: [ensure_tmp, android_account_fixup, seed_resolv_conf]
            environment:
              login_shell: /bin/sh
              shell_args: ["--login"]
              package_manager: apk
            """.trimIndent()
        )

        assertEquals(setOf("alpinelinux"), profile.aliases)
        assertEquals(RootFsProbe.DistroId.ALPINE, profile.probeId)
        assertEquals("etc/alpine-release", profile.detection.versionFrom)
        assertEquals(mapOf("ld-musl-aarch64" to "arm64"), profile.detection.architectureHints)
        assertEquals(listOf("bin/busybox"), profile.detection.elfProbePaths)
        assertEquals(listOf("bin/sh", "bin/ash"), profile.layout.shellCandidates)
        assertEquals(
            listOf(InstallStep.ENSURE_TMP, InstallStep.ANDROID_ACCOUNT_FIXUP, InstallStep.SEED_RESOLV_CONF),
            profile.install
        )
        assertEquals("/bin/sh", profile.environment.loginShell)
        assertEquals("apk", profile.environment.packageManager)
    }

    // ── the security boundary ────────────────────────────────────────────

    @Test
    fun aStepOutsideTheClosedEnumIsRejected() {
        // The single most important assertion in this file: if a profile could
        // name an arbitrary step, importing a hostile rootfs would be a code
        // execution path.
        val reason = invalid(
            doc(
                """
                install: [ensure_tmp, "rm -rf /"]
                """
            )
        )
        assertTrue(reason.contains("is not supported"))
    }

    @Test
    fun aHostPathIsRejectedEverywhereAPathIsAccepted() {
        listOf(
            "detection:\n  marker_files: [/data/data/com.fem.linexa/files/secret]",
            "detection:\n  elf_probe_paths: [/system/bin/sh]",
            "layout:\n  required_directories: [/etc]",
            "layout:\n  shell_candidates: [/bin/sh]"
        ).forEach { field ->
            val reason = invalid("$minimal\n$field")
            assertTrue(
                "absolute path should be rejected for: $field (got '$reason')",
                reason.contains("must be relative")
            )
        }
    }

    @Test
    fun aTraversalPathIsRejected() {
        val reason = invalid(
            doc(
                """
                detection:
                  marker_files: ["../../../etc/passwd"]
                """
            )
        )
        assertTrue(reason.contains("escapes the rootfs root"))
    }

    @Test
    fun shellMetacharactersAreRejectedInPathsAndInTheLoginShell() {
        assertTrue(
            invalid("$minimal\ndetection:\n  marker_files: [\"etc/x;rm -rf /\"]")
                .contains("unsafe characters")
        )
        assertTrue(
            invalid("$minimal\nenvironment:\n  login_shell: \"/bin/sh; id\"")
                .contains("unsafe characters")
        )
        assertTrue(
            invalid("$minimal\nenvironment:\n  shell_args: [\"--login\", \"\$(id)\"]")
                .contains("unsafe characters")
        )
    }

    @Test
    fun anArbitraryJavaTypeCannotBeInstantiatedFromTheDocument() {
        // SafeConstructor rejects explicit tags at parse time rather than letting
        // a gadget class be constructed and filtered afterwards.
        val reason = invalid(
            """
            id: evil
            name: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://example.invalid/"]]
            """.trimIndent()
        )
        assertTrue(reason.contains("not valid YAML") || reason.contains("required"))
    }

    @Test
    fun anOversizedDocumentIsRejectedWithoutParsing() {
        val huge = "id: x\nname: " + "a".repeat(RootFsProfileParser.MAX_PROFILE_BYTES + 1)
        assertTrue(invalid(huge).contains("exceeds"))
    }

    @Test
    fun aPackageManagerOutsideTheRegistryIsRejected() {
        val reason = invalid("$minimal\nenvironment:\n  package_manager: curlbash")
        assertTrue(reason.contains("must be one of"))
        // Sanity: the message lists what is actually supported.
        PackageManagerDetector.supportedKeys.forEach { assertTrue(reason.contains(it)) }
    }

    @Test
    fun aRelativeLoginShellIsRejectedBecauseTheRuntimeExecsItAbsolutely() {
        assertTrue(invalid("$minimal\nenvironment:\n  login_shell: bin/sh").contains("absolute"))
    }

    // ── coherence and typo detection ─────────────────────────────────────

    @Test
    fun anUnknownKeyIsReportedRatherThanIgnored() {
        // A silently-ignored typo would mean a profile that looks configured and
        // does nothing.
        assertTrue(invalid("$minimal\nlayuot:\n  required_directories: [etc]").contains("unknown"))
        assertTrue(
            invalid("$minimal\ndetection:\n  markerfiles: [etc/os-release]").contains("unknown detection")
        )
    }

    @Test
    fun aDuplicateKeyIsRejected() {
        assertTrue(invalid("id: a\nname: A\nid: b").isNotEmpty())
    }

    @Test
    fun anIdThatIsNotALowerCaseTokenIsRejected() {
        assertTrue(invalid("id: Debian\nname: Debian").contains("'id' must match"))
        assertTrue(invalid("id: \"deb ian\"\nname: Debian").contains("'id' must match"))
        assertTrue(invalid("id: \"../etc\"\nname: X").contains("'id' must match"))
    }

    @Test
    fun aMissingRequiredFieldIsReportedByName() {
        assertTrue(invalid("name: No Id Here").contains("'id' is required"))
        assertTrue(invalid("id: noname").contains("'name' is required"))
    }

    @Test
    fun versionFromMustAlsoBeWatchedOrItWouldNeverBeCaptured() {
        val reason = invalid(
            doc(
                """
                detection:
                  marker_files: [etc/os-release]
                  version_from: etc/testdistro-release
                """
            )
        )
        assertTrue(reason.contains("must also appear"))
    }

    @Test
    fun anOciLayoutWithoutTheUnwrapStepIsRejectedAsIncoherent() {
        // Otherwise the `blobs/`+`index.json` tree is handed to tar as a rootfs
        // and the user is told their good image is "incomplete".
        val reason = invalid(
            doc(
                """
                layout:
                  archive_kind: oci_image_layout
                install: [ensure_tmp]
                """
            )
        )
        assertTrue(reason.contains("unwrap_oci_layer"))
    }

    @Test
    fun aRepeatedInstallStepIsRejected() {
        assertTrue(
            invalid("$minimal\ninstall: [ensure_tmp, ensure_tmp]").contains("repeats")
        )
    }

    @Test
    fun anUnknownArchitectureHintValueIsRejected() {
        val reason = invalid(
            doc(
                """
                detection:
                  architecture_hints:
                    ld-musl-sparc: sparc64
                """
            )
        )
        assertTrue(reason.contains("not a known architecture"))
    }

    @Test
    fun stepNamesAreAcceptedInEitherCaseAndWithEitherSeparator() {
        val profile = valid("$minimal\ninstall: [ENSURE-TMP, seed_resolv_conf]")
        assertEquals(listOf(InstallStep.ENSURE_TMP, InstallStep.SEED_RESOLV_CONF), profile.install)
    }

    @Test
    fun declaredPathsAreNormalizedSoBothPackingConventionsMatch() {
        val profile = valid(
            doc(
                """
                detection:
                  marker_files: ["./etc/os-release"]
                  elf_probe_paths: ["./bin/busybox"]
                """
            )
        )
        assertEquals(setOf("etc/os-release"), profile.detection.markerFiles)
        assertEquals(listOf("bin/busybox"), profile.detection.elfProbePaths)
    }

    @Test
    fun aNonMappingDocumentIsRejected() {
        assertTrue(invalid("- just\n- a\n- list").contains("must be a YAML mapping"))
        assertTrue(invalid("").contains("empty"))
    }

    @Test
    fun aWrongTypeForAListFieldIsReportedRatherThanCoerced() {
        assertTrue(invalid("$minimal\ninstall: ensure_tmp").contains("must be a list"))
        assertTrue(invalid("$minimal\nlayout: nope").contains("'layout' must be a mapping"))
    }

    @Test
    fun theSameStrictRulesApplyToBuiltInProfiles() {
        // One parser, two trust levels: a built-in that would be rejected as
        // embedded is a bug, and two code paths would let them drift.
        val result = RootFsProfileParser.parse(
            "$minimal\ninstall: [not_a_step]".toByteArray(),
            RootFsProfileSource.BUILT_IN
        )
        assertTrue(result is RootFsProfileParser.Result.Invalid)
    }
}
