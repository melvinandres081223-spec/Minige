package com.melvin.minige.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.ui.MinigeUiState
import com.melvin.minige.ui.components.BossReceivablesCard
import com.melvin.minige.ui.components.SukiAnalyticsCard
import com.melvin.minige.ui.components.SundayMakeupHelper
import com.melvin.minige.ui.components.SundayMakeupToggle
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun WeeklyScreen(
    uiState: MinigeUiState,
    onSelectDate: (LocalDate) -> Unit,
    onSyncCalendarClick: () -> Unit,
    onToggleSundayMakeup: (Boolean) -> Unit,
    onSyncSundayMakeupClick: (LocalDate) -> Unit,
    onAddBossReceivable: (periodLabel: String, amount: Int, gallons: Int?, ratePerGallon: Double?, note: String) -> Unit = { _, _, _, _, _ -> },
    onMarkBossReceivablePaid: (String) -> Unit = {},
    onDeleteBossReceivable: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val weekly by remember(uiState.weeklySummary, uiState.sundayMakeupDaySummary, uiState.sundayMakeupEnabled) {
        derivedStateOf {
            SundayMakeupHelper.applySundayMakeup(
                baseWeekly = uiState.weeklySummary,
                sundaySummary = uiState.sundayMakeupDaySummary,
                enabled = uiState.sundayMakeupEnabled,
            )
        }
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
            item(key = "weekly_summary_hero", contentType = "weekly_hero") {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (uiState.sundayMakeupEnabled) {
                                "Mon–Sun (Brownout Makeup) Summary"
                            } else {
                                "Monday–Saturday Summary"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "${MinigeDateUtils.formatShort(weekly.monday)} – ${MinigeDateUtils.formatShort(weekly.saturday)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                        Text(
                            text = "${weekly.totalPaidGallons} Paid Gals • ₱${weekly.totalCommission} Commission",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Cash Advance: -₱${weekly.totalCashAdvance}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                            )
                            Text(
                                text = "Net Remaining: ₱${weekly.netRemainingCommission}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("weekly_net_remaining_commission"),
                            )
                        }
                        Text(
                            text = "Cash: ₱${weekly.totalCashCollected} • Bank / Online: ₱${weekly.totalBankDeposit} • Exp: ₱${weekly.totalExpenses}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            item(key = "sunday_makeup_toggle", contentType = "sunday_toggle") {
                SundayMakeupToggle(
                    enabled = uiState.sundayMakeupEnabled,
                    sundaySummary = uiState.sundayMakeupDaySummary,
                    onToggle = onToggleSundayMakeup,
                )
            }

            item(key = "weekly_suki_analytics", contentType = "suki_analytics") {
                SukiAnalyticsCard(
                    analytics = uiState.weeklySukiAnalytics,
                    inactiveAlertsByCustomerId = uiState.inactiveAlertsByCustomerId,
                )
            }

            item(key = "weekly_boss_receivables", contentType = "boss_receivables") {
                BossReceivablesCard(
                    entries = uiState.bossReceivables,
                    defaultCommissionRate = uiState.activeCommissionRate,
                    suggestedPeriodLabel = "${MinigeDateUtils.formatShort(weekly.monday)} – ${MinigeDateUtils.formatShort(weekly.saturday)}",
                    onAddEntry = onAddBossReceivable,
                    onMarkPaid = onMarkBossReceivablePaid,
                    onDeleteEntry = onDeleteBossReceivable,
                )
            }

            item(key = "weekly_calendar_actions", contentType = "calendar_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSyncCalendarClick,
                        enabled = !uiState.isCalendarSyncing,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("weekly_calendar_sync_button"),
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (uiState.isCalendarSyncing) "Syncing ${uiState.selectedDateKey}..."
                            else "Sync ${uiState.selectedDateKey} to Calendar",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (uiState.sundayMakeupEnabled) {
                        FilledTonalButton(
                            onClick = { onSyncSundayMakeupClick(uiState.sundayMakeupDaySummary.date) },
                            enabled = !uiState.isCalendarSyncing,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("sunday_makeup_sync_button"),
                        ) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sync Makeup (${uiState.sundayMakeupDaySummary.dateKey})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            items(
                items = weekly.days,
                key = { it.dateKey },
                contentType = { "weekly_day_card" },
            ) { day ->
                val isSelected = day.dateKey == uiState.selectedDateKey
                val isSundayMakeup = day.date.dayOfWeek == DayOfWeek.SUNDAY
                val suffix = buildString {
                    if (isSundayMakeup) append(" (Makeup)")
                    if (isSelected) append(" (Selected)")
                }
                OutlinedCard(
                    onClick = { onSelectDate(day.date) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = MinigeDateUtils.formatShort(day.date) + suffix,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val advText = if (day.cashAdvance > 0) " • Adv ₱${day.cashAdvance}" else ""
                            Text(
                                text = "Cash ₱${day.cashCollected} • Bank ₱${day.bankDeposit} • Exp ₱${day.expenses}$advText • Pick-up ${day.pickupGallons}g",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "₱${day.commission}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = "${day.paidGallons} gals",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
