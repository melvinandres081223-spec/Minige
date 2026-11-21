package com.melvin.minige.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val publishedAt: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
)

sealed interface UpdateCheckOutcome {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckOutcome
    data object AlreadyUpToDate : UpdateCheckOutcome
    data class Error(val message: String) : UpdateCheckOutcome
}

object GitHubAppUpdater {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/melvinandres081223-spec/Minige/releases/latest"
    private const val PREFS_NAME = "minige_updater_prefs"
    private const val KEY_LAST_PUBLISHED_AT = "last_installed_published_at"
    private const val APK_FILE_NAME = "Minige-update.apk"

    fun getLastInstalledPublishedAt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PUBLISHED_AT, "") ?: ""
    }

    fun saveInstalledPublishedAt(context: Context, publishedAt: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PUBLISHED_AT, publishedAt)
            .apply()
    }

    internal fun parseLatestReleaseJson(jsonBody: String): GitHubReleaseInfo? {
        val root = JSONObject(jsonBody)
        val publishedAt = root.optString("published_at")
            .ifBlank { root.optString("updated_at") }
            .trim()
        val tagName = root.optString("tag_name").trim()
        val assets = root.optJSONArray("assets") ?: return null

        var apkUrl: String? = null
        var apkName = APK_FILE_NAME
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                apkUrl = url
                apkName = name
                break
            }
        }

        if (apkUrl.isNullOrBlank() || publishedAt.isBlank()) return null
        return GitHubReleaseInfo(
            tagName = tagName,
            publishedAt = publishedAt,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
        )
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_LATEST_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Minige-Android")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val errMsg = runCatching { JSONObject(errBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                return@withContext UpdateCheckOutcome.Error(
                    errMsg?.let { "GitHub error ($code): $it" } ?: "Update check failed (HTTP $code)"
                )
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = parseLatestReleaseJson(body)
                ?: return@withContext UpdateCheckOutcome.Error("No APK asset found in latest release.")

            val lastSaved = getLastInstalledPublishedAt(context)
            if (lastSaved.isNotEmpty() && lastSaved == release.publishedAt) {
                UpdateCheckOutcome.AlreadyUpToDate
            } else {
                UpdateCheckOutcome.UpdateAvailable(release)
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Error(e.localizedMessage ?: "Network error while checking updates.")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Enqueues APK download via DownloadManager to external app downloads (accessible via FileProvider
     * on Android 11+), registers a BroadcastReceiver for completion, and returns a handle to clean up.
     */
    fun startDownloadAndInstall(
        context: Context,
        release: GitHubReleaseInfo,
        onStatus: (String) -> Unit,
        onFinished: () -> Unit,
    ): BroadcastReceiver {
        val appContext = context.applicationContext
        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val destinationFile = File(downloadDir, APK_FILE_NAME)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(release.apkDownloadUrl)).apply {
            setTitle("Minige Update")
            setDescription("Downloading ${release.apkFileName}...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
        }

        val downloadId = downloadManager.enqueue(request)
        onStatus("Downloading update...")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (completedId != downloadId) return

                runCatching { appContext.unregisterReceiver(this) }

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                var succeeded = false
                var localFilePath: String? = null
                var failReason = 0

                cursor?.use {
                    if (it.moveToFirst()) {
                        val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val uriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val reasonIdx = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val status = if (statusIdx >= 0) it.getInt(statusIdx) else -1
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            succeeded = true
                            val rawUri = if (uriIdx >= 0) it.getString(uriIdx) else null
                            localFilePath = rawUri?.let { uriStr -> Uri.parse(uriStr).path }
                        } else if (reasonIdx >= 0) {
                            failReason = it.getInt(reasonIdx)
                        }
                    }
                }

                if (!succeeded) {
                    val msg = "Download failed (code $failReason)."
                    onStatus(msg)
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                    onFinished()
                    return
                }

                val apkFile = localFilePath?.let(::File)?.takeIf { it.exists() } ?: destinationFile
                if (!apkFile.exists()) {
                    val msg = "Downloaded APK file could not be found."
                    onStatus(msg)
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                    onFinished()
                    return
                }

                saveInstalledPublishedAt(appContext, release.publishedAt)

                val apkUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    apkFile,
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val launchResult = runCatching { appContext.startActivity(installIntent) }
                if (launchResult.isSuccess) {
                    onStatus("Update downloaded. Launching installer...")
                } else {
                    val msg = "Unable to launch installer: ${launchResult.exceptionOrNull()?.localizedMessage ?: "Unknown error"}"
                    onStatus(msg)
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                }
                onFinished()
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return receiver
    }
}
