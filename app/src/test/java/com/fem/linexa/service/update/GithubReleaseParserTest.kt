package com.fem.linexa.service.update

import com.fem.linexa.domain.update.ReleaseSelector
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * GitHub's wire shapes → domain models, against the EXACT JSON shape the
 * `ForExample25/Linexa` releases endpoint returns (v0.1.0-beta, with its
 * `digest: "sha256:…"` asset field, a `SHA256SUMS` sidecar, and GitHub's
 * `starter` placeholder assets).
 */
class GithubReleaseParserTest {

    private val v010beta = """
        [
          {
            "id": 260000001,
            "tag_name": "v0.1.0-beta",
            "target_commitish": "main",
            "name": "Linexa 0.1.0-beta",
            "draft": false,
            "prerelease": true,
            "created_at": "2026-11-14T08:20:00Z",
            "published_at": "2026-11-14T08:25:46Z",
            "body": "## What's new\n\nFirst public preview build.",
            "assets": [
              {
                "id": 1,
                "name": "app-release.apk",
                "size": 57257408,
                "browser_download_url": "https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk",
                "content_type": "application/vnd.android.package-archive",
                "state": "uploaded",
                "digest": "sha256:6CE2D01922FF9A73DEA0B37787E5F5E4E83F1B7C52157D3670510AB510293680"
              },
              {
                "id": 2,
                "name": "SHA256SUMS",
                "size": 128,
                "browser_download_url": "https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/SHA256SUMS",
                "content_type": "text/plain",
                "state": "uploaded"
              },
              {
                "id": 3,
                "name": "placeholder.apk",
                "browser_download_url": "http://insecure.example/placeholder.apk",
                "state": "starter"
              },
              {
                "id": 4,
                "name": "insecure.apk",
                "browser_download_url": "http://insecure.example/insecure.apk",
                "state": "uploaded"
              }
            ]
          },
          {
            "id": 260000002,
            "tag_name": "v9.9.9-draft",
            "name": "Unpublished",
            "draft": true
          },
          {
            "id": 260000003,
            "tag_name": null,
            "name": "broken"
          }
        ]
    """.trimIndent()

    @Test
    fun theLiveReleaseShapeParsesToTheDomainModel() {
        val releases = GithubReleaseParser.parseList(v010beta)

        // The tag-less entry is dropped; the draft is PARSED (the selector
        // filters drafts — parsing must not lose information).
        assertEquals(2, releases.size)

        val release = releases.first { it.tagName == "v0.1.0-beta" }
        assertEquals("v0.1.0-beta", release.tagName)
        assertEquals("Linexa 0.1.0-beta", release.displayName)
        assertTrue(release.isPrerelease)
        assertFalse(release.isDraft)
        assertEquals(Instant.parse("2026-11-14T08:25:46Z").toEpochMilli(), release.publishedAtMs)
        assertEquals("## What's new\n\nFirst public preview build.", release.notes)
    }

    @Test
    fun onlyUploadedHttpsAssetsSurvive() {
        val release = GithubReleaseParser.parseList(v010beta).first { it.tagName == "v0.1.0-beta" }
        // "starter" placeholder AND the http:// asset are dropped; the APK and
        // SHA256SUMS (both https + uploaded) remain.
        assertEquals(2, release.assets.size)
        assertEquals("app-release.apk", release.assets[0].name)
        assertEquals(
            "https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk",
            release.assets[0].downloadUrl
        )
        assertEquals(57257408L, release.assets[0].sizeBytes)
    }

    @Test
    fun theGitHubAssetDigestBecomesLowercaseHexWithoutThePrefix() {
        val release = GithubReleaseParser.parseList(v010beta).first { it.tagName == "v0.1.0-beta" }
        assertEquals(
            "6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680",
            release.assets[0].sha256Hex
        )
        // The sidecar asset has no digest field.
        assertNull(release.assets[1].sha256Hex)
    }

    @Test
    fun aNonSha256DigestIsIgnored() {
        val body = """
            [{"tag_name":"v1","assets":[
              {"name":"a.apk","browser_download_url":"https://x/a.apk","state":"uploaded","digest":"md5:abc"}
            ]}]
        """.trimIndent()
        assertNull(GithubReleaseParser.parseList(body).single().assets.single().sha256Hex)
    }

    @Test
    fun aGarbageDigestIsIgnored() {
        // 4 hex chars — not the 64 the sha256 prefix promises.
        val body = """
            [{"tag_name":"v1","assets":[
              {"name":"a.apk","browser_download_url":"https://x/a.apk","state":"uploaded","digest":"sha256:6ce2"}
            ]}]
        """.trimIndent()
        assertNull(GithubReleaseParser.parseList(body).single().assets.single().sha256Hex)
    }

    @Test
    fun aDraftReleaseParsesWithItsDraftFlagIntact() {
        val releases = GithubReleaseParser.parseList(v010beta)
        val draft = releases.first { it.tagName == "v9.9.9-draft" }
        assertTrue(draft.isDraft)
        // The selector — not the parser — is where drafts are excluded: the
        // draft is present in the parsed list, but the selection picks the
        // valid (non-draft) release, never the draft.
        assertEquals(
            "v0.1.0-beta",
            ReleaseSelector.select(releases, includePrereleases = true)?.tagName
        )
    }

    @Test
    fun aVersionCodeMarkerIsExtractedAndRemovedFromTheVisibleNotes() {
        val body = """
            [{
              "tag_name": "v0.2.0",
              "body": "## What's new\n\n<!-- linexa-version-code: 1002 -->\nSmaller and faster."
            }]
        """.trimIndent()
        val release = GithubReleaseParser.parseList(body).single()
        assertEquals(1002, release.declaredVersionCode)
        assertEquals("## What's new\n\nSmaller and faster.", release.notes)
    }

    @Test
    fun aMalformedVersionCodeMarkerIsIgnoredAndLeftInTheNotes() {
        val body = """
            [{"tag_name":"v0.2.0","body":"<!-- linexa-version-code: not-a-number -->"}]
        """.trimIndent()
        val release = GithubReleaseParser.parseList(body).single()
        assertNull(release.declaredVersionCode)
        assertTrue(release.notes!!.contains("not-a-number"))
    }

    @Test
    fun anEmptyListParsesToNoReleases() {
        assertTrue(GithubReleaseParser.parseList("[]").isEmpty())
    }

    @Test
    fun malformedBodiesThrowSerializationException() {
        assertThrows(SerializationException::class.java) {
            GithubReleaseParser.parseList("not json at all")
        }
        assertThrows(SerializationException::class.java) {
            GithubReleaseParser.parseList("""{"tag_name": "an object, not an array"}""")
        }
    }
}
