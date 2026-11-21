package com.melvin.minige

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melvin.minige.update.GitHubAppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Minige", appName)
    }

    @Test
    fun `github release parser extracts published_at, apk download url, and saves timestamp`() {
        val sampleJson = """
            {
              "tag_name": "v1.2.0",
              "published_at": "2026-11-20T14:30:00Z",
              "updated_at": "2026-11-20T14:35:00Z",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.com/checksums.txt"
                },
                {
                  "name": "Minige-v1.2.0.apk",
                  "browser_download_url": "https://github.com/melvinandres081223-spec/Minige/releases/download/v1.2.0/Minige-v1.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubAppUpdater.parseLatestReleaseJson(sampleJson)
        assertEquals("2026-11-20T14:30:00Z", parsed?.publishedAt)
        assertEquals("Minige-v1.2.0.apk", parsed?.apkFileName)
        assertEquals(
            "https://github.com/melvinandres081223-spec/Minige/releases/download/v1.2.0/Minige-v1.2.0.apk",
            parsed?.apkDownloadUrl
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("", GitHubAppUpdater.getLastInstalledPublishedAt(context))
        GitHubAppUpdater.saveInstalledPublishedAt(context, parsed!!.publishedAt)
        assertEquals("2026-11-20T14:30:00Z", GitHubAppUpdater.getLastInstalledPublishedAt(context))
    }

    @Test
    fun `github release parser returns null when no apk asset exists`() {
        val noApkJson = """
            {
              "tag_name": "v1.2.0",
              "published_at": "2026-11-20T14:30:00Z",
              "assets": [
                { "name": "source.zip", "browser_download_url": "https://example.com/source.zip" }
              ]
            }
        """.trimIndent()

        assertNull(GitHubAppUpdater.parseLatestReleaseJson(noApkJson))
    }
}
