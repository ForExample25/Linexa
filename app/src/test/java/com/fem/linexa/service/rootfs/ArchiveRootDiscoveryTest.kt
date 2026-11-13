package com.fem.linexa.service.rootfs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §1.147 — wrapper-directory detection.
 *
 * The rule this pins is intentionally conservative because both failure
 * directions are expensive: stripping a component that is not a wrapper
 * destroys the rootfs layout, and failing to strip one turns a valid Debian
 * rootfs into `missing /etc` after extraction.
 */
class ArchiveRootDiscoveryTest {

    private fun strip(vararg entries: Pair<String, Boolean>) =
        ArchiveRootDiscovery.stripComponents(entries.toList())

    @Test
    fun aBareRootfsIsNotStripped() {
        assertEquals(
            0,
            strip(
                "./etc/" to true,
                "./usr/" to true,
                "./usr/bin/bash" to false,
                "./var/" to true
            )
        )
    }

    @Test
    fun aRootfsUnderOneWrapperDirectoryIsStripped() {
        assertEquals(
            1,
            strip(
                "./debian-rootfs/etc/" to true,
                "./debian-rootfs/usr/" to true,
                "./debian-rootfs/usr/bin/bash" to false,
                "./debian-rootfs/var/" to true
            )
        )
    }

    @Test
    fun theWrapperNameDoesNotHaveToLookLikeARootfs() {
        assertEquals(1, strip("image-x/" to true, "image-x/etc/" to true))
    }

    @Test
    fun aSingleStandardTopLevelDirectoryIsNotTreatedAsAWrapper() {
        // `tar -C /etc .` produces exactly this shape; stripping `etc` would
        // leave the guest with no /etc at all.
        assertEquals(0, strip("./etc/" to true, "./etc/hosts" to false, "./etc/os-release" to false))
    }

    @Test
    fun aRootLevelFileBlocksStrippingEvenWithOneDirectory() {
        assertEquals(
            0,
            strip(
                "./README" to false,
                "./rootfs/" to true,
                "./rootfs/etc/" to true
            )
        )
    }

    @Test
    fun multipleTopLevelDirectoriesAreNeverStripped() {
        assertEquals(
            0,
            strip("rootfs/" to true, "extras/" to true, "rootfs/etc/" to true)
        )
    }

    @Test
    fun aWrapperWithOnlyOneEntryIsStillStripped() {
        assertEquals(1, strip("rootfs/etc/" to true))
        assertEquals(1, strip("rootfs/etc/os-release" to false))
    }

    @Test
    fun theArchiveRootItselfIsIgnored() {
        val shape = ArchiveRootDiscovery.observe(
            ArchiveRootDiscovery.Shape(emptySet(), rootLevelFile = false),
            "",
            isDirectory = true
        )
        assertEquals(0, ArchiveRootDiscovery.stripComponents(shape))
    }

    @Test
    fun anEmptyArchiveHasNothingToStrip() {
        assertEquals(0, strip())
    }

    @Test
    fun pathNormalizationIsAppliedBeforeTheFirstSegmentIsRead() {
        // No leading `./`, no trailing slash: the same archive as the wrapped
        // case above, spelled differently.
        assertEquals(1, strip("rootfs" to true, "rootfs/etc/os-release" to false))
        assertEquals(1, strip("././rootfs/etc/" to true, "/rootfs/usr/" to true))
    }
}
