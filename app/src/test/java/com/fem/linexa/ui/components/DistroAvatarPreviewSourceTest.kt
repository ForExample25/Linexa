package com.fem.linexa.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.124 structural guard for the avatar's rendering precedence.
 *
 * Why a source test rather than a behavioural one: asserting that
 * [DistroAvatar] draws the declared preview needs Compose UI testing
 * (`ui-test-junit4`) or Robolectric, and neither is available in this repo —
 * `app/src/androidTest` does not exist and the offline module cache has no
 * `androidx.test`. The alternative to a source test is *no* coverage of the one
 * decision §1.124 exists to make, which is worse: the whole point of the change
 * is that the image's own artwork outranks a glyph Linexa guessed from a catalog
 * id, and a well-meaning refactor reordering those branches would silently
 * restore the pre-§1.121 rendering with every other test still green.
 *
 * Same technique and rationale as `WorkspaceSettingsAccessSourceTest` and
 * `LifecycleConformanceSourceTest`.
 */
class DistroAvatarPreviewSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/ui/components/OsDistributionIcon.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String = File(moduleRoot, relative).readText()

    private val avatar: String get() = source("src/main/java/com/fem/linexa/ui/components/OsDistributionIcon.kt")

    /**
     * [DistroAvatar]'s own body.
     *
     * §1.133 moved [DistroGlyph] into this file (the workspace app bar's leading
     * slot needs the same mark), so the file now declares two renderings and the
     * *branch-order* assertions below have to be scoped to the one whose contract
     * is an order — the glyph half deliberately has no preview branch at all,
     * which would otherwise read as the preview having lost its precedence.
     */
    private val avatarFn: String get() = avatar.substring(avatar.indexOf("fun DistroAvatar("))

    private val glyphFn: String
        get() = avatar.substring(
            avatar.indexOf("fun DistroGlyph("),
            // Stops at DistroAvatar's KDoc, not at its signature: that doc block
            // names `previewFile` several times, which would otherwise read as
            // DistroGlyph having grown a preview branch.
            avatar.indexOf("/**\n * Draws the avatar")
        )

    @Test
    fun theAvatarAcceptsAResolvedPreviewFile() {
        assertTrue(
            "DistroAvatar must take the preview as an already-resolved File — passing a declared path string " +
                "would let a caller render an author string that never went through RootFsPreviewImage (§1.121)",
            avatar.contains("previewFile: File? = null")
        )
    }

    @Test
    fun thePreviewParameterDefaultsToNullSoExistingCallSitesAreUnchanged() {
        // Every workspace without a declared preview, and the catalog sheet's
        // avatar, must keep rendering exactly what they rendered before §1.121.
        assertTrue(avatar.contains("previewFile: File? = null"))
    }

    @Test
    fun theDeclaredPreviewOutranksTheDistroGlyphAndTheLetter() {
        // §1.121: the preview wins. The fallback below it is the DistroGlyph call,
        // which itself renders branded-glyph-or-letter (see theSmallGlyphIsTheGlyphOrLetterHalfOnly).
        // (2026-08-29: the former inline `else if (iconRes != null)` branch was folded
        // into DistroGlyph; this test follows the current shape.)
        val previewBranch = avatarFn.indexOf("if (declaredPreview != null)")
        val glyphBranch = avatarFn.indexOf("} else {")
        assertTrue("the preview must be the first branch", previewBranch in 0 until glyphBranch)
        assertTrue(
            "the non-preview fallback must be DistroGlyph (branded glyph or letter)",
            avatarFn.substring(glyphBranch).contains("DistroGlyph(")
        )
    }

    @Test
    fun theSmallGlyphIsTheGlyphOrLetterHalfOnly() {
        // §1.127/§1.133: DistroGlyph must NOT gain a preview branch. Its callers
        // (the Home card's identity line, the workspace app bar) are places where
        // the preview is either already shown full-width or deliberately not shown
        // at all — rendering it again in a 32dp tile is the defect this separation
        // exists to prevent.
        assertFalse(
            "DistroGlyph must not render a declared preview",
            glyphFn.contains("previewFile") || glyphFn.contains("rememberPreview")
        )
        assertTrue("it must still render the branded glyph", glyphFn.contains("DistroLogo("))
        assertTrue("and the letter fallback", glyphFn.contains("text = letter"))
    }

    @Test
    fun bothFallbacksSurviveTheChange() {
        // §1.121's contract is that a rejected preview is *no preview*, not an
        // error — which is only true if both older renderings still exist.
        assertTrue(
            "the branded distro glyph must still be rendered (via DistroGlyph)",
            avatarFn.contains("DistroGlyph(")
        )
        assertTrue(
            "the letter avatar must still be rendered (inside DistroGlyph)",
            avatar.contains("text = letter")
        )
    }

    @Test
    fun theAvatarNeverResolvesAPathItself() {
        // Resolution re-canonicalizes against the live filesystem on every read
        // (§1.121(b)) and belongs on the ViewModel's IO dispatcher. Doing it in
        // composition would put a stat + canonicalize on the main thread and,
        // worse, invite a String overload.
        assertFalse(
            "DistroAvatar must not call RootFsPreviewImage — the file arrives already resolved",
            avatarFn.contains("RootFsPreviewImage")
        )
        assertFalse(
            "DistroAvatar must not read a rootfs path",
            avatarFn.contains("getRootfsPath")
        )
    }

    @Test
    fun theDecodeIsOffTheMainThread() {
        val loader = source("src/main/java/com/fem/linexa/ui/components/RootFsPreviewLoader.kt")
        assertTrue(
            "the decode must run on Dispatchers.IO — BitmapFactory on a file is disk I/O plus allocation",
            loader.contains("withContext(Dispatchers.IO)")
        )
        assertTrue(
            "bounds must be read before the pixels so a hostile header cannot cause an OOM",
            loader.indexOf("inJustDecodeBounds = true") <
                loader.indexOf("RootFsPreviewScaling.sampleSizeFor")
        )
        assertTrue(
            "the bounds check must gate the real decode",
            loader.contains("RootFsPreviewScaling.isDecodable(")
        )
    }

    @Test
    fun theLoaderIntroducesNoImageLoadingDependency() {
        // §1.124(a): the build must resolve --offline, and the offline module
        // cache contains exactly the current graph. A new coordinate here does
        // not make the build slower, it makes it fail.
        val loader = source("src/main/java/com/fem/linexa/ui/components/RootFsPreviewLoader.kt")
        assertFalse("no Coil", loader.contains("import coil"))
        assertFalse("no Glide", loader.contains("com.bumptech.glide"))

        val gradle = source("build.gradle.kts")
        assertFalse("no Coil coordinate", gradle.contains("io.coil-kt"))
        assertFalse("no Glide coordinate", gradle.contains("com.github.bumptech.glide"))
    }

    @Test
    fun theHomePipelineDoesNotResolveAnUnrenderedPreview() {
        // PERF-005 (performance remediation 2026-11-23): `Workspace.previewImage`
        // was populated on every enrichment pass but no composable ever read it —
        // `HavenWorkspaceCard` renders the distro glyph. One canonicalize +
        // `exists()` per workspace, on IO, on every Home visit, for a `File` that
        // was immediately discarded. The field and its resolution were deleted.
        //
        // Reintroducing either is only legitimate together with a consumer, so
        // this guard is deliberately about the *absence* of the wasted work, not
        // about the (still shipped, still tested) store API.
        val vm = source("src/main/java/com/fem/linexa/ui/screens/home/HomeViewModel.kt")
        assertFalse(
            "HomeViewModel must not resolve a preview nothing renders (PERF-005)",
            vm.contains("rootFsMetadata.previewImage(")
        )
        assertFalse(
            "HomeViewModel must not carry a declaredPreview() helper (PERF-005)",
            vm.contains("declaredPreview")
        )
        val models = source("src/main/java/com/fem/linexa/data/Models.kt")
        assertFalse(
            "Workspace.previewImage must stay deleted until a card renders it (PERF-005)",
            models.contains("val previewImage")
        )
    }
}
