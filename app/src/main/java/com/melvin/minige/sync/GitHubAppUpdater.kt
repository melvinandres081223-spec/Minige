package com.melvin.minige.sync

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.melvin.minige.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val releaseNotes: String = "",
)

sealed interface UpdateCheckOutcome {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckOutcome
    data object AlreadyUpToDate : UpdateCheckOutcome
    data class Error(val message: String) : UpdateCheckOutcome
}

object GitHubAppUpdater {

    internal val RELEASES_TAG_LATEST_URL: String
        get() = BuildConfig.GITHUB_RELEASE_API
    internal val RELEASES_LATEST_URL: String
        get() = BuildConfig.GITHUB_RELEASE_FALLBACK_API
    internal val DIRECT_APK_DOWNLOAD_URL: String
        get() = BuildConfig.GITHUB_APK_URL

    internal const val USER_AGENT = "Minige-Android-Updater"
    internal const val ACCEPT_HEADER = "application/vnd.github+json"

    internal const val RELEASE_NOTES_ATTRIBUTION = "Release Notes by Vin"
    internal const val FALLBACK_RELEASE_NOTE = "Bug fixes, sync improvements, and UI refinements."
    internal const val RELEASES_PAGE_URL = "https://github.com/melvinandres081223-spec/Minige/releases"

    private const val PREFS_NAME = "minige_updater_prefs"
    private const val KEY_LAST_PUBLISHED_AT = "last_installed_published_at"
    private const val KEY_CACHED_RELEASE_NOTES = "cached_latest_release_notes"
    private const val KEY_CACHED_CHANGELOG_URL = "cached_latest_changelog_url"
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

    fun getCachedReleaseNotes(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CACHED_RELEASE_NOTES, "") ?: ""
    }

    fun saveCachedReleaseNotes(context: Context, notes: String, tagName: String? = null) {
        if (notes.isBlank()) return
        val extractedUrl = extractChangelogUrl(notes, tagName)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_RELEASE_NOTES, notes)
            .putString(KEY_CACHED_CHANGELOG_URL, extractedUrl)
            .apply()
    }

    fun getCachedChangelogUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CACHED_CHANGELOG_URL, RELEASES_PAGE_URL)
            ?.takeIf { it.isNotBlank() }
            ?: RELEASES_PAGE_URL
    }

    fun extractChangelogUrl(rawBody: String?, tagName: String? = null): String {
        if (!rawBody.isNullOrBlank()) {
            val match = Regex("https://github\\.com/[^\\s)>\\]]+").find(rawBody)
            if (match != null) {
                return match.value.trimEnd('.', ',', ';')
            }
        }
        val cleanTag = tagName?.trim()?.takeIf { it.isNotEmpty() && !it.equals("latest", ignoreCase = true) }
        return if (cleanTag != null) {
            "$RELEASES_PAGE_URL/tag/$cleanTag"
        } else {
            RELEASES_PAGE_URL
        }
    }

    /**
     * Splits and parses Markdown or plain text release notes into clean user-facing bullet points.
     * Filters out any line containing "Full Changelog" or "https://github.com/", strips raw "**"
     * markdown bold markers, and returns an empty list when no bullet items remain.
     */
    fun formatReleaseNotes(rawBody: String?): List<String> {
        if (rawBody.isNullOrBlank()) return emptyList()
        val lines = rawBody.lines()
        val bullets = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Skip markdown headers
            if (trimmed.startsWith("#")) continue
            // Filter out Full Changelog lines and GitHub URLs
            if (trimmed.contains("Full Changelog", ignoreCase = true)) continue
            if (trimmed.contains("https://github.com/", ignoreCase = true)) continue
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) continue

            val withoutPrefix = when {
                trimmed.startsWith("* ") -> trimmed.removePrefix("* ").trim()
                trimmed.startsWith("- ") -> trimmed.removePrefix("- ").trim()
                trimmed.startsWith("• ") -> trimmed.removePrefix("• ").trim()
                trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> trimmed.substringAfter(" ").trim()
                else -> trimmed
            }
            val cleanedBullet = withoutPrefix
                .replace("**", "")
                .replace(Regex("\\s+by\\s+@[^\\s]+"), "")
                .trim()

            if (cleanedBullet.isNotBlank()) {
                bullets.add(cleanedBullet)
            }
        }

        return bullets
    }

    /**
     * Provides clean release notes for the active version, returning the standard fallback bullet
     * when cached release notes only contain filtered changelog URLs, or curated highlights before
     * any release notes have been fetched.
     */
    fun getWhatsNewForCurrentVersion(context: Context): List<String> {
        val cached = getCachedReleaseNotes(context)
        if (cached.isNotBlank()) {
            val parsed = formatReleaseNotes(cached)
            return if (parsed.isNotEmpty()) parsed else listOf(FALLBACK_RELEASE_NOTE)
        }
        return listOf(
            "Visible Google Drive Auto-Backup sa My Drive 'Minige Backup' folder (drive.file scope)",
            "Automatic 'What's New' release notes & direct in-app APK installer",
            "3-Day Inactive Suki warning alerts with 1-tap Re-order prefill",
            "Built-in App Diagnostics & Health Check with secret redaction",
            "Variable commission rate history (₱8/gal) & bulleted Daily Hakot Messenger export",
            "Zero-lag 120Hz display refresh rate lock & offline-first Room + Cloud sync",
        )
    }

    internal fun parseLatestReleaseJson(jsonBody: String): GitHubReleaseInfo? {
        val root = JSONObject(jsonBody)
        val publishedAt = root.optString("published_at")
            .ifBlank { root.optString("updated_at") }
            .ifBlank { root.optString("created_at") }
            .trim()
        val tagName = root.optString("tag_name").trim().ifBlank { "latest" }
        val releaseNotes = root.optString("body").trim()
        val assets = root.optJSONArray("assets")

        var apkUrl: String? = null
        var apkName = "app-debug.apk"
        if (assets != null) {
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
        }

        if (apkUrl.isNullOrBlank()) {
            apkUrl = DIRECT_APK_DOWNLOAD_URL
        }
        if (publishedAt.isBlank()) return null
        return GitHubReleaseInfo(
            tagName = tagName,
            publishedAt = publishedAt,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            releaseNotes = releaseNotes,
        )
    }

    internal data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    internal fun parseVersion(raw: String?): SemanticVersion? {
        val cleaned = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (cleaned.isEmpty()) return null
        val core = cleaned.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return SemanticVersion(major, minor, patch)
    }

    internal fun isRemoteReleaseNewer(
        release: GitHubReleaseInfo,
        installedVersionCode: Int,
        installedVersionName: String,
        lastSavedPublishedAt: String,
    ): Boolean {
        val remoteVersion = parseVersion(release.tagName)
        if (remoteVersion != null) {
            val localVersion = parseVersion(installedVersionName)
            if (localVersion != null) {
                val cmp = remoteVersion.compareTo(localVersion)
                if (cmp != 0) return cmp > 0
            }
            return remoteVersion.patch > installedVersionCode
        }
        return lastSavedPublishedAt.isEmpty() || lastSavedPublishedAt != release.publishedAt
    }

    private fun fetchReleaseFromApi(apiUrl: String): Result<GitHubReleaseInfo> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", ACCEPT_HEADER)
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val errMsg = runCatching { JSONObject(errBody).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                return Result.failure(
                    IllegalStateException(
                        errMsg?.let { "GitHub error ($code): $it" } ?: "Update check failed (HTTP $code)"
                    )
                )
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = parseLatestReleaseJson(body)
                ?: return Result.failure(IllegalStateException("Release metadata missing timestamp."))
            Result.success(release)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun probeDirectApkFallback(): Result<GitHubReleaseInfo> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(DIRECT_APK_DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", ACCEPT_HEADER)
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val versionToken = connection.getHeaderField("Last-Modified")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: connection.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "direct-latest"
                Result.success(
                    GitHubReleaseInfo(
                        tagName = "latest",
                        publishedAt = versionToken,
                        apkDownloadUrl = DIRECT_APK_DOWNLOAD_URL,
                        apkFileName = "app-debug.apk",
                        releaseNotes = "",
                    )
                )
            } else {
                Result.failure(IllegalStateException("Update check failed (HTTP $code)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val latestResult = fetchReleaseFromApi(RELEASES_LATEST_URL)
        val releaseResult = if (latestResult.isSuccess) {
            latestResult
        } else {
            val tagLatestResult = fetchReleaseFromApi(RELEASES_TAG_LATEST_URL)
            if (tagLatestResult.isSuccess) {
                tagLatestResult
            } else {
                val directResult = probeDirectApkFallback()
                if (directResult.isSuccess) directResult else latestResult
            }
        }

        releaseResult.fold(
            onSuccess = { release ->
                if (release.releaseNotes.isNotBlank()) {
                    saveCachedReleaseNotes(context, release.releaseNotes, release.tagName)
                }
                val isNewer = isRemoteReleaseNewer(
                    release = release,
                    installedVersionCode = BuildConfig.VERSION_CODE,
                    installedVersionName = BuildConfig.VERSION_NAME,
                    lastSavedPublishedAt = getLastInstalledPublishedAt(context),
                )
                if (isNewer) {
                    UpdateCheckOutcome.UpdateAvailable(release)
                } else {
                    UpdateCheckOutcome.AlreadyUpToDate
                }
            },
            onFailure = { err ->
                UpdateCheckOutcome.Error(err.localizedMessage ?: "Network error while checking updates.")
            },
        )
    }

    /**
     * Checks if Minige has unknown package installation permission, launching the system setting
     * if permission is missing, or launching the system package installer intent directly.
     */
    fun launchApkInstaller(context: Context, apkFile: File) {
        val appContext = context.applicationContext

        // Check canRequestPackageInstalls on Android 8.0 (API 26)+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(manageIntent)
                Toast.makeText(
                    appContext,
                    "Paki-allow ang 'Install unknown apps' para sa Minige upang ma-install ang update.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

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
        if (launchResult.isFailure) {
            val msg = "Unable to launch installer: ${launchResult.exceptionOrNull()?.localizedMessage ?: "Unknown error"}"
            Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Enqueues APK download via DownloadManager to external app downloads (accessible via FileProvider),
     * tracks live percentage progress, registers a BroadcastReceiver for completion, and prompts installation.
     */
    fun startDownloadAndInstall(
        context: Context,
        release: GitHubReleaseInfo,
        onStatus: (String) -> Unit,
        onProgress: (percent: Int, status: String) -> Unit = { _, _ -> },
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
            addRequestHeader("User-Agent", USER_AGENT)
            setTitle("Minige Update ${release.tagName}")
            setDescription("Downloading ${release.apkFileName}...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
        }

        val downloadId = downloadManager.enqueue(request)
        onStatus("Downloading update...")
        onProgress(0, "Connecting...")

        var isDownloading = true
        val progressScope = CoroutineScope(Dispatchers.IO)
        var progressJob: Job? = null

        progressJob = progressScope.launch {
            val query = DownloadManager.Query().setFilterById(downloadId)
            while (isActive && isDownloading) {
                delay(400)
                try {
                    val cursor = downloadManager.query(query)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val bytesIdx = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = if (statusIdx >= 0) it.getInt(statusIdx) else -1

                            if (status == DownloadManager.STATUS_RUNNING && bytesIdx >= 0 && totalIdx >= 0) {
                                val bytes = it.getLong(bytesIdx)
                                val total = it.getLong(totalIdx)
                                if (total > 0) {
                                    val pct = ((bytes * 100) / total).toInt().coerceIn(0, 100)
                                    val mbDownloaded = bytes / (1024 * 1024.0)
                                    val mbTotal = total / (1024 * 1024.0)
                                    val statusMsg = "Downloading: $pct% (%.1f / %.1f MB)".format(mbDownloaded, mbTotal)
                                    withContext(Dispatchers.Main) {
                                        onStatus(statusMsg)
                                        onProgress(pct, statusMsg)
                                    }
                                }
                            } else if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                isDownloading = false
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient cursor read errors
                }
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (completedId != downloadId) return

                isDownloading = false
                progressJob?.cancel()
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
                    onProgress(0, msg)
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                    onFinished()
                    return
                }

                val apkFile = localFilePath?.let(::File)?.takeIf { it.exists() } ?: destinationFile
                if (!apkFile.exists()) {
                    val msg = "Downloaded APK file could not be found."
                    onStatus(msg)
                    onProgress(0, msg)
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                    onFinished()
                    return
                }

                saveInstalledPublishedAt(appContext, release.publishedAt)
                onStatus("Update downloaded! Launching installer...")
                onProgress(100, "Ready to install")

                launchApkInstaller(appContext, apkFile)
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
