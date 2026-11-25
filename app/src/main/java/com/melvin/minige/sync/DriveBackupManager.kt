package com.melvin.minige.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.melvin.minige.BuildConfig
import com.melvin.minige.auth.DriveAuthResult
import com.melvin.minige.auth.GoogleAuthManager
import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.MinigeDao
import com.melvin.minige.data.local.UtangPaymentEntity
import com.melvin.minige.ui.components.DailyQuotaPrefs
import com.melvin.minige.ui.components.SundayMakeupPrefs
import com.melvin.minige.util.BossReceivableEntry
import com.melvin.minige.util.BossReceivablesPrefs
import com.melvin.minige.util.CommissionRateEntry
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.SmallGallonHelper
import com.melvin.minige.util.SmallGallonPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DriveBackupStatus(val label: String) {
    ACTIVE("Active"),
    BACKING_UP("Backing up..."),
    NEEDS_PERMISSION("Needs Drive Permission"),
    ERROR("Error"),
}

data class BackupAppPreferences(
    val purifiedPrice: Int = 30,
    val alkalinePrice: Int = 40,
    val commissionRate: Int = 8,
    val defaultSukli: Int = 0,
    val dailyQuotaTarget: Int = 50,
    val sundayMakeupEnabled: Boolean = false,
    val smallGallonPrice: Double = SmallGallonHelper.DEFAULT_PRICE,
    val smallGallonCommissionRate: Double = SmallGallonHelper.DEFAULT_COMMISSION_RATE,
)

data class BackupMetadata(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val exportedAt: String = "",
    val totalDeliveries: Int = 0,
    val totalPickups: Int = 0,
    val totalPautang: Int = 0,
    val totalExpenses: Int = 0,
    val totalCustomers: Int = 0,
    val totalPayments: Int = 0,
)

data class BackupMetadataSummary(
    val appVersion: String,
    val totalDeliveries: Int,
    val totalPayments: Int,
    val totalExpenses: Int,
    val totalCustomers: Int,
    val exportedAt: String,
)

data class DriveBackupPreview(
    val backupTimestamp: Long,
    val userId: String,
    val metadata: BackupMetadata,
    val preferences: BackupAppPreferences,
    val deliveryCount: Int,
    val paymentCount: Int,
    val expenseCount: Int,
    val customerCount: Int,
)

data class DriveRestoreSummary(
    val deliveryCount: Int,
    val paymentCount: Int,
    val expenseCount: Int,
    val customerCount: Int,
    val backupTimestamp: Long,
    val restoredQuota: Int? = null,
    val restoredSundayMakeup: Boolean? = null,
    val restoredSmallGallonPrice: Double? = null,
    val restoredSmallGallonCommissionRate: Double? = null,
)

data class MinigeBackupData(
    val version: Int = 2,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val userId: String = "",
    val deliveries: List<DeliveryEntity> = emptyList(),
    val utangPayments: List<UtangPaymentEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val dailyLedgers: List<DailyLedgerEntity> = emptyList(),
    val settings: AppSettingsEntity? = null,
    val customers: List<CustomerEntity> = emptyList(),
    val commissionRateHistory: List<CommissionRateEntry> = emptyList(),
    val preferences: BackupAppPreferences = BackupAppPreferences(),
    val metadata: BackupMetadata = BackupMetadata(),
    val unpaidBossReceivables: List<BossReceivableEntry> = emptyList(),
)

class DriveUnauthorizedException(
    val failedToken: String,
    message: String = "Google Drive authorization expired or invalid (HTTP 401).",
) : RuntimeException(message)

class DriveConsentRequiredException(
    val intentSender: IntentSender? = null,
    val recoveryIntent: Intent? = null,
    message: String = "Google Drive re-authentication required.",
) : RuntimeException(message)

interface DriveTokenProvider {
    suspend fun getFreshAccessToken(context: Context): DriveAuthResult
    suspend fun clearToken(context: Context, token: String)
}

private object DefaultDriveTokenProvider : DriveTokenProvider {
    override suspend fun getFreshAccessToken(context: Context): DriveAuthResult =
        GoogleAuthManager.fetchFreshDriveAccessToken(context)

    override suspend fun clearToken(context: Context, token: String) {
        GoogleAuthManager.invalidateDriveAccessToken(context, token)
    }
}

object DriveBackupManager {

    private const val TAG = "DriveBackupManager"
    const val BACKUP_FOLDER_NAME = "Minige Backup"
    const val BACKUP_FILE_NAME = "minige_auto_backup.json"
    const val LOCAL_BACKUP_DISPLAY_PATH = "/Download/Minige/Backups"
    const val LOCAL_BACKUP_RELATIVE_PATH = "Minige/Backups"
    const val GITHUB_SOURCE_CODE_URL = "https://github.com/melvinandres081223-spec/Minige"
    private const val PREFS_NAME = "minige_drive_backup_prefs"
    private const val KEY_LAST_BACKUP = "last_drive_backup_"
    private const val KEY_CACHED_TOKEN = "cached_drive_token_"
    private const val KEY_LAST_BACKUP_META = "last_backup_meta_summary_"
    private const val KEY_AUTO_BACKUP_ON_UPDATE = "auto_backup_on_update_"
    private const val KEY_LAST_LOCAL_BACKUP_PATH = "last_local_backup_path_"
    private const val KEY_LAST_DRIVE_AUTH_EPOCH = "last_successful_drive_auth_epoch_ms_"
    private const val BOUNDARY = "MINIGE_DRIVE_BACKUP_BOUNDARY_7788"

    private const val DRIVE_FILES_ROOT_URL = "https://www.googleapis.com/drive/v3/files"
    private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

    @VisibleForTesting
    internal var tokenProviderForTest: DriveTokenProvider? = null

    @VisibleForTesting
    internal var openConnectionForTest: ((String) -> HttpURLConnection)? = null

    private fun openDriveConnection(urlString: String): HttpURLConnection =
        openConnectionForTest?.invoke(urlString) ?: (URL(urlString).openConnection() as HttpURLConnection)

    private suspend fun resolveFreshTokenOrThrow(
        context: Context,
        provider: DriveTokenProvider,
    ): String {
        return when (val auth = provider.getFreshAccessToken(context)) {
            is DriveAuthResult.Authorized -> auth.accessToken.also {
                if (it.isBlank()) throw IllegalStateException("Received blank Google Drive access token.")
            }
            is DriveAuthResult.NeedsConsent -> throw DriveConsentRequiredException(
                intentSender = auth.intentSender,
            )
            is DriveAuthResult.NeedsConsentIntent -> throw DriveConsentRequiredException(
                recoveryIntent = auth.intent,
            )
            is DriveAuthResult.Failure -> throw IllegalStateException(auth.message)
        }
    }

    private suspend fun <T> executeWithTokenRetry(
        context: Context,
        userId: String,
        providedToken: String?,
        block: (activeToken: String) -> T,
    ): Pair<T, String> {
        val provider = tokenProviderForTest ?: DefaultDriveTokenProvider
        // Always retrieve a fresh token right before executing Drive REST calls unless
        // a non-blank token was just supplied directly from an interactive consent flow.
        val initialToken = if (!providedToken.isNullOrBlank()) {
            providedToken.trim()
        } else {
            resolveFreshTokenOrThrow(context, provider)
        }

        return try {
            block(initialToken) to initialToken
        } catch (e: DriveUnauthorizedException) {
            Log.w(TAG, "Drive API returned HTTP 401; clearing stale token and retrying once", e)
            clearCachedToken(context, userId)
            provider.clearToken(context, e.failedToken)
            val refreshedToken = resolveFreshTokenOrThrow(context, provider)
            val retried = block(refreshedToken)
            retried to refreshedToken
        }
    }

    suspend fun buildBackupPayload(
        dao: MinigeDao,
        userId: String,
        context: Context,
        timestamp: Long = System.currentTimeMillis(),
    ): Pair<String, BackupMetadata> {
        val deliveries = dao.getAllDeliveriesOnce(userId)
        val utangPayments = dao.getAllUtangPaymentsOnce(userId)
        val expenses = dao.getAllExpensesOnce(userId)
        val dailyLedgers = dao.getAllDailyLedgersOnce(userId)
        val settings = dao.getSettingsOnce()
        val customers = dao.getAllCustomersOnce(userId)
        val rateHistory = CommissionRateHistory.loadHistory(context, userId)
        val bossReceivables = BossReceivablesPrefs.loadEntries(context, userId)

        val quota = DailyQuotaPrefs.loadQuotaTarget(context, userId)
        val sundayMakeup = SundayMakeupPrefs.isEnabled(context, userId)
        val smallPrice = SmallGallonPrefs.loadPrice(context, userId)
        val smallCommRate = SmallGallonPrefs.loadCommissionRate(context, userId)
        val effectiveRate = settings?.commissionRate ?: 8
        val purified = settings?.purifiedPrice ?: 30
        val alkaline = settings?.alkalinePrice ?: 40
        val sukli = settings?.defaultSukli ?: 0

        val appPreferences = BackupAppPreferences(
            purifiedPrice = purified,
            alkalinePrice = alkaline,
            commissionRate = effectiveRate,
            defaultSukli = sukli,
            dailyQuotaTarget = quota,
            sundayMakeupEnabled = sundayMakeup,
            smallGallonPrice = smallPrice,
            smallGallonCommissionRate = smallCommRate,
        )

        val totalPickups = deliveries.count { it.paymentType == "PICK_UP" }
        val totalPautang = deliveries.count { it.paymentType == "PAUTANG" }

        val backupMetadata = BackupMetadata(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            exportedAt = formatBackupTimestamp(timestamp),
            totalDeliveries = deliveries.size,
            totalPickups = totalPickups,
            totalPautang = totalPautang,
            totalExpenses = expenses.size,
            totalCustomers = customers.size,
            totalPayments = utangPayments.size,
        )

        val backupData = MinigeBackupData(
            version = 2,
            backupTimestamp = timestamp,
            userId = userId,
            deliveries = deliveries,
            utangPayments = utangPayments,
            expenses = expenses,
            dailyLedgers = dailyLedgers,
            settings = settings,
            customers = customers,
            commissionRateHistory = rateHistory,
            preferences = appPreferences,
            metadata = backupMetadata,
            unpaidBossReceivables = bossReceivables,
        )

        return serializeBackupPayload(backupData) to backupMetadata
    }

    /**
     * Exports [BACKUP_FILE_NAME] to the local Downloads folder (`Download/Minige/Backups`)
     * using scoped [MediaStore.Downloads] on API 29+ (zero storage permissions required) with a
     * safe app-external/internal fallback on API 26–28 or test environments.
     */
    suspend fun exportLocalBackupJson(
        dao: MinigeDao,
        userId: String,
        context: Context,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val (jsonPayload, backupMetadata) = buildBackupPayload(dao, userId, context, now)
            val appContext = context.applicationContext
            val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)

            var writtenViaMediaStore = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LOCAL_BACKUP_RELATIVE_PATH"
                val resolver = appContext.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val existingUri = runCatching {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf(BACKUP_FILE_NAME, "$relativePath%"),
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(0)
                            ContentUris.withAppendedId(collection, id)
                        } else {
                            null
                        }
                    }
                }.getOrNull()

                val targetUri = existingUri ?: runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    resolver.insert(collection, values)
                }.getOrNull()

                if (targetUri != null) {
                    val wrote = runCatching {
                        resolver.openOutputStream(targetUri, "wt")?.use { out ->
                            out.write(bytes)
                            out.flush()
                            true
                        } ?: false
                    }.getOrDefault(false)
                    writtenViaMediaStore = wrote
                }
            }

            if (!writtenViaMediaStore) {
                val baseDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: appContext.filesDir
                val backupDir = java.io.File(baseDir, LOCAL_BACKUP_RELATIVE_PATH)
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }
                val outFile = java.io.File(backupDir, BACKUP_FILE_NAME)
                outFile.writeBytes(bytes)
            }

            val displayLocation = "$LOCAL_BACKUP_DISPLAY_PATH/$BACKUP_FILE_NAME"
            saveLastBackupTimestamp(context, userId, now)
            saveBackupMetadataSummary(context, userId, backupMetadata)
            saveLastLocalBackupPath(context, userId, displayLocation)
            Result.success(displayLocation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Local JSON backup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun backupToDrive(
        accessToken: String? = null,
        dao: MinigeDao,
        userId: String,
        context: Context,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val (jsonPayload, backupMetadata) = buildBackupPayload(dao, userId, context, now)
            val (_, activeToken) = executeWithTokenRetry(
                context = context,
                userId = userId,
                providedToken = accessToken,
            ) { token ->
                val folderId = findOrCreateBackupFolder(token)
                val existingFileId = findExistingBackupFileId(token, folderId)
                if (existingFileId != null) {
                    overwriteDriveFile(token, existingFileId, folderId, jsonPayload)
                } else {
                    createNewDriveFile(token, folderId, jsonPayload)
                }
            }

            saveLastBackupTimestamp(context, userId, now)
            saveCachedToken(context, userId, activeToken)
            saveBackupMetadataSummary(context, userId, backupMetadata)
            saveLastSuccessfulDriveAuthEpochMs(context, userId, now)
            GoogleAuthManager.recordSuccessfulDriveAuth(context, now)
            Log.i(TAG, "Successfully backed up Minige to Google Drive folder '$BACKUP_FOLDER_NAME' at $now")
            Result.success(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveConsentRequiredException) {
            clearLastSuccessfulDriveAuthEpochMs(context, userId)
            GoogleAuthManager.clearDriveAuthRecord(context)
            Log.w(TAG, "Google Drive consent required during backup: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Google Drive backup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun fetchBackupPreview(
        context: Context,
        userId: String,
        accessToken: String? = null,
    ): Result<DriveBackupPreview> = withContext(Dispatchers.IO) {
        try {
            val (jsonPayload, activeToken) = executeWithTokenRetry(
                context = context,
                userId = userId,
                providedToken = accessToken,
            ) { token ->
                val folderId = findBackupFolder(token)
                val fileId = findExistingBackupFileId(token, folderId)
                    ?: throw NoSuchElementException(
                        "Walang nahanap na backup file ($BACKUP_FILE_NAME) sa '$BACKUP_FOLDER_NAME' sa Google Drive."
                    )
                downloadDriveFile(token, fileId)
            }

            val authNow = System.currentTimeMillis()
            saveCachedToken(context, userId, activeToken)
            saveLastSuccessfulDriveAuthEpochMs(context, userId, authNow)
            GoogleAuthManager.recordSuccessfulDriveAuth(context, authNow)
            val backupData = deserializeBackupPayload(jsonPayload)
            Result.success(
                DriveBackupPreview(
                    backupTimestamp = backupData.backupTimestamp,
                    userId = backupData.userId,
                    metadata = backupData.metadata,
                    preferences = backupData.preferences,
                    deliveryCount = backupData.deliveries.size,
                    paymentCount = backupData.utangPayments.size,
                    expenseCount = backupData.expenses.size,
                    customerCount = backupData.customers.size,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveConsentRequiredException) {
            clearLastSuccessfulDriveAuthEpochMs(context, userId)
            GoogleAuthManager.clearDriveAuthRecord(context)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFromDrive(
        accessToken: String? = null,
        dao: MinigeDao,
        userId: String,
        context: Context,
    ): Result<DriveRestoreSummary> = withContext(Dispatchers.IO) {
        try {
            val (jsonPayload, activeToken) = executeWithTokenRetry(
                context = context,
                userId = userId,
                providedToken = accessToken,
            ) { token ->
                val folderId = findBackupFolder(token)
                val fileId = findExistingBackupFileId(token, folderId)
                    ?: throw NoSuchElementException(
                        "Walang nahanap na backup file ($BACKUP_FILE_NAME) sa '$BACKUP_FOLDER_NAME' sa Google Drive."
                    )
                downloadDriveFile(token, fileId)
            }
            val backupData = deserializeBackupPayload(jsonPayload)

            // Upsert records into Room by ID to prevent duplicate deliveries, payments, or pautangs
            val restoredDeliveries = backupData.deliveries.map {
                it.copy(userId = userId, pendingSync = true)
            }
            if (restoredDeliveries.isNotEmpty()) {
                dao.upsertDeliveries(restoredDeliveries)
            }

            val restoredPayments = backupData.utangPayments.map {
                it.copy(userId = userId, pendingSync = true)
            }
            if (restoredPayments.isNotEmpty()) {
                dao.upsertUtangPayments(restoredPayments)
            }

            val restoredExpenses = backupData.expenses.map {
                it.copy(userId = userId, pendingSync = true)
            }
            if (restoredExpenses.isNotEmpty()) {
                dao.upsertExpenses(restoredExpenses)
            }

            val restoredLedgers = backupData.dailyLedgers.map {
                it.copy(userId = userId, pendingSync = true)
            }
            if (restoredLedgers.isNotEmpty()) {
                dao.upsertDailyLedgers(restoredLedgers)
            }

            val targetSettings = backupData.settings ?: AppSettingsEntity(
                id = "default",
                userId = userId,
                purifiedPrice = backupData.preferences.purifiedPrice,
                alkalinePrice = backupData.preferences.alkalinePrice,
                commissionRate = backupData.preferences.commissionRate,
                defaultSukli = backupData.preferences.defaultSukli,
                pendingSync = true,
                updatedAtEpochMs = backupData.backupTimestamp,
            )
            dao.upsertSettings(targetSettings.copy(userId = userId, pendingSync = true))

            val restoredCustomers = backupData.customers.map {
                it.copy(userId = userId, pendingSync = true)
            }
            if (restoredCustomers.isNotEmpty()) {
                dao.upsertCustomers(restoredCustomers)
            }

            if (backupData.commissionRateHistory.isNotEmpty()) {
                CommissionRateHistory.saveHistory(context, userId, backupData.commissionRateHistory)
            }
            if (backupData.unpaidBossReceivables.isNotEmpty()) {
                val mergedReceivables = BossReceivablesPrefs.mergeRestoredEntries(
                    existing = BossReceivablesPrefs.loadEntries(context, userId),
                    restored = backupData.unpaidBossReceivables,
                )
                BossReceivablesPrefs.saveEntries(context, userId, mergedReceivables)
            }

            // Restore App Preferences
            backupData.preferences.let { p ->
                DailyQuotaPrefs.saveQuotaTarget(context, userId, p.dailyQuotaTarget)
                SundayMakeupPrefs.setEnabled(context, userId, p.sundayMakeupEnabled)
                SmallGallonPrefs.savePrice(context, userId, p.smallGallonPrice)
                SmallGallonPrefs.saveCommissionRate(context, userId, p.smallGallonCommissionRate)
            }

            val authNow = System.currentTimeMillis()
            saveLastBackupTimestamp(context, userId, backupData.backupTimestamp)
            saveCachedToken(context, userId, activeToken)
            saveBackupMetadataSummary(context, userId, backupData.metadata)
            saveLastSuccessfulDriveAuthEpochMs(context, userId, authNow)
            GoogleAuthManager.recordSuccessfulDriveAuth(context, authNow)

            val summary = DriveRestoreSummary(
                deliveryCount = restoredDeliveries.size,
                paymentCount = restoredPayments.size,
                expenseCount = restoredExpenses.size,
                customerCount = restoredCustomers.size,
                backupTimestamp = backupData.backupTimestamp,
                restoredQuota = backupData.preferences.dailyQuotaTarget,
                restoredSundayMakeup = backupData.preferences.sundayMakeupEnabled,
                restoredSmallGallonPrice = backupData.preferences.smallGallonPrice,
                restoredSmallGallonCommissionRate = backupData.preferences.smallGallonCommissionRate,
            )
            Log.i(TAG, "Successfully restored Minige from Google Drive folder '$BACKUP_FOLDER_NAME': $summary")
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveConsentRequiredException) {
            clearLastSuccessfulDriveAuthEpochMs(context, userId)
            GoogleAuthManager.clearDriveAuthRecord(context)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Restore from Google Drive failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun readErrorText(conn: HttpURLConnection): String? =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()

    private fun findOrCreateBackupFolder(accessToken: String): String {
        val existingId = findBackupFolder(accessToken)
        if (!existingId.isNullOrBlank()) {
            return existingId
        }
        return createBackupFolder(accessToken)
    }

    private fun findBackupFolder(accessToken: String): String? {
        val query = "name%3D'Minige+Backup'+and+mimeType%3D'application%2Fvnd.google-apps.folder'+and+'root'+in+parents+and+trashed%3Dfalse"
        val conn = openDriveConnection("$DRIVE_FILES_ROOT_URL?q=$query&fields=files(id,name)").apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorText = readErrorText(conn)
                throw DriveUnauthorizedException(
                    failedToken = accessToken,
                    message = "Search backup folder unauthorized (HTTP 401): $errorText",
                )
            }
            if (code in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val files = JSONObject(responseText).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                } else null
            } else {
                Log.w(TAG, "Search backup folder returned HTTP $code")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun createBackupFolder(accessToken: String): String {
        val conn = openDriveConnection(DRIVE_FILES_ROOT_URL).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        val metadata = JSONObject().apply {
            put("name", BACKUP_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", JSONArray(listOf("root")))
        }.toString()

        return try {
            conn.outputStream.use { os ->
                os.write(metadata.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorText = readErrorText(conn)
                throw DriveUnauthorizedException(
                    failedToken = accessToken,
                    message = "Failed to create '$BACKUP_FOLDER_NAME' folder (HTTP 401): $errorText",
                )
            }
            if (code !in 200..299) {
                val errorText = readErrorText(conn)
                throw IllegalStateException("Failed to create '$BACKUP_FOLDER_NAME' folder (HTTP $code): $errorText")
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val id = JSONObject(responseText).optString("id")
            if (id.isBlank()) {
                throw IllegalStateException("Created '$BACKUP_FOLDER_NAME' folder but returned no ID.")
            }
            id
        } finally {
            conn.disconnect()
        }
    }

    private fun findExistingBackupFileId(accessToken: String, folderId: String?): String? {
        val folderQuery = if (!folderId.isNullOrBlank()) {
            "name%3D'minige_auto_backup.json'+and+'$folderId'+in+parents+and+trashed%3Dfalse"
        } else {
            "name%3D'minige_auto_backup.json'+and+trashed%3Dfalse"
        }
        val conn = openDriveConnection("$DRIVE_FILES_ROOT_URL?q=$folderQuery&fields=files(id,name,modifiedTime,size)").apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorText = readErrorText(conn)
                throw DriveUnauthorizedException(
                    failedToken = accessToken,
                    message = "Search backup file unauthorized (HTTP 401): $errorText",
                )
            }
            if (code in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val files = JSONObject(responseText).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                } else if (!folderId.isNullOrBlank()) {
                    findExistingBackupFileId(accessToken, folderId = null)
                } else null
            } else {
                Log.w(TAG, "Search backup file returned HTTP $code")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun createNewDriveFile(accessToken: String, folderId: String, jsonPayload: String): String {
        val conn = openDriveConnection(DRIVE_UPLOAD_URL).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
        }

        val metadataJson = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
            put("parents", JSONArray(listOf(folderId)))
        }.toString()

        val body = buildMultipartBody(metadataJson, jsonPayload)

        return try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorText = readErrorText(conn)
                throw DriveUnauthorizedException(
                    failedToken = accessToken,
                    message = "Drive file create unauthorized (HTTP 401): $errorText",
                )
            }
            if (code !in 200..299) {
                val errorText = readErrorText(conn)
                throw IllegalStateException("Drive file create failed (HTTP $code): $errorText")
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            responseJson.optString("id")
        } finally {
            conn.disconnect()
        }
    }

    private fun overwriteDriveFile(accessToken: String, fileId: String, folderId: String, jsonPayload: String): String {
        val conn = openDriveConnection("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart").apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("X-HTTP-Method-Override", "PATCH")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
        }

        val metadataJson = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
        }.toString()

        val body = buildMultipartBody(metadataJson, jsonPayload)

        return try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorText = readErrorText(conn)
                throw DriveUnauthorizedException(
                    failedToken = accessToken,
                    message = "Drive file overwrite unauthorized (HTTP 401): $errorText",
                )
            }
            if (code !in 200..299) {
                val errorText = readErrorText(conn)
                if (code == 404) {
                    return createNewDriveFile(accessToken, folderId, jsonPayload)
                }
                throw IllegalStateException("Drive file overwrite failed (HTTP $code): $errorText")
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseText)
            responseJson.optString("id").ifBlank { fileId }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadDriveFile(accessToken: String, fileId: String): String {
        val conn = openDriveConnection("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorText = readErrorText(conn)
                throw DriveUnauthorizedException(
                    failedToken = accessToken,
                    message = "Drive file download unauthorized (HTTP 401): $errorText",
                )
            }
            if (code !in 200..299) {
                val errorText = readErrorText(conn)
                throw IllegalStateException("Drive file download failed (HTTP $code): $errorText")
            }

            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMultipartBody(metadataJson: String, contentJson: String): String {
        return buildString {
            append("--").append(BOUNDARY).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson).append("\r\n")
            append("--").append(BOUNDARY).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(contentJson).append("\r\n")
            append("--").append(BOUNDARY).append("--\r\n")
        }
    }

    fun serializeBackupPayload(data: MinigeBackupData): String {
        val root = JSONObject().apply {
            put("version", data.version)
            put("app", "Minige")
            put("backupTimestamp", data.backupTimestamp)
            put("userId", data.userId)

            val delivArr = JSONArray()
            data.deliveries.forEach { d ->
                delivArr.put(JSONObject().apply {
                    put("id", d.id)
                    put("userId", d.userId)
                    put("dateKey", d.dateKey)
                    put("customerName", d.customerName)
                    put("purifiedGallons", d.purifiedGallons)
                    put("alkalineGallons", d.alkalineGallons)
                    put("purifiedUnitPrice", d.purifiedUnitPrice)
                    put("alkalineUnitPrice", d.alkalineUnitPrice)
                    put("totalAmount", d.totalAmount)
                    put("amountPaid", d.amountPaid)
                    put("paymentType", d.paymentType)
                    put("commissionRate", d.commissionRate)
                    put("commissionEarned", d.commissionEarned)
                    d.settledDateKey?.let { put("settledDateKey", it) }
                    put("notes", d.notes)
                    d.calendarEventId?.let { put("calendarEventId", it) }
                    put("createdAtEpochMs", d.createdAtEpochMs)
                    put("updatedAtEpochMs", d.updatedAtEpochMs)
                })
            }
            put("deliveries", delivArr)

            val payArr = JSONArray()
            data.utangPayments.forEach { p ->
                payArr.put(JSONObject().apply {
                    put("id", p.id)
                    put("deliveryId", p.deliveryId)
                    put("userId", p.userId)
                    put("dateKey", p.dateKey)
                    put("amount", p.amount)
                    put("method", p.method)
                    put("notes", p.notes)
                    put("createdAtEpochMs", p.createdAtEpochMs)
                })
            }
            put("utangPayments", payArr)

            val expArr = JSONArray()
            data.expenses.forEach { e ->
                expArr.put(JSONObject().apply {
                    put("id", e.id)
                    put("userId", e.userId)
                    put("dateKey", e.dateKey)
                    put("category", e.category)
                    put("amount", e.amount)
                    put("notes", e.notes)
                    put("createdAtEpochMs", e.createdAtEpochMs)
                    put("updatedAtEpochMs", e.updatedAtEpochMs)
                })
            }
            put("expenses", expArr)

            val ledArr = JSONArray()
            data.dailyLedgers.forEach { l ->
                ledArr.put(JSONObject().apply {
                    put("dateKey", l.dateKey)
                    put("userId", l.userId)
                    put("startingSukli", l.startingSukli)
                    put("notes", l.notes)
                    put("updatedAtEpochMs", l.updatedAtEpochMs)
                })
            }
            put("dailyLedgers", ledArr)

            data.settings?.let { s ->
                put("settings", JSONObject().apply {
                    put("id", s.id)
                    put("userId", s.userId)
                    put("purifiedPrice", s.purifiedPrice)
                    put("alkalinePrice", s.alkalinePrice)
                    put("commissionRate", s.commissionRate)
                    put("defaultSukli", s.defaultSukli)
                    put("updatedAtEpochMs", s.updatedAtEpochMs)
                })
            }

            val custArr = JSONArray()
            data.customers.forEach { c ->
                custArr.put(JSONObject().apply {
                    put("id", c.id)
                    put("userId", c.userId)
                    put("name", c.name)
                    c.lastUnitPrice?.let { put("lastUnitPrice", it) }
                    put("updatedAtEpochMs", c.updatedAtEpochMs)
                })
            }
            put("customers", custArr)

            put("commissionRateHistory", CommissionRateHistory.serialize(data.commissionRateHistory))
            put("unpaidBossReceivables", BossReceivablesPrefs.serializeToJsonArray(data.unpaidBossReceivables))

            // App Preferences
            put("preferences", JSONObject().apply {
                put("purifiedPrice", data.preferences.purifiedPrice)
                put("alkalinePrice", data.preferences.alkalinePrice)
                put("commissionRate", data.preferences.commissionRate)
                put("defaultSukli", data.preferences.defaultSukli)
                put("dailyQuotaTarget", data.preferences.dailyQuotaTarget)
                put("sundayMakeupEnabled", data.preferences.sundayMakeupEnabled)
                put("smallGallonPrice", data.preferences.smallGallonPrice)
                put("smallGallonCommissionRate", data.preferences.smallGallonCommissionRate)
            })

            // Metadata
            put("metadata", JSONObject().apply {
                put("appVersion", data.metadata.appVersion)
                put("versionCode", data.metadata.versionCode)
                put("exportedAt", data.metadata.exportedAt)
                put("totalDeliveries", data.metadata.totalDeliveries)
                put("totalPickups", data.metadata.totalPickups)
                put("totalPautang", data.metadata.totalPautang)
                put("totalExpenses", data.metadata.totalExpenses)
                put("totalCustomers", data.metadata.totalCustomers)
                put("totalPayments", data.metadata.totalPayments)
            })
        }
        return root.toString(2)
    }

    fun deserializeBackupPayload(rawJson: String): MinigeBackupData {
        val root = JSONObject(rawJson)
        val version = root.optInt("version", 1)
        val timestamp = root.optLong("backupTimestamp", System.currentTimeMillis())
        val userId = root.optString("userId", "")

        val deliveries = mutableListOf<DeliveryEntity>()
        val delivArr = root.optJSONArray("deliveries")
        if (delivArr != null) {
            for (i in 0 until delivArr.length()) {
                val obj = delivArr.getJSONObject(i)
                deliveries.add(
                    DeliveryEntity(
                        id = obj.getString("id"),
                        userId = obj.optString("userId", userId),
                        dateKey = obj.getString("dateKey"),
                        customerName = obj.getString("customerName"),
                        purifiedGallons = obj.optInt("purifiedGallons", 0),
                        alkalineGallons = obj.optInt("alkalineGallons", 0),
                        purifiedUnitPrice = obj.optInt("purifiedUnitPrice", 30),
                        alkalineUnitPrice = obj.optInt("alkalineUnitPrice", 40),
                        totalAmount = obj.optInt("totalAmount", 0),
                        amountPaid = obj.optInt("amountPaid", 0),
                        paymentType = obj.optString("paymentType", "CASH"),
                        commissionRate = obj.optInt("commissionRate", 8),
                        commissionEarned = obj.optInt("commissionEarned", 0),
                        settledDateKey = if (obj.has("settledDateKey")) obj.optString("settledDateKey") else null,
                        notes = obj.optString("notes", ""),
                        calendarEventId = if (obj.has("calendarEventId")) obj.optString("calendarEventId") else null,
                        pendingSync = true,
                        isDeleted = false,
                        createdAtEpochMs = obj.optLong("createdAtEpochMs", timestamp),
                        updatedAtEpochMs = obj.optLong("updatedAtEpochMs", timestamp),
                    )
                )
            }
        }

        val utangPayments = mutableListOf<UtangPaymentEntity>()
        val payArr = root.optJSONArray("utangPayments")
        if (payArr != null) {
            for (i in 0 until payArr.length()) {
                val obj = payArr.getJSONObject(i)
                utangPayments.add(
                    UtangPaymentEntity(
                        id = obj.getString("id"),
                        deliveryId = obj.getString("deliveryId"),
                        userId = obj.optString("userId", userId),
                        dateKey = obj.getString("dateKey"),
                        amount = obj.optInt("amount", 0),
                        method = obj.optString("method", "CASH"),
                        notes = obj.optString("notes", ""),
                        pendingSync = true,
                        isDeleted = false,
                        createdAtEpochMs = obj.optLong("createdAtEpochMs", timestamp),
                    )
                )
            }
        }

        val expenses = mutableListOf<ExpenseEntity>()
        val expArr = root.optJSONArray("expenses")
        if (expArr != null) {
            for (i in 0 until expArr.length()) {
                val obj = expArr.getJSONObject(i)
                expenses.add(
                    ExpenseEntity(
                        id = obj.getString("id"),
                        userId = obj.optString("userId", userId),
                        dateKey = obj.getString("dateKey"),
                        category = obj.optString("category", "FOOD"),
                        amount = obj.optInt("amount", 0),
                        notes = obj.optString("notes", ""),
                        pendingSync = true,
                        isDeleted = false,
                        createdAtEpochMs = obj.optLong("createdAtEpochMs", timestamp),
                        updatedAtEpochMs = obj.optLong("updatedAtEpochMs", timestamp),
                    )
                )
            }
        }

        val dailyLedgers = mutableListOf<DailyLedgerEntity>()
        val ledArr = root.optJSONArray("dailyLedgers")
        if (ledArr != null) {
            for (i in 0 until ledArr.length()) {
                val obj = ledArr.getJSONObject(i)
                dailyLedgers.add(
                    DailyLedgerEntity(
                        dateKey = obj.getString("dateKey"),
                        userId = obj.optString("userId", userId),
                        startingSukli = obj.optInt("startingSukli", 0),
                        notes = obj.optString("notes", ""),
                        pendingSync = true,
                        updatedAtEpochMs = obj.optLong("updatedAtEpochMs", timestamp),
                    )
                )
            }
        }

        val settings = if (root.has("settings")) {
            val obj = root.getJSONObject("settings")
            AppSettingsEntity(
                id = obj.optString("id", "default"),
                userId = obj.optString("userId", userId),
                purifiedPrice = obj.optInt("purifiedPrice", 30),
                alkalinePrice = obj.optInt("alkalinePrice", 40),
                commissionRate = obj.optInt("commissionRate", 8),
                defaultSukli = obj.optInt("defaultSukli", 0),
                pendingSync = true,
                updatedAtEpochMs = obj.optLong("updatedAtEpochMs", timestamp),
            )
        } else null

        val customers = mutableListOf<CustomerEntity>()
        val custArr = root.optJSONArray("customers")
        if (custArr != null) {
            for (i in 0 until custArr.length()) {
                val obj = custArr.getJSONObject(i)
                customers.add(
                    CustomerEntity(
                        id = obj.getString("id"),
                        userId = obj.optString("userId", userId),
                        name = obj.getString("name"),
                        lastUnitPrice = if (obj.has("lastUnitPrice")) obj.optDouble("lastUnitPrice") else null,
                        pendingSync = true,
                        updatedAtEpochMs = obj.optLong("updatedAtEpochMs", timestamp),
                    )
                )
            }
        }

        val commissionRateHistory = if (root.has("commissionRateHistory")) {
            CommissionRateHistory.deserialize(root.optString("commissionRateHistory", ""))
        } else emptyList()

        val preferences = if (root.has("preferences")) {
            val pObj = root.getJSONObject("preferences")
            val parsedSmallPrice = pObj.optDouble("smallGallonPrice", SmallGallonHelper.DEFAULT_PRICE)
                .takeIf { !it.isNaN() && it >= 0.0 }
                ?: SmallGallonHelper.DEFAULT_PRICE
            val parsedSmallComm = pObj.optDouble("smallGallonCommissionRate", SmallGallonHelper.DEFAULT_COMMISSION_RATE)
                .takeIf { !it.isNaN() && it >= 0.0 }
                ?: SmallGallonHelper.DEFAULT_COMMISSION_RATE
            BackupAppPreferences(
                purifiedPrice = pObj.optInt("purifiedPrice", settings?.purifiedPrice ?: 30),
                alkalinePrice = pObj.optInt("alkalinePrice", settings?.alkalinePrice ?: 40),
                commissionRate = pObj.optInt("commissionRate", settings?.commissionRate ?: 8),
                defaultSukli = pObj.optInt("defaultSukli", settings?.defaultSukli ?: 0),
                dailyQuotaTarget = pObj.optInt("dailyQuotaTarget", 50),
                sundayMakeupEnabled = pObj.optBoolean("sundayMakeupEnabled", false),
                smallGallonPrice = parsedSmallPrice,
                smallGallonCommissionRate = parsedSmallComm,
            )
        } else {
            BackupAppPreferences(
                purifiedPrice = settings?.purifiedPrice ?: 30,
                alkalinePrice = settings?.alkalinePrice ?: 40,
                commissionRate = settings?.commissionRate ?: 8,
                defaultSukli = settings?.defaultSukli ?: 0,
            )
        }

        val metadata = if (root.has("metadata")) {
            val mObj = root.getJSONObject("metadata")
            BackupMetadata(
                appVersion = mObj.optString("appVersion", BuildConfig.VERSION_NAME),
                versionCode = mObj.optInt("versionCode", BuildConfig.VERSION_CODE),
                exportedAt = mObj.optString("exportedAt", ""),
                totalDeliveries = mObj.optInt("totalDeliveries", deliveries.size),
                totalPickups = mObj.optInt("totalPickups", deliveries.count { it.paymentType == "PICK_UP" }),
                totalPautang = mObj.optInt("totalPautang", deliveries.count { it.paymentType == "PAUTANG" }),
                totalExpenses = mObj.optInt("totalExpenses", expenses.size),
                totalCustomers = mObj.optInt("totalCustomers", customers.size),
                totalPayments = mObj.optInt("totalPayments", utangPayments.size),
            )
        } else {
            BackupMetadata(
                totalDeliveries = deliveries.size,
                totalExpenses = expenses.size,
                totalCustomers = customers.size,
                totalPayments = utangPayments.size,
            )
        }

        val unpaidBossReceivables = BossReceivablesPrefs.deserializeFromJsonArray(
            root.optJSONArray("unpaidBossReceivables")
        )

        return MinigeBackupData(
            version = version,
            backupTimestamp = timestamp,
            userId = userId,
            deliveries = deliveries,
            utangPayments = utangPayments,
            expenses = expenses,
            dailyLedgers = dailyLedgers,
            settings = settings,
            customers = customers,
            commissionRateHistory = commissionRateHistory,
            preferences = preferences,
            metadata = metadata,
            unpaidBossReceivables = unpaidBossReceivables,
        )
    }

    fun getLastBackupTimestamp(context: Context, userId: String): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong("$KEY_LAST_BACKUP$userId", 0L)
    }

    fun saveLastBackupTimestamp(context: Context, userId: String, timestamp: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong("$KEY_LAST_BACKUP$userId", timestamp).apply()
    }

    fun saveBackupMetadataSummary(context: Context, userId: String, meta: BackupMetadata) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("appVersion", meta.appVersion)
            put("totalDeliveries", meta.totalDeliveries)
            put("totalPayments", meta.totalPayments)
            put("totalExpenses", meta.totalExpenses)
            put("totalCustomers", meta.totalCustomers)
            put("exportedAt", meta.exportedAt)
        }.toString()
        prefs.edit().putString("$KEY_LAST_BACKUP_META$userId", json).apply()
    }

    fun getBackupMetadataSummary(context: Context, userId: String): BackupMetadataSummary? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("$KEY_LAST_BACKUP_META$userId", null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            BackupMetadataSummary(
                appVersion = obj.optString("appVersion", "1.0.0"),
                totalDeliveries = obj.optInt("totalDeliveries", 0),
                totalPayments = obj.optInt("totalPayments", 0),
                totalExpenses = obj.optInt("totalExpenses", 0),
                totalCustomers = obj.optInt("totalCustomers", 0),
                exportedAt = obj.optString("exportedAt", ""),
            )
        }.getOrNull()
    }

    fun getCachedToken(context: Context, userId: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("$KEY_CACHED_TOKEN$userId", null)?.takeIf { it.isNotBlank() }
    }

    fun saveCachedToken(context: Context, userId: String, token: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$KEY_CACHED_TOKEN$userId", token).apply()
    }

    fun clearCachedToken(context: Context, userId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("$KEY_CACHED_TOKEN$userId").apply()
    }

    fun isAutoBackupBeforeUpdateEnabled(context: Context, userId: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("$KEY_AUTO_BACKUP_ON_UPDATE$userId", true)
    }

    fun setAutoBackupBeforeUpdateEnabled(context: Context, userId: String, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("$KEY_AUTO_BACKUP_ON_UPDATE$userId", enabled).apply()
    }

    fun getLastLocalBackupPath(context: Context, userId: String): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("$KEY_LAST_LOCAL_BACKUP_PATH$userId", null)
            ?.takeIf { it.isNotBlank() }
            ?: LOCAL_BACKUP_DISPLAY_PATH
    }

    fun saveLastLocalBackupPath(context: Context, userId: String, path: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$KEY_LAST_LOCAL_BACKUP_PATH$userId", path).apply()
    }

    fun saveLastSuccessfulDriveAuthEpochMs(context: Context, userId: String, epochMs: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong("$KEY_LAST_DRIVE_AUTH_EPOCH$userId", epochMs).apply()
    }

    fun getLastSuccessfulDriveAuthEpochMs(context: Context, userId: String): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong("$KEY_LAST_DRIVE_AUTH_EPOCH$userId", 0L)
    }

    fun clearLastSuccessfulDriveAuthEpochMs(context: Context, userId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("$KEY_LAST_DRIVE_AUTH_EPOCH$userId").apply()
    }

    fun formatBackupTimestamp(epochMs: Long): String {
        if (epochMs <= 0L) return "No backup yet"
        val formatter = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        return formatter.format(Date(epochMs))
    }
}
