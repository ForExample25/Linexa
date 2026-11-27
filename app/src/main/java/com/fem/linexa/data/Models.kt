package com.fem.linexa.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.fem.linexa.domain.workspace.WorkspaceStatus

data class Workspace(
    val id: String,
    val name: String,
    val description: String,
    /**
     * Pre-formatted total on-disk size, or null when it could not be measured.
     *
     * Nullable rather than `"0 B"`: `WorkspaceFileManager.getWorkspaceSize`
     * returns `0` when the workspace directory does not exist yet — a row
     * persisted before its rootfs, or one being deleted from under the Home list
     * (§1.117) — and an installed workspace is never genuinely zero bytes. The
     * mappers translate that sentinel into null here so the card can omit the
     * size line rather than print a figure nobody measured.
     */
    val storageUsedLabel: String?,
    val storageUsedFraction: Float, // 0f..1f, purely cosmetic
    val avatarLetter: String,
    val avatarColor: Color,
    val isRunning: Boolean = false,
    val status: WorkspaceStatus = WorkspaceStatus.Ready,
    /** Distro catalog id (e.g. "ubuntu") used to pick a branded icon; null → letter avatar fallback. */
    val distroId: String? = null,
    /**
     * UI Phase 2 — the distribution's own identity, split out of the single
     * `description` string the card used to render.
     *
     * [description] is now the *image's* prose (its declared
     * `metadata.description`), so the distro facts need their own fields: a
     * card showing a title, a description and a distro line cannot derive
     * three independent pieces of text from one pre-joined
     * `"Debian 13"`. These are nullable because a workspace installed before
     * any of this — or from an archive that declared nothing — genuinely has
     * only what `DistroInfo` recorded, and the card must render honestly rather
     * than invent a codename.
     *
     * @see distroCode the short lower-case id (`debian`), which is what the
     *   user sees referred to as the distribution's "code" and what selects the
     *   branded glyph.
     */
    val distroName: String? = null,
    val distroCode: String? = null,
    val distroVersion: String? = null,
    /**
     * Release codename (`trixie`, `jammy`), when the image declared one.
     *
     * Only ever populated from a *declaration* — `DistroInfo` has no codename
     * column and the probe does not read `VERSION_CODENAME`, so this stays null
     * for every legacy workspace rather than being guessed from a version
     * number.
     */
    val distroCodename: String? = null,
    /**
     * § export — the architecture ABI this workspace was installed for
     * (`arm64-v8a`, `x86_64`, …), from the image's declaration or the install
     * probe, or null when neither recorded one.
     *
     * Not rendered by the card; it feeds the suggested export file name
     * ([com.fem.linexa.service.exporter.ExportNames]) so a backup is
     * recognisable — and installable on the right device — by name alone.
     */
    val distroArch: String? = null,
    /**
     * UI Phase 2 — the **image's own** prose description
     * (`linexa.metadata.description`, §1.118, or a legacy `.tiny.yaml`
     * `description:`, §1.119), or null when it declared none.
     *
     * Deliberately a new field rather than a redefinition of [description].
     * [description] has always been a *distro summary* (`"Debian 13"`) and two
     * other screens read it as one — the Home share intent and
     * `DiagnosticViewerScreen`'s metadata bar, which labels it `distroName`.
     * Redefining it would have silently blanked the diagnostics header for every
     * workspace that declared no prose, so the card gets its own field and the
     * old meaning is left intact.
     *
     * Null is the common case and renders as *no* description line. The card
     * must not synthesize prose by restating the distro facts it already shows
     * one line above.
     */
    val imageDescription: String? = null,
    /**
     * When the workspace was created (epoch millis, from `WorkspaceMetadata.createdAt`).
     *
     * Added for the Home list's Newest / Oldest sort (P3). It is data the sort needs and
     * the card does not render; the default only serves previews and tests that build a
     * `Workspace` by hand, where every card tying at `0L` keeps the incoming order.
     */
    val createdAt: Long = 0L
)

data class Shortcut(
    val id: String,
    val title: String,
    val description: String,
    @DrawableRes val iconRes: Int,
    /**
     * One-shot command executed for this shortcut, or null when the shortcut
     * is interactive (e.g. "Custom Command" prompts for input before running).
     */
    val command: String? = null,
    /**
     * UI/UX Phase 5C — the distribution this command came from
     * (`Debian GNU/Linux 13 (trixie)`), or null when nothing declared one.
     *
     * Null is the common case and renders as **no** distribution line. It is
     * populated only for a command the *image's own metadata* declared, next to
     * a distro that same metadata declared — so the line always attributes a
     * command to the distribution that actually shipped it.
     *
     * The app's built-in fallback catalog therefore never carries one: those
     * `apt`-flavoured commands are Kotlin's guess for an image that declared
     * nothing (see the built-in Quick Commands catalog), and stamping the
     * workspace's distro onto them would present a hardcoded guess as a
     * declaration. A legacy `.tiny.yaml` image gets none either — it states no
     * distro at all (`TinyMetadataAdapter`), and the probe's *detected* identity
     * is not a declaration about the command.
     */
    val distributionLabel: String? = null,
    /**
     * Issue #1 (2026-09-03): the entry declares `as_root: true` — tapping it
     * opens its terminal with the ROOT emulation (the explicit elevation
     * surface for operations that genuinely need root). Built-in catalog and
     * legacy `.tiny.yaml` entries are never elevated.
     */
    val asRoot: Boolean = false,
    /**
     * The custom-commands feature: true ONLY for a card that renders one of
     * the workspace's saved user commands (the "My Commands" section). An
     * explicit model flag — the UI must offer Edit/Delete on the model's
     * statement, never by inspecting an id prefix or a command string.
     * Built-in catalog and image-declared commands are project/image data
     * and are never editable here.
     */
    val isCustom: Boolean = false
)

data class Feature(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val iconRes: Int,
    val enabledByDefault: Boolean
)
