package com.fem.linexa.service.runtime.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.112: [ClipboardBridgeProvisioner] — the pure half of the Clipboard feature.
 *
 * Three things are pinned here. First the *contract with the guest*: the shims'
 * shape (POSIX-only, write-then-rename, never failing the caller) and the raw
 * record they produce, since a mismatch with [ClipboardBridgeProvisioner.decodeRecord]
 * would silently drop every copy. Second the *alias dispatch*: `xclip` and `xsel`
 * are one binary with two directions and **opposite defaults**, so getting the
 * argv dispatch wrong silently swaps copy and paste — the worst possible failure
 * mode for a clipboard, because it looks like it works. Third the *hardening*:
 * guest→host text is arbitrary output from code running in the workspace.
 */
class ClipboardBridgeProvisionerTest {

    // ── the shims' contract with the guest ──

    @Test
    fun bothShimsArePosixShAndNeverFailTheCallingScript() {
        listOf(
            "copy" to ClipboardBridgeProvisioner.copyShimScript(),
            "paste" to ClipboardBridgeProvisioner.pasteShimScript()
        ).forEach { (name, script) ->
            assertTrue("$name: must be POSIX sh (a rootfs may ship only busybox ash)", script.startsWith("#!/bin/sh"))
            assertFalse("$name: no bashisms", script.contains("[["))
            assertFalse("$name: no \$RANDOM (not POSIX)", script.contains("\$RANDOM"))
            assertFalse("$name: no mktemp dependency", script.contains("mktemp"))
            assertTrue("$name: exits 0", script.contains("exit 0"))
            assertFalse(
                "$name: must not exit non-zero anywhere — it stands in for a command in someone else's script",
                Regex("exit [1-9]").containsMatchIn(script)
            )
        }
    }

    @Test
    fun theCopyShimWritesATempFileAndRenamesItSoTheHostNeverReadsAPartialClip() {
        val lines = ClipboardBridgeProvisioner.copyShimScript().lines()

        // The invariant: the only name `cat` ever writes carries the temp
        // extension, and the record extension the host polls for appears solely
        // as the rename target.
        val tmpAssignment = lines.first { it.trimStart().startsWith("tmp=") }
        assertTrue(
            "the temp path must carry the temp extension: $tmpAssignment",
            tmpAssignment.contains(ClipboardBridgeProvisioner.RECORD_TEMP_EXTENSION)
        )
        assertFalse(
            "the temp path must not be the name the host polls for: $tmpAssignment",
            tmpAssignment.contains(ClipboardBridgeProvisioner.RECORD_EXTENSION)
        )

        val catLine = lines.first { it.trimStart().startsWith("cat >") }
        assertTrue("stdin is written to the temp path", catLine.contains("> \"\$tmp\""))
        assertFalse(
            "the write must not target the polled name: $catLine",
            catLine.contains(ClipboardBridgeProvisioner.RECORD_EXTENSION)
        )

        val moveLine = lines.first { it.trimStart().startsWith("mv ") }
        assertTrue("commits by renaming the temp path", moveLine.contains("\"\$tmp\""))
        assertTrue(
            "the rename target is the name the host polls for: $moveLine",
            moveLine.contains(ClipboardBridgeProvisioner.RECORD_EXTENSION)
        )
    }

    @Test
    fun theCopyShimSendsStdinVerbatimWithNoFraming() {
        // A clipboard record has no argv to preserve, so the bytes ARE the clip.
        // Framing would only add a way for the two sides to disagree.
        val script = ClipboardBridgeProvisioner.copyShimScript()
        assertTrue(script.contains("cat > \"\$tmp\""))
        assertFalse("no argv framing", script.contains("printf"))
    }

    @Test
    fun thePasteShimPrintsNothingRatherThanErroringWhenThereIsNoClipboard() {
        val script = ClipboardBridgeProvisioner.pasteShimScript()

        // A paste must never invent content and never break a pipeline: an
        // absent mirror is empty output, not an error and not stale text.
        assertTrue("guards on readability before cat", script.contains("[ -r \"\$f\" ] || exit 0"))
        assertTrue(script.contains("cat \"\$f\""))
        assertFalse("must not write to stderr on a missing mirror", script.contains("echo"))
    }

    @Test
    fun thePasteShimReadsExactlyTheMirrorTheHostWrites() {
        assertTrue(
            ClipboardBridgeProvisioner.pasteShimScript()
                .contains("f='${ClipboardBridgeProvisioner.IN_GUEST_FILE}'")
        )
    }

    // ── guest vs host views of the same paths ──

    @Test
    fun theGuestAndHostViewsOfTheBridgeCannotDrift() {
        // The host resolves these under {workspace}/rootfs, so the relative
        // constants must be exactly the guest absolute paths without the leading
        // slash — a drift here means the host polls a directory nothing writes.
        assertEquals(
            ClipboardBridgeProvisioner.OUT_GUEST_DIR.removePrefix("/"),
            ClipboardBridgeProvisioner.OUT_RELATIVE_PATH
        )
        assertEquals(
            ClipboardBridgeProvisioner.IN_GUEST_DIR.removePrefix("/"),
            ClipboardBridgeProvisioner.IN_RELATIVE_PATH
        )
        assertEquals(
            ClipboardBridgeProvisioner.IN_GUEST_FILE.removePrefix("/"),
            ClipboardBridgeProvisioner.IN_FILE_RELATIVE_PATH
        )
    }

    @Test
    fun theBridgeLivesInsideTheRootfsSoNoBindMountOrSocketIsNeeded() {
        // The whole security story of §1.111/§1.112: the guest's only capability
        // is files in one directory, reachable by the host with no extra mount.
        assertTrue(ClipboardBridgeProvisioner.OUT_GUEST_DIR.startsWith(ClipboardBridgeProvisioner.BRIDGE_GUEST_DIR))
        assertTrue(ClipboardBridgeProvisioner.IN_GUEST_DIR.startsWith(ClipboardBridgeProvisioner.BRIDGE_GUEST_DIR))
        assertFalse(ClipboardBridgeProvisioner.OUT_RELATIVE_PATH.startsWith("/"))
        assertFalse(ClipboardBridgeProvisioner.IN_FILE_RELATIVE_PATH.startsWith("/"))
    }

    // ── install / uninstall commands ──

    @Test
    fun theInstallCommandUsesAQuotedHeredocSoTheGuestShellExpandsNothing() {
        val command = ClipboardBridgeProvisioner.installCommand()

        // Unquoted <<EOF would expand $$/$n in the shim body before it hit disk.
        assertTrue("heredoc delimiter must be quoted", command.contains("<<'LINEXA_EOF'"))
        assertTrue("the shim's own \$\$ must reach disk verbatim", command.contains("${'$'}${'$'}"))
        assertTrue(command.contains(ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH))
        assertTrue(command.contains(ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH))
        assertTrue("creates the spool", command.contains(ClipboardBridgeProvisioner.OUT_GUEST_DIR))
        assertTrue("creates the mirror directory", command.contains(ClipboardBridgeProvisioner.IN_GUEST_DIR))
        assertTrue(command.contains("chmod 0755 ${ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH}"))
        assertTrue(command.contains("chmod 0755 ${ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH}"))
    }

    @Test
    fun theInstallCommandInterpolatesNoUserSuppliedString() {
        // §1.14: every byte of this command comes from constants this object
        // owns. Nothing derived from a workspace name, a path or guest output.
        val command = ClipboardBridgeProvisioner.installCommand()
        // A Kotlin null that slipped into an interpolation would appear as the
        // literal "null"; the shims' own `2>/dev/null` redirects are expected, so
        // only a bare occurrence is a defect.
        assertFalse(
            "a null must never reach a generated guest command",
            command.replace("/dev/null", "").contains("null")
        )
        assertFalse("no backticks", command.contains("`"))
        // No command substitution. Checked as `$(` NOT followed by another `(`
        // because the shim legitimately uses POSIX arithmetic expansion `$((…))`.
        assertFalse(
            "no shell command substitution",
            Regex("""\$\((?!\()""").containsMatchIn(command)
        )
    }

    @Test
    fun anAliasIsOnlyInstalledWhenTheNameDoesNotAlreadyResolve() {
        // The point of the whole conditional: a workspace running a real X server
        // has a WORKING xclip talking to a real selection. Replacing it with a
        // file-based shim would break clipboard inside that desktop.
        ClipboardBridgeProvisioner.ALIASES.forEach { alias ->
            val command = ClipboardBridgeProvisioner.aliasInstallCommand(alias)
            assertTrue(
                "${alias.name}: must probe with command -v, not [ -e path ] — what matters is whether the NAME resolves",
                command.contains("command -v ${alias.name} >/dev/null 2>&1")
            )
            assertTrue("${alias.name}: bails out when it resolves", command.contains("then exit 0; fi"))
            assertTrue(command.contains("/usr/local/bin/${alias.name}"))
            assertTrue(command.contains("chmod 0755 /usr/local/bin/${alias.name}"))
        }
    }

    @Test
    fun theCanonicalShimsAreAlwaysInstalledUnconditionally() {
        // They are the bridge; an alias is a convenience. Gating these on
        // `command -v` would make the feature silently unreachable.
        val command = ClipboardBridgeProvisioner.installCommand()
        assertFalse("no conditional around the canonical shims", command.contains("command -v"))
    }

    @Test
    fun uninstallOnlyRemovesAliasesThatAreOurs() {
        val command = ClipboardBridgeProvisioner.uninstallCommand()

        // Turning the feature off must not delete a real xclip the user
        // installed into /usr/local/bin themselves.
        ClipboardBridgeProvisioner.ALIASES.forEach { alias ->
            assertTrue(
                "${alias.name}: removal must be guarded by a content check",
                command.contains("grep -q '${ClipboardBridgeProvisioner.SHIM_MARKER}' /usr/local/bin/${alias.name}")
            )
        }
        assertTrue(command.contains(ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH))
        assertTrue(command.contains(ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH))
        assertTrue("exits 0 even if nothing was installed", command.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun uninstallRemovesTheMirrorBecauseItHoldsTheUsersClipboard() {
        val command = ClipboardBridgeProvisioner.uninstallCommand()
        assertTrue(
            "the mirror is the user's own clipboard text — it must not survive the toggle going off",
            command.contains(ClipboardBridgeProvisioner.IN_GUEST_FILE)
        )
        assertTrue("pending copies go too", command.contains(ClipboardBridgeProvisioner.RECORD_EXTENSION))
    }

    @Test
    fun everyShimCarriesTheMarkerUninstallGrepsFor() {
        val marker = ClipboardBridgeProvisioner.SHIM_MARKER
        assertTrue(ClipboardBridgeProvisioner.copyShimScript().contains(marker))
        assertTrue(ClipboardBridgeProvisioner.pasteShimScript().contains(marker))
        ClipboardBridgeProvisioner.ALIASES.forEach { alias ->
            assertTrue(
                "${alias.name}: without the marker, uninstall would leave it behind forever",
                ClipboardBridgeProvisioner.aliasScript(alias).contains(marker)
            )
        }
    }

    @Test
    fun theMarkerIsPlainAsciiSoItIsSafeInsideAGeneratedGrepPattern() {
        assertTrue(
            "a non-ASCII byte in a grep pattern inside a generated shell command is a portability risk",
            ClipboardBridgeProvisioner.SHIM_MARKER.all { it.code in 32..126 }
        )
    }

    // ── alias dispatch: the direction must not be swapped ──

    @Test
    fun xclipReadsWithDashOAndWritesOtherwise() {
        val script = ClipboardBridgeProvisioner.aliasScript(
            ClipboardAlias("xclip", ClipboardAliasKind.DISPATCH_XCLIP)
        )
        // xclip's own default is WRITE; -o/-out/--output reads.
        assertTrue(script.contains("-o|-out|--output) exec ${ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH}"))
        assertTrue(
            "a bare xclip must fall through to copy",
            script.trimEnd().endsWith("exec ${ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH}")
        )
    }

    @Test
    fun xselWritesWithDashIAndReadsOtherwise() {
        val script = ClipboardBridgeProvisioner.aliasScript(
            ClipboardAlias("xsel", ClipboardAliasKind.DISPATCH_XSEL)
        )
        // xsel's default is the OPPOSITE of xclip's: a bare xsel reads. Getting
        // this backwards silently swaps copy and paste.
        assertTrue(script.contains("-i|--input) exec ${ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH}"))
        assertTrue(
            "a bare xsel (and xsel -b) must fall through to paste",
            script.trimEnd().endsWith("exec ${ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH}")
        )
    }

    @Test
    fun theTwoDispatchingAliasesHaveOppositeFallthroughs() {
        // Stated as its own assertion because it is the single easiest thing to
        // get wrong in this file, and the symptom (copy/paste swapped) looks
        // like a working feature.
        val xclip = ClipboardBridgeProvisioner.aliasScript(ClipboardAlias("xclip", ClipboardAliasKind.DISPATCH_XCLIP))
        val xsel = ClipboardBridgeProvisioner.aliasScript(ClipboardAlias("xsel", ClipboardAliasKind.DISPATCH_XSEL))
        assertTrue(xclip.trimEnd().endsWith(ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH))
        assertTrue(xsel.trimEnd().endsWith(ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH))
    }

    @Test
    fun waylandAndMacAliasesMapToOneDirectionEach() {
        val byName = ClipboardBridgeProvisioner.ALIASES.associateBy { it.name }
        assertEquals(ClipboardAliasKind.COPY, byName.getValue("wl-copy").kind)
        assertEquals(ClipboardAliasKind.PASTE, byName.getValue("wl-paste").kind)
        assertEquals(ClipboardAliasKind.COPY, byName.getValue("pbcopy").kind)

        assertTrue(
            ClipboardBridgeProvisioner.aliasScript(byName.getValue("wl-copy"))
                .contains("exec ${ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH}")
        )
        assertTrue(
            ClipboardBridgeProvisioner.aliasScript(byName.getValue("wl-paste"))
                .contains("exec ${ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH}")
        )
    }

    @Test
    fun everyAliasDelegatesToTheCanonicalShimsRatherThanReimplementingThem() {
        // One copy path and one paste path no matter how many aliases exist.
        ClipboardBridgeProvisioner.ALIASES.forEach { alias ->
            val script = ClipboardBridgeProvisioner.aliasScript(alias)
            assertFalse("${alias.name}: must not touch the spool itself", script.contains("mkdir"))
            assertFalse("${alias.name}: must not write records itself", script.contains("mv "))
            assertTrue(
                "${alias.name}: delegates to a canonical shim",
                script.contains(ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH) ||
                    script.contains(ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH)
            )
        }
    }

    @Test
    fun aliasesAreInstalledOutsideTheDistrosOwnBinDirectories() {
        // /usr/local/bin, never /usr/bin: a package manager must be able to
        // install the real tool later without fighting us for the path.
        ClipboardBridgeProvisioner.ALIASES.forEach { alias ->
            assertTrue(
                alias.name,
                ClipboardBridgeProvisioner.aliasInstallCommand(alias).contains("/usr/local/bin/${alias.name}")
            )
        }
        assertTrue(ClipboardBridgeProvisioner.COPY_SHIM_GUEST_PATH.startsWith("/usr/local/bin/"))
        assertTrue(ClipboardBridgeProvisioner.PASTE_SHIM_GUEST_PATH.startsWith("/usr/local/bin/"))
    }

    // ── record decoding ──

    @Test
    fun aRecordDecodesToItsTextVerbatim() {
        val decoded = ClipboardBridgeProvisioner.decodeRecord("hello world".toByteArray())
        assertEquals("hello world", decoded)
    }

    @Test
    fun aMultiLineCopyKeepsItsLineBreaks() {
        // The whole point of a clipboard: a copied snippet that loses its
        // newlines is worse than no bridge. This is the deliberate difference
        // from NotifySendProvisioner.sanitizeText, which collapses them.
        val snippet = "fun main() {\n\tprintln(\"hi\")\r\n}"
        assertEquals(snippet, ClipboardBridgeProvisioner.decodeRecord(snippet.toByteArray()))
    }

    @Test
    fun anEmptyRecordIsUnusable() {
        assertNull(ClipboardBridgeProvisioner.decodeRecord(ByteArray(0)))
    }

    @Test
    fun anOversizedRecordIsRejectedWithoutBeingDecoded() {
        val tooBig = ByteArray(ClipboardBridgeProvisioner.MAX_CLIP_BYTES + 1) { 'x'.code.toByte() }
        assertNull(ClipboardBridgeProvisioner.decodeRecord(tooBig))
    }

    @Test
    fun aRecordAtTheSizeLimitIsStillAccepted() {
        val atLimit = ByteArray(ClipboardBridgeProvisioner.MAX_CLIP_BYTES) { 'x'.code.toByte() }
        assertNotNull("the bound must not be off by one", ClipboardBridgeProvisioner.decodeRecord(atLimit))
    }

    @Test
    fun aRecordOfOnlyWhitespaceIsUnusable() {
        assertNull(ClipboardBridgeProvisioner.decodeRecord("   \n\t  ".toByteArray()))
    }

    // ── guest → host sanitization (§1.14) ──

    @Test
    fun controlCharactersAreStrippedOnTheWayToTheHostClipboard() {
        // A guest can otherwise smuggle terminal escape sequences into whatever
        // the user pastes into next. Removing the ESC/BEL/NUL/DEL bytes is what
        // de-fangs them: the "[31m" tail is left as ordinary printable text
        // rather than parsed away, because reconstructing CSI grammar here would
        // risk eating legitimate content the user really copied.
        val hostile = "safe\u001B[31m\u0007\u0000\u007Ftext"
        assertEquals("safe[31mtext", ClipboardBridgeProvisioner.sanitizeForHost(hostile))
    }

    @Test
    fun tabsAndNewlinesSurviveSanitizationButOtherControlsDoNot() {
        val text = "a\tb\nc\r\nd\u0001e"
        assertEquals("a\tb\nc\r\nde", ClipboardBridgeProvisioner.sanitizeForHost(text))
    }

    @Test
    fun aByteOrderMarkIsDropped() {
        assertEquals("plain", ClipboardBridgeProvisioner.sanitizeForHost("\uFEFFplain"))
    }

    @Test
    fun overlongGuestTextIsTruncatedRatherThanRejected() {
        // A truncated paste is recoverable; a silently empty clipboard looks
        // like a broken feature.
        val long = "y".repeat(ClipboardBridgeProvisioner.MAX_CLIP_CHARS + 500)
        val result = ClipboardBridgeProvisioner.sanitizeForHost(long)
        assertEquals(ClipboardBridgeProvisioner.MAX_CLIP_CHARS, result?.length)
    }

    @Test
    fun textThatSanitizesToNothingIsNull() {
        assertNull(ClipboardBridgeProvisioner.sanitizeForHost("\u0000\u0001\u0002"))
        assertNull(ClipboardBridgeProvisioner.sanitizeForHost(""))
        assertNull(ClipboardBridgeProvisioner.sanitizeForHost(null))
    }

    @Test
    fun unicodeContentIsPreserved() {
        val text = "日本語 — émoji 🎉 ok"
        assertEquals(text, ClipboardBridgeProvisioner.sanitizeForHost(text))
    }

    // ── host → guest bounding ──

    @Test
    fun hostTextIsBoundedButNotStripped() {
        // The user's own text: an escape sequence in their clipboard is their
        // business, and mangling it would make paste lossy.
        val withEscape = "git log --color\u001B[0m"
        assertEquals(withEscape, ClipboardBridgeProvisioner.boundForGuest(withEscape))
    }

    @Test
    fun overlongHostTextIsTruncatedSoAHugeClipboardCannotBeWrittenIntoEveryRootfs() {
        val long = "z".repeat(ClipboardBridgeProvisioner.MAX_CLIP_CHARS + 1)
        assertEquals(
            ClipboardBridgeProvisioner.MAX_CLIP_CHARS,
            ClipboardBridgeProvisioner.boundForGuest(long)?.length
        )
    }

    @Test
    fun anEmptyHostClipboardIsNullNotAnEmptyMirror() {
        assertNull(ClipboardBridgeProvisioner.boundForGuest(""))
        assertNull(ClipboardBridgeProvisioner.boundForGuest(null))
    }

    // ── feature identity ──

    @Test
    fun theFeatureIdMatchesTheBuiltInCatalogEntry() {
        assertEquals("clipboard", ClipboardBridgeProvisioner.FEATURE_CLIPBOARD)
        assertTrue(
            "the toggle must exist in the built-in catalog or nothing can enable it",
            com.fem.linexa.data.WorkspaceContent.builtinFeatureDefaults
                .containsKey(ClipboardBridgeProvisioner.FEATURE_CLIPBOARD)
        )
    }
}
