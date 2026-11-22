package com.melvin.minige

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.MinigeDatabase
import com.melvin.minige.data.local.UtangPaymentEntity
import com.melvin.minige.data.remote.FirestoreSyncHelper
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.GitHubAppUpdater
import com.melvin.minige.sync.MinigeBackupData
import com.melvin.minige.util.CommissionRateEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            assertEquals(1, dao.observeTotalPendingSyncCount("u1").first())

            // Mark synced and verify pending count clears cleanly
            dao.markCustomerSynced("aling_nena")
            assertEquals(0, dao.observeTotalPendingSyncCount("u1").first())

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

    @Test
    fun `firestore sync helper detects UNAVAILABLE vs PERMISSION_DENIED FirebaseFirestoreException`() {
        val unavailable = FirebaseFirestoreException(
            "Failed to get document because the client is offline.",
            FirebaseFirestoreException.Code.UNAVAILABLE,
        )
        val denied = FirebaseFirestoreException(
            "Missing or insufficient permissions.",
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
        )

        assertTrue(FirestoreSyncHelper.isOfflineFirestoreError(unavailable))
        assertEquals("Saved offline — will auto-sync when online", FirestoreSyncHelper.formatSyncErrorMessage(unavailable))
        assertFalse(FirestoreSyncHelper.isOfflineFirestoreError(denied))
        assertTrue(FirestoreSyncHelper.isPermissionDeniedError(denied))
    }

    @Test
    fun `drive backup manager serializes and deserializes local records without data loss`() {
        val original = MinigeBackupData(
            version = 1,
            backupTimestamp = 1732212900000L,
            userId = "user_42",
            deliveries = listOf(
                DeliveryEntity(
                    id = "deliv_1",
                    userId = "user_42",
                    dateKey = "2026-11-21",
                    customerName = "Aling Nena",
                    purifiedGallons = 5,
                    alkalineGallons = 2,
                    purifiedUnitPrice = 30,
                    alkalineUnitPrice = 40,
                    totalAmount = 230,
                    amountPaid = 230,
                    paymentType = "CASH",
                    commissionRate = 8,
                    commissionEarned = 56,
                    settledDateKey = "2026-11-21",
                    notes = "Delivered on time",
                    calendarEventId = "cal_event_1",
                    pendingSync = false,
                    isDeleted = false,
                    createdAtEpochMs = 1732212000000L,
                    updatedAtEpochMs = 1732212000000L,
                )
            ),
            utangPayments = listOf(
                UtangPaymentEntity(
                    id = "pay_1",
                    deliveryId = "deliv_1",
                    userId = "user_42",
                    dateKey = "2026-11-21",
                    amount = 50,
                    method = "CASH",
                    notes = "Partial payment",
                    pendingSync = false,
                    isDeleted = false,
                    createdAtEpochMs = 1732212500000L,
                )
            ),
            expenses = listOf(
                ExpenseEntity(
                    id = "exp_1",
                    userId = "user_42",
                    dateKey = "2026-11-21",
                    category = "GAS",
                    amount = 120,
                    notes = "Motorcycle fuel",
                    pendingSync = false,
                    isDeleted = false,
                    createdAtEpochMs = 1732212600000L,
                    updatedAtEpochMs = 1732212600000L,
                )
            ),
            dailyLedgers = listOf(
                DailyLedgerEntity(
                    dateKey = "2026-11-21",
                    userId = "user_42",
                    startingSukli = 350,
                    notes = "Starting loose coins",
                    pendingSync = false,
                    updatedAtEpochMs = 1732212700000L,
                )
            ),
            settings = AppSettingsEntity(
                id = "default",
                userId = "user_42",
                purifiedPrice = 30,
                alkalinePrice = 40,
                commissionRate = 8,
                defaultSukli = 300,
                pendingSync = false,
                updatedAtEpochMs = 1732212800000L,
            ),
            customers = listOf(
                CustomerEntity(
                    id = "aling_nena",
                    userId = "user_42",
                    name = "Aling Nena",
                    lastUnitPrice = 28.0,
                    pendingSync = false,
                    updatedAtEpochMs = 1732212850000L,
                )
            ),
            commissionRateHistory = listOf(
                CommissionRateEntry(
                    effectiveDateKey = "2026-11-01",
                    ratePerGallon = 8.0,
                    updatedAtEpochMs = 1732200000000L,
                )
            ),
        )

        val serialized = DriveBackupManager.serializeBackupPayload(original)
        val deserialized = DriveBackupManager.deserializeBackupPayload(serialized)

        assertEquals(original.version, deserialized.version)
        assertEquals(original.backupTimestamp, deserialized.backupTimestamp)
        assertEquals(original.userId, deserialized.userId)
        assertEquals(1, deserialized.deliveries.size)
        assertEquals("Aling Nena", deserialized.deliveries[0].customerName)
        assertEquals(230, deserialized.deliveries[0].totalAmount)
        assertEquals(1, deserialized.utangPayments.size)
        assertEquals(50, deserialized.utangPayments[0].amount)
        assertEquals(1, deserialized.expenses.size)
        assertEquals("GAS", deserialized.expenses[0].category)
        assertEquals(1, deserialized.dailyLedgers.size)
        assertEquals(350, deserialized.dailyLedgers[0].startingSukli)
        assertEquals(30, deserialized.settings?.purifiedPrice)
        assertEquals(1, deserialized.customers.size)
        assertEquals(28.0, deserialized.customers[0].lastUnitPrice ?: 0.0, 0.001)
        assertEquals(1, deserialized.commissionRateHistory.size)
        assertEquals(8.0, deserialized.commissionRateHistory[0].ratePerGallon, 0.001)
        assertEquals(DriveBackupManager.BACKUP_FILE_NAME, "minige_auto_backup.json")
    }

    @Test
    fun `drive backup manager reads all local room entities once for backup`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.minigeDao()
            dao.upsertDelivery(
                DeliveryEntity(
                    id = "d1",
                    userId = "u1",
                    dateKey = "2026-11-21",
                    customerName = "Kuya Jun",
                    purifiedGallons = 10,
                    alkalineGallons = 0,
                    purifiedUnitPrice = 30,
                    alkalineUnitPrice = 40,
                    totalAmount = 300,
                    amountPaid = 300,
                    paymentType = "CASH",
                    commissionRate = 8,
                    commissionEarned = 80,
                    settledDateKey = "2026-11-21",
                    notes = "",
                    calendarEventId = null,
                    pendingSync = false,
                    isDeleted = false,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 100L,
                )
            )
            dao.upsertExpense(
                ExpenseEntity(
                    id = "e1",
                    userId = "u1",
                    dateKey = "2026-11-21",
                    category = "FOOD",
                    amount = 65,
                    notes = "",
                    pendingSync = false,
                    isDeleted = false,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 100L,
                )
            )

            val deliveries = dao.getAllDeliveriesOnce("u1")
            assertEquals(1, deliveries.size)
            assertEquals("Kuya Jun", deliveries[0].customerName)

            val expenses = dao.getAllExpensesOnce("u1")
            assertEquals(1, expenses.size)
            assertEquals(65, expenses[0].amount)

            assertEquals(0, dao.getAllUtangPaymentsOnce("u1").size)
            assertEquals(0, dao.getAllDailyLedgersOnce("u1").size)
            assertEquals(0, dao.getAllCustomersOnce("u1").size)
        } finally {
            db.close()
        }
    }
}
