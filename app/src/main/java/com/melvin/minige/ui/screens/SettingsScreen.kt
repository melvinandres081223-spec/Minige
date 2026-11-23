package com.melvin.minige.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.BuildConfig
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.DriveBackupStatus
import com.melvin.minige.sync.GitHubAppUpdater
import com.melvin.minige.sync.GitHubReleaseInfo
import com.melvin.minige.sync.UpdateCheckOutcome
import com.melvin.minige.ui.MinigeUiState
import com.melvin.minige.ui.components.AppDiagnosticsPanel
import com.melvin.minige.ui.components.CommissionScheduleDialog
import com.melvin.minige.ui.components.DailyQuotaDialog
import com.melvin.minige.ui.components.DeveloperSignatureCard
import com.melvin.minige.ui.components.DeveloperSignatureConfig
import com.melvin.minige.ui.components.DiagnosticStatusBadge
import com.melvin.minige.ui.components.PricingSettingsDialog
import com.melvin.minige.ui.components.SettingsActionRow
import com.melvin.minige.ui.components.SettingsCategoryHeader
import com.melvin.minige.ui.components.SettingsDivider
import com.melvin.minige.ui.components.SettingsSearchBar
import com.melvin.minige.ui.components.SettingsSwitchRow
import com.melvin.minige.ui.components.UpdateAvailableDialog
import com.melvin.minige.ui.components.VerifyDriveRestoreDialog
import com.melvin.minige.ui.components.WhatsNewBlock
import com.melvin.minige.util.CommissionRateHistory
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(
    uiState: MinigeUiState,
    userEmail: String?,
    onSaveSettings: (purified: Int, alkaline: Int, rate: Int, defaultSukli: Int) -> Unit,
    onScheduleCommissionRate: (ratePerGallon: Double, effectiveDate: LocalDate) -> Unit,
    onUpdateQuotaTarget: (Int) -> Unit = {},
    onToggleSundayMakeup: (Boolean) -> Unit = {},
    onSyncNow: () -> Unit,
    onGrantDriveAccess: () -> Unit = {},
    onBackupToDriveNow: () -> Unit = {},
    onPreviewRestoreFromDrive: () -> Unit = {},
    onConfirmRestoreFromDrive: () -> Unit = {},
    onDismissRestorePreview: () -> Unit = {},
    onToggleAutoBackupBeforeUpdate: (Boolean) -> Unit = {},
    onBackupBeforeUpdate: suspend () -> String = { "" },
    onRunDiagnostics: () -> Unit = {},
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim().lowercase()
    fun matches(vararg keywords: String): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return keywords.any { it.contains(normalizedQuery, ignoreCase = true) }
    }

    var isCheckingOrDownloadingUpdate by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var pendingUpdateRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgressPercent by remember { mutableIntStateOf(0) }
    var downloadProgressStatus by remember { mutableStateOf("Ready to download") }

    var showWhatsNew by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showPricingDialog by remember { mutableStateOf(false) }
    var showCommissionDialog by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
    }

    val whatsNewBullets = remember(pendingUpdateRelease, updateStatusText) {
        GitHubAppUpdater.getWhatsNewForCurrentVersion(context)
    }
    val cachedChangelogUrl = remember(pendingUpdateRelease, updateStatusText) {
        GitHubAppUpdater.getCachedChangelogUrl(context)
    }

    val showCheckUpdateRow = matches("check", "update", "apk", "version", "github", "updating")
    val showWhatsNewRow = matches("what's new", "release notes", "vin", "changelog", "updating")
    val showUpdatingCategory = showCheckUpdateRow || showWhatsNewRow

    val showDriveGrantRow = uiState.driveBackupStatus == DriveBackupStatus.NEEDS_PERMISSION &&
        matches("grant", "drive", "permission", "backup")
    val showBackupNowRow = matches("backup", "drive", "local", "json", "export", "auto")
    val showRestoreRow = matches("restore", "drive", "backup", "verify", "import")
    val showAutoBackupRow = matches("automatic backup", "auto", "update", "backup", "new version")
    val showBackupPathRow = matches("backup path", "path", "folder", "download", "minige", "drive")
    val showBackupCategory = showDriveGrantRow || showBackupNowRow || showRestoreRow || showAutoBackupRow || showBackupPathRow

    val showDiagnosticsCategory = matches("logs", "diagnostics", "health", "check", "report", "redact", "bug")

    val showPricingRow = matches("pricing", "purified", "alkaline", "sukli", "commission", "preferences")
    val showCommissionScheduleRow = matches("commission", "rate", "schedule", "history", "preferences")
    val showQuotaRow = matches("quota", "target", "gallons", "daily", "preferences")
    val showSundayRow = matches("sunday", "makeup", "weekly", "preferences")
    val showCloudSyncRow = matches("cloud", "sync", "firebase", "account", "email", "preferences")
    val showMicRow = !hasMicPermission && matches("mic", "microphone", "voice", "permission", "preferences")
    val showSignOutRow = matches("sign out", "logout", "account", "preferences")
    val showPreferencesCategory = showPricingRow || showCommissionScheduleRow || showQuotaRow ||
        showSundayRow || showCloudSyncRow || showMicRow || showSignOutRow

    val showVersionRow = matches("about", "version", "build", "minige")
    val showPackageRow = matches("about", "package", "com.melvin.minige", "id")
    val showSourceRow = matches("about", "source", "github", "repo", "code", "open")
    val showAboutCategory = showVersionRow || showPackageRow || showSourceRow

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
        ) {
            item(key = "settings_search_bar", contentType = "search_bar") {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (showUpdatingCategory) {
                item(key = "header_updating", contentType = "category_header") {
                    SettingsCategoryHeader(title = "Updating")
                }

                if (showCheckUpdateRow) {
                    item(key = "row_check_updates", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.SystemUpdate,
                            title = "Check for App Updates",
                            subtitle = updateStatusText
                                ?: "Current: v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
                            enabled = !isCheckingOrDownloadingUpdate,
                            iconTint = MaterialTheme.colorScheme.primary,
                            onClick = {
                                if (isCheckingOrDownloadingUpdate) return@SettingsActionRow
                                isCheckingOrDownloadingUpdate = true
                                updateStatusText = "Checking for updates..."
                                Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()

                                scope.launch {
                                    when (val outcome = GitHubAppUpdater.checkForUpdate(context)) {
                                        is UpdateCheckOutcome.AlreadyUpToDate -> {
                                            isCheckingOrDownloadingUpdate = false
                                            updateStatusText = "Up to date (v${BuildConfig.VERSION_NAME})"
                                            Toast.makeText(context, "Already up to date!", Toast.LENGTH_SHORT).show()
                                        }
                                        is UpdateCheckOutcome.Error -> {
                                            isCheckingOrDownloadingUpdate = false
                                            updateStatusText = outcome.message
                                            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                        }
                                        is UpdateCheckOutcome.UpdateAvailable -> {
                                            isCheckingOrDownloadingUpdate = false
                                            updateStatusText = "Update available: ${outcome.release.tagName}"
                                            pendingUpdateRelease = outcome.release
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                if (isCheckingOrDownloadingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("check_updates_button"),
                        )
                    }
                }

                if (showWhatsNewRow) {
                    item(key = "row_whats_new", contentType = "whats_new_row") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SettingsActionRow(
                                icon = Icons.Rounded.NewReleases,
                                title = "What's New in Minige",
                                subtitle = GitHubAppUpdater.RELEASE_NOTES_ATTRIBUTION,
                                onClick = { showWhatsNew = !showWhatsNew },
                                trailingContent = {
                                    Icon(
                                        imageVector = if (showWhatsNew || normalizedQuery.isNotEmpty()) {
                                            Icons.Rounded.ExpandLess
                                        } else {
                                            Icons.Rounded.ExpandMore
                                        },
                                        contentDescription = "Toggle release notes",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            AnimatedVisibility(visible = showWhatsNew || normalizedQuery.isNotEmpty()) {
                                WhatsNewBlock(
                                    bullets = whatsNewBullets,
                                    onViewFullChangelog = { openUrl(context, cachedChangelogUrl) },
                                )
                            }
                        }
                    }
                }

                item(key = "divider_updating", contentType = "divider") {
                    SettingsDivider(modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (showBackupCategory) {
                item(key = "header_backup", contentType = "category_header") {
                    SettingsCategoryHeader(title = "Backup and restore")
                }

                if (showDriveGrantRow) {
                    item(key = "row_grant_drive", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.CloudUpload,
                            title = "Grant Google Drive Access",
                            subtitle = "Needed to save backups in My Drive '${DriveBackupManager.BACKUP_FOLDER_NAME}'",
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            onClick = onGrantDriveAccess,
                            modifier = Modifier.testTag("grant_drive_access_button"),
                        )
                    }
                }

                if (showBackupNowRow) {
                    item(key = "row_backup_now", contentType = "action_row") {
                        val summary = uiState.driveBackupSummary
                        val statusPrefix = when {
                            uiState.isDriveBackingUp || uiState.driveBackupStatus == DriveBackupStatus.BACKING_UP ->
                                "Backing up to Drive & Downloads..."
                            uiState.driveBackupStatus == DriveBackupStatus.ACTIVE -> "Drive Active"
                            uiState.driveBackupStatus == DriveBackupStatus.ERROR -> "Drive offline / retry queued"
                            else -> "Local export active • Drive permission needed"
                        }
                        val countsLine = summary?.let {
                            " • ${it.totalDeliveries} deliveries, ${it.totalPayments} payments, ${it.totalCustomers} customers"
                        }.orEmpty()

                        SettingsActionRow(
                            icon = Icons.Rounded.Backup,
                            title = "Backup",
                            subtitle = "$statusPrefix • Last: ${
                                DriveBackupManager.formatBackupTimestamp(uiState.driveLastBackupTimestamp)
                            }$countsLine",
                            enabled = !uiState.isDriveBackingUp,
                            iconTint = MaterialTheme.colorScheme.primary,
                            onClick = onBackupToDriveNow,
                            trailingContent = {
                                if (uiState.isDriveBackingUp) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("backup_now_button"),
                        )
                    }
                }

                if (showRestoreRow) {
                    item(key = "row_restore", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Restore,
                            title = "Restore",
                            subtitle = "Verify & restore from '${DriveBackupManager.BACKUP_FOLDER_NAME}' (${DriveBackupManager.BACKUP_FILE_NAME})",
                            enabled = !uiState.isDriveBackingUp,
                            onClick = onPreviewRestoreFromDrive,
                            modifier = Modifier.testTag("restore_from_drive_button"),
                        )
                    }
                }

                if (showAutoBackupRow) {
                    item(key = "row_auto_backup", contentType = "switch_row") {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.AutoMode,
                            title = "Automatic backup",
                            subtitle = "Automatically make a backup of everything when a new version of the application is found",
                            checked = uiState.autoBackupBeforeUpdate,
                            onCheckedChange = onToggleAutoBackupBeforeUpdate,
                            switchTestTag = "auto_backup_on_update_switch",
                        )
                    }
                }

                if (showBackupPathRow) {
                    item(key = "row_backup_path", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.FolderOpen,
                            title = "Backup path",
                            subtitle = "Google Drive: ${DriveBackupManager.BACKUP_FOLDER_NAME}\nLocal: ${DriveBackupManager.LOCAL_BACKUP_DISPLAY_PATH}",
                            onClick = {
                                copyToClipboard(
                                    context = context,
                                    label = "Minige Backup Path",
                                    text = "Drive: ${DriveBackupManager.BACKUP_FOLDER_NAME} | Local: ${DriveBackupManager.LOCAL_BACKUP_DISPLAY_PATH}",
                                    toastMessage = "Backup path copied",
                                )
                            },
                            modifier = Modifier.testTag("backup_path_row"),
                        )
                    }
                }

                item(key = "divider_backup", contentType = "divider") {
                    SettingsDivider(modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (showDiagnosticsCategory) {
                item(key = "header_diagnostics", contentType = "category_header") {
                    SettingsCategoryHeader(title = "Diagnostics & Logs")
                }

                item(key = "row_diagnostics", contentType = "diagnostics_row") {
                    val report = uiState.diagnosticReport
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingsActionRow(
                            icon = Icons.Rounded.HealthAndSafety,
                            title = "Logs / App Diagnostics",
                            subtitle = report?.let {
                                "5 checks evaluated • ${it.overallStatus.badgeLabel} (${it.generatedAtDisplay})"
                            } ?: "Read-only health check across auth, Drive backup, ledger math & permissions",
                            iconTint = MaterialTheme.colorScheme.primary,
                            onClick = {
                                showDiagnostics = !showDiagnostics
                                if (showDiagnostics && report == null && !uiState.isRunningDiagnostics) {
                                    onRunDiagnostics()
                                }
                            },
                            trailingContent = {
                                if (report != null) {
                                    DiagnosticStatusBadge(status = report.overallStatus)
                                } else {
                                    Icon(
                                        imageVector = if (showDiagnostics) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = "Toggle diagnostics",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("diagnostics_toggle_row"),
                        )

                        AnimatedVisibility(visible = showDiagnostics || report != null || normalizedQuery.isNotEmpty()) {
                            AppDiagnosticsPanel(
                                report = report,
                                isRunning = uiState.isRunningDiagnostics,
                                onRunDiagnostics = onRunDiagnostics,
                                onCopyReport = { text ->
                                    copyToClipboard(
                                        context = context,
                                        label = "Minige Diagnostic Report",
                                        text = text,
                                        toastMessage = "Diagnostic report copied (secrets redacted)",
                                    )
                                },
                            )
                        }
                    }
                }

                item(key = "divider_diagnostics", contentType = "divider") {
                    SettingsDivider(modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (showPreferencesCategory) {
                item(key = "header_preferences", contentType = "category_header") {
                    SettingsCategoryHeader(title = "Preferences")
                }

                if (showPricingRow) {
                    item(key = "row_pricing", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Payments,
                            title = "Pricing & Default Sukli",
                            subtitle = "Purified ₱${uiState.settings.purifiedPrice} • Alkaline ₱${uiState.settings.alkalinePrice} • Rate ₱${uiState.settings.commissionRate}/gal • Sukli ₱${uiState.settings.defaultSukli}",
                            onClick = { showPricingDialog = true },
                            modifier = Modifier.testTag("settings_pricing_row"),
                        )
                    }
                }

                if (showCommissionScheduleRow) {
                    item(key = "row_commission_schedule", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Percent,
                            title = "Commission Rate Schedule",
                            subtitle = "Active rate: ₱${CommissionRateHistory.formatRate(uiState.activeCommissionRate)}/gal • Schedule future rates",
                            onClick = { showCommissionDialog = true },
                            modifier = Modifier.testTag("settings_commission_schedule_row"),
                        )
                    }
                }

                if (showQuotaRow) {
                    item(key = "row_quota", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.TrackChanges,
                            title = "Daily Quota Target",
                            subtitle = "${uiState.dailyQuotaTarget} paid gallons per day",
                            onClick = { showQuotaDialog = true },
                            modifier = Modifier.testTag("settings_quota_row"),
                        )
                    }
                }

                if (showSundayRow) {
                    item(key = "row_sunday_makeup", contentType = "switch_row") {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.EventAvailable,
                            title = "Sunday Makeup Work Mode",
                            subtitle = "Include Sunday deliveries & commissions in this week's tally",
                            checked = uiState.sundayMakeupEnabled,
                            onCheckedChange = onToggleSundayMakeup,
                            switchTestTag = "settings_sunday_makeup_switch",
                        )
                    }
                }

                if (showCloudSyncRow) {
                    item(key = "row_cloud_sync", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.CloudSync,
                            title = if (uiState.isSyncing) "Syncing Cloud Data..." else "Cloud Sync Now",
                            subtitle = "${userEmail ?: "Google Account"} • Pending offline: ${uiState.pendingSyncCount}",
                            enabled = !uiState.isSyncing,
                            onClick = onSyncNow,
                            trailingContent = {
                                if (uiState.isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            modifier = Modifier.testTag("cloud_sync_now_button"),
                        )
                    }
                }

                if (showMicRow) {
                    item(key = "row_mic_permission", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Mic,
                            title = "Enable Microphone",
                            subtitle = "Allow quick voice input in delivery forms",
                            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.testTag("enable_mic_row"),
                        )
                    }
                }

                if (showSignOutRow) {
                    item(key = "row_sign_out", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            title = "Sign Out",
                            subtitle = "Switch Google account on this device",
                            iconTint = MaterialTheme.colorScheme.error,
                            onClick = onSignOut,
                            modifier = Modifier.testTag("sign_out_button"),
                        )
                    }
                }

                item(key = "divider_preferences", contentType = "divider") {
                    SettingsDivider(modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (showAboutCategory) {
                item(key = "header_about", contentType = "category_header") {
                    SettingsCategoryHeader(title = "About")
                }

                if (showVersionRow) {
                    item(key = "row_about_version", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Info,
                            title = "App Version",
                            subtitle = "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
                            onClick = {
                                val nextTap = versionTapCount + 1
                                if (nextTap >= DeveloperSignatureConfig.EASTER_EGG_TAPS) {
                                    versionTapCount = 0
                                    Toast.makeText(
                                        context,
                                        DeveloperSignatureConfig.EASTER_EGG_MESSAGE,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    versionTapCount = nextTap
                                }
                            },
                            modifier = Modifier.testTag("about_version_row"),
                        )
                    }
                }

                if (showPackageRow) {
                    item(key = "row_about_package", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Android,
                            title = "Package Name",
                            subtitle = BuildConfig.APPLICATION_ID,
                            onClick = {
                                copyToClipboard(
                                    context = context,
                                    label = "Minige Package Name",
                                    text = BuildConfig.APPLICATION_ID,
                                    toastMessage = "Package name copied",
                                )
                            },
                            modifier = Modifier.testTag("about_package_row"),
                        )
                    }
                }

                if (showSourceRow) {
                    item(key = "row_about_source", contentType = "action_row") {
                        SettingsActionRow(
                            icon = Icons.Rounded.Code,
                            title = "Source Code",
                            subtitle = DriveBackupManager.GITHUB_SOURCE_CODE_URL,
                            onClick = { openUrl(context, DriveBackupManager.GITHUB_SOURCE_CODE_URL) },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = "Open source repository",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.testTag("about_source_code_row"),
                        )
                    }
                }
            }

            if (!showUpdatingCategory && !showBackupCategory && !showDiagnosticsCategory &&
                !showPreferencesCategory && !showAboutCategory
            ) {
                item(key = "settings_no_match", contentType = "empty_search") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "No matching settings",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Try searching for update, backup, diagnostics, rate, quota, or about.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "developer_signature_card", contentType = "developer_signature") {
                DeveloperSignatureCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (showPricingDialog) {
        PricingSettingsDialog(
            initialPurified = uiState.settings.purifiedPrice,
            initialAlkaline = uiState.settings.alkalinePrice,
            initialRate = uiState.settings.commissionRate,
            initialSukli = uiState.settings.defaultSukli,
            onDismiss = { showPricingDialog = false },
            onSave = onSaveSettings,
        )
    }

    if (showCommissionDialog) {
        CommissionScheduleDialog(
            currentRate = uiState.activeCommissionRate,
            history = uiState.commissionRateHistory,
            onScheduleRateChange = onScheduleCommissionRate,
            onDismiss = { showCommissionDialog = false },
        )
    }

    if (showQuotaDialog) {
        DailyQuotaDialog(
            currentQuota = uiState.dailyQuotaTarget,
            onDismiss = { showQuotaDialog = false },
            onSave = onUpdateQuotaTarget,
        )
    }

    val updateRelease = pendingUpdateRelease
    if (updateRelease != null) {
        UpdateAvailableDialog(
            release = updateRelease,
            autoBackupBeforeUpdate = uiState.autoBackupBeforeUpdate,
            isDownloading = isDownloadingUpdate,
            progressPercent = downloadProgressPercent,
            progressStatus = downloadProgressStatus,
            onOpenChangelog = { url -> openUrl(context, url) },
            onDownloadAndInstall = {
                if (isDownloadingUpdate) return@UpdateAvailableDialog
                isDownloadingUpdate = true
                downloadProgressPercent = 0
                scope.launch {
                    if (uiState.autoBackupBeforeUpdate) {
                        downloadProgressStatus = "Backing up data before update..."
                        updateStatusText = downloadProgressStatus
                        val backupNote = onBackupBeforeUpdate()
                        updateStatusText = backupNote
                    }
                    downloadProgressStatus = "Starting download..."
                    GitHubAppUpdater.startDownloadAndInstall(
                        context = context,
                        release = updateRelease,
                        onStatus = { status ->
                            downloadProgressStatus = status
                            updateStatusText = status
                        },
                        onProgress = { pct, status ->
                            downloadProgressPercent = pct
                            downloadProgressStatus = status
                        },
                        onFinished = {
                            isDownloadingUpdate = false
                            pendingUpdateRelease = null
                        },
                    )
                }
            },
            onDismiss = { pendingUpdateRelease = null },
        )
    }

    val preview = uiState.driveBackupPreview
    if (uiState.showRestorePreviewDialog && preview != null) {
        VerifyDriveRestoreDialog(
            preview = preview,
            onConfirm = onConfirmRestoreFromDrive,
            onDismiss = onDismissRestorePreview,
        )
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Unable to open link.", Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    toastMessage: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}
