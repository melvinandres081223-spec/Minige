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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.BuildConfig
import com.melvin.minige.domain.InactiveSeverity
import com.melvin.minige.domain.InactiveSukiAlert
import com.melvin.minige.domain.MinigeCustomerUtils
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
import com.melvin.minige.ui.components.DiagnosticStatusBadge
import com.melvin.minige.ui.components.InactiveSukiAlertPrefs
import com.melvin.minige.ui.components.InactiveSukiAlertRow
import com.melvin.minige.ui.components.PricingSettingsDialog
import com.melvin.minige.ui.components.SettingsActionRow
import com.melvin.minige.ui.components.SettingsDivider
import com.melvin.minige.ui.components.SettingsSearchBar
import com.melvin.minige.ui.components.SettingsSwitchRow
import com.melvin.minige.ui.components.UpdateAvailableDialog
import com.melvin.minige.ui.components.VerifyDriveRestoreDialog
import com.melvin.minige.ui.components.WhatsNewBlock
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.DiagnosticStatus
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(
    uiState: MinigeUiState,
    userEmail: String?,
    onSaveSettings: (purified: Int, alkaline: Int, rate: Int, defaultSukli: Int) -> Unit,
    onSaveSmallGallonSettings: (smallPrice: Double, smallCommissionRate: Double) -> Unit = { _, _ -> },
    onScheduleCommissionRate: (ratePerGallon: Double, effectiveDate: LocalDate) -> Unit,
    onUpdateQuotaTarget: (Int) -> Unit = {},
    onToggleSundayMakeup: (Boolean) -> Unit = {},
    onToggleInactiveAlerts: (Boolean) -> Unit = {},
    onUpdateInactiveThresholdDays: (Int) -> Unit = {},
    onReorderInactiveSuki: (InactiveSukiAlert) -> Unit = {},
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

    // Accordion states: Cards 1–4 collapsed by default, Card 5 expanded by default
    var expandInactiveAccordion by rememberSaveable { mutableStateOf(false) }
    var expandUpdatesAccordion by rememberSaveable { mutableStateOf(false) }
    var expandBackupSyncAccordion by rememberSaveable { mutableStateOf(false) }
    var expandDiagnosticsAccordion by rememberSaveable { mutableStateOf(false) }
    var expandPreferencesAccordion by rememberSaveable { mutableStateOf(true) }

    var isCheckingOrDownloadingUpdate by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var pendingUpdateRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgressPercent by remember { mutableIntStateOf(0) }
    var downloadProgressStatus by remember { mutableStateOf("Ready to download") }

    var showPricingDialog by remember { mutableStateOf(false) }
    var showCommissionDialog by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }

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

    val showInactiveAccordion = matches(
        "walang karga",
        "inactive",
        "suki",
        "overdue",
        "customer",
        "alerts",
        "days",
        "threshold",
        "reorder",
        "re-order",
    ) || uiState.inactiveSukiAlerts.any { it.customerName.contains(normalizedQuery, ignoreCase = true) }

    val showUpdatesAccordion = matches(
        "updates",
        "update",
        "check",
        "apk",
        "version",
        "github",
        "what's new",
        "release notes",
        "vin",
        "changelog",
    )

    val showBackupSyncAccordion = matches(
        "backup",
        "restore",
        "drive",
        "cloud",
        "sync",
        "firebase",
        "auto",
        "automatic",
        "offline",
        "pending",
        "grant",
        "permission",
    )

    val showDiagnosticsAccordion = matches(
        "diagnostics",
        "logs",
        "health",
        "check",
        "report",
        "redact",
        "bug",
    )

    val showPreferencesAccordion = matches(
        "pricing",
        "purified",
        "alkaline",
        "sukli",
        "commission",
        "maliit",
        "small",
        "galon",
        "rate",
        "schedule",
        "quota",
        "target",
        "gallons",
        "daily",
        "sunday",
        "makeup",
        "weekly",
        "mic",
        "microphone",
        "voice",
        "preferences",
    )

    val showSignOutRow = matches("sign out", "logout", "account", userEmail ?: "")

    val isSearching = normalizedQuery.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "settings_search_bar", contentType = "search_bar") {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            // 1. ⚠️ 3+ Days Walang Karga (X suki) — collapsed by default
            if (showInactiveAccordion) {
                item(key = "accordion_inactive_suki", contentType = "accordion_card") {
                    val hasCritical = uiState.inactiveSukiAlerts.any {
                        it.severity == InactiveSeverity.RED_CRITICAL
                    }
                    val borderColor = when {
                        !uiState.inactiveAlertsEnabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        hasCritical -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                        uiState.inactiveSukiCount > 0 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    }
                    val subtitle = when {
                        !uiState.inactiveAlertsEnabled ->
                            "Alerts off • Threshold: ${uiState.inactiveThresholdDays}d"
                        uiState.inactiveSukiCount > 0 -> {
                            val longest = uiState.inactiveSukiAlerts.maxOf { it.daysSinceLastDelivery }
                            "${uiState.inactiveSukiCount} suki overdue • Pinakamatagal: ${longest}d"
                        }
                        else -> "All suki active (${uiState.inactiveThresholdDays}d+ threshold)"
                    }

                    SettingsAccordionCard(
                        title = "⚠️ ${uiState.inactiveThresholdDays}+ Days Walang Karga (${uiState.inactiveSukiCount} suki)",
                        subtitle = subtitle,
                        expanded = expandInactiveAccordion || isSearching,
                        onToggle = { expandInactiveAccordion = !expandInactiveAccordion },
                        borderColor = borderColor,
                        cardTestTag = "settings_accordion_inactive_suki",
                        headerTestTag = "settings_accordion_inactive_suki_header",
                    ) {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.NotificationsActive,
                            title = "Walang Karga Alerts",
                            subtitle = "Flag suki without orders after threshold",
                            checked = uiState.inactiveAlertsEnabled,
                            onCheckedChange = onToggleInactiveAlerts,
                            switchTestTag = "inactive_alerts_enabled_switch",
                        )

                        if (uiState.inactiveAlertsEnabled) {
                            SettingsDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 56.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Overdue Threshold",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    )
                                    Text(
                                        text = "Flag after ${uiState.inactiveThresholdDays} day(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            onUpdateInactiveThresholdDays(
                                                (uiState.inactiveThresholdDays - 1)
                                                    .coerceAtLeast(InactiveSukiAlertPrefs.MIN_THRESHOLD_DAYS)
                                            )
                                        },
                                        enabled = uiState.inactiveThresholdDays > InactiveSukiAlertPrefs.MIN_THRESHOLD_DAYS,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("inactive_threshold_minus_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Remove,
                                            contentDescription = "Decrease overdue threshold",
                                        )
                                    }
                                    Text(
                                        text = "${uiState.inactiveThresholdDays}d",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .testTag("inactive_threshold_value_text"),
                                    )
                                    FilledTonalIconButton(
                                        onClick = {
                                            onUpdateInactiveThresholdDays(
                                                (uiState.inactiveThresholdDays + 1)
                                                    .coerceAtMost(InactiveSukiAlertPrefs.MAX_THRESHOLD_DAYS)
                                            )
                                        },
                                        enabled = uiState.inactiveThresholdDays < InactiveSukiAlertPrefs.MAX_THRESHOLD_DAYS,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("inactive_threshold_plus_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = "Increase overdue threshold",
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (uiState.inactiveSukiAlerts.isNotEmpty()) {
                                    uiState.inactiveSukiAlerts.forEach { alert ->
                                        InactiveSukiAlertRow(
                                            alert = alert,
                                            onReorderClick = { onReorderInactiveSuki(alert) },
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("inactive_suki_all_active_row"),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircleOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = "All suki are active — no ${uiState.inactiveThresholdDays}+ day overdue orders.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 🔄 Updates & What's New (vX.Y.Z) — collapsed by default
            if (showUpdatesAccordion) {
                item(key = "accordion_updates", contentType = "accordion_card") {
                    SettingsAccordionCard(
                        title = "🔄 Updates & What's New (v${BuildConfig.VERSION_NAME})",
                        subtitle = updateStatusText ?: GitHubAppUpdater.RELEASE_NOTES_ATTRIBUTION,
                        expanded = expandUpdatesAccordion || isSearching,
                        onToggle = { expandUpdatesAccordion = !expandUpdatesAccordion },
                        cardTestTag = "settings_accordion_updates",
                        headerTestTag = "settings_accordion_updates_header",
                    ) {
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
                        SettingsDivider()
                        WhatsNewBlock(
                            bullets = whatsNewBullets,
                            onViewFullChangelog = { openUrl(context, cachedChangelogUrl) },
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            // 3. ☁️ Backup & Cloud Sync — collapsed by default
            if (showBackupSyncAccordion) {
                item(key = "accordion_backup_sync", contentType = "accordion_card") {
                    val driveShortStatus = when {
                        uiState.isDriveBackingUp || uiState.driveBackupStatus == DriveBackupStatus.BACKING_UP ->
                            "Backing up..."
                        uiState.driveBackupStatus == DriveBackupStatus.ACTIVE -> "Drive Active"
                        uiState.driveBackupStatus == DriveBackupStatus.ERROR -> "Drive retry queued"
                        else -> "Needs Drive Permission"
                    }
                    val lastBackupText = DriveBackupManager.formatBackupTimestamp(uiState.driveLastBackupTimestamp)
                    val summaryCounts = uiState.driveBackupSummary?.let {
                        " • ${it.totalDeliveries} deliveries, ${it.totalPayments} payments"
                    }.orEmpty()

                    SettingsAccordionCard(
                        title = "☁️ Backup & Cloud Sync",
                        subtitle = "$driveShortStatus • Last: $lastBackupText • Pending: ${uiState.pendingSyncCount}",
                        expanded = expandBackupSyncAccordion || isSearching,
                        onToggle = { expandBackupSyncAccordion = !expandBackupSyncAccordion },
                        cardTestTag = "settings_accordion_backup_sync",
                        headerTestTag = "settings_accordion_backup_sync_header",
                    ) {
                        if (uiState.driveBackupStatus == DriveBackupStatus.NEEDS_PERMISSION) {
                            SettingsActionRow(
                                icon = Icons.Rounded.CloudUpload,
                                title = "Grant Google Drive Access",
                                subtitle = "Save backups in My Drive '${DriveBackupManager.BACKUP_FOLDER_NAME}'",
                                iconTint = MaterialTheme.colorScheme.tertiary,
                                onClick = onGrantDriveAccess,
                                modifier = Modifier.testTag("grant_drive_access_button"),
                            )
                            SettingsDivider()
                        }

                        SettingsActionRow(
                            icon = Icons.Rounded.Backup,
                            title = "Backup",
                            subtitle = "$driveShortStatus • Last: $lastBackupText$summaryCounts",
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
                        SettingsDivider()

                        SettingsActionRow(
                            icon = Icons.Rounded.Restore,
                            title = "Restore",
                            subtitle = "Verify & restore from '${DriveBackupManager.BACKUP_FOLDER_NAME}'",
                            enabled = !uiState.isDriveBackingUp,
                            onClick = onPreviewRestoreFromDrive,
                            modifier = Modifier.testTag("restore_from_drive_button"),
                        )
                        SettingsDivider()

                        SettingsSwitchRow(
                            icon = Icons.Rounded.AutoMode,
                            title = "Automatic backup before update",
                            subtitle = "Back up data automatically when an update is found",
                            checked = uiState.autoBackupBeforeUpdate,
                            onCheckedChange = onToggleAutoBackupBeforeUpdate,
                            switchTestTag = "auto_backup_on_update_switch",
                        )
                        SettingsDivider()

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
            }

            // 4. 🩺 App Diagnostics — collapsed by default; runs on tap
            if (showDiagnosticsAccordion) {
                item(key = "accordion_diagnostics", contentType = "accordion_card") {
                    val report = uiState.diagnosticReport
                    val diagBorderColor = when (report?.overallStatus) {
                        DiagnosticStatus.FAIL -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                        DiagnosticStatus.WARN -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    }

                    SettingsAccordionCard(
                        title = "🩺 App Diagnostics",
                        subtitle = report?.let {
                            "5 checks • ${it.overallStatus.badgeLabel} (${it.generatedAtDisplay})"
                        } ?: "Tap to run 5-point health check & copy report",
                        expanded = expandDiagnosticsAccordion || isSearching,
                        onToggle = {
                            val next = !expandDiagnosticsAccordion
                            expandDiagnosticsAccordion = next
                            if (next && report == null && !uiState.isRunningDiagnostics) {
                                onRunDiagnostics()
                            }
                        },
                        borderColor = diagBorderColor,
                        trailingBadge = {
                            if (report != null) {
                                DiagnosticStatusBadge(status = report.overallStatus)
                            }
                        },
                        cardTestTag = "settings_accordion_diagnostics",
                        headerTestTag = "diagnostics_toggle_row",
                    ) {
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
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }

            // 5. ⚙️ Pricing, Commission & Quota — expanded by default
            if (showPreferencesAccordion) {
                item(key = "accordion_preferences", contentType = "accordion_card") {
                    val formattedRate = CommissionRateHistory.formatRate(uiState.activeCommissionRate)
                    SettingsAccordionCard(
                        title = "⚙️ Pricing, Commission & Quota",
                        subtitle = "Purified ₱${uiState.settings.purifiedPrice} • Comm ₱$formattedRate/gal • Quota ${uiState.dailyQuotaTarget}g",
                        expanded = expandPreferencesAccordion || isSearching,
                        onToggle = { expandPreferencesAccordion = !expandPreferencesAccordion },
                        cardTestTag = "settings_accordion_preferences",
                        headerTestTag = "settings_accordion_preferences_header",
                    ) {
                        SettingsActionRow(
                            icon = Icons.Rounded.Payments,
                            title = "Pricing & Default Sukli",
                            subtitle = "Purified ₱${uiState.settings.purifiedPrice} • Alkaline ₱${uiState.settings.alkalinePrice} • Maliit ₱${MinigeCustomerUtils.formatPrice(uiState.smallGallonPrice)} • Sukli ₱${uiState.settings.defaultSukli}",
                            onClick = { showPricingDialog = true },
                            modifier = Modifier.testTag("settings_pricing_row"),
                        )
                        SettingsDivider()

                        SettingsActionRow(
                            icon = Icons.Rounded.Percent,
                            title = "Commission Rate Schedule",
                            subtitle = "Active rate: ₱$formattedRate/gal • Schedule future rates",
                            onClick = { showCommissionDialog = true },
                            modifier = Modifier.testTag("settings_commission_schedule_row"),
                        )
                        SettingsDivider()

                        SettingsActionRow(
                            icon = Icons.Rounded.TrackChanges,
                            title = "Daily Quota Target",
                            subtitle = "${uiState.dailyQuotaTarget} gallons per day",
                            onClick = { showQuotaDialog = true },
                            modifier = Modifier.testTag("settings_quota_row"),
                        )
                        SettingsDivider()

                        SettingsSwitchRow(
                            icon = Icons.Rounded.EventAvailable,
                            title = "Sunday Makeup Work Mode",
                            subtitle = "Include Sunday deliveries & commissions in this week's tally",
                            checked = uiState.sundayMakeupEnabled,
                            onCheckedChange = onToggleSundayMakeup,
                            switchTestTag = "settings_sunday_makeup_switch",
                        )

                        if (!hasMicPermission) {
                            SettingsDivider()
                            SettingsActionRow(
                                icon = Icons.Rounded.Mic,
                                title = "Enable Microphone",
                                subtitle = "Allow quick voice input in delivery forms",
                                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.testTag("enable_mic_row"),
                            )
                        }
                    }
                }
            }

            if (!showInactiveAccordion && !showUpdatesAccordion && !showBackupSyncAccordion &&
                !showDiagnosticsAccordion && !showPreferencesAccordion && !showSignOutRow
            ) {
                item(key = "settings_no_match", contentType = "empty_search") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "No matching settings",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Try searching for walang karga, update, backup, diagnostics, rate, or quota.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Compact bottom Sign Out row + Developer attribution card (preserves 5-tap secret gesture)
            if (showSignOutRow) {
                item(key = "row_sign_out_compact", contentType = "sign_out_card") {
                    OutlinedCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SettingsActionRow(
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            title = "Sign Out",
                            subtitle = userEmail ?: "Switch Google account on this device",
                            iconTint = MaterialTheme.colorScheme.error,
                            onClick = onSignOut,
                            modifier = Modifier.testTag("sign_out_button"),
                        )
                    }
                }
            }

            item(key = "developer_signature_card", contentType = "developer_signature") {
                DeveloperSignatureCard(
                    modifier = Modifier.padding(top = 4.dp),
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
            initialSmallGallonPrice = uiState.smallGallonPrice,
            initialSmallGallonCommissionRate = uiState.smallGallonCommissionRate,
            onDismiss = { showPricingDialog = false },
            onSave = onSaveSettings,
            onSaveSmallGallon = onSaveSmallGallonSettings,
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

@Composable
private fun SettingsAccordionCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    cardTestTag: String,
    headerTestTag: String,
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    trailingBadge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag(cardTestTag),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 60.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag(headerTestTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (trailingBadge != null) {
                    trailingBadge()
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse section" else "Expand section",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    content()
                }
            }
        }
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
