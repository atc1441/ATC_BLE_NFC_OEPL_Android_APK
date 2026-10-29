package io.github.atc1441.oeplnfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the update check that can be tested without a phone: which
 * of two versions is the newer one, and reading a release off the API's answer.
 *
 * Both are the kind of thing that fails silently in the field - an app that
 * decides it is up to date when it is not, or that stops finding the APK
 * because the published file was renamed, says nothing at all about it. The
 * JSON below is the shape the real API returns for this repository, cut down
 * to the fields that are read.
 */
class AppUpdateTest {

    private fun release(vararg assets: Pair<String, Long>, tag: String = "v1.0.52") =
        """
        {
          "tag_name": "$tag",
          "name": "$tag",
          "assets": [
            ${assets.joinToString(",") { (n, s) ->
                """{"name":"$n","size":$s,
                    "browser_download_url":"https://github.com/${AppUpdate.REPO}/releases/download/$tag/$n"}"""
            }}
          ]
        }
        """.trimIndent()

    // --- which version is newer ---------------------------------------------

    @Test
    fun theNumbersComeOutOfATag() {
        assertEquals(listOf(1, 0, 52), AppUpdate.versionNumbers("v1.0.52"))
        assertEquals(listOf(1, 0, 52), AppUpdate.versionNumbers("1.0.52"))
        assertEquals(listOf(1, 0), AppUpdate.versionNumbers("1.0"))
        // A suffix one day must not stop the numbers being read.
        assertEquals(listOf(1, 0, 53), AppUpdate.versionNumbers("v1.0.53-rc1"))
        assertEquals(emptyList<Int>(), AppUpdate.versionNumbers("latest"))
    }

    @Test
    fun aLaterReleaseIsNewer() {
        assertTrue(AppUpdate.isNewer("1.0.53", "1.0.52"))
        assertTrue(AppUpdate.isNewer("1.1.0", "1.0.99"))
        assertTrue(AppUpdate.isNewer("2.0", "1.0.52"))
    }

    @Test
    fun theSameReleaseIsNotNewer() {
        assertFalse(AppUpdate.isNewer("1.0.52", "1.0.52"))
        assertFalse(AppUpdate.isNewer("v1.0.52", "1.0.52"))
        assertFalse(AppUpdate.isNewer("1.0.51", "1.0.52"))
        // The one that matters for not nagging: 1.0 and 1.0.0 are the same
        // version, written two ways.
        assertFalse(AppUpdate.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun aLocalBuildIsBehindAnythingPublished() {
        // build.gradle.kts carries "1.0" until CI passes a version in, so a
        // hand-built APK has to count as older than every published release -
        // otherwise a phone running one would never be offered the update.
        assertTrue(AppUpdate.isNewer("1.0.1", "1.0"))
        assertTrue(AppUpdate.isNewer("1.0.52", "1.0"))
    }

    @Test
    fun anUnreadableTagNeverCountsAsNewer() {
        // Rather be silent than offer an update on a tag nothing can be read
        // out of.
        assertFalse(AppUpdate.isNewer("latest", "1.0.52"))
        assertFalse(AppUpdate.isNewer("", "1.0.52"))
    }

    // --- reading the release ------------------------------------------------

    @Test
    fun theApkIsFoundInTheAnswer() {
        val r = AppUpdate.parseRelease(
            release("ATC_BLE_OEPL_NFC-1.0.52.apk" to 4_200_000L)
        )
        assertTrue("no release was read", r != null)
        assertEquals("v1.0.52", r!!.tag)
        assertEquals("1.0.52", r.versionName)
        assertEquals("ATC_BLE_OEPL_NFC-1.0.52.apk", r.assetName)
        assertEquals(4_200_000L, r.size)
        assertTrue(r.url.endsWith("/ATC_BLE_OEPL_NFC-1.0.52.apk"))
        assertTrue("a normally named APK must count as signed", r.signed)
    }

    @Test
    fun aReleaseWithoutAnApkGivesNothing() {
        // Not an exception and not a wrong answer: the caller shows "the
        // latest release has no APK attached", which is the truth.
        assertNull(AppUpdate.parseRelease(release("notes.txt" to 10L)))
        assertNull(AppUpdate.parseRelease("""{"tag_name":"v1.0.52"}"""))
        assertNull(AppUpdate.parseRelease("""{"assets":[]}"""))
    }

    @Test
    fun anUnsignedBuildIsRecognised() {
        // CI publishes this when the signing secrets are missing. Android
        // cannot install it, so the app has to know before it offers it.
        val r = AppUpdate.parseRelease(
            release("ATC_BLE_OEPL_NFC-1.0.52-unsigned.apk" to 4_200_000L)
        )
        assertTrue(r != null)
        assertFalse("an unsigned APK must not be offered", r!!.signed)
    }

    @Test
    fun theRealAnswerFromTheReleasesPageParses() {
        // releases_latest.json is a recorded answer from
        // api.github.com/repos/<this repo>/releases/latest, kept so the
        // parsing is checked against the real shape of it and not only
        // against what this test thinks it looks like. The same release was
        // fetched and downloaded for real once, which is the part no test can
        // keep doing.
        val json = javaClass.getResourceAsStream("/releases_latest.json")!!
            .bufferedReader().use { it.readText() }
        val r = AppUpdate.parseRelease(json)
        assertTrue("the recorded answer no longer parses", r != null)
        assertEquals("v1.0.5", r!!.tag)
        assertEquals("1.0.5", r.versionName)
        assertEquals("ATC_BLE_OEPL_NFC-1.0.5.apk", r.assetName)
        assertEquals(4_825_502L, r.size)
        assertTrue(r.signed)
        assertTrue(r.url.startsWith("https://github.com/"))
        // And what the app would decide from it.
        assertTrue(AppUpdate.isNewer(r.versionName, "1.0"))
        assertFalse(AppUpdate.isNewer(r.versionName, "1.0.5"))
        assertFalse(AppUpdate.isNewer(r.versionName, "1.0.6"))
    }

    @Test
    fun aSignedAssetWinsOverAnUnsignedOneInTheSameRelease() {
        val r = AppUpdate.parseRelease(
            release(
                "ATC_BLE_OEPL_NFC-1.0.52-unsigned.apk" to 10L,
                "ATC_BLE_OEPL_NFC-1.0.52.apk" to 4_200_000L
            )
        )
        assertEquals("ATC_BLE_OEPL_NFC-1.0.52.apk", r!!.assetName)
        assertTrue(r.signed)
    }
}
