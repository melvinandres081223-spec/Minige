package com.melvin.minige.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.ui.MinigeUiState

@Composable
fun TodayDashboardScreen(
    uiState: MinigeUiState,
    onShiftDate: (Long) -> Unit,
    onJumpToday: () -> Unit,
    onEditSukliClick: () -> Unit,
    onEditDelivery: (DeliveryRecord) -> Unit,
    onDeleteDelivery: (String) -> Unit,
    onAddExpenseClick: () -> Unit,
    onDeleteExpense: (String) -> Unit,
    onSyncCalendarClick: () -> Unit,
    onBossSendTriggered: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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
                DateNavigatorBar(
                    dateLabel = MinigeDateUtils.formatDisplay(uiState.selectedDate),
                    onPrev = { onShiftDate(-1) },
                    onNext = { onShiftDate(1) },
                    onToday = onJumpToday,
                )
            }

            item {
                CommissionHeroCard(uiState = uiState)
            }

            item {
                BossPickupUpdateCard(
                    pickupMessage = uiState.bossPickupMessage,
                    pickupGallons = uiState.dailySummary.pickupGallons,
                    onSendToBoss = {
                        copyAndShareToMessenger(context, uiState.bossPickupMessage)
                        onBossSendTriggered("Pick-up summary copied & opened share sheet")
                    },
                )
            }

            item {
                RemittanceBreakdownCard(
                    uiState = uiState,
                    onEditSukliClick = onEditSukliClick,
                    onSyncCalendarClick = onSyncCalendarClick,
                )
            }

            item {
                Text(
                    text = "Deliveries & Pick-ups (${uiState.deliveriesForDay.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (uiState.deliveriesForDay.isEmpty()) {
                item {
                    EmptySectionCard(
                        icon = Icons.Rounded.WaterDrop,
                        message = "No deliveries or pick-ups logged for this date. Tap + to add one.",
                    )
                }
            } else {
                items(uiState.deliveriesForDay, key = { it.id }) { delivery ->
                    DeliveryRowCard(
                        delivery = delivery,
                        onEdit = { onEditDelivery(delivery) },
                        onDelete = { onDeleteDelivery(delivery.id) },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Route Expenses (${uiState.expensesForDay.size})",
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
                item {
                    EmptySectionCard(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        message = "No route expenses logged for this date.",
                    )
                }
            } else {
                items(uiState.expensesForDay, key = { it.id }) { expense ->
                    ExpenseRowCard(
                        expense = expense,
                        onDelete = { onDeleteExpense(expense.id) },
                    )
                }
            }
        }
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
            IconButton(
                onClick = onPrev,
                modifier = Modifier.size(48.dp).testTag("prev_day_button"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous day")
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            TextButton(
                onClick = onToday,
                modifier = Modifier.testTag("today_jump_button"),
            ) {
                Text("Today")
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(48.dp).testTag("next_day_button"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next day")
            }
        }
    }
}

@Composable
private fun CommissionHeroCard(uiState: MinigeUiState) {
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
                    text = "Daily Commission (Personal)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                ) {
                    Text(
                        text = "Rate: ₱${uiState.settings.commissionRate}/gal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
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
    pickupGallons: Int,
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
                            text = "Na-pick up na Gallon",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "$pickupGallons gal(s) • ₱0 commission",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Prominent full-width 1-Tap Update / Send to Boss button underneath summary
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
                        text = "1-Tap Update / Send to Boss",
                        style = MaterialTheme.typography.labelLarge,
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

@Composable
private fun RemittanceBreakdownCard(
    uiState: MinigeUiState,
    onEditSukliClick: () -> Unit,
    onSyncCalendarClick: () -> Unit,
) {
    val s = uiState.dailySummary
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Remittance & Cash on Hand",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                FilledTonalButton(
                    onClick = onSyncCalendarClick,
                    enabled = !uiState.isCalendarSyncing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("sync_calendar_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (uiState.isCalendarSyncing) "Syncing..." else "Calendar Sync",
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            MetricLine(
                icon = Icons.Rounded.Payments,
                label = "Cash Collected (Remit)",
                value = "₱${s.cashCollected}",
            )
            MetricLine(
                icon = Icons.Rounded.AccountBalance,
                label = "Bank Deposit (Boss Account)",
                value = "₱${s.bankDepositTotal}",
            )
            MetricLine(
                icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                label = "Route Expenses",
                value = "-₱${s.expensesTotal}",
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Net Cash Remit", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "₱${s.netCashRemit}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Starting Sukli: ₱${s.startingSukli}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = onEditSukliClick,
                        modifier = Modifier.size(48.dp).testTag("edit_sukli_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit starting sukli",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = "Cash on Hand: ₱${s.cashOnHand}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MetricLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DeliveryRowCard(
    delivery: DeliveryRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = delivery.customerName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = delivery.paymentType.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Icon(
                        imageVector = if (delivery.pendingSync) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDone,
                        contentDescription = if (delivery.pendingSync) "Pending cloud sync" else "Synced",
                        tint = if (delivery.pendingSync) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(expense.category.label, style = MaterialTheme.typography.titleMedium)
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
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete expense")
            }
        }
    }
}

@Composable
private fun EmptySectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
