package com.melvin.minige.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.InactiveSeverity
import com.melvin.minige.domain.InactiveSukiAlert
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.ui.MinigeUiState
import com.melvin.minige.ui.components.DailyClosingHelper
import com.melvin.minige.ui.components.DailyClosingSheet
import com.melvin.minige.ui.components.DailyQuotaBar
import com.melvin.minige.ui.components.DeliveredTodayDrawerHeader
import com.melvin.minige.ui.components.DeliveryFilterBar
import com.melvin.minige.ui.components.DeliveryFilterChip
import com.melvin.minige.ui.components.DeliveryFilterHelper
import com.melvin.minige.ui.components.InactiveSukiAlertCard
import com.melvin.minige.ui.components.InactiveSukiHeaderBadge
import com.melvin.minige.ui.components.MinigeTooltipWrapper
import com.melvin.minige.ui.components.PinnedPickupDeliveryCard
import com.melvin.minige.ui.components.RemittanceSummaryCard
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.CustomerDirectoryHelper
import com.melvin.minige.util.DeliveryVoiceParser
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.RouteGroupingHelper
import com.melvin.minige.util.SmallGallonHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    uiState: MinigeUiState,
    onShiftDate: (Long) -> Unit,
    onJumpToday: () -> Unit,
    onEditSukliClick: () -> Unit,
    onEditDelivery: (DeliveryRecord) -> Unit,
    onDeleteDelivery: (String) -> Unit,
    onAddExpenseClick: () -> Unit,
    onAddCashAdvanceClick: () -> Unit = {},
    onDeleteExpense: (String) -> Unit,
    onSyncCalendarClick: () -> Unit,
    onBossSendTriggered: (String) -> Unit,
    onUpdateQuotaTarget: (Int) -> Unit = {},
    onReorderInactiveSuki: (InactiveSukiAlert) -> Unit = {},
    onMarkPickupDelivered: (DeliveryRecord) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSelectFilterChip: (DeliveryFilterChip) -> Unit = {},
    onRefreshToday: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf(uiState.todaySearchQuery) }
    val selectedFilter = uiState.todayFilterChip
    var groupByArea by remember { mutableStateOf(false) }
    var showClosingSheet by remember { mutableStateOf(false) }
    var isInactiveAlertExpanded by remember { mutableStateOf(true) }
    var isDeliveredDrawerExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.todaySearchQuery) {
        if (uiState.todaySearchQuery != searchQuery && uiState.todaySearchQuery.isEmpty()) {
            searchQuery = ""
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty()) {
            onSearchQueryChange("")
        } else {
            delay(120)
            onSearchQueryChange(searchQuery)
        }
    }

    val filteredDeliveries = uiState.filteredDeliveriesForDay
    val filteredPickups = uiState.pickupsForDay
    val filteredCompletedDeliveries = uiState.completedDeliveriesForDay

    val deliveredCustomerCount by remember(filteredCompletedDeliveries) {
        derivedStateOf {
            filteredCompletedDeliveries
                .distinctBy {
                    MinigeCustomerUtils.normalizeCustomerId(it.customerName).ifEmpty { it.id }
                }
                .size
        }
    }

    val deliveredTotalGallons by remember(filteredCompletedDeliveries) {
        derivedStateOf {
            filteredCompletedDeliveries.sumOf { it.totalGallons }
        }
    }

    val routeGrouping by remember(groupByArea, filteredCompletedDeliveries, uiState.sukiDirectory) {
        derivedStateOf {
            if (groupByArea) {
                RouteGroupingHelper.groupDeliveriesByArea(
                    deliveriesForDay = filteredCompletedDeliveries,
                    sukiDirectory = uiState.sukiDirectory,
                )
            } else {
                null
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshingToday,
        onRefresh = onRefreshToday,
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("today_pull_to_refresh"),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "daily_quota_bar", contentType = "quota_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DailyQuotaBar(
                        summary = uiState.dailySummary,
                        targetGallons = uiState.dailyQuotaTarget,
                        onUpdateTarget = onUpdateQuotaTarget,
                        modifier = Modifier.weight(1f),
                    )
                    if (uiState.inactiveSukiAlerts.isNotEmpty()) {
                        val hasCritical = uiState.inactiveSukiAlerts.any {
                            it.severity == InactiveSeverity.RED_CRITICAL
                        }
                        InactiveSukiHeaderBadge(
                            count = uiState.inactiveSukiCount,
                            hasCritical = hasCritical,
                            onClick = {
                                isInactiveAlertExpanded = true
                                scope.launch {
                                    listState.animateScrollToItem(index = 2)
                                }
                            },
                        )
                    }
                }
            }

            item(key = "date_navigator_bar", contentType = "date_nav") {
                DateNavigatorBar(
                    dateLabel = MinigeDateUtils.formatDisplay(uiState.selectedDate),
                    onPrev = { onShiftDate(-1) },
                    onNext = { onShiftDate(1) },
                    onToday = onJumpToday,
                )
            }

            if (uiState.inactiveSukiAlerts.isNotEmpty()) {
                item(key = "inactive_suki_alert_card", contentType = "inactive_suki_alert") {
                    InactiveSukiAlertCard(
                        alerts = uiState.inactiveSukiAlerts,
                        expanded = isInactiveAlertExpanded,
                        onToggleExpanded = { isInactiveAlertExpanded = !isInactiveAlertExpanded },
                        onReorderClick = onReorderInactiveSuki,
                    )
                }
            }

            item(key = "commission_hero_card", contentType = "commission_hero") {
                CommissionHeroCard(
                    uiState = uiState,
                    onOpenClosing = { showClosingSheet = true },
                )
            }

            item(key = "boss_pickup_update_card", contentType = "boss_pickup") {
                BossPickupUpdateCard(
                    pickupMessage = uiState.bossPickupMessage,
                    totalPaidGallons = uiState.bossHakotPaidGallons,
                    totalPautangGallons = uiState.bossHakotPautangGallons,
                    totalPautangPaidGallons = uiState.bossHakotPautangPaidGallons,
                    totalMayCommissionGallons = uiState.bossHakotMayCommissionGallons,
                    onSendToBoss = {
                        copyAndShareToMessenger(context, uiState.bossPickupMessage)
                        onBossSendTriggered("Daily Hakot report copied & opened share sheet")
                    },
                )
            }

            item(key = "remittance_summary_card", contentType = "remittance_summary") {
                RemittanceSummaryCard(
                    summary = uiState.dailySummary,
                    breakdown = uiState.todayPaymentBreakdown,
                    defaultStartingSukli = uiState.settings.defaultSukli,
                    isCalendarSyncing = uiState.isCalendarSyncing,
                    onEditSukliClick = onEditSukliClick,
                    onSyncCalendarClick = onSyncCalendarClick,
                    onAddCashAdvanceClick = onAddCashAdvanceClick,
                )
            }

            stickyHeader(key = "today_sticky_filter_header", contentType = "sticky_filter_header") {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Deliveries & Pick-ups (${filteredDeliveries.size}/${uiState.deliveriesForDay.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            )
                            MinigeTooltipWrapper(label = "Group deliveries by route area") {
                                FilterChip(
                                    selected = groupByArea,
                                    onClick = { groupByArea = !groupByArea },
                                    label = {
                                        Text(
                                            text = "By Area",
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Place,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    modifier = Modifier.testTag("by_area_toggle_chip"),
                                )
                            }
                        }

                        DeliveryFilterBar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            selectedChip = selectedFilter,
                            onSelectChip = { onSelectFilterChip(it) },
                            testTagPrefix = "today_filter",
                            onSearchFocusChanged = { focused ->
                                if (focused) {
                                    scope.launch {
                                        val stickyIndex = if (uiState.inactiveSukiAlerts.isNotEmpty()) 6 else 5
                                        listState.animateScrollToItem(index = stickyIndex)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (uiState.deliveriesForDay.isEmpty()) {
                item(key = "empty_deliveries", contentType = "empty_card") {
                    EmptySectionCard(
                        icon = Icons.Rounded.WaterDrop,
                        message = "No deliveries or pick-ups logged for this date. Tap Pick Up or + to add one.",
                    )
                }
            } else if (filteredDeliveries.isEmpty()) {
                item(key = "empty_filtered_deliveries", contentType = "empty_card") {
                    EmptySectionCard(
                        icon = Icons.Rounded.WaterDrop,
                        message = "No deliveries match \"${searchQuery.ifBlank { selectedFilter.label }}\".",
                    )
                }
            } else {
                if (filteredPickups.isNotEmpty()) {
                    item(key = "pinned_pickups_header", contentType = "section_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "🚚 FOR DELIVERY / PICKED UP — ${filteredPickups.size} pending",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    items(
                        items = filteredPickups,
                        key = { "pinned_pickup_${it.id}" },
                        contentType = { "pinned_pickup_card" },
                    ) { pickup ->
                        PinnedPickupDeliveryCard(
                            delivery = pickup,
                            onMarkDelivered = { onMarkPickupDelivered(pickup) },
                            onEdit = { onEditDelivery(pickup) },
                            onDelete = { onDeleteDelivery(pickup.id) },
                        )
                    }
                }

                if (filteredCompletedDeliveries.isNotEmpty()) {
                    val effectiveDeliveredExpanded = isDeliveredDrawerExpanded ||
                        uiState.todaySearchQuery.isNotBlank() ||
                        selectedFilter != DeliveryFilterChip.ALL

                    item(key = "delivered_today_drawer_header", contentType = "delivered_drawer_header") {
                        DeliveredTodayDrawerHeader(
                            customerCount = deliveredCustomerCount,
                            totalDeliveredGals = deliveredTotalGallons,
                            expanded = effectiveDeliveredExpanded,
                            onToggleExpanded = {
                                isDeliveredDrawerExpanded = !effectiveDeliveredExpanded
                            },
                        )
                    }

                    if (effectiveDeliveredExpanded) {
                        val activeGrouping = routeGrouping
                        if (activeGrouping != null) {
                            activeGrouping.groups.forEach { group ->
                                item(key = "area_header_${group.areaName}", contentType = "area_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${group.areaName} • ${group.totalGallons} gal",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (group.unvisitedSuki.isNotEmpty()) {
                                            val missedNames = group.unvisitedSuki.take(2).joinToString(", ") { it.name }
                                            val extra = if (group.unvisitedSuki.size > 2) " +${group.unvisitedSuki.size - 2}" else ""
                                            Text(
                                                text = "Next: $missedNames$extra",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                }
                                items(
                                    items = group.deliveries,
                                    key = { "area_${group.areaName}_${it.id}" },
                                    contentType = { "delivery_card" },
                                ) { delivery ->
                                    DeliveryRowCard(
                                        delivery = delivery,
                                        onEdit = { onEditDelivery(delivery) },
                                        onDelete = { onDeleteDelivery(delivery.id) },
                                    )
                                }
                            }
                        } else {
                            items(
                                items = filteredCompletedDeliveries,
                                key = { "delivery_${it.id}" },
                                contentType = { "delivery_card" },
                            ) { delivery ->
                                DeliveryRowCard(
                                    delivery = delivery,
                                    onEdit = { onEditDelivery(delivery) },
                                    onDelete = { onDeleteDelivery(delivery.id) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "route_expenses_header", contentType = "section_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Expenses & Cash Advance (${uiState.expensesForDay.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    FilledTonalButton(
                        onClick = onAddExpenseClick,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("add_expense_header_button"),
                    ) {
                        Text(
                            text = "+ Expense",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            if (uiState.expensesForDay.isEmpty()) {
                item(key = "empty_expenses", contentType = "empty_card") {
                    EmptySectionCard(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        message = "No route expenses or cash advances logged for this date.",
                    )
                }
            } else {
                items(
                    items = uiState.expensesForDay,
                    key = { "expense_${it.id}" },
                    contentType = { "expense_card" },
                ) { expense ->
                    ExpenseRowCard(
                        expense = expense,
                        onDelete = { onDeleteExpense(expense.id) },
                    )
                }
            }
        }
    }

    if (showClosingSheet) {
        val closingData = remember(
            uiState.selectedDateKey,
            uiState.dailySummary,
            uiState.todayPaymentBreakdown,
            uiState.activeCommissionRate,
            uiState.settings.defaultSukli,
        ) {
            DailyClosingHelper.compute(
                dateKey = uiState.selectedDateKey,
                summary = uiState.dailySummary,
                breakdown = uiState.todayPaymentBreakdown,
                activeCommissionRate = uiState.activeCommissionRate,
                defaultStartingSukli = uiState.settings.defaultSukli,
            )
        }
        DailyClosingSheet(
            closingData = closingData,
            dateLabel = MinigeDateUtils.formatDisplay(uiState.selectedDate),
            onCopiedPrivateSummary = {
                onBossSendTriggered("Private closing summary copied")
            },
            onDismiss = { showClosingSheet = false },
        )
    }
}

@Composable
private fun DateNavigatorBar(
    dateLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MinigeTooltipWrapper(label = "Previous day") {
                IconButton(
                    onClick = onPrev,
                    modifier = Modifier.size(48.dp).testTag("prev_day_button"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous day")
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            TextButton(
                onClick = onToday,
                modifier = Modifier.testTag("today_jump_button"),
            ) {
                Text("Today", maxLines = 1, softWrap = false)
            }

            MinigeTooltipWrapper(label = "Next day") {
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(48.dp).testTag("next_day_button"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next day")
                }
            }
        }
    }
}

@Composable
private fun CommissionHeroCard(
    uiState: MinigeUiState,
    onOpenClosing: () -> Unit,
) {
    val summary = uiState.dailySummary
    val weekly = uiState.weeklySummary
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        )
    )

    val formulaText = if (summary.effectiveRateDisplay != null) {
        "${summary.totalPaidGallons} gals × ₱${summary.effectiveRateDisplay} = ₱${summary.dailyCommission}"
    } else {
        "${summary.totalPaidGallons} paid gals = ₱${summary.dailyCommission}"
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .background(gradient)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Daily Commission",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    ) {
                        Text(
                            text = "₱${CommissionRateHistory.formatRate(uiState.activeCommissionRate)}/gal",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    MinigeTooltipWrapper(label = "End-of-day private closing") {
                        Surface(
                            onClick = onOpenClosing,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("closing_sheet_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Summarize,
                                    contentDescription = "Open end-of-day closing",
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Closing",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "₱${summary.dailyCommission}",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Text(
                text = formulaText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Text(
                text = "Paid Gallons: ${summary.directPaidGallons} direct + ${summary.settledUtangGallons} settled utang = ${summary.totalPaidGallons} gals",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )

            val breakdown = uiState.todayPaymentBreakdown
            val walkInSegment = if (breakdown.paidWalkInGallons > 0) {
                " • Walk-in: ₱${breakdown.commissionFromPaidWalkIn} (${breakdown.paidWalkInGallons}g)"
            } else {
                ""
            }
            Text(
                text = "Cash: ₱${breakdown.commissionFromCash} (${breakdown.cashPaidGallons}g) • Bank / Online: ₱${breakdown.commissionFromBank} (${breakdown.bankOnlineGallons}g)$walkInSegment",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Mon–Sat Weekly Total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                )
                Text(
                    text = "${weekly.totalPaidGallons} gals • ₱${weekly.totalCommission}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BossPickupUpdateCard(
    pickupMessage: String,
    totalPaidGallons: Int,
    totalPautangGallons: Int,
    totalPautangPaidGallons: Int,
    totalMayCommissionGallons: Int,
    onSendToBoss: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocalShipping,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Daily Hakot Report",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "$totalPaidGallons delivered • $totalPautangGallons new pautang • $totalPautangPaidGallons pautang paid • $totalMayCommissionGallons may comm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = onSendToBoss,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("send_to_boss_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Send Daily Hakot",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = pickupMessage,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeliveryRowCard(
    delivery: DeliveryRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val methodLabel = PaymentBreakdownHelper.displayMethodLabel(delivery)
    val classification = DeliveryVoiceParser.extractClassification(delivery.notes)
    val routeArea = CustomerDirectoryHelper.extractRouteArea(delivery.notes)

    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .testTag("delivery_card_${delivery.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = delivery.customerName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Text(
                            text = methodLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (SmallGallonHelper.isMaliitTagged(delivery.notes)) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = "Maliit",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (classification != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = classification.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (!routeArea.isNullOrBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = routeArea,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Icon(
                        imageVector = if (delivery.pendingSync) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDone,
                        contentDescription = if (delivery.pendingSync) "Pending cloud sync" else "Synced",
                        tint = if (delivery.pendingSync) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.CenterVertically),
                    )
                }

                Text(
                    text = buildString {
                        if (delivery.purifiedGallons > 0) append("${delivery.purifiedGallons} Purified")
                        if (delivery.purifiedGallons > 0 && delivery.alkalineGallons > 0) append(" + ")
                        if (delivery.alkalineGallons > 0) append("${delivery.alkalineGallons} Alkaline")
                        append(" (${delivery.totalGallons} gal")
                        if (delivery.totalGallons != 1) append("s")
                        append(")")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val subStatus = when (delivery.paymentType) {
                    PaymentType.PICK_UP -> "Pick-up only • ₱0 commission"
                    PaymentType.PAID_WALK_IN -> if (delivery.hasPaidWalkInCommission) {
                        "Paid Walk-in • ₱0 cash remit • Comm: ₱${delivery.commissionEarned}"
                    } else {
                        "Paid Walk-in • ₱0 cash remit • ₱0 comm"
                    }
                    PaymentType.PAUTANG -> if (delivery.isUtangFullyPaid) {
                        "Utang Paid • Commission: ₱${delivery.commissionEarned} (${delivery.settledDateKey})"
                    } else {
                        "Bill: ₱${delivery.totalAmount} • Bal: ₱${delivery.remainingBalance} • Comm: ₱0"
                    }
                    else -> "Total: ₱${delivery.totalAmount} • Comm: ₱${delivery.commissionEarned}"
                }
                Text(
                    text = subStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_delivery_${delivery.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit ${delivery.customerName}",
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete ${delivery.customerName}",
                )
            }
        }
    }
}

@Composable
private fun ExpenseRowCard(
    expense: ExpenseRecord,
    onDelete: () -> Unit,
) {
    val isCashAdvance = expense.category.isCashAdvance
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(expense.category.label, style = MaterialTheme.typography.titleMedium)
                    if (isCashAdvance || expense.category.isBossRemitAdvance) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = if (expense.category.isBossRemitAdvance) {
                                    "Deducts Remit Only"
                                } else {
                                    "Deducts Remit & Weekly"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (expense.notes.isNotBlank()) {
                    Text(
                        text = expense.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "-₱${expense.amount}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${expense.category.label}")
            }
        }
    }
}

@Composable
private fun EmptySectionCard(
    icon: ImageVector,
    message: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copyAndShareToMessenger(context: Context, message: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Minige Pick-up Update", message))

    val messengerIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
        setPackage("com.facebook.orca")
    }
    val hasMessenger = messengerIntent.resolveActivity(context.packageManager) != null
    val launchIntent = if (hasMessenger) {
        messengerIntent
    } else {
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            },
            "Send Pick-up Update to Boss",
        )
    }
    runCatching { context.startActivity(launchIntent) }
}
