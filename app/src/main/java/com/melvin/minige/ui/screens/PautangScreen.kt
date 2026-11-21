package com.melvin.minige.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.ui.MinigeUiState
import com.melvin.minige.ui.components.DeliveryFilterBar
import com.melvin.minige.ui.components.DeliveryFilterChip
import com.melvin.minige.ui.components.DeliveryFilterHelper
import com.melvin.minige.ui.components.OverduePautangBadge
import com.melvin.minige.ui.components.OverduePautangBanner
import com.melvin.minige.ui.components.PartialPaymentDialog
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PartialPaymentHelper
import com.melvin.minige.util.PautangOverdueHelper
import com.melvin.minige.util.PaymentBreakdownHelper

@Composable
fun PautangScreen(
    uiState: MinigeUiState,
    onRecordPayment: (deliveryId: String, amount: Int, method: UtangMethod, notes: String) -> Unit,
    onRecordPartialGallonPayment: (deliveryId: String, paidGallons: Int, amount: Int, channel: PaidChannel, notes: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedUtang by remember { mutableStateOf<DeliveryRecord?>(null) }
    var partialPayTarget by remember { mutableStateOf<DeliveryRecord?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(DeliveryFilterChip.ALL) }

    val totalOutstanding = uiState.activeUtangList.sumOf { it.remainingBalance }
    val overdueSummary = remember(uiState.activeUtangList) {
        PautangOverdueHelper.summarizeOverdue(uiState.activeUtangList)
    }
    val filteredActiveUtang = remember(uiState.activeUtangList, searchQuery, selectedFilter) {
        DeliveryFilterHelper.filterDeliveries(uiState.activeUtangList, searchQuery, selectedFilter)
    }
    val filteredSettledUtang = remember(uiState.settledUtangList, searchQuery, selectedFilter) {
        DeliveryFilterHelper.filterDeliveries(uiState.settledUtangList, searchQuery, selectedFilter)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Pautang (Credit) Rule",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Outstanding Balance: ₱$totalOutstanding (${uiState.activeUtangList.size} active)",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Unpaid & partial payments earn ₱0 commission. Full commission (${uiState.settings.commissionRate}/gal) is awarded on the exact date balance reaches ₱0.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (overdueSummary.overdueCount > 0) {
                item(key = "overdue_pautang_banner") {
                    OverduePautangBanner(summary = overdueSummary)
                }
            }

            item(key = "pautang_filter_bar") {
                DeliveryFilterBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    selectedChip = selectedFilter,
                    onSelectChip = { selectedFilter = it },
                    testTagPrefix = "pautang_filter",
                )
            }

            item {
                Text(
                    text = "Unpaid / Partial Utang (${filteredActiveUtang.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (filteredActiveUtang.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.CreditScore, contentDescription = null)
                            Text(
                                text = if (uiState.activeUtangList.isEmpty()) {
                                    "All Pautang accounts are settled!"
                                } else {
                                    "No unpaid pautang accounts match the active filter."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                items(filteredActiveUtang, key = { it.id }) { item ->
                    val overdueDays = overdueSummary.daysElapsedByDeliveryId[item.id]
                        ?.takeIf { it >= PautangOverdueHelper.OVERDUE_THRESHOLD_DAYS }
                    ActiveUtangCard(
                        item = item,
                        allUtangPayments = uiState.utangPayments,
                        commissionRate = uiState.settings.commissionRate,
                        overdueDays = overdueDays,
                        onPartialPayClick = { partialPayTarget = item },
                        onCollectClick = { selectedUtang = item },
                    )
                }
            }

            if (filteredSettledUtang.isNotEmpty()) {
                item {
                    Text(
                        text = "Fully Paid Utang (${filteredSettledUtang.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(filteredSettledUtang, key = { it.id }) { settled ->
                    SettledUtangCard(item = settled)
                }
            }
        }
    }

    selectedUtang?.let { target ->
        CollectUtangDialog(
            delivery = target,
            paymentDateKey = uiState.selectedDateKey,
            commissionRate = uiState.settings.commissionRate,
            onDismiss = { selectedUtang = null },
            onConfirm = { amount, method, notes ->
                onRecordPayment(target.id, amount, method, notes)
                selectedUtang = null
            },
        )
    }

    partialPayTarget?.let { target ->
        PartialPaymentDialog(
            delivery = target,
            allUtangPayments = uiState.utangPayments,
            paymentDateKey = uiState.selectedDateKey,
            activeCommissionRate = uiState.activeCommissionRate,
            onDismiss = { partialPayTarget = null },
            onConfirm = { paidGallons, amount, channel, notes ->
                onRecordPartialGallonPayment(target.id, paidGallons, amount, channel, notes)
                partialPayTarget = null
            },
        )
    }
}

@Composable
private fun ActiveUtangCard(
    item: DeliveryRecord,
    allUtangPayments: List<UtangPaymentRecord>,
    commissionRate: Int,
    overdueDays: Long?,
    onPartialPayClick: () -> Unit,
    onCollectClick: () -> Unit,
) {
    val progress = if (item.totalAmount > 0) {
        (item.amountPaid.toFloat() / item.totalAmount.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val partialStatus = remember(item, allUtangPayments) {
        PartialPaymentHelper.getStatus(item, allUtangPayments)
    }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.customerName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Delivered ${item.dateKey} • Unpaid: ${partialStatus.remainingUnpaidGallons}/${item.totalGallons} gal(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Bal: ₱${item.remainingBalance}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (overdueDays != null) {
                        OverduePautangBadge(daysElapsed = overdueDays)
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Paid ₱${item.amountPaid} / ₱${item.totalAmount} • Potential Comm: ₱${partialStatus.remainingUnpaidGallons * commissionRate}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPartialPayClick,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("partial_pay_utang_${item.id}"),
                ) {
                    Text("Partial Pay")
                }
                Button(
                    onClick = onCollectClick,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("collect_utang_${item.id}"),
                ) {
                    Text("Collect")
                }
            }
        }
    }
}

@Composable
private fun SettledUtangCard(item: DeliveryRecord) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Fully paid",
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.customerName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${item.totalGallons} gals • Settled ${item.settledDateKey ?: item.dateKey}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "+₱${item.commissionEarned}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectUtangDialog(
    delivery: DeliveryRecord,
    paymentDateKey: String,
    commissionRate: Int,
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, method: UtangMethod, notes: String) -> Unit,
) {
    val remaining = delivery.remainingBalance
    var amountText by remember(delivery.id) { mutableStateOf(remaining.toString()) }
    var selectedChannel by remember(delivery.id) { mutableStateOf(PaidChannel.CASH) }
    var notes by remember { mutableStateOf("") }

    val parsedAmount = (amountText.toIntOrNull() ?: 0).coerceIn(0, remaining)
    val willFullySettle = parsedAmount >= remaining && remaining > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collect from ${delivery.customerName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Remaining Balance: ₱$remaining • Date: $paymentDateKey",
                    style = MaterialTheme.typography.bodyMedium,
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaidChannel.entries.forEach { channel ->
                        FilterChip(
                            selected = selectedChannel == channel,
                            onClick = { selectedChannel = channel },
                            label = { Text(channel.label) },
                            modifier = Modifier.testTag("utang_channel_${channel.name}"),
                        )
                    }
                    FilterChip(
                        selected = parsedAmount == remaining,
                        onClick = { amountText = remaining.toString() },
                        label = { Text("Full ₱$remaining") },
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit).take(7) },
                    label = { Text("Payment Amount (₱)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(300) },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = if (willFullySettle) {
                        "Reaches ₱0 balance → Awards ₱${delivery.totalGallons * commissionRate} commission on $paymentDateKey!"
                    } else {
                        "Partial payment → Remaining ₱${remaining - parsedAmount} • ₱0 commission until fully paid."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (willFullySettle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val taggedNotes = PaymentBreakdownHelper.applyChannelTag(notes, selectedChannel)
                    onConfirm(parsedAmount, selectedChannel.storedUtangMethod, taggedNotes)
                },
                enabled = parsedAmount > 0,
                modifier = Modifier.testTag("confirm_utang_payment_button"),
            ) {
                Text("Record Payment")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
