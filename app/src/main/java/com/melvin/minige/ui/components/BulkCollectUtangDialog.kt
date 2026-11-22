package com.melvin.minige.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.util.BulkUtangFifoHelper
import com.melvin.minige.util.BulkUtangSettlementSummary
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PaymentBreakdownHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkCollectUtangDialog(
    customerName: String,
    openEntriesFifo: List<DeliveryRecord>,
    paymentDateKey: String,
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, method: UtangMethod, notes: String) -> Unit,
) {
    val combinedBalance = remember(openEntriesFifo) {
        openEntriesFifo.sumOf { it.remainingBalance }
    }
    var amountText by remember(customerName, combinedBalance) {
        mutableStateOf(combinedBalance.toString())
    }
    var selectedChannel by remember(customerName) { mutableStateOf(PaidChannel.CASH) }
    var notes by remember { mutableStateOf("") }

    val enteredAmount = (amountText.toIntOrNull() ?: 0).coerceAtLeast(0)
    val preview = remember(openEntriesFifo, enteredAmount) {
        BulkUtangFifoHelper.previewFifoAllocation(openEntriesFifo, enteredAmount)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Settle Lump-Sum • $customerName",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "${openEntriesFifo.size} open entries • Combined ₱$combinedBalance • Date: $paymentDateKey",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PaidChannel.entries.forEach { channel ->
                        FilterChip(
                            selected = selectedChannel == channel,
                            onClick = { selectedChannel = channel },
                            label = { Text(channel.label) },
                            modifier = Modifier.testTag("bulk_utang_channel_${channel.name}"),
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = enteredAmount == combinedBalance && combinedBalance > 0,
                        onClick = { amountText = combinedBalance.toString() },
                        label = { Text("Full ₱$combinedBalance") },
                        modifier = Modifier.testTag("bulk_quick_full"),
                    )
                    listOf(100, 200, 500).forEach { quickAmt ->
                        FilterChip(
                            selected = enteredAmount == quickAmt,
                            onClick = { amountText = quickAmt.toString() },
                            label = { Text("₱$quickAmt") },
                            modifier = Modifier.testTag("bulk_quick_$quickAmt"),
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit).take(7) },
                    label = { Text("Lump-Sum Amount (₱)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bulk_utang_amount_input"),
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(300) },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bulk_utang_notes_input"),
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "FIFO Preview (Oldest First)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            preview.steps.forEach { step ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${step.dateKey} (${step.totalGallons}g)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val statusText = when {
                                        step.willFullySettle -> "Settled ₱${step.allocatedAmount}"
                                        step.allocatedAmount > 0 -> "Partial ₱${step.allocatedAmount} / ₱${step.remainingBefore}"
                                        else -> "Unpaid ₱${step.remainingBefore}"
                                    }
                                    val statusColor = when {
                                        step.willFullySettle -> MaterialTheme.colorScheme.primary
                                        step.allocatedAmount > 0 -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = statusColor,
                                    )
                                }
                            }
                        }
                        if (preview.excessAmount > 0) {
                            Text(
                                text = "Applied: ₱${preview.totalAppliedAmount} • Excess (unused): ₱${preview.excessAmount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                text = "Applied: ₱${preview.totalAppliedAmount} • Remaining after: ₱${preview.remainingCustomerBalanceAfter}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val taggedNotes = PaymentBreakdownHelper.applyChannelTag(notes, selectedChannel)
                    onConfirm(enteredAmount, selectedChannel.storedUtangMethod, taggedNotes)
                },
                enabled = enteredAmount > 0 && openEntriesFifo.isNotEmpty(),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("confirm_bulk_utang_payment_button"),
            ) {
                Text("Apply Lump-Sum")
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
}

@Composable
fun BulkSettlementConfirmationDialog(
    summary: BulkUtangSettlementSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Lump-Sum Applied • ${summary.customerName}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Total Applied: ₱${summary.totalAppliedAmount} across ${summary.affectedCount} entry(ies)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (summary.settledDateKeys.isNotEmpty()) {
                    Text(
                        text = "Fully Settled (${summary.fullySettledCount}): ${summary.settledDateKeys.distinct().joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (summary.partiallyPaidCount > 0) {
                    Text(
                        text = "Partially Paid (${summary.partiallyPaidCount}): ${summary.partiallyPaidDateKeys.distinct().joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    text = "Remaining Customer Balance: ₱${summary.remainingCustomerBalance}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (summary.excessAmount > 0) {
                    Text(
                        text = "Excess Amount (not applied): ₱${summary.excessAmount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (summary.pendingCloudSync) {
                    Text(
                        text = "Saved locally — queued for cloud sync when online.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("dismiss_bulk_settlement_dialog_button"),
            ) {
                Text("OK")
            }
        },
    )
}
