package com.fem.linexa.navigation

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-030 — every route argument must round-trip through its builder.
 *
 * The audit found raw interpolation in `Routes.workspace` and manual
 * `Uri.encode` in some builders but not others, so an id containing `/`, `?`,
 * `&`, a space or non-ASCII text could change the route's shape (or be
 * truncated) before Navigation ever saw it.
 */
class RoutesTest {

    private fun decode(value: String): String =
        URLDecoder.decode(value, "UTF-8")

    @Test
    fun `workspace id and terminal id round-trip through the route`() {
        val id = "ws 1/2?x=é&y"
        val terminalId = "term & 1/2 #3"

        val route = Routes.workspace(id, terminalId)

        assertTrue(route.startsWith("workspace/"))
        assertFalse(route.substringBefore('?').removePrefix("workspace/").contains('/'))
        assertEquals(id, decode(route.substringAfter("workspace/").substringBefore('?')))
        assertEquals(terminalId, decode(route.substringAfter("terminalId=")))
    }

    @Test
    fun `a workspace without a terminal has no query string`() {
        val route = Routes.workspace("plain-id")

        assertEquals("workspace/plain-id", route)
    }

    @Test
    fun `install distro round-trips`() {
        val distro = "ubuntu 24.04/arm64"

        val route = Routes.installDistro(distro)

        assertTrue(route.startsWith("install?distroId="))
        assertEquals(distro, decode(route.removePrefix("install?distroId=")))
    }

    @Test
    fun `install file round-trips uri, name and version`() {
        val uri = "content://com.example/doc/1?x=a b&y=é"
        val name = "My Rootfs (v2).tar.gz"
        val version = "24.04 LTS"

        val route = Routes.installFile(uri, name, version)

        assertEquals(uri, decode(route.substringAfter("uri=").substringBefore("&name=")))
        assertEquals(name, decode(route.substringAfter("&name=").substringBefore("&version=")))
        assertEquals(version, decode(route.substringAfter("&version=")))
    }

    @Test
    fun `the route pattern keeps its placeholders`() {
        assertEquals("workspace/{workspaceId}?terminalId={terminalId}", Routes.WORKSPACE)
        assertEquals(
            "install?distroId={distroId}&uri={uri}&name={name}&version={version}",
            Routes.INSTALL
        )
    }
}
