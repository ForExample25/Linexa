package com.fem.linexa.ui.components

import androidx.compose.ui.graphics.Color
import com.fem.linexa.data.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UI Phase 2 — the Home card's text rules.
 *
 * These are the cases that only occur on *real* installed workspaces and would
 * otherwise be verified by looking at a screenshot of whichever workspace the
 * developer happened to have on device: a pre-§1.118 workspace with no
 * declaration at all, an imported archive whose version was never detected (the
 * literal string `"unknown"`, persisted by `InstallCoordinator.buildSummary`), a
 * rolling release with a codename but no version, and a `.tiny.yaml` image whose
 * `description:` just repeats its own name.
 *
 * [WorkspaceCardContent] is pure precisely so this file can exist — the repo has
 * no Compose UI test harness and no Robolectric (§1.124(a)).
 */
class WorkspaceCardContentTest {

    private fun workspace(
        name: String = "My Workspace",
        distroName: String? = null,
        distroCode: String? = null,
        distroVersion: String? = null,
        distroCodename: String? = null,
        imageDescription: String? = null,
        storageUsedLabel: String? = "1.4 GB"
    ) = Workspace(
        id = "ws-1",
        name = name,
        description = "ignored legacy summary",
        storageUsedLabel = storageUsedLabel,
        storageUsedFraction = 0.14f,
        avatarLetter = "M",
        avatarColor = Color.Red,
        distroName = distroName,
        distroCode = distroCode,
        distroVersion = distroVersion,
        distroCodename = distroCodename,
        imageDescription = imageDescription
    )

    @Test
    fun `title is always the user's workspace name, never the image's`() {
        // The declared display_name is only the install screen's pre-filled
        // value (§1.119); showing it here would make Rename look like a no-op.
        val content = WorkspaceCardContent.from(
            workspace(name = "Dev box", imageDescription = "Debian Bookworm Desktop")
        )
        assertEquals("Dev box", content.title)
    }

    @Test
    fun `name version and codename compose into one line`() {
        assertEquals(
            "Debian 13 (trixie)",
            WorkspaceCardContent.distroLabelOf("Debian", "13", "trixie")
        )
    }

    @Test
    fun `the literal unknown version is not rendered`() {
        // Real value for an undetected import — "Debian unknown" on a healthy
        // workspace is the bug this guards.
        assertEquals("Debian", WorkspaceCardContent.distroLabelOf("Debian", "unknown", null))
    }

    @Test
    fun `a codename survives a missing version for rolling releases`() {
        assertEquals(
            "Arch Linux ARM (rolling)",
            WorkspaceCardContent.distroLabelOf("Arch Linux ARM", null, "rolling")
        )
    }

    @Test
    fun `a codename identical to the version is dropped`() {
        // Some images declare version: trixie — "Debian trixie (trixie)" is not
        // information.
        assertEquals(
            "Debian trixie",
            WorkspaceCardContent.distroLabelOf("Debian", "trixie", "TRIXIE")
        )
    }

    @Test
    fun `nothing known means no distro line at all`() {
        assertNull(WorkspaceCardContent.distroLabelOf(null, "  ", null))
        assertNull(WorkspaceCardContent.from(workspace()).distroLabel)
    }

    @Test
    fun `the short code is normalised and kept separate from the label`() {
        val content = WorkspaceCardContent.from(
            workspace(distroName = "Debian", distroCode = " Debian ", distroVersion = "13")
        )
        assertEquals("debian", content.distroCode)
        assertEquals("Debian 13", content.distroLabel)
    }

    @Test
    fun `a description that merely repeats the distro label is suppressed`() {
        val content = WorkspaceCardContent.from(
            workspace(
                distroName = "Debian",
                distroVersion = "13",
                distroCodename = "trixie",
                imageDescription = "Debian 13 (trixie)"
            )
        )
        assertNull(content.description)
    }

    @Test
    fun `a description that merely repeats the workspace name is suppressed`() {
        val content = WorkspaceCardContent.from(
            workspace(name = "Kali", imageDescription = " kali ")
        )
        assertNull(content.description)
    }

    @Test
    fun `a real description is kept`() {
        val content = WorkspaceCardContent.from(
            workspace(
                name = "Dev box",
                distroName = "Debian",
                distroVersion = "13",
                imageDescription = "XFCE desktop with build tools preinstalled."
            )
        )
        assertEquals("XFCE desktop with build tools preinstalled.", content.description)
    }

    @Test
    fun `an undeclared workspace still reports its size`() {
        val content = WorkspaceCardContent.from(workspace())
        assertEquals("1.4 GB", content.sizeLabel)
        assertEquals(0.14f, content.storageFraction, 0.0001f)
        assertNull(content.description)
        assertNull(content.distroCode)
    }

    // ── The redesigned header's split identity ─────────────────────────
    // The card places the distribution name under the workspace title and the
    // version beside the name, as two independently-styled elements. They
    // cannot come from the pre-joined `distroLabel`, so they get their own
    // fields — and the same absence rules.

    @Test
    fun `name and version are exposed separately for the header`() {
        val content = WorkspaceCardContent.from(
            workspace(distroName = "Debian", distroVersion = "13", distroCodename = "trixie")
        )
        assertEquals("Debian", content.distroName)
        assertEquals("13 (trixie)", content.distroVersionLabel)
        // The joined form still exists: it is what the description rule compares
        // against, and what other screens read.
        assertEquals("Debian 13 (trixie)", content.distroLabel)
    }

    @Test
    fun `the split version drops the literal unknown`() {
        // Same guard as the joined label — the header must not read
        // "Debian  unknown" on a healthy imported workspace.
        assertNull(WorkspaceCardContent.distroVersionLabelOf("unknown", null))
        assertNull(WorkspaceCardContent.from(workspace(distroName = "Debian")).distroVersionLabel)
    }

    @Test
    fun `a rolling release shows its codename as the version`() {
        assertEquals("rolling", WorkspaceCardContent.distroVersionLabelOf(null, "rolling"))
    }

    @Test
    fun `a codename identical to the version is dropped from the split version`() {
        assertEquals("trixie", WorkspaceCardContent.distroVersionLabelOf("trixie", "TRIXIE"))
    }

    @Test
    fun `an undeclared workspace has neither name nor version in the header`() {
        val content = WorkspaceCardContent.from(workspace())
        assertNull(content.distroName)
        assertNull(content.distroVersionLabel)
    }

    @Test
    fun `a blank declared name is absent rather than empty`() {
        val content = WorkspaceCardContent.from(
            workspace(distroName = "   ", distroVersion = "13")
        )
        assertNull(content.distroName)
        assertEquals("13", content.distroVersionLabel)
    }

    // ── The header's distribution headline ─────────────────────────────
    // The short code is no longer its own monospace line under the name: for the
    // common case that read as a stutter (`Debian` / `debian`) and cost every
    // card a third text row for information the name already carried. It is now
    // the *fallback* for the name, so an image that declared only `distro.id`
    // still identifies itself without anything being invented.

    @Test
    fun `the headline prefers the declared name over the short code`() {
        val content = WorkspaceCardContent.from(
            workspace(distroName = "Alpine Linux", distroCode = "alpine")
        )
        assertEquals("Alpine Linux", content.distroHeadline)
    }

    @Test
    fun `the headline falls back to the short code when no name was declared`() {
        // A real shape: `linexa.distro.id` with no `distro.name` (§1.118 makes
        // the name optional), where the code is the only identity available.
        val content = WorkspaceCardContent.from(workspace(distroCode = "archlinux"))
        assertEquals("archlinux", content.distroHeadline)
    }

    @Test
    fun `no name and no code means no headline at all`() {
        assertNull(WorkspaceCardContent.from(workspace()).distroHeadline)
    }

    // ── The size line ──────────────────────────────────────────────────

    @Test
    fun `an unmeasurable size is absent rather than zero`() {
        // HomeViewModel maps `getWorkspaceSize() == 0` — a workspace whose
        // directory does not exist yet, or is being deleted (§1.117) — to null.
        // "0 B" on an installed workspace would state a measurement nobody took.
        assertNull(WorkspaceCardContent.from(workspace(storageUsedLabel = null)).sizeLabel)
    }

    @Test
    fun `a blank size label is treated as absent`() {
        assertNull(WorkspaceCardContent.from(workspace(storageUsedLabel = "  ")).sizeLabel)
    }
}
