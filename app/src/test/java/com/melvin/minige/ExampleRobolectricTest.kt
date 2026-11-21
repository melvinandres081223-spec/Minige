package com.melvin.minige

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.MinigeDatabase
import com.melvin.minige.sync.GitHubAppUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    fun `github release parser falls back to direct apk download url and checks required endpoints and headers`() {
        assertEquals(
            "https://api.github.com/repos/melvinandres081223-spec/Minige/releases/tags/latest",
            GitHubAppUpdater.RELEASES_TAG_LATEST_URL
        )
        assertEquals(
            "https://api.github.com/repos/melvinandres081223-spec/Minige/releases/latest",
            GitHubAppUpdater.RELEASES_LATEST_URL
        )
        assertEquals(
            "https://github.com/melvinandres081223-spec/Minige/releases/download/latest/app-debug.apk",
            GitHubAppUpdater.DIRECT_APK_DOWNLOAD_URL
        )
        assertEquals("Minige-Android-Updater", GitHubAppUpdater.USER_AGENT)
        assertEquals("application/vnd.github+json", GitHubAppUpdater.ACCEPT_HEADER)

        val noApkJson = """
            {
              "tag_name": "latest",
              "published_at": "2026-11-20T14:30:00Z",
              "assets": [
                { "name": "source.zip", "browser_download_url": "https://example.com/source.zip" }
              ]
            }
        """.trimIndent()

        val fallbackParsed = GitHubAppUpdater.parseLatestReleaseJson(noApkJson)
        assertEquals(GitHubAppUpdater.DIRECT_APK_DOWNLOAD_URL, fallbackParsed?.apkDownloadUrl)
        assertEquals("app-debug.apk", fallbackParsed?.apkFileName)

        val missingTimestampJson = """{ "tag_name": "latest", "assets": [] }"""
        assertNull(GitHubAppUpdater.parseLatestReleaseJson(missingTimestampJson))

        val ciVersionedJson = """
            {
              "tag_name": "v1.0.42",
              "published_at": "2026-11-21T10:00:00Z",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/melvinandres081223-spec/Minige/releases/download/v1.0.42/app-debug.apk"
                }
              ]
            }
        """.trimIndent()
        val ciParsed = GitHubAppUpdater.parseLatestReleaseJson(ciVersionedJson)
        assertEquals("v1.0.42", ciParsed?.tagName)
        assertEquals("app-debug.apk", ciParsed?.apkFileName)
        assertEquals(
            "https://github.com/melvinandres081223-spec/Minige/releases/download/v1.0.42/app-debug.apk",
            ciParsed?.apkDownloadUrl,
        )
    }

    @Test
    fun `room customer dao persists and updates per-customer custom unit price`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.minigeDao()
            dao.upsertCustomer(
                CustomerEntity(
                    id = "aling_nena",
                    userId = "u1",
                    name = "Aling Nena",
                    lastUnitPrice = 25.0,
                    pendingSync = true,
                    updatedAtEpochMs = 100L,
                )
            )

            val saved = dao.getCustomerById("aling_nena")
            assertEquals(25.0, saved?.lastUnitPrice ?: 0.0, 0.001)

            // Update price on subsequent delivery
            dao.upsertCustomer(
                CustomerEntity(
                    id = "aling_nena",
                    userId = "u1",
                    name = "Aling Nena",
                    lastUnitPrice = 28.0,
                    pendingSync = false,
                    updatedAtEpochMs = 200L,
                )
            )
            val list = dao.observeCustomers("u1").first()
            assertEquals(1, list.size)
            assertEquals(28.0, list.first().lastUnitPrice ?: 0.0, 0.001)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 1 to 2 creates customers table without errors`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory SQLite
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE deliveries (id TEXT NOT NULL PRIMARY KEY)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val sqliteDb = helper.writableDatabase
        MinigeDatabase.MIGRATION_1_2.migrate(sqliteDb)
        sqliteDb.execSQL(
            "INSERT INTO customers (id, userId, name, lastUnitPrice, pendingSync, updatedAtEpochMs) " +
                "VALUES ('kuya_ben', 'u1', 'Kuya Ben', 25.0, 1, 12345)"
        )
        val cursor = sqliteDb.query("SELECT lastUnitPrice FROM customers WHERE id = 'kuya_ben'")
        try {
            assertEquals(true, cursor.moveToFirst())
            assertEquals(25.0, cursor.getDouble(0), 0.001)
        } finally {
            cursor.close()
            helper.close()
        }
    }
}
