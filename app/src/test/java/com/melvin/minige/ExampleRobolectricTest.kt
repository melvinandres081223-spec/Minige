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
import android.content.Intent
import com.melvin.minige.auth.DriveAuthResult
import com.melvin.minige.auth.GoogleAuthManager
import com.melvin.minige.domain.BossPickupFormatter
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.InactiveSeverity
import com.melvin.minige.domain.InactiveSukiAlertHelper
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.data.remote.FirestoreDelivery
import com.melvin.minige.data.remote.FirestoreExpense
import com.melvin.minige.data.remote.toFirestoreCreateMap
import com.melvin.minige.sync.BackupAppPreferences
import com.melvin.minige.sync.BackupMetadata
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.DriveBackupStatus
import com.melvin.minige.sync.DriveConsentRequiredException
import com.melvin.minige.sync.DriveTokenProvider
import com.melvin.minige.sync.GitHubAppUpdater
import com.melvin.minige.sync.MinigeBackupData
import com.melvin.minige.ui.components.PickupChecklistHelper
import com.melvin.minige.util.AppDiagnosticsRunner
import com.melvin.minige.util.BossReceivablesPrefs
import com.melvin.minige.util.CommissionRateEntry
import com.melvin.minige.util.DiagnosticEnvironmentSnapshot
import com.melvin.minige.util.DiagnosticStatus
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PartialPaymentHelper
import com.melvin.minige.util.PaymentBreakdownHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
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
              "body": "## What's Changed\n* Added visible Minige Backup folder\n* Direct in-app APK installer\n- Zero-lag 120Hz display refresh lock\n• 5-second undo banner\n**Full Changelog**: https://github.com/melvin/minige/compare/v1.0.41...v1.0.42",
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
    fun `boss pickup formatter lists paid deliveries and unpaid pautang delivered today with tagalog day and excludes pickup`() {
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
                id = "u_unpaid_today_partial",
                userId = "u1",
                dateKey = "2026-11-23",
                customerName = "Kuya Joel Split",
                purifiedGallons = 3,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 90,
                amountPaid = 30,
                paymentType = PaymentType.PAUTANG,
                commissionRate = 8,
                commissionEarned = 0,
                settledDateKey = null,
                notes = "",
                calendarEventId = null,
                pendingSync = false,
                createdAtEpochMs = 5L,
                updatedAtEpochMs = 55L,
            ),
            DeliveryRecord(
                id = "u_old_unpaid",
                userId = "u1",
                dateKey = "2026-11-21",
                customerName = "Old Unpaid Customer",
                purifiedGallons = 6,
                alkalineGallons = 0,
                purifiedUnitPrice = 30,
                alkalineUnitPrice = 40,
                totalAmount = 180,
                amountPaid = 0,
                paymentType = PaymentType.PAUTANG,
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
            ),
            UtangPaymentRecord(
                id = "pay_2",
                deliveryId = "u_unpaid_today_partial",
                userId = "u1",
                dateKey = "2026-11-23",
                amount = 30,
                method = UtangMethod.CASH,
                notes = "[Gallons:1 Comm:8]",
                pendingSync = false,
                createdAtEpochMs = 80L,
            ),
        )

        val report = BossPickupFormatter.buildReport(date, entries, payments)
        val formatted = report.message

        assertEquals(21, report.paidGallons) // 10 + 5 + 3 + 2 + 1
        assertEquals(6, report.unpaidPautangGallons) // 4 (Ate Rosa) + 2 remaining (Kuya Joel)
        assertEquals(27, report.grandTotalGallons)

        assertTrue(formatted.contains("🚚 DAILY HAKOT (DELIVERED)"))
        assertTrue(formatted.contains("📅 Petsa: Nov 23, 2026 (Lunes)"))
        assertTrue(formatted.contains("• Store Alpha — 10 gals"))
        assertTrue(formatted.contains("• Store Beta — 5 gals"))
        assertTrue(formatted.contains("• Kuya Ben Settled — 3 gals"))
        assertTrue(formatted.contains("• Mang Tomas Partial — 2 gals"))
        assertTrue(formatted.contains("• Kuya Joel Split — 1 gal"))
        assertTrue(formatted.contains("DELIVERED PAID TOTAL: 21 gals"))

        assertTrue(formatted.contains("📋 PAUTANG DELIVERED TODAY"))
        assertTrue(formatted.contains("• Ate Rosa Unpaid — 4 gals"))
        assertTrue(formatted.contains("• Kuya Joel Split — 2 gals"))
        assertTrue(formatted.contains("PAUTANG TOTAL: 6 gals"))
        assertTrue(formatted.contains("GRAND TOTAL: 27 gals"))

        // Verify PICK_UP and older unpaid Pautang are excluded, and zero peso/money figures appear
        assertFalse(formatted.contains("Nene Pickup"))
        assertFalse(formatted.contains("Old Unpaid Customer"))
        assertFalse(formatted.contains("₱"))
        assertFalse(formatted.contains("320"))
        assertFalse(formatted.contains("Commission", ignoreCase = true))
        assertFalse(formatted.contains("Remit", ignoreCase = true))

        // Verify Tagalog day names and empty state
        val emptySunday = BossPickupFormatter.format(LocalDate.of(2026, 11, 22), listOf(entries.first()))
        assertTrue(emptySunday.contains("Nov 22, 2026 (Linggo)"))
        assertTrue(emptySunday.contains("(Wala pang paid delivery ngayon)"))
        assertTrue(emptySunday.contains("(Walang pautang delivered today)"))
        assertEquals("Martes", BossPickupFormatter.tagalogDayName(java.time.DayOfWeek.TUESDAY))
        assertEquals("Miyerkules", BossPickupFormatter.tagalogDayName(java.time.DayOfWeek.WEDNESDAY))
        assertEquals("Huwebes", BossPickupFormatter.tagalogDayName(java.time.DayOfWeek.THURSDAY))
        assertEquals("Biyernes", BossPickupFormatter.tagalogDayName(java.time.DayOfWeek.FRIDAY))
        assertEquals("Sabado", BossPickupFormatter.tagalogDayName(java.time.DayOfWeek.SATURDAY))
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

    @Test
    fun `drive backup manager clears stale token on HTTP 401 and retries with refreshed token`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val clearedTokens = mutableListOf<String>()
        val fetchedTokens = ArrayDeque(listOf("stale_token_401", "fresh_token_200"))
        val recordedAuthHeaders = mutableListOf<String>()

        DriveBackupManager.saveCachedToken(context, "u_401", "stale_token_401")
        DriveBackupManager.tokenProviderForTest = object : DriveTokenProvider {
            override suspend fun getFreshAccessToken(context: Context): DriveAuthResult {
                val next = fetchedTokens.removeFirst()
                return DriveAuthResult.Authorized(next)
            }

            override suspend fun clearToken(context: Context, token: String) {
                clearedTokens.add(token)
            }
        }
        DriveBackupManager.openConnectionForTest = { urlString ->
            object : HttpURLConnection(URL(urlString)) {
                private val headers = mutableMapOf<String, String>()
                private val outStream = ByteArrayOutputStream()
                private var responseBody = "{}"

                override fun setRequestProperty(key: String?, value: String?) {
                    if (key != null && value != null) {
                        headers[key] = value
                    }
                }

                override fun getOutputStream(): OutputStream = outStream

                override fun getResponseCode(): Int {
                    val authHeader = headers["Authorization"].orEmpty()
                    recordedAuthHeaders.add(authHeader)
                    if (authHeader == "Bearer stale_token_401") {
                        responseCode = HTTP_UNAUTHORIZED
                        responseBody = """{"error":{"code":401,"message":"Invalid Credentials","status":"UNAUTHENTICATED"}}"""
                        return HTTP_UNAUTHORIZED
                    }
                    responseCode = HTTP_OK
                    responseBody = when {
                        urlString.contains("mimeType%3D'application%2Fvnd.google-apps.folder'") -> {
                            """{"files":[]}"""
                        }
                        urlString == "https://www.googleapis.com/drive/v3/files" -> {
                            """{"id":"folder_minige_backup"}"""
                        }
                        urlString.contains("name%3D'minige_auto_backup.json'") -> {
                            """{"files":[]}"""
                        }
                        else -> {
                            """{"id":"file_backup_123"}"""
                        }
                    }
                    return HTTP_OK
                }

                override fun getInputStream(): InputStream =
                    ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))

                override fun getErrorStream(): InputStream =
                    ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))

                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
            }
        }

        try {
            val result = DriveBackupManager.backupToDrive(
                accessToken = null,
                dao = db.minigeDao(),
                userId = "u_401",
                context = context,
            )

            assertTrue(result.isSuccess)
            assertEquals(listOf("stale_token_401"), clearedTokens)
            assertTrue(recordedAuthHeaders.isNotEmpty())
            assertEquals("Bearer stale_token_401", recordedAuthHeaders.first())
            assertTrue(recordedAuthHeaders.drop(1).all { it == "Bearer fresh_token_200" })
            assertEquals("fresh_token_200", DriveBackupManager.getCachedToken(context, "u_401"))
        } finally {
            DriveBackupManager.tokenProviderForTest = null
            DriveBackupManager.openConnectionForTest = null
            DriveBackupManager.clearCachedToken(context, "u_401")
            db.close()
        }
    }

    @Test
    fun `drive backup manager surfaces DriveConsentRequiredException when 401 refresh needs consent`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val clearedTokens = mutableListOf<String>()
        var fetchCallCount = 0
        val recoveryIntent = Intent("com.google.android.gms.auth.RECOVERY")

        DriveBackupManager.saveCachedToken(context, "u_consent", "stale_token_401")
        DriveBackupManager.tokenProviderForTest = object : DriveTokenProvider {
            override suspend fun getFreshAccessToken(context: Context): DriveAuthResult {
                fetchCallCount++
                return if (fetchCallCount == 1) {
                    DriveAuthResult.Authorized("stale_token_401")
                } else {
                    DriveAuthResult.NeedsConsentIntent(recoveryIntent)
                }
            }

            override suspend fun clearToken(context: Context, token: String) {
                clearedTokens.add(token)
            }
        }
        DriveBackupManager.openConnectionForTest = { urlString ->
            object : HttpURLConnection(URL(urlString)) {
                override fun getResponseCode(): Int = HTTP_UNAUTHORIZED
                override fun getErrorStream(): InputStream = ByteArrayInputStream(
                    """{"error":{"code":401,"message":"Invalid Credentials","status":"UNAUTHENTICATED"}}"""
                        .toByteArray(StandardCharsets.UTF_8)
                )
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
            }
        }

        try {
            val result = DriveBackupManager.backupToDrive(
                accessToken = null,
                dao = db.minigeDao(),
                userId = "u_consent",
                context = context,
            )

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(exception is DriveConsentRequiredException)
            assertEquals(recoveryIntent, (exception as DriveConsentRequiredException).recoveryIntent)
            assertEquals(listOf("stale_token_401"), clearedTokens)
            assertNull(DriveBackupManager.getCachedToken(context, "u_consent"))
        } finally {
            DriveBackupManager.tokenProviderForTest = null
            DriveBackupManager.openConnectionForTest = null
            DriveBackupManager.clearCachedToken(context, "u_consent")
            db.close()
        }
    }

    @Test
    fun `repository and firestore models round trip Paid Walk-in and Cash Advance while respecting firestore rules enums`() = runTest {
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
            dao.upsertSettings(
                AppSettingsEntity(
                    id = "default",
                    userId = "rider_walkin",
                    purifiedPrice = 30,
                    alkalinePrice = 40,
                    commissionRate = 8,
                    defaultSukli = 0,
                    pendingSync = false,
                    updatedAtEpochMs = 1L,
                )
            )

            // 1) Add Paid Walk-in WITH commission
            repo.addDelivery(
                userId = "rider_walkin",
                dateKey = "2026-11-24",
                customerName = "Station Suki",
                purifiedGallons = 5,
                alkalineGallons = 0,
                paymentType = PaymentType.PAID_WALK_IN,
                notes = "[Paid Walk-in]",
                hasCommission = true,
            )
            // 2) Add Paid Walk-in WITHOUT commission
            repo.addDelivery(
                userId = "rider_walkin",
                dateKey = "2026-11-24",
                customerName = "Boss Friend",
                purifiedGallons = 3,
                alkalineGallons = 0,
                paymentType = PaymentType.PAID_WALK_IN,
                notes = "[Paid Walk-in] [No Commission]",
                hasCommission = false,
            )
            // 3) Add Cash Advance expense
            repo.addExpense(
                userId = "rider_walkin",
                dateKey = "2026-11-24",
                category = ExpenseCategory.CASH_ADVANCE,
                amount = 250,
                notes = "Afternoon cash advance",
            )

            val savedDeliveries = dao.getAllDeliveriesOnce("rider_walkin")
            assertEquals(2, savedDeliveries.size)
            val withCommEntity = savedDeliveries.first { it.customerName == "Station Suki" }
            val noCommEntity = savedDeliveries.first { it.customerName == "Boss Friend" }

            assertEquals(0, withCommEntity.totalAmount)
            assertEquals(0, withCommEntity.amountPaid)
            assertEquals(40, withCommEntity.commissionEarned) // 5 * 8
            assertEquals(0, noCommEntity.commissionEarned)
            assertTrue(withCommEntity.toDomain().hasPaidWalkInCommission)
            assertFalse(noCommEntity.toDomain().hasPaidWalkInCommission)

            // Verify Firestore rule enum compliance + round-trip
            val fsDeliveryMap = withCommEntity.toFirestoreCreateMap(uid = "rider_walkin")
            assertEquals("BANK_DEPOSIT", fsDeliveryMap["paymentType"])
            assertTrue((fsDeliveryMap["notes"] as String).contains("[Paid Walk-in]"))
            val parsedFsDelivery = FirestoreDelivery(
                id = withCommEntity.id,
                userId = withCommEntity.userId,
                dateKey = withCommEntity.dateKey,
                customerName = withCommEntity.customerName,
                purifiedGallons = withCommEntity.purifiedGallons.toLong(),
                alkalineGallons = withCommEntity.alkalineGallons.toLong(),
                purifiedUnitPrice = withCommEntity.purifiedUnitPrice.toLong(),
                alkalineUnitPrice = withCommEntity.alkalineUnitPrice.toLong(),
                totalAmount = withCommEntity.totalAmount.toLong(),
                amountPaid = withCommEntity.amountPaid.toLong(),
                paymentType = fsDeliveryMap["paymentType"] as String,
                commissionRate = withCommEntity.commissionRate.toLong(),
                commissionEarned = withCommEntity.commissionEarned.toLong(),
                settledDateKey = withCommEntity.settledDateKey,
                notes = fsDeliveryMap["notes"] as String,
                calendarEventId = null,
            ).toEntity()
            assertEquals(PaymentType.PAID_WALK_IN, parsedFsDelivery.toDomain().paymentType)

            val savedExpense = dao.getAllExpensesOnce("rider_walkin").single()
            assertEquals(ExpenseCategory.CASH_ADVANCE, savedExpense.toDomain().category)
            assertEquals("Afternoon cash advance", savedExpense.toDomain().notes)
            val fsExpenseMap = savedExpense.toFirestoreCreateMap(uid = "rider_walkin")
            assertEquals("FOOD", fsExpenseMap["category"])
            assertTrue((fsExpenseMap["notes"] as String).contains("[Cash Advance]"))
            val parsedFsExpense = FirestoreExpense(
                id = savedExpense.id,
                userId = savedExpense.userId,
                dateKey = savedExpense.dateKey,
                category = fsExpenseMap["category"] as String,
                amount = savedExpense.amount.toLong(),
                notes = fsExpenseMap["notes"] as String,
            ).toEntity()
            assertEquals(ExpenseCategory.CASH_ADVANCE, parsedFsExpense.toDomain().category)
            assertEquals("Afternoon cash advance", parsedFsExpense.toDomain().notes)
        } finally {
            db.close()
        }
    }

    @Test
    fun `inactive suki alert flags 3plus day non-pickup customers and clears on new delivery`() = runTest {
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
            dao.upsertSettings(
                AppSettingsEntity(
                    id = "default",
                    userId = "rider_inactive",
                    purifiedPrice = 30,
                    alkalinePrice = 40,
                    commissionRate = 8,
                    defaultSukli = 0,
                    pendingSync = false,
                    updatedAtEpochMs = 1L,
                )
            )

            val referenceDate = LocalDate.of(2026, 11, 25)

            // Active customer: 2 days ago (Nov 23) -> must NOT be flagged
            repo.addDelivery(
                userId = "rider_inactive",
                dateKey = "2026-11-23",
                customerName = "Active Suki",
                purifiedGallons = 3,
                alkalineGallons = 0,
                paymentType = PaymentType.CASH,
                notes = "",
            )

            // 4 days ago (Nov 21) delivery + yesterday PICK_UP -> MUST remain flagged at 4 days (AMBER_WARNING)
            repo.addDelivery(
                userId = "rider_inactive",
                dateKey = "2026-11-21",
                customerName = "Amber Canteen",
                purifiedGallons = 5,
                alkalineGallons = 2,
                paymentType = PaymentType.CASH,
                notes = "[Outlet] [Area: Poblacion]",
                customUnitPrice = 25.0,
            )
            repo.addDelivery(
                userId = "rider_inactive",
                dateKey = "2026-11-24",
                customerName = "Amber Canteen",
                purifiedGallons = 4,
                alkalineGallons = 0,
                paymentType = PaymentType.PICK_UP,
                notes = "",
            )

            // 6 days ago (Nov 19) -> MUST be flagged RED_CRITICAL and sorted first
            repo.addDelivery(
                userId = "rider_inactive",
                dateKey = "2026-11-19",
                customerName = "Critical Reseller",
                purifiedGallons = 10,
                alkalineGallons = 0,
                paymentType = PaymentType.PAUTANG,
                notes = "",
                customUnitPrice = 28.0,
            )

            // Pick-up only customer 7 days ago -> never had a delivery, must NOT be flagged
            repo.addDelivery(
                userId = "rider_inactive",
                dateKey = "2026-11-18",
                customerName = "Pickup Only Walkin",
                purifiedGallons = 2,
                alkalineGallons = 0,
                paymentType = PaymentType.PICK_UP,
                notes = "",
            )

            val customers = dao.observeCustomers("rider_inactive").first().map { it.toDomain() }
            val deliveries = dao.getAllDeliveriesOnce("rider_inactive").map { it.toDomain() }

            val alerts = InactiveSukiAlertHelper.findInactiveCustomers(
                customers = customers,
                deliveries = deliveries,
                referenceDate = referenceDate,
            )

            assertEquals(2, alerts.size)
            assertEquals("Critical Reseller", alerts[0].customerName)
            assertEquals(6, alerts[0].daysSinceLastDelivery)
            assertEquals(InactiveSeverity.RED_CRITICAL, alerts[0].severity)
            assertEquals(10, alerts[0].lastOrderGallons)
            assertEquals(28.0, alerts[0].lastUnitPrice ?: 0.0, 0.01)

            assertEquals("Amber Canteen", alerts[1].customerName)
            assertEquals(4, alerts[1].daysSinceLastDelivery)
            assertEquals(InactiveSeverity.AMBER_WARNING, alerts[1].severity)
            assertEquals(7, alerts[1].lastOrderGallons)
            assertEquals(5, alerts[1].lastPurifiedGallons)
            assertEquals(2, alerts[1].lastAlkalineGallons)
            assertEquals(25.0, alerts[1].lastUnitPrice ?: 0.0, 0.01)

            // Recording a new non-PICK_UP delivery on referenceDate clears Amber Canteen immediately
            repo.addDelivery(
                userId = "rider_inactive",
                dateKey = "2026-11-25",
                customerName = "Amber Canteen",
                purifiedGallons = 5,
                alkalineGallons = 2,
                paymentType = PaymentType.CASH,
                notes = "[Outlet] [Area: Poblacion]",
                customUnitPrice = 25.0,
            )

            val updatedDeliveries = dao.getAllDeliveriesOnce("rider_inactive").map { it.toDomain() }
            val updatedAlerts = InactiveSukiAlertHelper.findInactiveCustomers(
                customers = customers,
                deliveries = updatedDeliveries,
                referenceDate = referenceDate,
            )
            assertEquals(listOf("Critical Reseller"), updatedAlerts.map { it.customerName })
        } finally {
            db.close()
        }
    }

    @Test
    fun `release notes parser strips Full Changelog URLs and bold markdown markers cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawBodyWithChanges = """
            ## What's Changed
            - **Added** 3-day inactive customer alert
            * Clean bulleted **Daily Hakot** format by @melvin
            **Full Changelog**: https://github.com/melvinandres081223-spec/Minige/compare/v1.0.1...v1.0.2
        """.trimIndent()

        val parsed = GitHubAppUpdater.formatReleaseNotes(rawBodyWithChanges)
        assertEquals(
            listOf(
                "Added 3-day inactive customer alert",
                "Clean bulleted Daily Hakot format",
            ),
            parsed,
        )
        assertTrue(parsed.none { it.contains("**") })
        assertTrue(parsed.none { it.contains("Full Changelog", ignoreCase = true) })
        assertTrue(parsed.none { it.contains("https://github.com/") })

        // Body containing ONLY Full Changelog link should yield emptyList() and fallback text in What's New
        val changelogOnly = "**Full Changelog**: https://github.com/melvinandres081223-spec/Minige/compare/v1.0.1...v1.0.2"
        assertTrue(GitHubAppUpdater.formatReleaseNotes(changelogOnly).isEmpty())
        assertEquals(
            "https://github.com/melvinandres081223-spec/Minige/compare/v1.0.1...v1.0.2",
            GitHubAppUpdater.extractChangelogUrl(changelogOnly, "v1.0.2"),
        )

        GitHubAppUpdater.saveCachedReleaseNotes(context, changelogOnly, "v1.0.2")
        assertEquals(
            listOf(GitHubAppUpdater.FALLBACK_RELEASE_NOTE),
            GitHubAppUpdater.getWhatsNewForCurrentVersion(context),
        )
        assertEquals("Release Notes by Vin", GitHubAppUpdater.RELEASE_NOTES_ATTRIBUTION)
    }

    @Test
    fun `app diagnostics runner validates 5 health areas and strictly redacts secrets`() {
        val dateKey = "2026-11-25"
        val validDelivery = DeliveryRecord(
            id = "diag_d1",
            userId = "u1",
            dateKey = dateKey,
            customerName = "Suki Uno",
            purifiedGallons = 4,
            alkalineGallons = 0,
            purifiedUnitPrice = 30,
            alkalineUnitPrice = 40,
            totalAmount = 120,
            amountPaid = 120,
            paymentType = PaymentType.CASH,
            commissionRate = 8,
            commissionEarned = 32,
            settledDateKey = null,
            notes = "",
            calendarEventId = null,
            pendingSync = false,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val validExpense = ExpenseRecord(
            id = "diag_e1",
            userId = "u1",
            dateKey = dateKey,
            category = ExpenseCategory.GAS,
            amount = 50,
            notes = "Gas",
            pendingSync = false,
            createdAtEpochMs = 2L,
            updatedAtEpochMs = 2L,
        )

        val healthyEnv = DiagnosticEnvironmentSnapshot(
            isFirebaseSignedIn = true,
            accountLabelMasked = "me***@gmail.com",
            pendingSyncCount = 0,
            hasDrivePermission = true,
            hasDriveCachedToken = true,
            driveBackupStatus = DriveBackupStatus.ACTIVE,
            lastDriveBackupTimestamp = 1_700_000_000_000L,
            backupMetadataSummary = null,
            hasMicPermission = true,
            hasCalendarPermission = true,
            canRequestPackageInstalls = true,
            versionName = "1.0.99",
            versionCode = 99,
        )

        val passReport = AppDiagnosticsRunner.evaluate(
            env = healthyEnv,
            selectedDateKey = dateKey,
            deliveries = listOf(validDelivery),
            utangPayments = emptyList(),
            expenses = listOf(validExpense),
            dailyLedgers = emptyList(),
            customers = emptyList(),
            defaultSukli = 0,
        )
        assertEquals(5, passReport.items.size)
        assertEquals(DiagnosticStatus.PASS, passReport.overallStatus)
        assertTrue(passReport.items.all { it.status == DiagnosticStatus.PASS })
        assertTrue(passReport.formattedReportText.contains("v1.0.99"))

        // Tampered PICK_UP with non-zero commission and pending sync should flag FAIL + WARN
        val invalidPickup = validDelivery.copy(
            id = "diag_bad_pickup",
            customerName = "Bad Pickup",
            paymentType = PaymentType.PICK_UP,
            totalAmount = 0,
            amountPaid = 0,
            commissionEarned = 16,
        )
        val unhealthyReport = AppDiagnosticsRunner.evaluate(
            env = healthyEnv.copy(pendingSyncCount = 3, hasMicPermission = false),
            selectedDateKey = dateKey,
            deliveries = listOf(validDelivery, invalidPickup),
            utangPayments = emptyList(),
            expenses = listOf(validExpense),
            dailyLedgers = emptyList(),
            customers = emptyList(),
            defaultSukli = 0,
        )
        assertEquals(DiagnosticStatus.FAIL, unhealthyReport.overallStatus)
        assertEquals(
            DiagnosticStatus.WARN,
            unhealthyReport.items.first { it.id == "firebase_auth_sync" }.status,
        )
        assertEquals(
            DiagnosticStatus.FAIL,
            unhealthyReport.items.first { it.id == "ledger_math_integrity" }.status,
        )
        assertEquals(
            DiagnosticStatus.WARN,
            unhealthyReport.items.first { it.id == "device_permissions" }.status,
        )

        // Verify strict redaction of OAuth tokens, passwords, and private vault paths
        val sensitiveSample = "Bearer ya29.A0AfB_secret_token password=mySecret123 path=/data/user/0/com.melvin.minige/files/vault/notes.db com.melvin.minige.vault.VaultLock"
        val redacted = AppDiagnosticsRunner.redactSensitiveInfo(sensitiveSample)
        assertFalse(redacted.contains("ya29.A0AfB_secret_token"))
        assertFalse(redacted.contains("mySecret123"))
        assertFalse(redacted.contains("com.melvin.minige.vault"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `auto backup before update defaults to true and local JSON backup exports cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.minigeDao()
            val userId = "rider_local_backup"

            // Default ON for Automatic backup before app update
            assertTrue(DriveBackupManager.isAutoBackupBeforeUpdateEnabled(context, userId))
            DriveBackupManager.setAutoBackupBeforeUpdateEnabled(context, userId, false)
            assertFalse(DriveBackupManager.isAutoBackupBeforeUpdateEnabled(context, userId))
            DriveBackupManager.setAutoBackupBeforeUpdateEnabled(context, userId, true)
            assertTrue(DriveBackupManager.isAutoBackupBeforeUpdateEnabled(context, userId))

            dao.upsertDelivery(
                DeliveryEntity(
                    id = "loc_del_1",
                    userId = userId,
                    dateKey = "2026-11-25",
                    customerName = "Local Backup Suki",
                    purifiedGallons = 6,
                    alkalineGallons = 0,
                    purifiedUnitPrice = 30,
                    alkalineUnitPrice = 40,
                    totalAmount = 180,
                    amountPaid = 180,
                    paymentType = "CASH",
                    commissionRate = 8,
                    commissionEarned = 48,
                    settledDateKey = "2026-11-25",
                    notes = "",
                    calendarEventId = null,
                    pendingSync = false,
                    isDeleted = false,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 100L,
                )
            )

            val result = DriveBackupManager.exportLocalBackupJson(
                dao = dao,
                userId = userId,
                context = context,
            )
            assertTrue("Expected local backup export to succeed: ${result.exceptionOrNull()}", result.isSuccess)
            val displayPath = result.getOrThrow()
            assertTrue(displayPath.contains(DriveBackupManager.LOCAL_BACKUP_DISPLAY_PATH))
            assertTrue(displayPath.endsWith(DriveBackupManager.BACKUP_FILE_NAME))
            assertTrue(DriveBackupManager.getLastBackupTimestamp(context, userId) > 0L)
            assertEquals(1, DriveBackupManager.getBackupMetadataSummary(context, userId)?.totalDeliveries)

            // Verify serialized payload round-trips cleanly
            val (payloadJson, _) = DriveBackupManager.buildBackupPayload(dao, userId, context)
            val parsed = DriveBackupManager.deserializeBackupPayload(payloadJson)
            assertEquals(1, parsed.deliveries.size)
            assertEquals("Local Backup Suki", parsed.deliveries.first().customerName)
        } finally {
            db.close()
        }
    }

    @Test
    fun `diagnostics marks Calendar On-demand as PASS, persists Drive auth, and surfaces Firestore sync errors`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val userId = "diag_user_persist"
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
        try {
            // 1) Healthy environment with Calendar = On-demand (hasCalendarPermission = false)
            // and silent Drive refresh verified -> Overall Health MUST be PASS
            val healthyOnDemandEnv = DiagnosticEnvironmentSnapshot(
                isFirebaseSignedIn = true,
                accountLabelMasked = "me***@gmail.com",
                pendingSyncCount = 0,
                hasDrivePermission = false,
                hasDriveCachedToken = false,
                driveBackupStatus = DriveBackupStatus.NEEDS_PERMISSION,
                lastDriveBackupTimestamp = 0L,
                backupMetadataSummary = null,
                hasMicPermission = true,
                hasCalendarPermission = false,
                canRequestPackageInstalls = true,
                versionName = "1.0.99",
                versionCode = 99,
                silentDriveRefreshSucceeded = true,
            )
            val passReport = AppDiagnosticsRunner.evaluate(
                env = healthyOnDemandEnv,
                selectedDateKey = "2026-11-25",
                deliveries = emptyList(),
                utangPayments = emptyList(),
                expenses = emptyList(),
                dailyLedgers = emptyList(),
                customers = emptyList(),
                defaultSukli = 0,
            )
            assertEquals(DiagnosticStatus.PASS, passReport.overallStatus)
            assertTrue(passReport.formattedReportText.contains("Overall Health: PASS"))
            assertEquals(
                DiagnosticStatus.PASS,
                passReport.items.first { it.id == "device_permissions" }.status,
            )
            assertEquals(
                DiagnosticStatus.PASS,
                passReport.items.first { it.id == "google_drive_backup" }.status,
            )
            assertTrue(
                passReport.items.first { it.id == "device_permissions" }.detail.contains("Google Calendar: On-demand")
            )

            // 2) Persisted Drive auth timestamp prevents false-positive Needs Drive Permission across restarts
            GoogleAuthManager.clearDriveAuthRecord(context)
            DriveBackupManager.clearLastSuccessfulDriveAuthEpochMs(context, userId)
            assertFalse(GoogleAuthManager.hasDrivePermission(context))

            GoogleAuthManager.recordSuccessfulDriveAuth(context, 1_750_000_000_000L)
            DriveBackupManager.saveLastSuccessfulDriveAuthEpochMs(context, userId, 1_750_000_000_000L)
            assertTrue(GoogleAuthManager.hasDrivePermission(context))
            assertEquals(1_750_000_000_000L, GoogleAuthManager.getLastSuccessfulDriveAuthEpochMs(context))

            // 3) Firestore sync error is surfaced in item #1 when pending items remain, and cleared on success
            MinigeFirestoreDataSource.recordLastSyncError(
                context = context,
                userId = userId,
                message = "PERMISSION_DENIED: Missing or insufficient permissions (users/$userId/deliveries/d1)",
            )
            val syncErrorEnv = healthyOnDemandEnv.copy(
                pendingSyncCount = 2,
                hasDrivePermission = true,
                driveBackupStatus = DriveBackupStatus.ACTIVE,
                lastFirestoreSyncError = MinigeFirestoreDataSource.getLastSyncError(context, userId),
            )
            val warnReport = AppDiagnosticsRunner.evaluate(
                env = syncErrorEnv,
                selectedDateKey = "2026-11-25",
                deliveries = emptyList(),
                utangPayments = emptyList(),
                expenses = emptyList(),
                dailyLedgers = emptyList(),
                customers = emptyList(),
                defaultSukli = 0,
            )
            val syncItem = warnReport.items.first { it.id == "firebase_auth_sync" }
            assertEquals(DiagnosticStatus.WARN, syncItem.status)
            assertTrue(syncItem.summary.contains("PERMISSION_DENIED"))
            assertTrue(syncItem.detail.contains("Last sync error: PERMISSION_DENIED"))

            MinigeFirestoreDataSource.clearLastSyncError(context, userId)
            assertNull(MinigeFirestoreDataSource.getLastSyncError(context, userId))

            // 4) Chunked flush helper processes batches and reports synced count & errors accurately
            val remote = MinigeFirestoreDataSource(FirebaseFirestore.getInstance(), appContext = context)
            val chunkOutcome = remote.flushInChunks(
                items = (1..12).toList(),
                chunkSize = 5,
            ) { item ->
                if (item == 12) {
                    Result.failure(IllegalStateException("Simulated transient error"))
                } else {
                    Result.success(Unit)
                }
            }
            assertEquals(11, chunkOutcome.syncedCount)
            assertEquals("Simulated transient error", chunkOutcome.lastError?.message)
        } finally {
            GoogleAuthManager.clearDriveAuthRecord(context)
            DriveBackupManager.clearLastSuccessfulDriveAuthEpochMs(context, userId)
            MinigeFirestoreDataSource.clearLastSyncError(context, userId)
        }
    }

    @Test
    fun `pautang turned over to refilling credits commission without changing net cash to remit and passes diagnostics`() = runTest {
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
            val remote = MinigeFirestoreDataSource(FirebaseFirestore.getInstance())
            val repo = MinigeRepository(
                dao = dao,
                remote = remote,
                pricingRepository = PricingRepository(dao, remote),
            )
            val userId = "rider_turned_over"
            dao.upsertSettings(
                AppSettingsEntity(
                    id = "default",
                    userId = userId,
                    purifiedPrice = 30,
                    alkalinePrice = 40,
                    commissionRate = 8,
                    defaultSukli = 0,
                    pendingSync = false,
                    updatedAtEpochMs = 1L,
                )
            )

            // Prior-day Pautang A: 5 gals = ₱150 (will be settled & turned over directly to refilling)
            repo.addDelivery(
                userId = userId,
                dateKey = "2026-11-23",
                customerName = "Suki Turned Over",
                purifiedGallons = 5,
                alkalineGallons = 0,
                paymentType = PaymentType.PAUTANG,
                notes = "",
            )
            // Prior-day Pautang B: 4 gals = ₱120 (partial 2 gals = ₱60 turned over, plus normal Pautang C 2 gals = ₱60 held by rider)
            repo.addDelivery(
                userId = userId,
                dateKey = "2026-11-23",
                customerName = "Suki Partial Refilling",
                purifiedGallons = 4,
                alkalineGallons = 0,
                paymentType = PaymentType.PAUTANG,
                notes = "",
            )
            repo.addDelivery(
                userId = userId,
                dateKey = "2026-11-23",
                customerName = "Suki Cash Held",
                purifiedGallons = 2,
                alkalineGallons = 0,
                paymentType = PaymentType.PAUTANG,
                notes = "",
            )

            val allOpen = dao.getAllDeliveriesOnce(userId).map { it.toDomain() }
            val utangA = allOpen.first { it.customerName == "Suki Turned Over" }
            val utangB = allOpen.first { it.customerName == "Suki Partial Refilling" }
            val utangC = allOpen.first { it.customerName == "Suki Cash Held" }

            val settleDateKey = "2026-11-24"
            // Today's cash sale: 3 gals = ₱90 (₱24 commission)
            repo.addDelivery(
                userId = userId,
                dateKey = settleDateKey,
                customerName = "Cash Customer",
                purifiedGallons = 3,
                alkalineGallons = 0,
                paymentType = PaymentType.CASH,
                notes = "",
            )
            // Route expense: ₱20
            repo.addExpense(
                userId = userId,
                dateKey = settleDateKey,
                category = ExpenseCategory.GAS,
                amount = 20,
                notes = "Gas",
            )

            // 1) Settle utangA with [Turned Over to Refilling]
            val taggedFullNotes = PaymentBreakdownHelper.applyTurnedOverToRefillingTag(
                notes = "Gave directly to boss",
                turnedOver = true,
            )
            repo.recordUtangPayment(
                userId = userId,
                deliveryId = utangA.id,
                amount = 150,
                method = UtangMethod.CASH,
                paymentDateKey = settleDateKey,
                notes = taggedFullNotes,
            )

            // 2) Partial pay 2 gals (₱60) on utangB with [Turned Over to Refilling]
            val taggedPartialNotes = PaymentBreakdownHelper.applyTurnedOverToRefillingTag(
                notes = "Partial refilling",
                turnedOver = true,
            )
            repo.recordPartialGallonPayment(
                userId = userId,
                deliveryId = utangB.id,
                paidGallons = 2,
                amount = 60,
                channel = PaidChannel.CASH,
                paymentDateKey = settleDateKey,
                notes = taggedPartialNotes,
            )

            // 3) Normal collect on utangC (₱60 held by rider -> DOES enter Net Cash to Remit)
            repo.recordUtangPayment(
                userId = userId,
                deliveryId = utangC.id,
                amount = 60,
                method = UtangMethod.CASH,
                paymentDateKey = settleDateKey,
                notes = "",
            )

            val deliveries = dao.getAllDeliveriesOnce(userId).map { it.toDomain() }
            val payments = dao.getAllUtangPaymentsOnce(userId).map { it.toDomain() }
            val expenses = dao.getAllExpensesOnce(userId).map { it.toDomain() }

            val summary = MinigeCalculator.calculateDailySummary(
                dateKey = settleDateKey,
                deliveries = deliveries,
                utangPayments = payments,
                expenses = expenses,
                startingSukli = 0,
            )
            val breakdown = PaymentBreakdownHelper.calculateBreakdown(
                dateKey = settleDateKey,
                deliveries = deliveries,
                utangPayments = payments,
                expenses = expenses,
            )

            // Commission: 3 cash + 5 turned-over utangA + 2 partial turned-over utangB + 2 cash-held utangC = 12 gals * ₱8 = ₱96
            assertEquals(12, summary.totalPaidGallons)
            assertEquals(96, summary.dailyCommission)
            assertEquals(96, breakdown.totalPaidCommission)

            // Cash Collected & Net Cash to Remit exclude ₱150 + ₱60 = ₱210 turned over to refilling!
            // Cash Collected = ₱90 (direct cash) + ₱60 (utangC held by rider) = ₱150
            assertEquals(210, summary.pautangTurnedOverToRefilling)
            assertEquals(210, breakdown.pautangTurnedOverToRefilling)
            assertEquals(150, summary.cashCollected)
            assertEquals(150, breakdown.cashCollected)
            // Net Cash to Remit = ₱150 - ₱20 expense = ₱130
            assertEquals(130, summary.netCashRemit)
            assertEquals(130, breakdown.cashToRemit)

            // Verify AppDiagnosticsRunner passes math integrity checks with turned-over payments
            val diagEnv = DiagnosticEnvironmentSnapshot(
                isFirebaseSignedIn = true,
                accountLabelMasked = "me***@gmail.com",
                pendingSyncCount = 0,
                hasDrivePermission = true,
                hasDriveCachedToken = true,
                driveBackupStatus = DriveBackupStatus.ACTIVE,
                lastDriveBackupTimestamp = 1_750_000_000_000L,
                backupMetadataSummary = null,
                hasMicPermission = true,
                hasCalendarPermission = true,
                canRequestPackageInstalls = true,
                versionName = "1.0.99",
                versionCode = 99,
            )
            val report = AppDiagnosticsRunner.evaluate(
                env = diagEnv,
                selectedDateKey = settleDateKey,
                deliveries = deliveries,
                utangPayments = payments,
                expenses = expenses,
                dailyLedgers = emptyList(),
                customers = emptyList(),
                defaultSukli = 0,
            )
            val mathCheck = report.items.first { it.id == "ledger_math_integrity" }
            assertEquals(DiagnosticStatus.PASS, mathCheck.status)
            assertTrue(mathCheck.detail.contains("Non-Turned-Over Pautang Payments"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `boss receivables tracker isolates from net cash to remit and round-trips safely with legacy Drive JSON compatibility`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MinigeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val userId = "rider_boss_receivables"
        try {
            val dao = db.minigeDao()
            // 1) Gallons * Rate helper calculation
            val computed = BossReceivablesPrefs.calculateGallonsAmount(gallons = 50, ratePerGallon = 8.0)
            assertEquals(400, computed)

            // 2) Add, total, mark Nakuha Na, and delete entries
            var list = emptyList<com.melvin.minige.util.BossReceivableEntry>()
            list = BossReceivablesPrefs.addEntry(
                entries = list,
                periodLabel = "Nov 16–21, 2026",
                amount = computed,
                gallons = 50,
                ratePerGallon = 8.0,
                note = "Unpaid commission bago mag-app",
                id = "br_1",
                nowEpochMs = 1000L,
            )
            list = BossReceivablesPrefs.addEntry(
                entries = list,
                periodLabel = "Nov 22, 2026",
                amount = 250,
                gallons = null,
                ratePerGallon = null,
                note = "Sobra sa hakot",
                id = "br_2",
                nowEpochMs = 2000L,
            )
            list = BossReceivablesPrefs.addEntry(
                entries = list,
                periodLabel = "Mistaken entry",
                amount = 999,
                id = "br_3",
                nowEpochMs = 3000L,
            )

            assertEquals(1649, BossReceivablesPrefs.totalUnpaidBalance(list))

            list = BossReceivablesPrefs.deleteEntry(list, "br_3")
            assertEquals(650, BossReceivablesPrefs.totalUnpaidBalance(list))

            list = BossReceivablesPrefs.markPaid(list, "br_1", paidDateKey = "2026-11-25", nowEpochMs = 4000L)
            assertEquals(250, BossReceivablesPrefs.totalUnpaidBalance(list))
            assertEquals(400, BossReceivablesPrefs.totalPaidHistory(list))
            assertTrue(list.first { it.id == "br_1" }.isPaid)
            assertEquals("2026-11-25", list.first { it.id == "br_1" }.paidDateKey)

            // Daily Net Cash to Remit stays strictly unaffected by boss receivables
            val dailySummary = MinigeCalculator.calculateDailySummary(
                dateKey = "2026-11-25",
                deliveries = emptyList(),
                utangPayments = emptyList(),
                expenses = emptyList(),
                startingSukli = 0,
            )
            assertEquals(0, dailySummary.cashCollected)
            assertEquals(0, dailySummary.netCashRemit)

            // 3) Save to SharedPreferences and verify DriveBackupManager JSON round-trip
            BossReceivablesPrefs.saveEntries(context, userId, list)
            val (payloadJson, _) = DriveBackupManager.buildBackupPayload(dao, userId, context, timestamp = 5000L)
            val parsed = DriveBackupManager.deserializeBackupPayload(payloadJson)
            assertEquals(2, parsed.unpaidBossReceivables.size)
            assertEquals(250, BossReceivablesPrefs.totalUnpaidBalance(parsed.unpaidBossReceivables))
            val restoredPaid = parsed.unpaidBossReceivables.first { it.id == "br_1" }
            assertTrue(restoredPaid.isPaid)
            assertEquals(50, restoredPaid.gallons)
            assertEquals("2026-11-25", restoredPaid.paidDateKey)

            // 4) Legacy backup JSON without "unpaidBossReceivables" key must safely default to emptyList()
            val legacyJson = """
                {
                  "version": 2,
                  "app": "Minige",
                  "backupTimestamp": 1700000000000,
                  "userId": "$userId",
                  "deliveries": [],
                  "utangPayments": [],
                  "expenses": [],
                  "dailyLedgers": [],
                  "customers": []
                }
            """.trimIndent()
            val legacyParsed = DriveBackupManager.deserializeBackupPayload(legacyJson)
            assertTrue(legacyParsed.unpaidBossReceivables.isEmpty())
        } finally {
            BossReceivablesPrefs.saveEntries(context, userId, emptyList())
            db.close()
        }
    }

    @Test
    fun `pickup checklist converts pickup entries in place across payment types without duplicates`() = runTest {
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
            val remote = MinigeFirestoreDataSource(FirebaseFirestore.getInstance())
            val repo = MinigeRepository(
                dao = dao,
                remote = remote,
                pricingRepository = PricingRepository(dao, remote),
            )
            val userId = "rider_pickup_checklist"
            val date = LocalDate.of(2026, 11, 25)
            val dateKey = "2026-11-25"
            dao.upsertSettings(
                AppSettingsEntity(
                    id = "default",
                    userId = userId,
                    purifiedPrice = 30,
                    alkalinePrice = 40,
                    commissionRate = 8,
                    defaultSukli = 0,
                    pendingSync = false,
                    updatedAtEpochMs = 1L,
                )
            )

            // Log 5 station Pick-Up entries waiting to be delivered
            repo.addDelivery(userId, dateKey, "Cash Suki", 4, 0, PaymentType.PICK_UP, "[Outlet] [Area: Market]")
            repo.addDelivery(userId, dateKey, "Bank Suki", 3, 0, PaymentType.PICK_UP, "[Residential]")
            repo.addDelivery(userId, dateKey, "Walkin Comm On", 2, 0, PaymentType.PICK_UP, "[Outlet]")
            repo.addDelivery(userId, dateKey, "Walkin Comm Off", 2, 0, PaymentType.PICK_UP, "[Residential]")
            repo.addDelivery(userId, dateKey, "Pautang Suki", 5, 0, PaymentType.PICK_UP, "[Outlet] [Area: Poblacion]")

            val initialAll = dao.getAllDeliveriesOnce(userId).map { it.toDomain() }
            assertEquals(5, initialAll.size)
            assertTrue(initialAll.all { it.paymentType == PaymentType.PICK_UP })

            // Before checking off: ₱0 commission, ₱0 cash collected, 0 Boss Daily Hakot Delivered/Pautang
            val initialSummary = MinigeCalculator.calculateDailySummary(
                dateKey = dateKey,
                deliveries = initialAll,
                utangPayments = emptyList(),
                expenses = emptyList(),
                startingSukli = 0,
            )
            assertEquals(0, initialSummary.totalPaidGallons)
            assertEquals(0, initialSummary.dailyCommission)
            assertEquals(0, initialSummary.cashCollected)
            assertEquals(0, initialSummary.netCashRemit)

            val initialHakot = BossPickupFormatter.buildReport(date = date, deliveries = initialAll)
            assertEquals(0, initialHakot.paidGallons)
            assertEquals(0, initialHakot.unpaidPautangGallons)
            assertEquals(0, initialHakot.grandTotalGallons)

            // Check off & convert each Pick-Up in-place via updateDelivery (no duplicate rows)
            val byName = initialAll.associateBy { it.customerName }

            val cashPickup = byName.getValue("Cash Suki")
            repo.updateDelivery(
                userId = userId,
                deliveryId = cashPickup.id,
                customerName = cashPickup.customerName,
                purifiedGallons = cashPickup.purifiedGallons,
                alkalineGallons = cashPickup.alkalineGallons,
                paymentType = PaymentType.CASH,
                notes = PickupChecklistHelper.buildConvertedDeliveryNotes(cashPickup.notes, PaymentType.CASH, true),
                customUnitPrice = 30.0,
                hasCommission = true,
            )

            val bankPickup = byName.getValue("Bank Suki")
            repo.updateDelivery(
                userId = userId,
                deliveryId = bankPickup.id,
                customerName = bankPickup.customerName,
                purifiedGallons = bankPickup.purifiedGallons,
                alkalineGallons = bankPickup.alkalineGallons,
                paymentType = PaymentType.BANK_DEPOSIT,
                notes = PickupChecklistHelper.buildConvertedDeliveryNotes(bankPickup.notes, PaymentType.BANK_DEPOSIT, true),
                customUnitPrice = 30.0,
                hasCommission = true,
            )

            val walkInOnPickup = byName.getValue("Walkin Comm On")
            repo.updateDelivery(
                userId = userId,
                deliveryId = walkInOnPickup.id,
                customerName = walkInOnPickup.customerName,
                purifiedGallons = walkInOnPickup.purifiedGallons,
                alkalineGallons = walkInOnPickup.alkalineGallons,
                paymentType = PaymentType.PAID_WALK_IN,
                notes = PickupChecklistHelper.buildConvertedDeliveryNotes(walkInOnPickup.notes, PaymentType.PAID_WALK_IN, true),
                customUnitPrice = null,
                hasCommission = true,
            )

            val walkInOffPickup = byName.getValue("Walkin Comm Off")
            repo.updateDelivery(
                userId = userId,
                deliveryId = walkInOffPickup.id,
                customerName = walkInOffPickup.customerName,
                purifiedGallons = walkInOffPickup.purifiedGallons,
                alkalineGallons = walkInOffPickup.alkalineGallons,
                paymentType = PaymentType.PAID_WALK_IN,
                notes = PickupChecklistHelper.buildConvertedDeliveryNotes(walkInOffPickup.notes, PaymentType.PAID_WALK_IN, false),
                customUnitPrice = null,
                hasCommission = false,
            )

            val pautangPickup = byName.getValue("Pautang Suki")
            repo.updateDelivery(
                userId = userId,
                deliveryId = pautangPickup.id,
                customerName = pautangPickup.customerName,
                purifiedGallons = pautangPickup.purifiedGallons,
                alkalineGallons = pautangPickup.alkalineGallons,
                paymentType = PaymentType.PAUTANG,
                notes = PickupChecklistHelper.buildConvertedDeliveryNotes(pautangPickup.notes, PaymentType.PAUTANG, true),
                customUnitPrice = 25.0,
                hasCommission = true,
            )

            val convertedAll = dao.getAllDeliveriesOnce(userId).map { it.toDomain() }
            // Strictly 5 rows (no duplicates), all preserving dateKey and zero remaining PICK_UP
            assertEquals(5, convertedAll.size)
            assertTrue(convertedAll.all { it.dateKey == dateKey })
            assertTrue(convertedAll.none { it.paymentType == PaymentType.PICK_UP })
            assertTrue(convertedAll.first { it.id == cashPickup.id }.notes.contains("[Outlet]"))
            assertTrue(convertedAll.first { it.id == cashPickup.id }.notes.contains("[Area: Market]"))
            assertTrue(convertedAll.first { it.id == bankPickup.id }.notes.contains("[Bank / Online]"))
            assertTrue(convertedAll.first { it.id == walkInOffPickup.id }.notes.contains("[No Commission]"))

            val finalSummary = MinigeCalculator.calculateDailySummary(
                dateKey = dateKey,
                deliveries = convertedAll,
                utangPayments = emptyList(),
                expenses = emptyList(),
                startingSukli = 0,
            )
            // All 4 delivered non-Pautang orders count in totalPaidGallons: 4 (Cash) + 3 (Bank) + 2 (Walk-in ON) + 2 (Walk-in OFF) = 11 gals
            // Commission-eligible gallons: 4 + 3 + 2 = 9 gals * ₱8 = ₱72 (Walk-in OFF earns ₱0)
            assertEquals(11, finalSummary.totalPaidGallons)
            assertEquals(72, finalSummary.dailyCommission)
            // Cash Collected / Net Cash to Remit = 4 * 30 = ₱120 (Bank, Paid Walk-in, and Pautang excluded)
            assertEquals(120, finalSummary.cashCollected)
            assertEquals(120, finalSummary.netCashRemit)
            assertEquals(90, finalSummary.bankDepositTotal)

            val finalBreakdown = PaymentBreakdownHelper.calculateBreakdown(
                dateKey = dateKey,
                deliveries = convertedAll,
                utangPayments = emptyList(),
                expenses = emptyList(),
            )
            assertEquals(4, finalBreakdown.paidWalkInGallons)
            assertEquals(16, finalBreakdown.commissionFromPaidWalkIn)

            // Boss Daily Hakot includes all 4 paid/delivered orders (4+3+2+2 = 11 gals) + 5 Pautang = 16 grand total
            val finalHakot = BossPickupFormatter.buildReport(date = date, deliveries = convertedAll)
            assertEquals(11, finalHakot.paidGallons)
            assertEquals(5, finalHakot.unpaidPautangGallons)
            assertEquals(16, finalHakot.grandTotalGallons)
            assertFalse(finalHakot.message.contains("₱"))

            val report = AppDiagnosticsRunner.evaluate(
                env = DiagnosticEnvironmentSnapshot(
                    isFirebaseSignedIn = true,
                    accountLabelMasked = "me***@gmail.com",
                    pendingSyncCount = 0,
                    hasDrivePermission = true,
                    hasDriveCachedToken = true,
                    driveBackupStatus = DriveBackupStatus.ACTIVE,
                    lastDriveBackupTimestamp = 1_750_000_000_000L,
                    backupMetadataSummary = null,
                    hasMicPermission = true,
                    hasCalendarPermission = true,
                    canRequestPackageInstalls = true,
                    versionName = "1.0.99",
                    versionCode = 99,
                ),
                selectedDateKey = dateKey,
                deliveries = convertedAll,
                utangPayments = emptyList(),
                expenses = emptyList(),
                dailyLedgers = emptyList(),
                customers = emptyList(),
                defaultSukli = 0,
            )
            assertEquals(DiagnosticStatus.PASS, report.overallStatus)
        } finally {
            db.close()
        }
    }
}


