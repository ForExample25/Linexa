package com.fem.linexa.service.runtime

import com.fem.linexa.domain.runtime.RuntimeDegradation
import com.fem.linexa.domain.runtime.RuntimeHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transformation spec §6 — the component-health registry backing the
 * observable DEGRADED lifecycle: reports, recovery, teardown clearing, and
 * the aggregate → RuntimeState.Running.health mapping.
 */
class WorkspaceHealthRegistryTest {

    @Test
    fun `empty registry means healthy`() {
        val registry = WorkspaceHealthRegistry()
        assertTrue(registry.degradations("ws") .isEmpty())
        assertEquals(RuntimeHealth.Healthy, registry.healthOf("ws"))
    }

    @Test
    fun `degradations are per-workspace and per-component`() {
        val registry = WorkspaceHealthRegistry()
        registry.reportDegraded("ws-a", "ssh", "sshd could not start")
        registry.reportDegraded("ws-a", "daemon:xvnc", "daemon 'xvnc' exited unexpectedly (exit code 1)")
        registry.reportDegraded("ws-b", "network", "resolv.conf write failed (exit 1)")

        assertEquals(2, registry.degradations("ws-a").size)
        assertEquals(1, registry.degradations("ws-b").size)
        assertTrue(registry.degradations("ws-a").any { it.component == "ssh" })
        assertTrue(registry.degradations("ws-a").any { it.component == "daemon:xvnc" })
    }

    @Test
    fun `re-reporting a component replaces its detail`() {
        val registry = WorkspaceHealthRegistry()
        registry.reportDegraded("ws", "audio", "first failure")
        registry.reportDegraded("ws", "audio", "second failure")
        assertEquals(1, registry.degradations("ws").size)
        assertEquals("second failure", registry.degradations("ws").single().detail)
    }

    @Test
    fun `recovery is explicit - reportHealthy removes the entry`() {
        val registry = WorkspaceHealthRegistry()
        registry.reportDegraded("ws", "ssh", "down")
        registry.reportHealthy("ws", "ssh")
        assertTrue(registry.degradations("ws").isEmpty())
    }

    @Test
    fun `teardown clears the whole workspace`() {
        val registry = WorkspaceHealthRegistry()
        registry.reportDegraded("ws", "ssh", "down")
        registry.reportDegraded("ws", "audio", "down")
        registry.clear("ws")
        assertTrue(registry.degradations("ws").isEmpty())
        assertFalse(registry.health.value.containsKey("ws") && registry.health.value["ws"]!!.isNotEmpty())
    }

    @Test
    fun `aggregate health reflects the degradation set`() {
        val registry = WorkspaceHealthRegistry()
        registry.reportDegraded("ws", "network", "DNS write failed")
        val health = registry.healthOf("ws")
        assertTrue(health is RuntimeHealth.Degraded)
        assertEquals(1, (health as RuntimeHealth.Degraded).degradations.size)
    }

    @Test
    fun `daemon component label strips the prefix`() {
        assertEquals("Xvnc", RuntimeDegradation("daemon:xvnc", "x").componentLabel)
        assertEquals("Ssh", RuntimeDegradation("ssh", "x").componentLabel)
    }
}
