package com.fem.linexa.smoke

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 10B (OPT-TEST-002) smoke row 4: the §1.74 SAF DocumentsProvider answers a
 * roots query for its manifest authority (`${applicationId}.docs` →
 * `com.fem.linexa.docs`). Read-only by contract: the query only READS the
 * roots cursor; nothing is created, modified, or deleted.
 */
@RunWith(AndroidJUnit4::class)
class SafRootsSmokeTest {

    @Test
    fun theDocumentsProviderExposesAtLeastOneRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rootsUri = DocumentsContract.buildRootsUri("com.fem.linexa.docs")
        val cursor = context.contentResolver.query(rootsUri, null, null, null, null)
        assertNotNull("the SAF roots query must not return null for $rootsUri", cursor)
        cursor!!.use {
            assertTrue(
                "the SAF DocumentsProvider must expose at least one root, got ${it.count}",
                it.count >= 1
            )
        }
    }
}
