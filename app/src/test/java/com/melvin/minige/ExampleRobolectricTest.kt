package com.melvin.minige

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.melvin.minige.data.MinigeRepository
import com.melvin.minige.data.PricingRepository
import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.MinigeDatabase
import com.melvin.minige.data.local.UtangPaymentEntity
import com.melvin.minige.data.remote.FirestoreSyncHelper
import com.melvin.minige.data.remote.MinigeFirestoreDataSource
import com.melvin.minige.auth.GoogleAuthManager
import com.melvin.minige.domain.BossPickupFormatter
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.sync.BackupAppPreferences
import com.melvin.minige.sync.BackupMetadata
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.GitHubAppUpdater
import com.melvin.minige.sync.MinigeBackupData
import com.melvin.minige.util.CommissionRateEntry
import java.time.LocalDate
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

    @Test
    fun `drive backup folder is visible Minige Backup and scope is drive file`() {
        assertEquals("Minige Backup", DriveBackupManager.BACKUP_FOLDER_NAME)
        assertEquals("https://www.googleapis.com/auth/drive.file", GoogleAuthManager.DRIVE_FILE_SCOPE)
        assertEquals("https://www.googleapis.com/auth/drive.file", GoogleAuthManager.DRIVE_SCOPE.scopeUri)
    }

    @Test
    fun `backup payload serializes and deserializes preferences and metadata accurately`() {
        val original = MinigeBackupData(
            version = 2,
            backupTimestamp = 1732219999000L,
            userId = "driver_1",
            preferences = BackupAppPreferences(
                purifiedPrice = 30,
                alkalinePrice = 40,
                commissionRate = 8,
                defaultSukli = 500,
                dailyQuotaTarget = 75,
                sundayMakeupEnabled = true,
            ),
            metadata = BackupMetadata(
                appVersion = "1.0.12",
                versionCode = 12,
                exportedAt = "Nov 22, 2026",
                totalDeliveries = 5,
                totalPickups = 1,
                totalPautang = 2,
                totalExpenses = 1,
                totalCustomers = 3,
                totalPayments = 2,
            ),
        )

        val json = DriveBackupManager.serializeBackupPayload(original)
        val restored = DriveBackupManager.deserializeBackupPayload(json)

        assertEquals(2, restored.version)
        assertEquals(75, restored.preferences.dailyQuotaTarget)
        assertTrue(restored.preferences.sundayMakeupEnabled)
        assertEquals(500, restored.preferences.defaultSukli)
        assertEquals(8, restored.preferences.commissionRate)
        assertEquals("1.0.12", restored.metadata.appVersion)
        assertEquals(5, restored.metadata.totalDeliveries)
        assertEquals(1, restored.metadata.totalPickups)
        assertEquals(2, restored.metadata.totalPautang)
    }

    @Test
    fun `github release parser parses release notes and formats bullet points`() {
        val releaseJson = """
            {
              "tag_name": "v1.0.42",
              "published_at": "2026-11-22T04:00:00Z",
              "body": "## What's Changed\n* Added visible Minige Backup folder by @melvin in https://github.com/melvin/minige/pull/1\n* Direct in-app APK installer\n- Zero-lag 120Hz display refresh lock\n• 5-second undo banner",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/releases/download/v1.0.42/app-debug.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubAppUpdater.parseLatestReleaseJson(releaseJson)
        assertEquals("v1.0.42", parsed?.tagName)
        assertTrue(parsed?.releaseNotes?.contains("Added visible Minige Backup folder") == true)

        val bullets = GitHubAppUpdater.formatReleaseNotes(parsed?.releaseNotes)
        assertEquals(4, bullets.size)
        assertEquals("Added visible Minige Backup folder", bullets[0])
        assertEquals("Direct in-app APK installer", bullets[1])
        assertEquals("Zero-lag 120Hz display refresh lock", bullets[2])
        assertEquals("5-second undo banner", bullets[3])
    }

    @Test
    fun `boss pickup formatter lists paid deliveries with tagalog day and excludes unpaid pautang and pickup`() {
        val date = LocalDate.of(2026, 11, 23) // Monday -> Lunes
        val entries = listOf(
            DeliveryRecord(
                id = "pk1",
                userId = "u1",
                dateKey = "2026-11-23",
                customerName = "Nene Pickup",
                purifiedGallons = 12,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 0,
                amountPaid = 0,
                paymentType = PaymentType.PICK_UP,
                commissionRate = 8,
                commissionEarned = 0,
                settledDateKey = null,
                notes = "",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
            DeliveryRecord(
                id = "c1",
                userId = "u1",
                dateKey = "2026-11-23",
                customerName = "Store Alpha",
                purifiedGallons = 8,
                alkalineGallons = 2,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 320,
                amountPaid = 320,
                paymentType = PaymentType.CASH,
                commissionRate = 8,
                commissionEarned = 80,
                settledDateKey = "2026-11-23",
                notes = "",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 2L,
                updatedAtEpochMs = 2L,
            ),
            DeliveryRecord(
                id = "b1",
                userId = "u1",
                dateKey = "2026-11-23",
                customerName = "Store Beta",
                purifiedGallons = 5,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 150,
                amountPaid = 150,
                paymentType = PaymentType.BANK_DEPOSIT,
                commissionRate = 8,
                commissionEarned = 40,
                settledDateKey = "2026-11-23",
                notes = "[Bank Transfer]",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 3L,
                updatedAtEpochMs = 3L,
            ),
            DeliveryRecord(
                id = "u_unpaid",
                userId = "u1",
                dateKey = "2026-11-23",
                customerName = "Ate Rosa Unpaid",
                purifiedGallons = 4,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 120,
                amountPaid = 0,
                paymentType = PaymentType.PAUTANG,
                commissionRate = 8,
                commissionEarned = 0,
                settledDateKey = null,
                notes = "",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 4L,
                updatedAtEpochMs = 4L,
            ),
            DeliveryRecord(
                id = "u_settled",
                userId = "u1",
                dateKey = "2026-11-19",
                customerName = "Kuya Ben Settled",
                purifiedGallons = 3,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 90,
                amountPaid = 90,
                paymentType = PaymentType.PAUTANG,
                commissionRate = 8,
                commissionEarned = 24,
                settledDateKey = "2026-11-23",
                notes = "",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 5L,
                updatedAtEpochMs = 50L,
            ),
            DeliveryRecord(
                id = "u_partial",
                userId = "u1",
                dateKey = "2026-11-20",
                customerName = "Mang Tomas Partial",
                purifiedGallons = 5,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 150,
                amountPaid = 60,
                paymentType = PaymentType.PAUTANG,
                commissionRate = 8,
                commissionEarned = 0,
                settledDateKey = null,
                notes = "",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 6L,
                updatedAtEpochMs = 60L,
            ),
        )
        val payments = listOf(
            UtangPaymentRecord(
                id = "pay_1",
                deliveryId = "u_partial",
                userId = "u1",
                dateKey = "2026-11-23",
                amount = 60,
                method = UtangMethod.CASH,
                notes = "[Gallons:2 Comm:16]",
                pendingSync = false,
                createdAtEpochMs = 70L,
            )
        )

        val formatted = BossPickupFormatter.format(date, entries, payments)
        assertTrue(formatted.contains("🚚 DAILY HAKOT (DELIVERED)"))
        assertTrue(formatted.contains("📅 Petsa: Nov 23, 2026 (Lunes)"))
        assertTrue(formatted.contains("Store Alpha"))
        assertTrue(formatted.contains("10 gals"))
        assertTrue(formatted.contains("Store Beta"))
        assertTrue(formatted.contains("5 gals"))
        assertTrue(formatted.contains("Kuya Ben Settled"))
        assertTrue(formatted.contains("3 gals"))
        assertTrue(formatted.contains("Mang Tomas Partial"))
        assertTrue(formatted.contains("2 gals"))
        assertTrue(formatted.contains("TOTAL:"))
        assertTrue(formatted.contains("20 gals"))
        assertFalse(formatted.contains("Nene Pickup"))
        assertFalse(formatted.contains("Ate Rosa Unpaid"))
        assertFalse(formatted.contains("₱"))
        assertFalse(formatted.contains("320"))
        assertFalse(formatted.contains("Commission", ignoreCase = true))
        assertFalse(formatted.contains("Remit", ignoreCase = true))
    }

    @Test
    fun `repository recordBulkUtangPayment settles oldest entries FIFO and updates commissions`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId("minige-f6338")
                    .setApplicationId("1:466130893778:android:c5d0d190ec29d4fb2be60b")
                    .setApiKey("AIzaSyFakeKeyForRobolectricTest")
                    .build()
            )
        }
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.minigeDao()
            val firestore = FirebaseFirestore.getInstance()
            val remote = MinigeFirestoreDataSource(firestore)
            val repo = MinigeRepository(
                dao = dao,
                remote = remote,
                pricingRepository = PricingRepository(dao, remote),
            )
            // Simulate successful cloud sync first
            repo.utangSyncOverrideForTest = { _, _, _ -> Result.success(Unit) to Result.success(Unit) }

            dao.upsertSettings(
                AppSettingsEntity(
                    id = "default",
                    userId = "rider_1",
                    purifiedPrice = 30,
                    alkalinePrice = 40,
                    commissionRate = 8,
                    defaultSukli = 0,
                    pendingSync = false,
                    updatedAtEpochMs = 1L,
                )
            )

            // Two open pautang entries for Aling Nena (oldest Nov 18 ₱90, newer Nov 20 ₱60)
            dao.upsertDeliveries(
                listOf(
                    DeliveryEntity(
                        id = "d_new",
                        userId = "rider_1",
                        dateKey = "2026-11-20",
                        customerName = "Aling Nena",
                        purifiedGallons = 2,
                        alkalineGallons = 0,
                        purifiedUnitPrice = 30,
                        alkalineUnitPrice = 40,
                        totalAmount = 60,
                        amountPaid = 0,
                        paymentType = "PAUTANG",
                        commissionRate = 8,
                        commissionEarned = 0,
                        settledDateKey = null,
                        notes = "",
                        calendarEventId = null,
                        pendingSync = false,
                        isDeleted = false,
                        createdAtEpochMs = 2000L,
                        updatedAtEpochMs = 2000L,
                    ),
                    DeliveryEntity(
                        id = "d_old",
                        userId = "rider_1",
                        dateKey = "2026-11-18",
                        customerName = "aling nena",
                        purifiedGallons = 3,
                        alkalineGallons = 0,
                        purifiedUnitPrice = 30,
                        alkalineUnitPrice = 40,
                        totalAmount = 90,
                        amountPaid = 0,
                        paymentType = "PAUTANG",
                        commissionRate = 8,
                        commissionEarned = 0,
                        settledDateKey = null,
                        notes = "",
                        calendarEventId = null,
                        pendingSync = false,
                        isDeleted = false,
                        createdAtEpochMs = 1000L,
                        updatedAtEpochMs = 1000L,
                    ),
                )
            )

            val res = repo.recordBulkUtangPayment(
                userId = "rider_1",
                customerName = "Aling Nena",
                amount = 120,
                method = UtangMethod.BANK_DEPOSIT,
                paymentDateKey = "2026-11-22",
                notes = "[Bank Transfer] Lump sum bayad",
                overrideCommissionRate = 8.0,
            )

            assertTrue(res.isSuccess)
            val summary = res.getOrThrow()
            assertEquals(120, summary.totalAppliedAmount)
            assertEquals(0, summary.excessAmount)
            assertEquals(30, summary.remainingCustomerBalance)
            assertEquals(1, summary.fullySettledCount)
            assertEquals(1, summary.partiallyPaidCount)
            assertEquals(listOf("2026-11-18"), summary.settledDateKeys)
            assertEquals(listOf("2026-11-20"), summary.partiallyPaidDateKeys)
            assertFalse(summary.pendingCloudSync)

            val updatedOld = dao.getDeliveryById("d_old")!!
            assertEquals(90, updatedOld.amountPaid)
            assertEquals(24, updatedOld.commissionEarned) // 3 * 8 unlocked on settlement
            assertEquals("2026-11-22", updatedOld.settledDateKey)

            val updatedNew = dao.getDeliveryById("d_new")!!
            assertEquals(30, updatedNew.amountPaid)
            assertEquals(0, updatedNew.commissionEarned) // partial peso pay earns 0 until settled
            assertNull(updatedNew.settledDateKey)

            val payments = dao.getAllUtangPaymentsOnce("rider_1")
            assertEquals(2, payments.size)
            assertEquals(120, payments.sumOf { it.amount })

            // Now simulate offline cloud failure while settling remaining ₱30 + ₱20 excess (₱50 tendered)
            repo.utangSyncOverrideForTest = { _, _, _ ->
                val offlineEx = FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
                Result.failure<Unit>(offlineEx) to Result.failure(offlineEx)
            }
            val res2 = repo.recordBulkUtangPayment(
                userId = "rider_1",
                customerName = "Aling Nena",
                amount = 50,
                method = UtangMethod.CASH,
                paymentDateKey = "2026-11-23",
                notes = "Final balance",
                overrideCommissionRate = 8.0,
            )
            assertTrue(res2.isSuccess)
            val summary2 = res2.getOrThrow()
            assertEquals(30, summary2.totalAppliedAmount)
            assertEquals(20, summary2.excessAmount)
            assertEquals(0, summary2.remainingCustomerBalance)
            assertEquals(listOf("2026-11-20"), summary2.settledDateKeys)
            assertTrue(summary2.pendingCloudSync)

            val finalNew = dao.getDeliveryById("d_new")!!
            assertEquals(60, finalNew.amountPaid)
            assertEquals(16, finalNew.commissionEarned) // 2 * 8 unlocked on Nov 23
            assertEquals("2026-11-23", finalNew.settledDateKey)
            assertTrue(finalNew.pendingSync)
        } finally {
            db.close()
        }
    }
}

