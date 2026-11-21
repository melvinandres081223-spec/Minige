package com.melvin.minige.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.sync.GitHubAppUpdater
import com.melvin.minige.sync.UpdateCheckOutcome
import com.melvin.minige.ui.MinigeUiState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    uiState: MinigeUiState,
    userEmail: String?,
    onSaveSettings: (purified: Int, alkaline: Int, rate: Int, defaultSukli: Int) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isCheckingOrDownloadingUpdate by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }

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
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
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
                            text = "Editing commission rate applies only to new deliveries and newly settled utang — historical daily commission totals stay intact.",
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

            item {
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
                            text = "Signed in as: ${userEmail ?: "Google Account"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Pending offline sync items: ${uiState.pendingSyncCount}",
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
                            Text(if (uiState.isSyncing) "Syncing..." else "Sync Pending Changes to Cloud")
                        }

                        if (!hasMicPermission) {
                            OutlinedButton(
                                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enable Microphone for Voice Input")
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
                                            updateStatusText = "Already up to date!"
                                            Toast.makeText(context, "Already up to date!", Toast.LENGTH_SHORT).show()
                                        }
                                        is UpdateCheckOutcome.Error -> {
                                            isCheckingOrDownloadingUpdate = false
                                            updateStatusText = outcome.message
                                            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                        }
                                        is UpdateCheckOutcome.UpdateAvailable -> {
                                            updateStatusText = "Downloading update..."
                                            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
                                            GitHubAppUpdater.startDownloadAndInstall(
                                                context = context,
                                                release = outcome.release,
                                                onStatus = { status -> updateStatusText = status },
                                                onFinished = { isCheckingOrDownloadingUpdate = false },
                                            )
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
                                if (isCheckingOrDownloadingUpdate) (updateStatusText ?: "Checking for updates...")
                                else "Check for App Updates"
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
        }
    }
}
