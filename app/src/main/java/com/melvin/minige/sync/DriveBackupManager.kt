package com.melvin.minige.sync

import android.content.Context
import android.util.Log
import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.MinigeDao
import com.melvin.minige.data.local.UtangPaymentEntity
import com.melvin.minige.util.CommissionRateEntry
import com.melvin.minige.util.CommissionRateHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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

data class DriveRestoreSummary(
    val deliveryCount: Int,
    val paymentCount: Int,
    val expenseCount: Int,
    val customerCount: Int,
    val backupTimestamp: Long,
)

data class MinigeBackupData(
    val version: Int = 1,
    val backupTimestamp: Long = System.currentTimeMillis(),
    val userId: String = "",
    val deliveries: List<DeliveryEntity> = emptyList(),
    val utangPayments: List<UtangPaymentEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val dailyLedgers: List<DailyLedgerEntity> = emptyList(),
    val settings: AppSettingsEntity? = null,
    val customers: List<CustomerEntity> = emptyList(),
    val commissionRateHistory: List<CommissionRateEntry> = emptyList(),
)

object DriveBackupManager {

    private const val TAG = "DriveBackupManager"
    const val BACKUP_FILE_NAME = "minige_auto_backup.json"
    private const val PREFS_NAME = "minige_drive_backup_prefs"
    private const val KEY_LAST_BACKUP = "last_drive_backup_"
    private const val KEY_CACHED_TOKEN = "cached_drive_token_"
    private const val BOUNDARY = "MINIGE_DRIVE_BACKUP_BOUNDARY_7788"

    private const val DRIVE_FILES_URL =
        "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D'minige_auto_backup.json'+and+trashed%3Dfalse&fields=files(id,name,modifiedTime,size)"
    private const val DRIVE_UPLOAD_URL =
        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

    suspend fun backupToDrive(
        accessToken: String,
        dao: MinigeDao,
        userId: String,
        context: Context,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val deliveries = dao.getAllDeliveriesOnce(userId)
            val utangPayments = dao.getAllUtangPaymentsOnce(userId)
            val expenses = dao.getAllExpensesOnce(userId)
            val dailyLedgers = dao.getAllDailyLedgersOnce(userId)
            val settings = dao.getSettingsOnce()
            val customers = dao.getAllCustomersOnce(userId)
            val rateHistory = CommissionRateHistory.loadHistory(context, userId)

            val backupData = MinigeBackupData(
                version = 1,
                backupTimestamp = now,
                userId = userId,
                deliveries = deliveries,
                utangPayments = utangPayments,
                expenses = expenses,
                dailyLedgers = dailyLedgers,
                settings = settings,
                customers = customers,
                commissionRateHistory = rateHistory,
            )

            val jsonPayload = serializeBackupPayload(backupData)
            val existingFileId = findExistingBackupFileId(accessToken)

            if (existingFileId != null) {
                overwriteDriveFile(accessToken, existingFileId, jsonPayload)
            } else {
                createNewDriveFile(accessToken, jsonPayload)
            }

            saveLastBackupTimestamp(context, userId, now)
            saveCachedToken(context, userId, accessToken)
            Log.i(TAG, "Successfully backed up Minige to Google Drive appDataFolder at $now")
            Result.success(now)
        } catch (e: Exception) {
            Log.w(TAG, "Google Drive backup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun restoreFromDrive(
        accessToken: String,
        dao: MinigeDao,
        userId: String,
        context: Context,
    ): Result<DriveRestoreSummary> = withContext(Dispatchers.IO) {
        try {
            val fileId = findExistingBackupFileId(accessToken)
                ?: return@withContext Result.failure(
                    NoSuchElementException("Walang nahanap na backup file ($BACKUP_FILE_NAME) sa Google Drive appDataFolder.")
                )

            val jsonPayload = downloadDriveFile(accessToken, fileId)
            val backupData = deserializeBackupPayload(jsonPayload)

            // Restore records into Room with pendingSync = true so they also sync up to Firestore
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

            backupData.settings?.let { s ->
                dao.upsertSettings(s.copy(userId = userId, pendingSync = true))
            }

            val restoredCustomers = backupData.customers.map {
                it.copy(userId = userId, pendingSync = true)
            }
            if (restoredCustomers.isNotEmpty()) {
                dao.upsertCustomers(restoredCustomers)
            }

            if (backupData.commissionRateHistory.isNotEmpty()) {
                CommissionRateHistory.saveHistory(context, userId, backupData.commissionRateHistory)
            }

            saveLastBackupTimestamp(context, userId, backupData.backupTimestamp)
            saveCachedToken(context, userId, accessToken)

            val summary = DriveRestoreSummary(
                deliveryCount = restoredDeliveries.size,
                paymentCount = restoredPayments.size,
                expenseCount = restoredExpenses.size,
                customerCount = restoredCustomers.size,
                backupTimestamp = backupData.backupTimestamp,
            )
            Log.i(TAG, "Successfully restored Minige from Google Drive: $summary")
            Result.success(summary)
        } catch (e: Exception) {
            Log.e(TAG, "Restore from Google Drive failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun findExistingBackupFileId(accessToken: String): String? {
        val url = URL(DRIVE_FILES_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } else {
                Log.w(TAG, "Drive list files returned HTTP $code")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun createNewDriveFile(accessToken: String, jsonPayload: String): String {
        val url = URL(DRIVE_UPLOAD_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")
        }

        val metadataJson = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
            put("parents", JSONArray(listOf("appDataFolder")))
        }.toString()

        val body = buildMultipartBody(metadataJson, jsonPayload)

        conn.outputStream.use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errorText = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            throw IllegalStateException("Drive file create failed (HTTP $code): $errorText")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val responseJson = JSONObject(responseText)
        return responseJson.optString("id")
    }

    private fun overwriteDriveFile(accessToken: String, fileId: String, jsonPayload: String): String {
        val url = URL("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart")
        val conn = (url.openConnection() as HttpURLConnection).apply {
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

        conn.outputStream.use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errorText = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            // If file was deleted remotely, fallback to create new file
            if (code == 404) {
                return createNewDriveFile(accessToken, jsonPayload)
            }
            throw IllegalStateException("Drive file overwrite failed (HTTP $code): $errorText")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val responseJson = JSONObject(responseText)
        return responseJson.optString("id").ifBlank { fileId }
    }

    private fun downloadDriveFile(accessToken: String, fileId: String): String {
        val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errorText = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            throw IllegalStateException("Drive file download failed (HTTP $code): $errorText")
        }

        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
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

    fun getCachedToken(context: Context, userId: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("$KEY_CACHED_TOKEN$userId", null)?.takeIf { it.isNotBlank() }
    }

    fun saveCachedToken(context: Context, userId: String, token: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$KEY_CACHED_TOKEN$userId", token).apply()
    }

    fun formatBackupTimestamp(epochMs: Long): String {
        if (epochMs <= 0L) return "No backup yet"
        val formatter = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        return formatter.format(Date(epochMs))
    }
}
