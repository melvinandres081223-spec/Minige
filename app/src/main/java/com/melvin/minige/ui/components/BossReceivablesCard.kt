package com.melvin.minige.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.util.BossReceivableEntry
import com.melvin.minige.util.BossReceivablesPrefs
import com.melvin.minige.util.CommissionRateHistory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun BossReceivablesCard(
    entries: List<BossReceivableEntry>,
    defaultCommissionRate: Double,
    suggestedPeriodLabel: String,
    onAddEntry: (periodLabel: String, amount: Int, gallons: Int?, ratePerGallon: Double?, note: String) -> Unit,
    onMarkPaid: (entryId: String) -> Unit,
    onDeleteEntry: (entryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val unpaidEntries = remember(entries) { entries.filter { !it.isPaid } }
    val paidEntries = remember(entries) { entries.filter { it.isPaid } }
    val totalUnpaid = remember(unpaidEntries) { BossReceivablesPrefs.totalUnpaidBalance(unpaidEntries) }

    var isCardExpanded by remember { mutableStateOf(false) }
    var isHistoryExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    OutlinedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("boss_receivables_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    tint = if (totalUnpaid > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Unpaid Sahod / Commission Kay Boss",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (unpaidEntries.isEmpty()) {
                            "Walang utang na sahod si Boss"
                        } else {
                            "${unpaidEntries.size} unpaid • Isolated sa daily Net Cash to Remit"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (totalUnpaid > 0) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = "₱$totalUnpaid",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (totalUnpaid > 0) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("boss_receivables_total_badge"),
                    )
                }
                IconButton(
                    onClick = { isCardExpanded = !isCardExpanded },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("boss_receivables_expand_toggle"),
                ) {
                    Icon(
                        imageVector = if (isCardExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (isCardExpanded) "Collapse unpaid sahod" else "Expand unpaid sahod",
                    )
                }
            }

            AnimatedVisibility(visible = isCardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()

                    FilledTonalButton(
                        onClick = { showAddDialog = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_boss_receivable_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Unpaid Sahod / Commission")
                    }

                    if (unpaidEntries.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Walang nakalista na unpaid sahod/commission mula kay Boss. Puwede ka magdagdag ng lumang kulang na commission dito.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    } else {
                        unpaidEntries.forEach { entry ->
                            BossReceivableRow(
                                entry = entry,
                                onMarkPaid = { onMarkPaid(entry.id) },
                                onDelete = { onDeleteEntry(entry.id) },
                            )
                        }
                    }

                    if (paidEntries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { isHistoryExpanded = !isHistoryExpanded },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("boss_receivables_history_toggle"),
                        ) {
                            Icon(
                                imageVector = if (isHistoryExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Settled History (${paidEntries.size}) • ₱${BossReceivablesPrefs.totalPaidHistory(paidEntries)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        AnimatedVisibility(visible = isHistoryExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                paidEntries.forEach { settled ->
                                    BossReceivableSettledRow(
                                        entry = settled,
                                        onDelete = { onDeleteEntry(settled.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddBossReceivableDialog(
            defaultCommissionRate = defaultCommissionRate,
            suggestedPeriodLabel = suggestedPeriodLabel,
            onDismiss = { showAddDialog = false },
            onConfirm = { period, amount, gallons, rate, note ->
                onAddEntry(period, amount, gallons, rate, note)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun BossReceivableRow(
    entry: BossReceivableEntry,
    onMarkPaid: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("boss_receivable_unpaid_${entry.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.periodLabel,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val helperSub = buildString {
                    append("₱${entry.amount}")
                    if (entry.gallons != null && entry.ratePerGallon != null) {
                        append(" (${entry.gallons} gals × ₱${CommissionRateHistory.formatRate(entry.ratePerGallon)})")
                    }
                }
                Text(
                    text = helperSub,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.note.isNotBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledTonalButton(
                onClick = onMarkPaid,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("mark_boss_receivable_paid_${entry.id}"),
            ) {
                Text("Nakuha Na", maxLines = 1, softWrap = false)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_boss_receivable_${entry.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete unpaid sahod entry",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BossReceivableSettledRow(
    entry: BossReceivableEntry,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("boss_receivable_paid_${entry.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Paid",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${entry.periodLabel} • ₱${entry.amount}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val paidMeta = buildString {
                    append("Nakuha ${entry.paidDateKey ?: "Paid"}")
                    if (entry.note.isNotBlank()) {
                        append(" • ${entry.note}")
                    }
                }
                Text(
                    text = paidMeta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_boss_receivable_${entry.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete settled sahod entry",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBossReceivableDialog(
    defaultCommissionRate: Double,
    suggestedPeriodLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (periodLabel: String, amount: Int, gallons: Int?, ratePerGallon: Double?, note: String) -> Unit,
) {
    var periodText by remember { mutableStateOf(suggestedPeriodLabel) }
    var gallonsText by remember { mutableStateOf("") }
    var rateText by remember {
        mutableStateOf(
            if (defaultCommissionRate % 1.0 == 0.0) {
                defaultCommissionRate.toInt().toString()
            } else {
                CommissionRateHistory.formatRate(defaultCommissionRate)
            }
        )
    }
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val parsedGallons = gallonsText.toIntOrNull()?.takeIf { it > 0 }
    val parsedRate = rateText.toDoubleOrNull()?.takeIf { it > 0.0 }
    val parsedAmount = amountText.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Unpaid Sahod / Commission") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Naka-isolate ito sa daily Cash Collected at Net Cash to Remit.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = periodText,
                        onValueChange = { periodText = it.take(80) },
                        label = { Text("Date / Period") },
                        placeholder = { Text("e.g. Nov 16–21, 2026") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("boss_receivable_period_input"),
                    )
                    IconButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("boss_receivable_pick_date_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = "Pick date for period",
                        )
                    }
                }

                Text(
                    text = "Optional: Compute from Gallons × Rate",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = gallonsText,
                        onValueChange = { raw ->
                            val clean = raw.filter(Char::isDigit).take(5)
                            gallonsText = clean
                            val g = clean.toIntOrNull()
                            val r = rateText.toDoubleOrNull()
                            if (g != null && g > 0 && r != null && r > 0.0) {
                                amountText = BossReceivablesPrefs.calculateGallonsAmount(g, r).toString()
                            }
                        },
                        label = { Text("Gallons") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("boss_receivable_gallons_input"),
                    )
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { it.isDigit() || it == '.' }.take(6)
                            rateText = filtered
                            val g = gallonsText.toIntOrNull()
                            val r = filtered.toDoubleOrNull()
                            if (g != null && g > 0 && r != null && r > 0.0) {
                                amountText = BossReceivablesPrefs.calculateGallonsAmount(g, r).toString()
                            }
                        },
                        label = { Text("Rate (₱)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("boss_receivable_rate_input"),
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit).take(7) },
                    label = { Text("Amount (₱)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("boss_receivable_amount_input"),
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it.take(240) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("e.g. Unpaid commission bago mag-app") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("boss_receivable_note_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        periodText.trim(),
                        parsedAmount,
                        parsedGallons,
                        if (parsedGallons != null) parsedRate else null,
                        noteText.trim(),
                    )
                },
                enabled = periodText.isNotBlank() && parsedAmount > 0,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("confirm_boss_receivable_button"),
            ) {
                Text("Save Entry")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancel")
            }
        },
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            periodText = MinigeDateUtils.formatShort(picked)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
