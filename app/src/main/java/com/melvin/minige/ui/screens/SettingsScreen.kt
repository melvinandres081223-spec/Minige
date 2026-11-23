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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.BuildConfig
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.DriveBackupStatus
import com.melvin.minige.sync.GitHubAppUpdater
import com.melvin.minige.sync.GitHubReleaseInfo
import com.melvin.minige.sync.UpdateCheckOutcome
import com.melvin.minige.ui.MinigeUiState
import com.melvin.minige.ui.components.CommissionRateCard
import com.melvin.minige.ui.components.DeveloperSignatureCard
import com.melvin.minige.util.AppDiagnosticReport
import com.melvin.minige.util.DiagnosticCheckItem
import com.melvin.minige.util.DiagnosticStatus
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(
    uiState: MinigeUiState,
    userEmail: String?,
    onSaveSettings: (purified: Int, alkaline: Int, rate: Int, defaultSukli: Int) -> Unit,
    onScheduleCommissionRate: (ratePerGallon: Double, effectiveDate: LocalDate) -> Unit,
    onSyncNow: () -> Unit,
    onGrantDriveAccess: () -> Unit = {},
    onBackupToDriveNow: () -> Unit = {},
    onPreviewRestoreFromDrive: () -> Unit = {},
    onConfirmRestoreFromDrive: () -> Unit = {},
    onDismissRestorePreview: () -> Unit = {},
    onRunDiagnostics: () -> Unit = {},
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isCheckingOrDownloadingUpdate by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var pendingUpdateRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgressPercent by remember { mutableIntStateOf(0) }
    var downloadProgressStatus by remember { mutableStateOf("Ready to download") }

    var purifiedText by remember(uiState.settings.purifiedPrice) {
        mutableStateOf(uiState.settings.purifiedPrice.toString())
    }
    var alkalineText by remember(uiState.settings.alkalinePrice) {
        mutableStateOf(uiState.settings.alkalinePrice.toString())
    }
    var rateText by remember(uiState.settings.commissionRate) {
        mutableStateOf(uiState.settings.commissionRate.toString())
    }
    var defaultSukliText by remember(uiState.settings.defaultSukli) {
        mutableStateOf(uiState.settings.defaultSukli.toString())
    }

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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "pricing_commission_card", contentType = "pricing_card") {
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Pricing & Commission", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Rate edits apply only to future logs — past commission stays unchanged.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = purifiedText,
                            onValueChange = { purifiedText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Purified Unit Price (₱)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("settings_purified_input"),
                        )

                        OutlinedTextField(
                            value = alkalineText,
                            onValueChange = { alkalineText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Alkaline Unit Price (₱)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("settings_alkaline_input"),
                        )

                        OutlinedTextField(
                            value = rateText,
                            onValueChange = { rateText = it.filter(Char::isDigit).take(4) },
                            label = { Text("Commission per Gallon (₱)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("settings_rate_input"),
                        )

                        OutlinedTextField(
                            value = defaultSukliText,
                            onValueChange = { defaultSukliText = it.filter(Char::isDigit).take(6) },
                            label = { Text("Default Starting Sukli (₱)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("settings_sukli_input"),
                        )

                        Button(
                            onClick = {
                                onSaveSettings(
                                    purifiedText.toIntOrNull() ?: 30,
                                    alkalineText.toIntOrNull() ?: 40,
                                    rateText.toIntOrNull() ?: 8,
                                    defaultSukliText.toIntOrNull() ?: 0,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_settings_button"),
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Settings")
                        }
                    }
                }
            }

            item(key = "commission_rate_card", contentType = "commission_rate_card") {
                CommissionRateCard(
                    currentRate = uiState.activeCommissionRate,
                    history = uiState.commissionRateHistory,
                    onScheduleRateChange = onScheduleCommissionRate,
                )
            }

            item(key = "cloud_sync_permissions_card", contentType = "cloud_sync_card") {
                OutlinedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Cloud Sync & Permissions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Signed in: ${userEmail ?: "Google Account"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Pending offline items: ${uiState.pendingSyncCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Button(
                            onClick = onSyncNow,
                            enabled = !uiState.isSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("cloud_sync_now_button"),
                        ) {
                            Icon(Icons.Rounded.CloudSync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isSyncing) "Syncing..." else "Sync Now",
                                maxLines = 1,
                                softWrap = false,
                            )
                        }

                        if (!hasMicPermission) {
                            OutlinedButton(
                                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enable Mic", maxLines = 1, softWrap = false)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                if (isCheckingOrDownloadingUpdate) return@OutlinedButton
                                isCheckingOrDownloadingUpdate = true
                                updateStatusText = "Checking for updates..."
                                Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()

                                scope.launch {
                                    when (val outcome = GitHubAppUpdater.checkForUpdate(context)) {
                                        is UpdateCheckOutcome.AlreadyUpToDate -> {
                                            isCheckingOrDownloadingUpdate = false
                                            updateStatusText = "Minige is up to date! (v${BuildConfig.VERSION_NAME})"
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
                            enabled = !isCheckingOrDownloadingUpdate,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("check_updates_button"),
                        ) {
                            if (isCheckingOrDownloadingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isCheckingOrDownloadingUpdate) (updateStatusText ?: "Checking...")
                                else "Check for App Updates",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (!updateStatusText.isNullOrBlank()) {
                            Text(
                                text = updateStatusText ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("update_status_text"),
                            )
                        }

                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("sign_out_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }

            item(key = "whats_new_card", contentType = "whats_new_card") {
                OutlinedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("whats_new_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NewReleases,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "What's New in Minige",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = GitHubAppUpdater.RELEASE_NOTES_ATTRIBUTION,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("whats_new_attribution"),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        val whatsNewBullets = remember(pendingUpdateRelease, updateStatusText) {
                            GitHubAppUpdater.getWhatsNewForCurrentVersion(context)
                        }
                        val cachedChangelogUrl = remember(pendingUpdateRelease, updateStatusText) {
                            GitHubAppUpdater.getCachedChangelogUrl(context)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            whatsNewBullets.forEach { bullet ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = "✓",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = bullet,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { openChangelogUrl(context, cachedChangelogUrl) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("view_full_changelog_card_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "View Full Changelog",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            item(key = "google_drive_backup_card", contentType = "drive_backup_card") {
                OutlinedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drive_backup_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Google Drive Auto-Backup (15 GB)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        // Live status badge row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (uiState.isDriveBackingUp || uiState.driveBackupStatus == DriveBackupStatus.BACKING_UP) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Backing up...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                val (dotColor, statusLabel) = when (uiState.driveBackupStatus) {
                                    DriveBackupStatus.ACTIVE -> Color(0xFF1E9E52) to "Active (Auto-syncs on changes)"
                                    DriveBackupStatus.NEEDS_PERMISSION -> Color(0xFFE59200) to "Needs Drive Permission"
                                    DriveBackupStatus.ERROR -> MaterialTheme.colorScheme.error to "Error (Will auto-retry)"
                                    DriveBackupStatus.BACKING_UP -> MaterialTheme.colorScheme.primary to "Backing up..."
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = dotColor,
                                )
                            }
                        }

                        Text(
                            text = "Last backup: ${DriveBackupManager.formatBackupTimestamp(uiState.driveLastBackupTimestamp)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Compact status line displaying version & record counts read from minige_auto_backup.json summary
                        val summary = uiState.driveBackupSummary
                        val recordSummaryText = if (summary != null) {
                            "v${summary.appVersion} • ${summary.totalDeliveries} deliveries, ${summary.totalPayments} payments, ${summary.totalExpenses} expenses, ${summary.totalCustomers} customers"
                        } else {
                            "v${BuildConfig.VERSION_NAME} • No backup summary cached yet"
                        }
                        Text(
                            text = recordSummaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Text(
                            text = "Naka-save sa nakikitang folder na 'Minige Backup' sa iyong Google Drive (My Drive) bilang 'minige_auto_backup.json'. Ligtas, madaling basahin, at puwedeng i-export sa ibang app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (uiState.driveBackupStatus == DriveBackupStatus.NEEDS_PERMISSION) {
                            Button(
                                onClick = onGrantDriveAccess,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("grant_drive_access_button"),
                            ) {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Drive Access")
                            }
                        }

                        Button(
                            onClick = onBackupToDriveNow,
                            enabled = !uiState.isDriveBackingUp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("backup_now_button"),
                        ) {
                            if (uiState.isDriveBackingUp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Backing up to Drive...")
                            } else {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Backup Now")
                            }
                        }

                        OutlinedButton(
                            onClick = onPreviewRestoreFromDrive,
                            enabled = !uiState.isDriveBackingUp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("restore_from_drive_button"),
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore from Drive")
                        }
                    }
                }
            }

            item(key = "app_diagnostics_card", contentType = "diagnostics_card") {
                AppDiagnosticsCard(
                    report = uiState.diagnosticReport,
                    isRunning = uiState.isRunningDiagnostics,
                    onRunDiagnostics = onRunDiagnostics,
                    onCopyReport = { reportText ->
                        copyDiagnosticReport(context, reportText)
                    },
                )
            }

            item(key = "developer_signature_card", contentType = "developer_signature") {
                DeveloperSignatureCard()
            }
        }
    }

    // Update Available Dialog with Release Notes and Live Download Progress
    val updateRelease = pendingUpdateRelease
    if (updateRelease != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) {
                    pendingUpdateRelease = null
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Update Available",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = updateRelease.tagName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = GitHubAppUpdater.RELEASE_NOTES_ATTRIBUTION,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("dialog_release_notes_attribution"),
                    )

                    val bullets = GitHubAppUpdater.formatReleaseNotes(updateRelease.releaseNotes)
                    val changelogUrl = GitHubAppUpdater.extractChangelogUrl(
                        rawBody = updateRelease.releaseNotes,
                        tagName = updateRelease.tagName,
                    )
                    Text(
                        text = "What's New in this Version:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val displayBullets = bullets.ifEmpty { listOf(GitHubAppUpdater.FALLBACK_RELEASE_NOTE) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        displayBullets.forEach { note ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = "•",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = { openChangelogUrl(context, changelogUrl) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("view_full_changelog_dialog_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "View Full Changelog",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    if (isDownloadingUpdate) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (downloadProgressPercent > 0) {
                                LinearProgressIndicator(
                                    progress = { downloadProgressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                            }
                            Text(
                                text = downloadProgressStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isDownloadingUpdate) return@Button
                        isDownloadingUpdate = true
                        downloadProgressPercent = 0
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
                    },
                    enabled = !isDownloadingUpdate,
                    modifier = Modifier.testTag("download_and_install_button"),
                ) {
                    if (isDownloadingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (downloadProgressPercent > 0) "$downloadProgressPercent%" else "Downloading...")
                    } else {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download & Install")
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(
                        onClick = { pendingUpdateRelease = null },
                        modifier = Modifier.testTag("dismiss_update_dialog"),
                    ) {
                        Text("Later")
                    }
                }
            },
        )
    }

    // Rich Restore Confirmation Dialog displaying timestamp, record counts, and preferences from minige_auto_backup.json
    val preview = uiState.driveBackupPreview
    if (uiState.showRestorePreviewDialog && preview != null) {
        AlertDialog(
            onDismissRequest = onDismissRestorePreview,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text("Verify Drive Backup")
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "I-re-restore ang data mula sa Google Drive 'Minige Backup' folder (${DriveBackupManager.BACKUP_FILE_NAME}):",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "• Backup Time: ${DriveBackupManager.formatBackupTimestamp(preview.backupTimestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "• App Version: v${preview.metadata.appVersion} (Code ${preview.metadata.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "• Records: ${preview.deliveryCount} deliveries, ${preview.paymentCount} payments, ${preview.expenseCount} expenses, ${preview.customerCount} customers",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "• Preferences: Quota ${preview.preferences.dailyQuotaTarget} gal, Commission ₱${preview.preferences.commissionRate}/gal, Sunday Makeup: ${if (preview.preferences.sundayMakeupEnabled) "Yes" else "No"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "I-overwrite/upsert nito ang kasalukuyang local records at preferences. Nais mo bang magpatuloy?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmRestoreFromDrive,
                    modifier = Modifier.testTag("confirm_restore_drive_dialog"),
                ) {
                    Text("Proceed Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestorePreview) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppDiagnosticsCard(
    report: AppDiagnosticReport?,
    isRunning: Boolean,
    onRunDiagnostics: () -> Unit,
    onCopyReport: (String) -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_diagnostics_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.HealthAndSafety,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "App Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Read-only health check across auth, Drive backup, ledger math & permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (report != null) {
                    DiagnosticStatusBadge(status = report.overallStatus)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onRunDiagnostics,
                    enabled = !isRunning,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("run_diagnostics_button"),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Running...", maxLines = 1, softWrap = false)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Diagnostics", maxLines = 1, softWrap = false)
                    }
                }

                OutlinedButton(
                    onClick = { report?.let { onCopyReport(it.formattedReportText) } },
                    enabled = report != null && !isRunning,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("copy_diagnostics_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Diagnostic Report", maxLines = 1, softWrap = false)
                }
            }

            if (report != null) {
                Text(
                    text = "Last checked: ${report.generatedAtDisplay}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report.items.forEach { item ->
                        DiagnosticCheckRow(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCheckRow(item: DiagnosticCheckItem) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostic_item_${item.id}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                DiagnosticStatusBadge(status = item.status)
            }
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.detail.isNotBlank()) {
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticStatusBadge(status: DiagnosticStatus) {
    val (containerColor, contentColor, borderColor) = when (status) {
        DiagnosticStatus.PASS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
        DiagnosticStatus.WARN -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
        )
        DiagnosticStatus.FAIL -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        )
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = status.badgeLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun openChangelogUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Unable to open changelog link.", Toast.LENGTH_SHORT).show()
    }
}

private fun copyDiagnosticReport(context: Context, reportText: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Minige Diagnostic Report", reportText))
    Toast.makeText(context, "Diagnostic report copied (secrets redacted)", Toast.LENGTH_SHORT).show()
}

