package com.melvin.minige.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.ui.MinigeUiState
import com.melvin.minige.ui.components.BulkCollectUtangDialog
import com.melvin.minige.ui.components.BulkSettlementConfirmationDialog
import com.melvin.minige.ui.components.DeliveryFilterBar
import com.melvin.minige.ui.components.DeliveryFilterChip
import com.melvin.minige.ui.components.DeliveryFilterHelper
import com.melvin.minige.ui.components.OverduePautangBadge
import com.melvin.minige.ui.components.OverduePautangBanner
import com.melvin.minige.ui.components.PartialPaymentDialog
import com.melvin.minige.util.BulkUtangFifoHelper
import com.melvin.minige.util.CustomerOpenUtangGroup
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PartialPaymentHelper
import com.melvin.minige.util.PautangOverdueHelper
import com.melvin.minige.util.PaymentBreakdownHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PautangScreen(
    uiState: MinigeUiState,
    onRecordPayment: (deliveryId: String, amount: Int, method: UtangMethod, notes: String) -> Unit,
    onRecordPartialGallonPayment: (deliveryId: String, paidGallons: Int, amount: Int, channel: PaidChannel, notes: String) -> Unit,
    onRecordBulkPayment: (customerName: String, amount: Int, method: UtangMethod, notes: String) -> Unit = { _, _, _, _ -> },
    onDismissBulkSettlementConfirmation: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedUtang by remember { mutableStateOf<DeliveryRecord?>(null) }
    var partialPayTarget by remember { mutableStateOf<DeliveryRecord?>(null) }
    var bulkPayTargetGroup by remember { mutableStateOf<CustomerOpenUtangGroup?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(DeliveryFilterChip.ALL) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty()) {
            debouncedSearchQuery = ""
        } else {
            delay(120)
            debouncedSearchQuery = searchQuery
        }
    }

    val totalOutstanding by remember(uiState.activeUtangList) {
        derivedStateOf { uiState.activeUtangList.sumOf { it.remainingBalance } }
    }
    val overdueSummary by remember(uiState.activeUtangList) {
        derivedStateOf { PautangOverdueHelper.summarizeOverdue(uiState.activeUtangList) }
    }
    val filteredActiveUtang by remember(uiState.activeUtangList, debouncedSearchQuery, selectedFilter) {
        derivedStateOf {
            DeliveryFilterHelper.filterDeliveries(uiState.activeUtangList, debouncedSearchQuery, selectedFilter)
        }
    }
    val multiUtangGroups by remember(uiState.activeUtangList) {
        derivedStateOf {
            BulkUtangFifoHelper.buildMultiEntryCustomerGroups(uiState.activeUtangList)
        }
    }
    val visibleMultiUtangGroups by remember(multiUtangGroups, filteredActiveUtang) {
        derivedStateOf {
            val visibleKeys = filteredActiveUtang
                .map { BulkUtangFifoHelper.normalizeCustomerKey(it.customerName) }
                .toSet()
            multiUtangGroups.values
                .filter { it.normalizedKey in visibleKeys }
                .sortedByDescending { it.combinedBalance }
        }
    }
    val filteredSettledUtang by remember(uiState.settledUtangList, debouncedSearchQuery, selectedFilter) {
        derivedStateOf {
            DeliveryFilterHelper.filterDeliveries(uiState.settledUtangList, debouncedSearchQuery, selectedFilter)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "pautang_summary_header", contentType = "summary_header") {
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
                            text = "Pautang Credits (${uiState.activeUtangList.size} active)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "₱$totalOutstanding Outstanding",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Commission unlocks when paid (₱${uiState.settings.commissionRate}/gal on settlement or Partial Pay).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (overdueSummary.overdueCount > 0) {
                item(key = "overdue_pautang_banner", contentType = "overdue_banner") {
                    OverduePautangBanner(summary = overdueSummary)
                }
            }

            val filterHeaderIndex = if (overdueSummary.overdueCount > 0) 2 else 1
            stickyHeader(key = "pautang_sticky_filter_header", contentType = "sticky_filter_header") {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DeliveryFilterBar(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        selectedChip = selectedFilter,
                        onSelectChip = { selectedFilter = it },
                        testTagPrefix = "pautang_filter",
                        modifier = Modifier.padding(vertical = 4.dp),
                        onSearchFocusChanged = { focused ->
                            if (focused) {
                                scope.launch {
                                    listState.animateScrollToItem(index = filterHeaderIndex)
                                }
                            }
                        },
                    )
                }
            }

            item(key = "unpaid_utang_section_header", contentType = "section_header") {
                Text(
                    text = "Unpaid / Partial Utang (${filteredActiveUtang.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (visibleMultiUtangGroups.isNotEmpty()) {
                items(
                    items = visibleMultiUtangGroups,
                    key = { "multi_utang_group_${it.normalizedKey}" },
                    contentType = { "multi_utang_group_card" },
                ) { group ->
                    MultiUtangLumpSumCard(
                        group = group,
                        onSettleLumpSumClick = { bulkPayTargetGroup = group },
                    )
                }
            }

            if (filteredActiveUtang.isEmpty()) {
                item(key = "empty_active_utang", contentType = "empty_card") {
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
                items(
                    items = filteredActiveUtang,
                    key = { "active_utang_${it.id}" },
                    contentType = { "active_utang_card" },
                ) { item ->
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
                item(key = "settled_utang_section_header", contentType = "section_header") {
                    Text(
                        text = "Fully Paid Utang (${filteredSettledUtang.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(
                    items = filteredSettledUtang,
                    key = { "settled_utang_${it.id}" },
                    contentType = { "settled_utang_card" },
                ) { settled ->
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

    bulkPayTargetGroup?.let { group ->
        BulkCollectUtangDialog(
            customerName = group.displayName,
            openEntriesFifo = group.entriesFifo,
            paymentDateKey = uiState.selectedDateKey,
            onDismiss = { bulkPayTargetGroup = null },
            onConfirm = { amount, method, notes ->
                onRecordBulkPayment(group.displayName, amount, method, notes)
                bulkPayTargetGroup = null
            },
        )
    }

    uiState.bulkSettlementConfirmation?.let { summary ->
        BulkSettlementConfirmationDialog(
            summary = summary,
            onDismiss = onDismissBulkSettlementConfirmation,
        )
    }
}

@Composable
private fun MultiUtangLumpSumCard(
    group: CustomerOpenUtangGroup,
    onSettleLumpSumClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("multi_utang_card_${group.normalizedKey}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = group.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.entriesFifo.size} open dates • ${group.totalGallons} gal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Combined Balance: ₱${group.combinedBalance}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            FilledTonalButton(
                onClick = onSettleLumpSumClick,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("settle_lump_sum_${group.normalizedKey}"),
            ) {
                Text("Settle Lump-Sum", maxLines = 1, softWrap = false)
            }
        }
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.customerName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.dateKey} • ${partialStatus.remainingUnpaidGallons}/${item.totalGallons} gal unpaid",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "₱${item.remainingBalance}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    Text("Partial Pay", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onCollectClick,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("collect_utang_${item.id}"),
                ) {
                    Text("Collect", maxLines = 1, softWrap = false)
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
