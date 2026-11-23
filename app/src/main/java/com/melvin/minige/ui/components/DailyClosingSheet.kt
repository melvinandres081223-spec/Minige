package com.melvin.minige.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DailySummary
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.PaymentChannelBreakdown

@Immutable
data class DailyClosingData(
    val dateKey: String,
    val totalPickedUpGallons: Int,
    val directPaidGallons: Int,
    val settledUtangGallons: Int,
    val totalPaidGallons: Int,
    val pautangUnpaidGallons: Int,
    val cashPaidGallons: Int,
    val bankPaidGallons: Int,
    val totalCommissionEarned: Int,
    val activeCommissionRate: Double,
    val bankTransferAmount: Int,
    val bankDepositAmount: Int,
    val bankPaidTotal: Int,
    val retainedSukli: Int,
    val defaultStartingSukli: Int,
    val cashCollected: Int,
    val expensesTotal: Int,
    val cashAdvanceTotal: Int = 0,
    val netCashToRemitToBoss: Int,
    val cashOnHand: Int,
)

object DailyClosingHelper {

    fun compute(
        dateKey: String,
        summary: DailySummary,
        breakdown: PaymentChannelBreakdown,
        activeCommissionRate: Double,
        defaultStartingSukli: Int,
    ): DailyClosingData {
        return DailyClosingData(
            dateKey = dateKey,
            totalPickedUpGallons = summary.pickupGallons,
            directPaidGallons = summary.directPaidGallons,
            settledUtangGallons = summary.settledUtangGallons,
            totalPaidGallons = summary.totalPaidGallons,
            pautangUnpaidGallons = summary.unpaidUtangGallons,
            cashPaidGallons = breakdown.cashPaidGallons,
            bankPaidGallons = breakdown.bankOnlineGallons,
            totalCommissionEarned = summary.dailyCommission,
            activeCommissionRate = activeCommissionRate,
            bankTransferAmount = breakdown.bankTransferAmount,
            bankDepositAmount = breakdown.bankDepositAmount,
            bankPaidTotal = breakdown.bankPaidTotal,
            retainedSukli = summary.startingSukli,
            defaultStartingSukli = defaultStartingSukli,
            cashCollected = breakdown.cashCollected,
            expensesTotal = breakdown.cashExpenses,
            cashAdvanceTotal = breakdown.cashAdvanceTotal,
            netCashToRemitToBoss = breakdown.cashToRemit,
            cashOnHand = summary.cashOnHand,
        )
    }

    fun formatPrivateClosingText(data: DailyClosingData): String {
        val rateText = CommissionRateHistory.formatRate(data.activeCommissionRate)
        return buildString {
            appendLine("MINIGE PRIVATE CLOSING (${data.dateKey})")
            appendLine("Picked Up: ${data.totalPickedUpGallons} gal")
            appendLine("Paid Gallons: ${data.totalPaidGallons} gal (Cash ${data.cashPaidGallons}g + Bank / Online ${data.bankPaidGallons}g)")
            appendLine("Unpaid Pautang: ${data.pautangUnpaidGallons} gal")
            appendLine("Commission Earned: ₱${data.totalCommissionEarned} (Rate ₱$rateText/gal)")
            appendLine("Bank / Online: ₱${data.bankPaidTotal}")
            appendLine("Cash Collected: ₱${data.cashCollected} | Expenses: -₱${data.expensesTotal} | Cash Advance: -₱${data.cashAdvanceTotal}")
            appendLine("Retained Sukli: ₱${data.retainedSukli} (Default ₱${data.defaultStartingSukli})")
            append("Net Cash to Remit to Boss: ₱${data.netCashToRemitToBoss}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyClosingSheet(
    closingData: DailyClosingData,
    dateLabel: String,
    onCopiedPrivateSummary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .testTag("daily_closing_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "End-of-Day Closing",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Close")
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Private rider numbers only — excluded from Boss 1-Tap Daily Hakot.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Exact Net Cash to Remit to Boss",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                    Text(
                        text = "₱${closingData.netCashToRemitToBoss}",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "Cash ₱${closingData.cashCollected} − Exp ₱${closingData.expensesTotal} − Adv ₱${closingData.cashAdvanceTotal} • On Hand ₱${closingData.cashOnHand}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }

            ClosingSectionCard(title = "Gallon Totals", icon = Icons.Rounded.LocalShipping) {
                ClosingLine("Total Gallons Picked Up (Hakot)", "${closingData.totalPickedUpGallons} gal")
                ClosingLine("Paid Gallons (Direct + Settled)", "${closingData.totalPaidGallons} gal", emphasize = true)
                ClosingLine("  • Cash Paid / Bank / Online", "${closingData.cashPaidGallons}g Cash • ${closingData.bankPaidGallons}g Bank / Online")
                ClosingLine("Unpaid Pautang Gallons", "${closingData.pautangUnpaidGallons} gal")
            }

            ClosingSectionCard(title = "Commission & Bank / Online", icon = Icons.Rounded.AccountBalance) {
                ClosingLine(
                    "Total Commission (Rate ₱${CommissionRateHistory.formatRate(closingData.activeCommissionRate)}/g)",
                    "₱${closingData.totalCommissionEarned}",
                    emphasize = true,
                )
                ClosingLine("Bank / Online (Boss Account)", "₱${closingData.bankPaidTotal}")
            }

            ClosingSectionCard(title = "Sukli & Remittance", icon = Icons.Rounded.Savings) {
                ClosingLine("Cash Collected", "₱${closingData.cashCollected}")
                ClosingLine("Route Expenses", "-₱${closingData.expensesTotal}")
                ClosingLine("Cash Advance", "-₱${closingData.cashAdvanceTotal}")
                ClosingLine("Retained Customer Sukli", "₱${closingData.retainedSukli}")
                ClosingLine("Default Starting Sukli", "₱${closingData.defaultStartingSukli}")
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ClosingLine("Net Cash to Remit to Boss", "₱${closingData.netCashToRemitToBoss}", emphasize = true)
            }

            FilledTonalButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "Minige Private Closing",
                            DailyClosingHelper.formatPrivateClosingText(closingData),
                        )
                    )
                    onCopiedPrivateSummary()
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("copy_private_closing_button"),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Private Closing Summary")
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ClosingSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            content()
        }
    }
}

@Composable
private fun ClosingLine(
    label: String,
    value: String,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (emphasize) {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            text = value,
            style = if (emphasize) {
                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
