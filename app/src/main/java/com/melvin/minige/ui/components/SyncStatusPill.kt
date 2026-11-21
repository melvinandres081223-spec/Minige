package com.melvin.minige.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SyncedGreen = Color(0xFF1E9E52)
private val PendingAmber = Color(0xFFE59200)

@Composable
fun SyncStatusPill(
    pendingSyncCount: Int,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetailsDialog by remember { mutableStateOf(false) }

    val isPending = pendingSyncCount > 0
    val dotColor = if (isPending) PendingAmber else SyncedGreen
    val statusTooltip = when {
        isSyncing -> "Syncing to cloud..."
        isPending -> "Pending sync ($pendingSyncCount)"
        else -> "Cloud synced"
    }

    MinigeTooltipWrapper(
        label = statusTooltip,
        modifier = modifier,
    ) {
        IconButton(
            onClick = { showDetailsDialog = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("sync_status_dot_button"),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.18f)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
        }
    }

    if (showDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            icon = {
                Icon(
                    imageVector = if (isPending) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDone,
                    contentDescription = null,
                    tint = dotColor,
                )
            },
            title = {
                Text(
                    text = if (isPending) {
                        "$pendingSyncCount Pending Sync Item${if (pendingSyncCount == 1) "" else "s"}"
                    } else {
                        "Cloud Synced"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Text(
                            text = if (isPending) "Status: Queued locally (Pending)" else "Status: Up to date (Synced)",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        text = if (isPending) {
                            "Naka-save na sa phone ang mga pagbabago mo. I-tap ang Sync Now kapag malakas na ang signal."
                        } else {
                            "Lahat ng delivery at pautang records ay naka-backup na sa Firebase cloud."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSyncNow()
                        showDetailsDialog = false
                    },
                    enabled = !isSyncing,
                    modifier = Modifier.testTag("sync_status_dialog_sync_now"),
                ) {
                    Text(if (isSyncing) "Syncing..." else "Sync Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}
