package com.fem.linexa.domain.attribution

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.130 — the license reader must fail soft and must never show a partial document.
 *
 * These are the two properties that made [LicenseTextLoader] take a [LicenseTextSource]
 * lambda instead of an `AssetManager`: coupling it to Android would have made the only
 * behaviour worth asserting untestable on the JVM.
 */
class LicenseTextLoaderTest {

    private fun loader(vararg assets: Pair<String, String>): LicenseTextLoader {
        val byPath = assets.toMap()
        return LicenseTextLoader { path ->
            val text = byPath[path] ?: throw IOException("no such asset: $path")
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun readsTheTextForTheRefsOwnAssetPath() {
        val ref = LicenseRef.MIT
        val loaded = loader(ref.assetPath!! to "MIT text").load(ref)
        assertEquals("MIT text", loaded)
    }

    @Test
    fun theAssetPathIsUnderTheLicensesDirectory() {
        // The loader is given a path, not a name, so the directory is part of the
        // contract rather than something the caller re-derives.
        assertEquals("licenses/MIT.txt", LicenseRef.MIT.assetPath)
        assertNull("a license with no document has no path", LicenseRef.PUBLIC_DOMAIN.assetPath)
    }

    @Test
    fun aLicenseWithNoDocumentReturnsNullWithoutTouchingTheSource() {
        var opened = false
        val loader = LicenseTextLoader { path ->
            opened = true
            ByteArrayInputStream(path.toByteArray())
        }
        assertNull(loader.load(LicenseRef.PUBLIC_DOMAIN))
        assertTrue("public domain must not attempt a read", !opened)
    }

    @Test
    fun aMissingDocumentDegradesToNullInsteadOfThrowing() {
        // What AssetManager actually does for an absent asset. A crash here would take
        // the app down from an informational screen.
        assertNull(loader().load(LicenseRef.APACHE_2_0))
    }

    @Test
    fun aFailureMidReadIsNotReportedAsAPartialDocument() {
        // The worst outcome for a legal document: showing half of it as if it were
        // whole. The read is all-or-nothing, so a stream that dies partway yields null.
        val loader = LicenseTextLoader {
            object : InputStream() {
                private var served = 0
                override fun read(): Int {
                    if (served++ < 8) return 'A'.code
                    throw IOException("truncated")
                }
            }
        }
        assertNull(loader.load(LicenseRef.GPL_2_0_ONLY))
    }

    @Test
    fun bothGplGrantsResolveToTheSameDocument() {
        // One text, two grants — the loader must not need to know the difference.
        val path = LicenseRef.GPL_2_0_ONLY.assetPath!!
        assertEquals(path, LicenseRef.GPL_2_0_OR_LATER.assetPath)
        val loader = loader(path to "GPLv2 text")
        assertEquals("GPLv2 text", loader.load(LicenseRef.GPL_2_0_ONLY))
        assertEquals("GPLv2 text", loader.load(LicenseRef.GPL_2_0_OR_LATER))
    }

    @Test
    fun textIsDecodedAsUtf8AndKeptByteExact() {
        // These documents contain copyright signs and non-breaking spaces; a
        // platform-default decode would mangle them on a device with a different locale.
        val path = LicenseRef.ISC.assetPath!!
        val text = "Copyright \u00a9 1996\u201d2024  \u201cAS IS\u201d\n\n  indented\n"
        assertEquals(text, loader(path to text).load(LicenseRef.ISC))
    }

    @Test
    fun anEmptyDocumentReadsAsEmptyNotAsAFailure() {
        // Distinguishable from a missing asset on purpose: an empty file is a packaging
        // fault the About screen should surface as an unavailable text, and the test
        // suite already refuses to let one be committed.
        val path = LicenseRef.ZLIB.assetPath!!
        assertEquals("", loader(path to "").load(LicenseRef.ZLIB))
    }

    @Test
    fun theStreamIsClosedEvenWhenTheReadFails() {
        var closed = false
        val loader = LicenseTextLoader {
            object : InputStream() {
                override fun read(): Int = throw IOException("boom")
                override fun close() {
                    closed = true
                }
            }
        }
        assertNull(loader.load(LicenseRef.MIT))
        assertTrue("the asset stream must be closed on the failure path", closed)
    }
}
