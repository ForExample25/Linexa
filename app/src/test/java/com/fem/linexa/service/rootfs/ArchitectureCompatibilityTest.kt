package com.fem.linexa.service.rootfs

import com.fem.linexa.service.installer.RootFsProbe.RootFsArch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.147 — architecture gate.
 *
 * The gate exists to fail an install during analysis rather than after a
 * multi-minute extraction. Its dangerous edge is false rejection, so unknown
 * architecture and an unreportable ABI list must both pass.
 */
class ArchitectureCompatibilityTest {

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test
    fun anArm64RootfsRunsOnAnArm64Device() {
        assertNull(ArchitectureCompatibility.rejectionReason(RootFsArch.ARM64_V8A, arm64Device))
    }

    @Test
    fun a32BitArmRootfsRunsOnAnArm64DeviceThatStillReportsArmv7() {
        assertNull(ArchitectureCompatibility.rejectionReason(RootFsArch.ARMEABI_V7A, arm64Device))
    }

    @Test
    fun anArm64RootfsIsRejectedOnA32BitOnlyDevice() {
        val reason = ArchitectureCompatibility.rejectionReason(
            RootFsArch.ARM64_V8A,
            listOf("armeabi-v7a", "armeabi")
        )
        assertTrue("expected an actionable reason, got: $reason", reason != null)
        assertTrue("the reason must name the archive arch", reason!!.contains("arm64"))
        assertTrue("the reason must name the device ABIs", reason.contains("armeabi-v7a"))
    }

    @Test
    fun anX86RootfsIsRejectedOnAnArmDevice() {
        val reason = ArchitectureCompatibility.rejectionReason(RootFsArch.X86_64, arm64Device)
        assertTrue("x86_64 cannot execute on arm64", reason != null)
        assertTrue(reason!!.contains("x86_64"))
    }

    @Test
    fun anArmRootfsIsRejectedOnAnX86Emulator() {
        val reason = ArchitectureCompatibility.rejectionReason(
            RootFsArch.ARM64_V8A,
            listOf("x86_64", "x86")
        )
        assertTrue(reason != null)
    }

    @Test
    fun unknownArchitectureIsNotARejection() {
        assertNull(ArchitectureCompatibility.rejectionReason(RootFsArch.UNKNOWN, arm64Device))
    }

    @Test
    fun anUnreportableAbiListDisablesTheCheckInsteadOfRejectingEverything() {
        assertNull(ArchitectureCompatibility.rejectionReason(RootFsArch.X86_64, emptyList()))
        assertNull(ArchitectureCompatibility.rejectionReason(RootFsArch.X86_64, listOf(" ")))
    }

    @Test
    fun abiComparisonIsCaseAndWhitespaceInsensitive() {
        assertNull(
            ArchitectureCompatibility.rejectionReason(
                RootFsArch.ARM64_V8A,
                listOf(" ARM64-V8A ")
            )
        )
    }

    @Test
    fun acceptedAbisMatchTheAndroidAbiSpellings() {
        assertEquals(
            setOf("arm64-v8a", "arm64"),
            ArchitectureCompatibility.acceptedAbis(RootFsArch.ARM64_V8A)
        )
        assertEquals(
            setOf("x86_64", "x86-64"),
            ArchitectureCompatibility.acceptedAbis(RootFsArch.X86_64)
        )
        assertTrue(ArchitectureCompatibility.acceptedAbis(RootFsArch.UNKNOWN).isEmpty())
    }
}
