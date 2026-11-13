package com.fem.linexa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.fem.linexa.R
import com.fem.linexa.ui.components.haven.HGlyph
import com.fem.linexa.ui.theme.haven.HavenDim
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * A distribution's visual identity, resolved from the official logo artwork
 * bundled under `res/drawable/ic_distro_*` (derived from the official
 * distribution logos in the Linexa IconSvg library, colors preserved).
 *
 * @param iconRes the official logo vector.
 * @param brandColor the distribution's official brand color, used for the
 *   letter-tile fallback (see [HGlyph]) and for status accents.
 * @param darkVariant the logo's recolor for dark surfaces. Only set for
 *   monochrome marks whose official single color is too dark to read on the
 *   dark theme (Alpine's slate, Tails' purple, black marks like MX Linux);
 *   full-color logos keep their official artwork untouched.
 */
data class DistroVisual(
    val iconRes: Int,
    val brandColor: Color,
    val darkVariant: Color? = null
)

/**
 * The central distro → logo registry. Every place the UI represents a Linux
 * distribution resolves through [visualFor] instead of hardcoding its own
 * icon, so adding a distribution's artwork is one table row here.
 */
object DistroIcons {

    private val visuals: Map<String, DistroVisual> = mapOf(
        "almalinux" to DistroVisual(R.drawable.ic_distro_almalinux, Color(0xFF000000), Color(0xFFE8EAED)),
        "alpine" to DistroVisual(R.drawable.ic_distro_alpine, Color(0xFF0D597F), Color(0xFF82B8D6)),
        "archlinux" to DistroVisual(R.drawable.ic_distro_archlinux, Color(0xFF1793D1)),
        "centos" to DistroVisual(R.drawable.ic_distro_centos, Color(0xFF262577)),
        "debian" to DistroVisual(R.drawable.ic_distro_debian, Color(0xFFA80030)),
        "elementary" to DistroVisual(R.drawable.ic_distro_elementary, Color(0xFF64BAFF)),
        "endeavouros" to DistroVisual(R.drawable.ic_distro_endeavouros, Color(0xFF7F7FFF)),
        "fedora" to DistroVisual(R.drawable.ic_distro_fedora, Color(0xFF3C6EB4)),
        "gentoo" to DistroVisual(R.drawable.ic_distro_gentoo, Color(0xFF54487A), Color(0xFFA79BC8)),
        "kalilinux" to DistroVisual(R.drawable.ic_distro_kalilinux, Color(0xFF2777FF)),
        "linuxmint" to DistroVisual(R.drawable.ic_distro_linuxmint, Color(0xFF69B53F)),
        "manjaro" to DistroVisual(R.drawable.ic_distro_manjaro, Color(0xFF35BF5C)),
        "mxlinux" to DistroVisual(R.drawable.ic_distro_mxlinux, Color(0xFF000000), Color(0xFFE8EAED)),
        "nixos" to DistroVisual(R.drawable.ic_distro_nixos, Color(0xFF5277C3)),
        "opensuse" to DistroVisual(R.drawable.ic_distro_opensuse, Color(0xFF73BA25)),
        "parrotsecurity" to DistroVisual(R.drawable.ic_distro_parrotsecurity, Color(0xFF15E0ED)),
        "popos" to DistroVisual(R.drawable.ic_distro_popos, Color(0xFF48B9C7)),
        "redhat" to DistroVisual(R.drawable.ic_distro_redhat, Color(0xFFEE0000)),
        "rockylinux" to DistroVisual(R.drawable.ic_distro_rockylinux, Color(0xFF10B981)),
        "tails" to DistroVisual(R.drawable.ic_distro_tails, Color(0xFF56347C), Color(0xFFA98FD0)),
        "ubuntu" to DistroVisual(R.drawable.ic_distro_ubuntu, Color(0xFFE95420)),
        "voidlinux" to DistroVisual(R.drawable.ic_distro_voidlinux, Color(0xFF478061))
    )

    /**
     * Common alternative spellings a distro id arrives under — DistroManifest
     * ids, RootFsProbe/RootFsDescriptor ids, os-release `ID` values and
     * importers' labels. Keys and values are normalized ([normalize]).
     */
    private val aliases: Map<String, String> = mapOf(
        "alpinelinux" to "alpine",
        "arch" to "archlinux",
        "archarm" to "archlinux",
        "archlinuxarm" to "archlinux",
        "elementaryos" to "elementary",
        "kali" to "kalilinux",
        "mint" to "linuxmint",
        "mx" to "mxlinux",
        "parrot" to "parrotsecurity",
        "parrotos" to "parrotsecurity",
        "pop" to "popos",
        "rhel" to "redhat",
        "redhatenterpriselinux" to "redhat",
        "rocky" to "rockylinux",
        "suse" to "opensuse",
        "void" to "voidlinux"
    )

    /** Lowercase, strip everything non-alphanumeric: "Arch Linux ARM" → "archlinuxarm". */
    fun normalize(distroId: String?): String? =
        distroId?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

    /**
     * The visual for a distro id in any of the spellings above, or null when
     * Linexa cannot identify it — callers keep their letter fallback, never a
     * generic "Linux" mark for a distro we can't name (that would be fake
     * branding).
     */
    fun visualFor(distroId: String?): DistroVisual? {
        val key = normalize(distroId) ?: return null
        return visuals[key] ?: visuals[aliases[key]]
    }
}

/**
 * The official distribution logo, drawn directly on the surface: no tile, no
 * background plate, no border, no tint — the mark's own colors are the
 * identity (only monochrome-dark marks swap to [DistroVisual.darkVariant] on
 * the dark theme, where their official single color would be illegible).
 * Aspect ratio is preserved ([ContentScale.Fit]); unknown ids draw nothing,
 * which is why the letter-fallback logic lives in [DistroIdentity].
 *
 * @param contentDescription null when the distro name is already adjacent
 *   text (the icon is decorative there); pass the name for a11y when the logo
 *   stands alone.
 */
@Composable
fun DistroLogo(
    distroId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val visual = remember(distroId) { DistroIcons.visualFor(distroId) } ?: return
    val dark = HavenTheme.colors.isDark
    val tint = if (dark) visual.darkVariant else null
    Image(
        painter = painterResource(visual.iconRes),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        colorFilter = tint?.let { ColorFilter.tint(it) }
    )
}

/**
 * The distro identity slot used by the Haven screens: the official logo when
 * the distro is identified, the existing letter tile when it is not. The tile
 * size ([size]) is shared by both renderings so list rows keep their rhythm.
 */
@Composable
fun DistroIdentity(
    distroId: String?,
    letter: String,
    color: Color,
    size: Dp = HavenDim.glyph,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    if (DistroIcons.visualFor(distroId) != null) {
        DistroLogo(
            distroId = distroId,
            size = size,
            modifier = modifier,
            contentDescription = contentDescription
        )
    } else {
        HGlyph(
            letter = letter,
            color = color,
            size = size,
            modifier = modifier
        )
    }
}
