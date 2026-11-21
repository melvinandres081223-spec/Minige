package com.melvin.minige.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DailySummary

data class DailyQuotaProgress(
    val currentGallons: Int,
    val targetGallons: Int,
    val commissionEarned: Int,
    val fraction: Float,
)

object DailyQuotaPrefs {
    const val DEFAULT_QUOTA_GALLONS = 50
    const val MIN_QUOTA_GALLONS = 1
    const val MAX_QUOTA_GALLONS = 500
    private const val PREFS_NAME = "minige_daily_quota"
    private const val KEY_PREFIX = "quota_"

    fun loadQuotaTarget(context: Context, userId: String): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getInt("$KEY_PREFIX$userId", DEFAULT_QUOTA_GALLONS)
        return raw.coerceIn(MIN_QUOTA_GALLONS, MAX_QUOTA_GALLONS)
    }

    fun saveQuotaTarget(context: Context, userId: String, targetGallons: Int): Int {
        val clamped = targetGallons.coerceIn(MIN_QUOTA_GALLONS, MAX_QUOTA_GALLONS)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("$KEY_PREFIX$userId", clamped).apply()
        return clamped
    }

    fun computeProgress(
        summary: DailySummary,
        targetGallons: Int,
    ): DailyQuotaProgress {
        val safeTarget = targetGallons.coerceIn(MIN_QUOTA_GALLONS, MAX_QUOTA_GALLONS)
        val handledGallons = summary.directPaidGallons + summary.unpaidUtangGallons + summary.pickupGallons
        val fraction = (handledGallons.toFloat() / safeTarget.toFloat()).coerceIn(0f, 1f)
        return DailyQuotaProgress(
            currentGallons = handledGallons,
            targetGallons = safeTarget,
            commissionEarned = summary.dailyCommission,
            fraction = fraction,
        )
    }
}

@Composable
fun DailyQuotaBar(
    summary: DailySummary,
    targetGallons: Int,
    onUpdateTarget: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(summary, targetGallons) {
        DailyQuotaPrefs.computeProgress(summary, targetGallons)
    }
    var showEditDialog by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { showEditDialog = true }
            .testTag("daily_quota_bar"),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (progress.fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.fraction)
                        .fillMaxHeight()
                        .background(
                            if (progress.currentGallons >= progress.targetGallons) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            }
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Hakot: ${progress.currentGallons} / ${progress.targetGallons} gal • ₱${progress.commissionEarned} comm",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit daily gallon quota",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }

    if (showEditDialog) {
        EditQuotaTargetDialog(
            currentTarget = progress.targetGallons,
            onDismiss = { showEditDialog = false },
            onSave = { newTarget ->
                onUpdateTarget(newTarget)
                showEditDialog = false
            },
        )
    }
}

@Composable
private fun EditQuotaTargetDialog(
    currentTarget: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var inputText by remember(currentTarget) { mutableStateOf(currentTarget.toString()) }
    val parsed = inputText.toIntOrNull()
    val valid = parsed != null && parsed in DailyQuotaPrefs.MIN_QUOTA_GALLONS..DailyQuotaPrefs.MAX_QUOTA_GALLONS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily Hakot Quota") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Set your daily target gallons for the top progress bar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { raw ->
                        inputText = raw.filter { it.isDigit() }.take(3)
                    },
                    label = { Text("Target gallons per day") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("daily_quota_input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(30, 50, 80, 100).forEach { preset ->
                        FilterChip(
                            selected = parsed == preset,
                            onClick = { inputText = preset.toString() },
                            label = { Text("$preset") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid && parsed != null) {
                        onSave(parsed)
                    }
                },
                enabled = valid,
                modifier = Modifier.testTag("save_quota_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
